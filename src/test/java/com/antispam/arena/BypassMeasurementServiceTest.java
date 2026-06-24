package com.antispam.arena;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.antispam.decision.Decision;
import com.antispam.decision.RouteUsed;
import com.antispam.decision.policy.Policy;
import com.antispam.decision.policy.PolicyRepository;
import com.antispam.decision.policy.PolicyScorer;
import com.antispam.decision.policy.ScoredDecision;
import com.antispam.ingest.Email;
import com.antispam.ingest.EmailRepository;
import com.antispam.ingest.ParsedEmail;
import com.antispam.retrain.RetrainLabel;
import com.antispam.retrain.RetrainLabelRepository;
import com.antispam.seed.GroundTruthLabel;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The bypass measurement and corpus feedback (story 08.04), pinned with mocks so it needs no provider
 * or database. It proves the run's variants are scored against a fixed baseline and the baseline bypass
 * rate stamped on the run (AC 2), every variant that beat the fixed defender — abuse delivered and legit
 * wrongly blocked — is fed into the retrain corpus labeled with arena provenance (AC 3), arena ground
 * truth is tagged a distinct source so eval integrity can keep it out of the golden set (AC 5), and the
 * cross-run trend reports whether bypass rate dropped (AC 4).
 */
@ExtendWith(MockitoExtension.class)
class BypassMeasurementServiceTest {

    @Mock
    private PolicyScorer scorer;
    @Mock
    private PolicyRepository policies;
    @Mock
    private EmailRepository emails;
    @Mock
    private AdversarialRunRepository runs;
    @Mock
    private AdversarialEmailRepository variants;
    @Mock
    private RetrainLabelRepository retrainLabels;

    private static final UUID RUN_ID = UUID.randomUUID();
    private static final Policy GENESIS = policy("pol-genesis");
    private static final Policy CONFIGURED = policy("pol-configured");

    private BypassMeasurementService service(String baselineVersion, double weight) {
        ArenaProperties props = new ArenaProperties(
                true, "attacker-x", 3, null, null, baselineVersion, weight);
        return new BypassMeasurementService(scorer, policies, emails, runs, variants, retrainLabels,
                props, new ObjectMapper());
    }

    @Test
    void scores_track_a_variants_against_the_genesis_baseline_and_records_the_rate() {
        when(policies.findOldest()).thenReturn(Optional.of(GENESIS));
        AdversarialEmail bypassed = abuse();
        AdversarialEmail caught = abuse();
        when(variants.findByRun(RUN_ID)).thenReturn(List.of(bypassed, caught));
        // Under the baseline one abuse variant is delivered (a bypass), the other withheld → rate 0.5.
        stubBaselineVerdict(bypassed, Decision.ALLOW);
        stubBaselineVerdict(caught, Decision.BLOCK);
        when(runs.recordBaseline(eq(RUN_ID), eq("pol-genesis"), eq(0.5))).thenReturn(measuredRun());

        service(null, 1.0).measure(runWith(0.5, null));

        verify(runs).recordBaseline(RUN_ID, "pol-genesis", 0.5);
    }

    @Test
    void prefers_the_configured_baseline_over_the_genesis_when_it_exists() {
        when(policies.findByVersion("pol-configured")).thenReturn(Optional.of(CONFIGURED));
        AdversarialEmail v = abuse();
        when(variants.findByRun(RUN_ID)).thenReturn(List.of(v));
        stubBaselineVerdict(v, Decision.ALLOW);
        when(runs.recordBaseline(eq(RUN_ID), eq("pol-configured"), any())).thenReturn(measuredRun());

        service("pol-configured", 1.0).measure(runWith(0.5, null));

        verify(runs).recordBaseline(RUN_ID, "pol-configured", 1.0);
        verify(policies, never()).findOldest();
    }

    @Test
    void records_a_null_baseline_rate_for_a_legit_only_run() {
        when(policies.findOldest()).thenReturn(Optional.of(GENESIS));
        // Only a Track B (ham) variant: there is no abuse to measure recall bypass over.
        when(variants.findByRun(RUN_ID)).thenReturn(List.of(ham()));
        when(runs.recordBaseline(eq(RUN_ID), eq("pol-genesis"), eq(null))).thenReturn(measuredRun());

        service(null, 1.0).measure(runWith(null, 0.0));

        verify(runs).recordBaseline(RUN_ID, "pol-genesis", null);
    }

    @Test
    void skips_the_baseline_but_still_feeds_the_corpus_when_no_policy_is_resolvable() {
        when(policies.findOldest()).thenReturn(Optional.empty());
        when(variants.findBypassingAbuse(RUN_ID)).thenReturn(List.of(abuse()));

        service(null, 1.0).measure(runWith(1.0, null));

        verify(runs, never()).recordBaseline(any(), any(), any());
        verify(retrainLabels).saveAll(any());
    }

    @Test
    void feeds_bypassing_abuse_and_wrongly_blocked_ham_into_the_corpus_with_arena_provenance() {
        when(policies.findOldest()).thenReturn(Optional.of(GENESIS));
        when(variants.findByRun(RUN_ID)).thenReturn(List.of()); // isolate corpus feedback from baseline scoring
        when(runs.recordBaseline(eq(RUN_ID), eq("pol-genesis"), eq(null))).thenReturn(measuredRun());
        AdversarialEmail bypass = abuse();
        AdversarialEmail falsePositive = ham();
        when(variants.findBypassingAbuse(RUN_ID)).thenReturn(List.of(bypass));
        when(variants.findWronglyBlockedHam(RUN_ID)).thenReturn(List.of(falsePositive));

        service(null, 2.0).measure(runWith(1.0, 1.0));

        ArgumentCaptor<List<RetrainLabel>> saved = labelCaptor();
        verify(retrainLabels).saveAll(saved.capture());
        List<RetrainLabel> labels = saved.getValue();
        assertThat(labels).hasSize(2);
        // The bypassing abuse keeps its abuse class; the wrongly-blocked legit stays ham. Both are arena
        // ground truth, weighted by config, and carry the run/variant/outcome provenance.
        assertThat(labels).allSatisfy(label -> {
            assertThat(label.source()).isEqualTo("arena");
            assertThat(label.weight()).isEqualTo(2.0);
            assertThat(label.provenance()).contains("\"runId\":\"" + RUN_ID + "\"");
        });
        assertThat(labels).anySatisfy(label -> {
            assertThat(label.emailId()).isEqualTo(bypass.variantEmailId());
            assertThat(label.label()).isEqualTo(GroundTruthLabel.SPAM);
            assertThat(label.provenance()).contains("\"outcome\":\"bypass\"");
        });
        assertThat(labels).anySatisfy(label -> {
            assertThat(label.emailId()).isEqualTo(falsePositive.variantEmailId());
            assertThat(label.label()).isEqualTo(GroundTruthLabel.HAM);
            assertThat(label.provenance()).contains("\"outcome\":\"false_positive\"");
        });
    }

    @Test
    void writes_nothing_to_the_corpus_when_the_defender_was_beaten_nowhere() {
        when(policies.findOldest()).thenReturn(Optional.of(GENESIS));
        when(variants.findByRun(RUN_ID)).thenReturn(List.of());
        when(runs.recordBaseline(eq(RUN_ID), eq("pol-genesis"), eq(null))).thenReturn(measuredRun());
        when(variants.findBypassingAbuse(RUN_ID)).thenReturn(List.of());
        when(variants.findWronglyBlockedHam(RUN_ID)).thenReturn(List.of());

        service(null, 1.0).measure(runWith(0.0, 0.0));

        verify(retrainLabels, never()).saveAll(any());
    }

    @Test
    void trend_reports_improved_when_the_latest_bypass_rate_is_below_the_first() {
        when(runs.findRecentTerminal(20)).thenReturn(List.of(
                trendRun(0.6), trendRun(0.45), trendRun(0.3)));

        BypassTrend trend = service(null, 1.0).trend(20);

        assertThat(trend.points()).hasSize(3);
        assertThat(trend.firstBypassRate()).isEqualTo(0.6);
        assertThat(trend.latestBypassRate()).isEqualTo(0.3);
        assertThat(trend.improved()).isTrue();
    }

    @Test
    void trend_is_not_improved_when_the_bypass_rate_rises() {
        when(runs.findRecentTerminal(20)).thenReturn(List.of(trendRun(0.2), trendRun(0.5)));

        BypassTrend trend = service(null, 1.0).trend(20);

        assertThat(trend.improved()).isFalse();
        assertThat(trend.firstBypassRate()).isEqualTo(0.2);
        assertThat(trend.latestBypassRate()).isEqualTo(0.5);
    }

    @Test
    void trend_picks_the_first_and_latest_non_null_rates() {
        // A legit-only run in the middle has a null bypass rate and must not anchor the trend ends.
        when(runs.findRecentTerminal(20)).thenReturn(List.of(
                trendRun(0.6), trendRun(null), trendRun(0.2)));

        BypassTrend trend = service(null, 1.0).trend(20);

        assertThat(trend.firstBypassRate()).isEqualTo(0.6);
        assertThat(trend.latestBypassRate()).isEqualTo(0.2);
        assertThat(trend.improved()).isTrue();
    }

    // --- stubs / builders -------------------------------------------------------------------------

    private void stubBaselineVerdict(AdversarialEmail variant, Decision decision) {
        Email email = email(variant.variantEmailId());
        when(emails.findById(variant.variantEmailId())).thenReturn(Optional.of(email));
        when(scorer.score(eq(email), any())).thenReturn(new ScoredDecision(
                decision, List.of(), RouteUsed.MODEL, List.of(), "pol-genesis", 0.5));
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<List<RetrainLabel>> labelCaptor() {
        return ArgumentCaptor.forClass(List.class);
    }

    private static AdversarialEmail abuse() {
        return variant(GroundTruthLabel.SPAM);
    }

    private static AdversarialEmail ham() {
        return variant(GroundTruthLabel.HAM);
    }

    private static AdversarialEmail variant(GroundTruthLabel label) {
        return new AdversarialEmail(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null,
                MutationStrategy.SYNONYM, label, "attacker-x", RUN_ID, 1,
                label == GroundTruthLabel.HAM ? false : true, Instant.EPOCH);
    }

    private static AdversarialRun runWith(Double actualBypassRate, Double precisionFpRate) {
        return new AdversarialRun(RUN_ID, "attacker-x", "model-7", "pol-active", 0.5, actualBypassRate,
                precisionFpRate, null, null, 3, new BigDecimal("1.00"), new BigDecimal("0.03"), 1,
                RunStatus.COMPLETED, Instant.EPOCH, Instant.EPOCH);
    }

    private static AdversarialRun measuredRun() {
        return new AdversarialRun(RUN_ID, "attacker-x", "model-7", "pol-active", 0.5, 0.5, null,
                "pol-genesis", 0.5, 3, new BigDecimal("1.00"), new BigDecimal("0.03"), 1,
                RunStatus.COMPLETED, Instant.EPOCH, Instant.EPOCH);
    }

    private static AdversarialRun trendRun(Double actualBypassRate) {
        return new AdversarialRun(UUID.randomUUID(), "attacker-x", "model-7", "pol-active", 0.5,
                actualBypassRate, null, "pol-genesis", 0.7, 3, new BigDecimal("1.00"),
                new BigDecimal("0.03"), 1, RunStatus.COMPLETED, Instant.EPOCH, Instant.EPOCH);
    }

    private static Email email(UUID id) {
        return new Email(id, new byte[32], "Subject: x\n\nbody".getBytes(),
                new ParsedEmail("s@evil.test", "evil.test", null, "x", null, null), "adversarial",
                Instant.EPOCH);
    }

    private static Policy policy(String version) {
        return new Policy(version, false, 0.5, 0.7, 0.9, 0.5, 0.05, 5, "model-7", Instant.EPOCH);
    }
}
