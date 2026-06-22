package com.antispam.decision.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.antispam.decision.FusedScore;
import com.antispam.decision.ModelScores;
import com.antispam.decision.policy.Policy;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * The LLM-routing predicates in isolation (story 05.01, PRD §Subsystem 1 step 5). The contract:
 * a fused model decision routes to the LLM if <em>any</em> of three predicates fires — low model
 * confidence, high sender (reputation) uncertainty, or a posterior near a tier boundary — and the
 * thresholds come from the active policy, never code. The Beta variance feeds two distinct signals:
 * the content-independent {@code senderUncertainty} fires the new-sender predicate (so a confident
 * verdict on an unseen sender still escalates), while the posterior-attenuated {@code uncertaintyBand}
 * widens the boundary band (PRD §Subsystem 2).
 *
 * <p>Boundaries under test: confidence floor 0.40 (so the uncertain band is calibrated
 * confidence in (0.30, 0.70)), tier cut-points warn 0.50 / quarantine 0.80 / block 0.95, and a
 * routing band half-width of 0.05.
 */
class LlmRouterTest {

    private static final Policy POLICY = policy(0.40, 0.05);

    @Test
    void a_confident_low_uncertainty_mid_tier_decision_stays_on_the_fast_path() {
        // Confident (0.95 → model confidence 0.90), posterior 0.65 a clear WARN far from every
        // boundary, no sender uncertainty: none of the predicates fire.
        RoutingDecision routing = LlmRouter.decide(POLICY, fused(0.65, 0.0), scores(0.95));

        assertThat(routing.routed()).isFalse();
        assertThat(routing.reasons()).isEmpty();
    }

    @Test
    void low_calibrated_model_confidence_routes_to_the_llm() {
        // 0.55 → model confidence 0.10 < 0.40. Posterior 0.65 / band 0.0 isolate the predicate.
        RoutingDecision routing = LlmRouter.decide(POLICY, fused(0.65, 0.0), scores(0.55));

        assertThat(routing.reasons()).containsExactly(RoutingReason.LOW_MODEL_CONFIDENCE);
    }

    @Test
    void model_confidence_exactly_at_the_threshold_is_not_low_enough_to_route() {
        // Exactly-representable arithmetic: 0.75 → model confidence exactly 0.50, against a 0.50
        // floor. The predicate is a strict <, so confidence equal to the floor does not fire.
        Policy floorAtHalf = policy(0.50, 0.05);
        RoutingDecision routing = LlmRouter.decide(floorAtHalf, fused(0.65, 0.0), scores(0.75));

        assertThat(routing.reasons()).doesNotContain(RoutingReason.LOW_MODEL_CONFIDENCE);
    }

    @Test
    void a_new_sender_with_wide_beta_variance_routes_to_the_llm() {
        // Confident model (0.95), posterior 0.65 far from boundaries, but a wide content-independent
        // sender uncertainty (0.08 ≥ 0.05) from a new sender's Beta variance routes it on its own —
        // the confident verdict does not let a brand-new sender skip the LLM.
        RoutingDecision routing = LlmRouter.decide(POLICY, fusedSender(0.65, 0.08), scores(0.95));

        assertThat(routing.reasons()).containsExactly(RoutingReason.NEW_SENDER_UNCERTAINTY);
    }

    @Test
    void sender_uncertainty_exactly_at_the_band_width_routes_as_a_new_sender() {
        // Sender uncertainty exactly 0.05 == routingBandWidth; the predicate is ≥, so it fires.
        RoutingDecision routing = LlmRouter.decide(POLICY, fusedSender(0.65, 0.05), scores(0.95));

        assertThat(routing.reasons()).containsExactly(RoutingReason.NEW_SENDER_UNCERTAINTY);
    }

    @Test
    void a_posterior_within_the_band_of_a_tier_boundary_routes_to_the_llm() {
        // Posterior 0.52 is 0.02 from the warn boundary 0.50, inside the 0.05 band; confident
        // model and no sender uncertainty isolate the boundary predicate.
        RoutingDecision routing = LlmRouter.decide(POLICY, fused(0.52, 0.0), scores(0.95));

        assertThat(routing.reasons()).containsExactly(RoutingReason.NEAR_TIER_BOUNDARY);
    }

    @Test
    void the_attenuated_band_widens_the_boundary_band() {
        // Posterior 0.58 is 0.08 from the warn boundary 0.50 — outside the fixed 0.05 band.
        // With a zero band it does not route...
        RoutingDecision withoutBand = LlmRouter.decide(POLICY, fused(0.58, 0.0), scores(0.95));
        assertThat(withoutBand.routed()).isFalse();

        // ...but a 0.04 attenuated band widens the boundary band to 0.09, which now reaches the
        // 0.08 gap. Sender uncertainty is zero here, so only the boundary predicate fires.
        RoutingDecision withBand = LlmRouter.decide(POLICY, fused(0.58, 0.04), scores(0.95));
        assertThat(withBand.reasons()).containsExactly(RoutingReason.NEAR_TIER_BOUNDARY);
    }

    @Test
    void the_predicates_combine_as_an_or_and_each_firing_reason_is_recorded() {
        // Low confidence (0.55), wide sender uncertainty (0.10), and posterior 0.50 on the warn
        // boundary: all three predicates fire and all three reasons are recorded.
        RoutingDecision routing = LlmRouter.decide(POLICY, fusedSender(0.50, 0.10), scores(0.55));

        assertThat(routing.routed()).isTrue();
        assertThat(routing.reasons()).containsExactlyInAnyOrder(
                RoutingReason.LOW_MODEL_CONFIDENCE,
                RoutingReason.NEW_SENDER_UNCERTAINTY,
                RoutingReason.NEAR_TIER_BOUNDARY);
    }

    @Test
    void the_confidence_threshold_is_sourced_from_the_policy() {
        // 0.65 → model confidence 0.30. It is "low" under a 0.40 floor but not under a 0.20 floor —
        // proving the threshold comes from the policy, not the code. (Posterior far, band 0.)
        FusedScore borderline = fused(0.65, 0.0);
        ModelScores scores = scores(0.65);

        assertThat(LlmRouter.decide(policy(0.40, 0.05), borderline, scores).reasons())
                .containsExactly(RoutingReason.LOW_MODEL_CONFIDENCE);
        assertThat(LlmRouter.decide(policy(0.20, 0.05), borderline, scores).routed())
                .isFalse();
    }

    @Test
    void the_boundary_band_width_is_sourced_from_the_policy() {
        // Posterior 0.58 is 0.08 from the warn boundary: near under a 0.10 band, not under a 0.05
        // band. Confident model, no sender uncertainty, so only the band width decides.
        FusedScore borderline = fused(0.58, 0.0);
        ModelScores scores = scores(0.95);

        assertThat(LlmRouter.decide(policy(0.40, 0.10), borderline, scores).reasons())
                .containsExactly(RoutingReason.NEAR_TIER_BOUNDARY);
        assertThat(LlmRouter.decide(policy(0.40, 0.05), borderline, scores).routed())
                .isFalse();
    }

    private static Policy policy(double llmThreshold, double routingBandWidth) {
        return new Policy("routing-test-v1", true, 0.50, 0.80, 0.95,
                llmThreshold, routingBandWidth, "bootstrap-v1", Instant.EPOCH);
    }

    /** A fused score with a posterior and an attenuated boundary band, and no sender uncertainty. */
    private static FusedScore fused(double posterior, double uncertaintyBand) {
        // posteriorLogit is unused by routing; 0.0 keeps the fixture valid (must be finite).
        return new FusedScore(posterior, 0.0, uncertaintyBand, 0.0);
    }

    /** A fused score with the given content-independent sender uncertainty and no boundary band. */
    private static FusedScore fusedSender(double posterior, double senderUncertainty) {
        return new FusedScore(posterior, 0.0, 0.0, senderUncertainty);
    }

    private static ModelScores scores(double calibratedConfidence) {
        return new ModelScores(0.0, 0.0, "bootstrap-v1", calibratedConfidence);
    }
}
