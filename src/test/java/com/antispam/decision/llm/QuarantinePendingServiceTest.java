package com.antispam.decision.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.antispam.decision.Classification;
import com.antispam.decision.ClassificationRepository;
import com.antispam.decision.Decision;
import com.antispam.decision.DecisionMadeEvent;
import com.antispam.decision.DecisionOutcome;
import com.antispam.decision.ModelScores;
import com.antispam.decision.ReasonCode;
import com.antispam.decision.RouteUsed;
import com.antispam.decision.TestEmails;
import com.antispam.decision.hardrule.HardRuleCircuitBreaker;
import com.antispam.decision.llm.LlmVerdict.Verdict;
import com.antispam.decision.routing.RoutingReason;
import com.antispam.ingest.Email;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

/**
 * The async resolver's orchestration (story 05.06): a successful verdict appends a promoted/confirmed
 * resolution, a degrade appends a degraded one, and a call that overruns the SLA is abandoned and
 * fail-degrades. The pure mapping is pinned in {@link PendingResolutionTest}; here the focus is the
 * SLA boundary, the metrics, and the two-row pending→resolved lifecycle.
 */
class QuarantinePendingServiceTest {

    private final LlmFallbackService llm = org.mockito.Mockito.mock(LlmFallbackService.class);
    private final ClassificationRepository repository = org.mockito.Mockito.mock(ClassificationRepository.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final LlmMeter meter = new LlmMeter(registry);
    private final HardRuleCircuitBreaker breaker = new HardRuleCircuitBreaker();
    private final ApplicationEventPublisher events = org.mockito.Mockito.mock(ApplicationEventPublisher.class);
    private final ExecutorService slaExecutor = Executors.newCachedThreadPool();

    private final Email email = TestEmails.bodyContaining("ambiguous content");
    private final ModelScores scores = new ModelScores(0.5, 0.3, "bootstrap-v1", 0.5);

    @AfterEach
    void shutdown() {
        slaExecutor.shutdownNow();
    }

    /** A service with a 30s SLA and a synchronous resolution executor (the async hop runs inline). */
    private QuarantinePendingService service() {
        return service(Duration.ofSeconds(30));
    }

    private QuarantinePendingService service(Duration sla) {
        when(repository.save(any(), any(), any(), any(), any())).thenAnswer(this::echo);
        return new QuarantinePendingService(
                llm, repository, breaker, meter, new QuarantinePendingProperties(sla),
                Runnable::run, slaExecutor, events);
    }

    private Classification echo(org.mockito.invocation.InvocationOnMock inv) {
        DecisionOutcome o = inv.getArgument(1);
        return new Classification(inv.getArgument(0), inv.getArgument(0), o.decision(), o.reasonCodes(),
                o.route(), o.latencyMs(), o.scores(), inv.getArgument(2), inv.getArgument(3),
                inv.getArgument(4), Instant.EPOCH);
    }

    private LlmOutcome verdict(Verdict v) {
        LlmVerdict verdict = new LlmVerdict(v, 0.9, 0.1, List.of(ReasonCode.BENIGN_CONTENT), "x");
        return LlmOutcome.valid(verdict, 12L, new BigDecimal("0.0012"), 1);
    }

    private QuarantinePendingService.ResolutionRequest request() {
        return new QuarantinePendingService.ResolutionRequest(
                email, List.of(RoutingReason.LOW_MODEL_CONFIDENCE), scores, null, "bootstrap-v1",
                Decision.ALLOW, Decision.ALLOW, 7L);
    }

    @Test
    void begin_records_a_quarantine_pending_row_then_resolves_asynchronously() {
        when(llm.classify(any(), anyList())).thenReturn(verdict(Verdict.LEGITIMATE));

        Classification pending = service().beginResolution(request());

        // The synchronous response is the withheld quarantine-pending row...
        assertThat(pending.decision()).isEqualTo(Decision.QUARANTINE);
        assertThat(pending.route()).isEqualTo(RouteUsed.LLM);
        // ...and (with the synchronous executor) the resolution row was appended too: two saves.
        verify(repository, times(2)).save(any(), any(), any(), any(), any());
        assertThat(registry.get(LlmMeter.RESOLUTION).tag("state", "pending").counter().count()).isEqualTo(1.0);
        assertThat(registry.get(LlmMeter.RESOLUTION).tag("state", "promoted").counter().count()).isEqualTo(1.0);
    }

    @Test
    void a_legitimate_verdict_appends_a_promoted_resolution_with_the_real_cost() {
        when(llm.classify(any(), anyList())).thenReturn(verdict(Verdict.LEGITIMATE));

        service().resolve(request());

        ArgumentCaptor<DecisionOutcome> outcome = ArgumentCaptor.forClass(DecisionOutcome.class);
        ArgumentCaptor<BigDecimal> cost = ArgumentCaptor.forClass(BigDecimal.class);
        verify(repository).save(eq(email.id()), outcome.capture(), isNull(), eq("bootstrap-v1"), cost.capture());
        assertThat(outcome.getValue().decision()).isEqualTo(Decision.ALLOW);
        assertThat(outcome.getValue().route()).isEqualTo(RouteUsed.LLM);
        assertThat(cost.getValue()).isEqualByComparingTo("0.0012"); // the call's real cost
        assertThat(registry.get(LlmMeter.RESOLUTION).tag("state", "promoted").counter().count()).isEqualTo(1.0);
    }

    @Test
    void a_spam_verdict_appends_a_confirmed_quarantine() {
        when(llm.classify(any(), anyList())).thenReturn(verdict(Verdict.SPAM));

        service().resolve(request());

        ArgumentCaptor<DecisionOutcome> outcome = ArgumentCaptor.forClass(DecisionOutcome.class);
        verify(repository).save(any(), outcome.capture(), any(), any(), any());
        assertThat(outcome.getValue().decision()).isEqualTo(Decision.QUARANTINE);
        assertThat(registry.get(LlmMeter.RESOLUTION).tag("state", "confirmed").counter().count()).isEqualTo(1.0);
    }

    @Test
    void a_degraded_llm_outcome_appends_a_degraded_conservative_resolution() {
        when(llm.classify(any(), anyList())).thenReturn(LlmOutcome.notAttempted());

        service().resolve(request());

        ArgumentCaptor<DecisionOutcome> outcome = ArgumentCaptor.forClass(DecisionOutcome.class);
        ArgumentCaptor<BigDecimal> cost = ArgumentCaptor.forClass(BigDecimal.class);
        verify(repository).save(any(), outcome.capture(), any(), any(), cost.capture());
        assertThat(outcome.getValue().decision()).isEqualTo(Decision.QUARANTINE); // conservative bias
        assertThat(cost.getValue()).isNull(); // no call was billed
        assertThat(registry.get(LlmMeter.RESOLUTION).tag("state", "degraded").counter().count()).isEqualTo(1.0);
    }

    @Test
    void a_resolution_publishes_the_resolved_decision_to_the_live_stream() {
        // Regression: the async resolution appended the resolved row to the DB but never published a
        // DecisionMadeEvent, so the console's live stream (its only verdict source) stayed frozen on
        // the provisional QUARANTINE — every routed email appeared to "hang" in quarantine forever.
        when(llm.classify(any(), anyList())).thenReturn(verdict(Verdict.LEGITIMATE));

        service().resolve(request());

        ArgumentCaptor<DecisionMadeEvent> event = ArgumentCaptor.forClass(DecisionMadeEvent.class);
        verify(events).publishEvent(event.capture());
        // The published row is the resolved verdict (promoted to ALLOW), not the pending quarantine.
        assertThat(event.getValue().classification().emailId()).isEqualTo(email.id());
        assertThat(event.getValue().classification().decision()).isEqualTo(Decision.ALLOW);
        assertThat(event.getValue().classification().route()).isEqualTo(RouteUsed.LLM);
    }

    @Test
    void a_degraded_resolution_still_publishes_so_the_card_leaves_pending() {
        when(llm.classify(any(), anyList())).thenReturn(LlmOutcome.notAttempted());

        service().resolve(request());

        ArgumentCaptor<DecisionMadeEvent> event = ArgumentCaptor.forClass(DecisionMadeEvent.class);
        verify(events).publishEvent(event.capture());
        assertThat(event.getValue().classification().decision()).isEqualTo(Decision.QUARANTINE); // conservative
    }

    @Test
    void a_publish_failure_never_fails_the_resolution() {
        // The row is already persisted before publishing; a stream hiccup must not throw out of resolve.
        when(llm.classify(any(), anyList())).thenReturn(verdict(Verdict.LEGITIMATE));
        org.mockito.Mockito.doThrow(new RuntimeException("stream down"))
                .when(events).publishEvent(any(DecisionMadeEvent.class));

        Classification row = service().resolve(request());

        assertThat(row.decision()).isEqualTo(Decision.ALLOW); // resolution completed despite the failure
    }

    @Test
    void a_call_that_overruns_the_sla_is_abandoned_and_fail_degrades() throws InterruptedException {
        // The LLM call blocks well past the tiny SLA; the resolver must not wait for it.
        CountDownLatch release = new CountDownLatch(1);
        when(llm.classify(any(), anyList())).thenAnswer(inv -> {
            release.await(5, TimeUnit.SECONDS);
            return verdict(Verdict.LEGITIMATE);
        });

        service(Duration.ofMillis(80)).resolve(request());
        release.countDown();

        ArgumentCaptor<DecisionOutcome> outcome = ArgumentCaptor.forClass(DecisionOutcome.class);
        verify(repository).save(any(), outcome.capture(), any(), any(), any());
        assertThat(outcome.getValue().decision()).isEqualTo(Decision.QUARANTINE); // degraded, withheld
        assertThat(registry.get(LlmMeter.SLA_TIMEOUT).counter().count()).isEqualTo(1.0);
        assertThat(registry.get(LlmMeter.RESOLUTION).tag("state", "degraded").counter().count()).isEqualTo(1.0);
    }
}
