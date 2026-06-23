package com.antispam.decision.llm;

import com.antispam.decision.Classification;
import com.antispam.decision.ClassificationRepository;
import com.antispam.decision.Decision;
import com.antispam.decision.DecisionOutcome;
import com.antispam.decision.FusedScore;
import com.antispam.decision.ModelScores;
import com.antispam.decision.ReasonCode;
import com.antispam.decision.RouteUsed;
import com.antispam.decision.hardrule.HardRuleCircuitBreaker;
import com.antispam.decision.routing.RoutingReason;
import com.antispam.ingest.Email;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * The quarantine-pending promotion within an SLA (story 05.06; PRD §Live decision timing). An
 * uncertain email is not delivered on the fast path — it is provisionally <em>withheld</em>
 * (quarantine-pending, recorded synchronously within the &lt;100ms budget) and then resolved
 * asynchronously by the LLM within a bounded SLA: promoted-to-inbox, confirmed-spam, or — if the
 * deadline passes or the budget is spent — fail-degraded to a conservative fast-path verdict with a
 * "running degraded" banner. The core invariant is <b>never deliver-then-retract</b>: a withheld
 * message is only ever delivered by an explicit promotion, so a user is never shown mail that is
 * later yanked back.
 *
 * <p><b>Append-only lifecycle.</b> Consistent with the append-only {@code classifications} table,
 * the pending decision is one row (route {@link RouteUsed#LLM}, tier {@link Decision#QUARANTINE}, no
 * cost yet) and the resolution is a second row (the resolved tier and the call's real cost). The
 * email's current decision is its latest row; the two-row history makes the promote/confirm/degrade
 * transition auditable. Lifecycle states are also emitted as metrics so the degraded banner is
 * observable (AC 5).
 *
 * <p><b>Two thread pools, one deadline.</b> {@link #beginResolution} returns immediately after
 * persisting the pending row and hands the resolution to the resolution pool; the resolution runs
 * the LLM call on a separate pool bounded by {@code Future.get(SLA)}, so an overrunning call is
 * abandoned and the decision fail-degrades rather than blocking forever.
 */
@Service
public class QuarantinePendingService {

    private static final Logger log = LoggerFactory.getLogger(QuarantinePendingService.class);

    private final LlmFallbackService llmFallbackService;
    private final ClassificationRepository repository;
    private final HardRuleCircuitBreaker circuitBreaker;
    private final LlmMeter meter;
    private final QuarantinePendingProperties properties;
    private final Executor resolutionExecutor;
    private final ExecutorService slaExecutor;

    @Autowired
    public QuarantinePendingService(
            LlmFallbackService llmFallbackService,
            ClassificationRepository repository,
            HardRuleCircuitBreaker circuitBreaker,
            LlmMeter meter,
            QuarantinePendingProperties properties,
            @Qualifier("llmResolutionExecutor") Executor resolutionExecutor,
            @Qualifier("llmSlaExecutor") ExecutorService slaExecutor) {
        this.llmFallbackService = llmFallbackService;
        this.repository = repository;
        this.circuitBreaker = circuitBreaker;
        this.meter = meter;
        this.properties = properties;
        this.resolutionExecutor = resolutionExecutor;
        this.slaExecutor = slaExecutor;
    }

    /**
     * Records the provisional quarantine-pending decision synchronously and schedules its async
     * resolution. The pending row is a withholding {@link Decision#QUARANTINE} on the
     * {@link RouteUsed#LLM} route, carrying the model scores and fused posterior so the fast-path
     * basis is preserved; no LLM call is made on this path, so it stays within the &lt;100ms budget.
     *
     * @param request everything the async resolution needs (the email, its routing reasons, the
     *                fast-path tier to fall back to, and the hard-rule floor)
     * @return the persisted quarantine-pending classification (the synchronous response)
     */
    public Classification beginResolution(ResolutionRequest request) {
        DecisionOutcome pending = new DecisionOutcome(
                Decision.QUARANTINE, request.routingReasonCodes(), RouteUsed.LLM,
                request.fastPathLatencyMs(), request.scores());
        Classification pendingRow =
                repository.save(request.email().id(), pending, request.fused(), request.policyVersion(), null);
        meter.recordResolution(ResolutionState.PENDING);

        resolutionExecutor.execute(() -> {
            try {
                resolve(request);
            } catch (RuntimeException e) {
                // The resolution runs off the request thread; a failure here must not be lost
                // silently. The pending row already withholds the mail, so the worst case is the
                // message stays quarantined — never delivered-then-retracted.
                log.error("quarantine-pending resolution failed for email={}", request.email().id(), e);
            }
        });
        return pendingRow;
    }

    /**
     * Resolves a pending email synchronously: calls the LLM under the SLA deadline, maps the outcome
     * to a final decision under the hard-rule circuit breaker, and appends the resolution row.
     * Package-visible (not private) so it is unit-testable directly without the async hop.
     *
     * @return the persisted resolution classification
     */
    Classification resolve(ResolutionRequest request) {
        LlmOutcome outcome = callWithinSla(request);
        ResolvedDecision resolved = PendingResolution.resolve(
                outcome, request.fastPathTier(), request.hardRuleFloor(), request.email().id(), circuitBreaker);
        meter.recordResolution(resolved.state());

        List<ReasonCode> reasonCodes = outcome.degraded() ? List.of() : outcome.verdict().reasonCodes();
        BigDecimal cost = outcome.costUsd().signum() > 0 ? outcome.costUsd() : null;
        DecisionOutcome resolvedOutcome = new DecisionOutcome(
                resolved.decision(), reasonCodes, RouteUsed.LLM, outcome.latencyMs(), request.scores());
        Classification row =
                repository.save(request.email().id(), resolvedOutcome, request.fused(), request.policyVersion(), cost);
        log.info("resolved quarantine-pending email={} state={} decision={} degradedBanner={} costUsd={}",
                request.email().id(), resolved.state(), resolved.decision(), resolved.degradedBanner(), cost);
        return row;
    }

    /**
     * Runs the LLM call bounded by the SLA: it executes on the SLA pool and is abandoned if it
     * overruns the deadline, in which case a {@link LlmOutcome#notAttempted()} (degraded) is returned
     * so the resolution fail-degrades. A budget denial or an unavailable provider already returns a
     * degraded outcome from {@link LlmFallbackService}, so all three "could not resolve" modes funnel
     * to the same degrade.
     */
    private LlmOutcome callWithinSla(ResolutionRequest request) {
        Future<LlmOutcome> future =
                slaExecutor.submit(() -> llmFallbackService.classify(request.email(), request.reasons()));
        try {
            return future.get(properties.sla().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("quarantine-pending resolution exceeded the {} SLA for email={}, fail-degrading",
                    properties.sla(), request.email().id());
            meter.recordSlaTimeout();
            return LlmOutcome.notAttempted();
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            return LlmOutcome.notAttempted();
        } catch (java.util.concurrent.ExecutionException e) {
            log.warn("quarantine-pending LLM call failed for email={}, fail-degrading: {}",
                    request.email().id(), e.getCause() == null ? e.toString() : e.getCause().toString());
            return LlmOutcome.notAttempted();
        }
    }

    /**
     * Everything the resolution needs, captured at decide-time. Bundled into one record so the
     * synchronous and async sides share a single, self-describing argument rather than a long
     * positional parameter list threaded across the async boundary.
     *
     * @param email             the email being decided
     * @param reasons           why the router escalated it (grounded into the LLM prompt)
     * @param scores            the model scores recorded on both the pending and resolved rows
     * @param fused             the reputation-fused posterior
     * @param policyVersion     the active policy the decision was made under
     * @param fastPathTier      the posterior-derived tier to fall back to on a degrade
     * @param hardRuleFloor     the hard-rule severity the resolution may never drop below
     * @param fastPathLatencyMs the fast path's latency, recorded on the pending row
     */
    public record ResolutionRequest(
            Email email,
            List<RoutingReason> reasons,
            ModelScores scores,
            FusedScore fused,
            String policyVersion,
            Decision fastPathTier,
            Decision hardRuleFloor,
            long fastPathLatencyMs) {

        /** The routing reasons as reason codes for the pending row — none today; kept empty. */
        List<ReasonCode> routingReasonCodes() {
            return List.of();
        }
    }
}
