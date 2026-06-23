package com.antispam.feedback;

import static org.assertj.core.api.Assertions.assertThat;

import com.antispam.decision.Decision;
import com.antispam.seed.GroundTruthLabel;
import java.util.EnumMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The truth-conditioned action sampler (story 07.02). Probabilistic, so it is tested with seeded
 * draws and asserted on distribution shape and directional properties, per the AI-aware TDD
 * guidance — never on a single exact draw. Covers: actions stay inside the decision-conditioned
 * space (AC 1/2), rates track ground truth + persona bias (AC 3), the event fields are populated
 * (AC 4), and a fixed seed reproduces the exact sequence (AC 5).
 */
class ActionSamplerTest {

    private static final int DRAWS = 5_000;

    private final ActionSampler sampler = new ActionSampler();

    private static Persona persona(String name, double click, double report, double risk) {
        return new Persona(Persona.idForName(name), name, click, report, risk, new PersonaConfig(false));
    }

    private static DecidedEmail decided(Decision shown, GroundTruthLabel truth) {
        return new DecidedEmail(UUID.randomUUID(), shown, truth);
    }

    /** Action frequencies over many seeded draws for one (email, persona). */
    private Map<FeedbackAction, Integer> histogram(DecidedEmail email, Persona persona, long seed) {
        Random rng = new Random(seed);
        Map<FeedbackAction, Integer> counts = new EnumMap<>(FeedbackAction.class);
        for (int i = 0; i < DRAWS; i++) {
            SampledAction sampled = sampler.sample(email, persona, rng);
            counts.merge(sampled.action(), 1, Integer::sum);
            assertThat(sampled.confidence()).isBetween(0.0, 1.0);
            assertThat(sampled.delaySeconds()).isBetween(0L, ActionSampler.MAX_DELAY_SECONDS);
        }
        return counts;
    }

    private double rate(Map<FeedbackAction, Integer> hist, FeedbackAction action) {
        return hist.getOrDefault(action, 0) / (double) DRAWS;
    }

    @Test
    void delivered_mail_never_yields_rescue_and_withheld_never_yields_click_or_report() {
        Persona p = persona("mixed", 0.6, 0.6, 0.6);

        Map<FeedbackAction, Integer> delivered = histogram(decided(Decision.ALLOW, GroundTruthLabel.SPAM), p, 1L);
        assertThat(delivered).containsOnlyKeys(FeedbackAction.CLICK, FeedbackAction.REPORT, FeedbackAction.IGNORE);

        Map<FeedbackAction, Integer> withheld = histogram(decided(Decision.QUARANTINE, GroundTruthLabel.HAM), p, 1L);
        assertThat(withheld).containsOnlyKeys(FeedbackAction.RESCUE, FeedbackAction.IGNORE);
    }

    @Test
    void a_high_report_bias_persona_reports_delivered_spam_far_more_than_delivered_ham() {
        Persona reporter = persona("reporter", 0.3, 0.95, 0.3);

        double spamReports = rate(histogram(decided(Decision.ALLOW, GroundTruthLabel.SPAM), reporter, 7L), FeedbackAction.REPORT);
        double hamReports = rate(histogram(decided(Decision.ALLOW, GroundTruthLabel.HAM), reporter, 7L), FeedbackAction.REPORT);

        assertThat(spamReports).isGreaterThan(0.5);   // recognizes and reports actual spam
        assertThat(hamReports).isLessThan(0.2);        // rarely misreports legitimate mail
        assertThat(spamReports).isGreaterThan(hamReports * 2);
    }

    @Test
    void report_rate_on_delivered_spam_scales_with_report_bias() {
        Persona low = persona("low-report", 0.3, 0.2, 0.3);
        Persona high = persona("high-report", 0.3, 0.9, 0.3);

        double lowRate = rate(histogram(decided(Decision.ALLOW, GroundTruthLabel.SPAM), low, 11L), FeedbackAction.REPORT);
        double highRate = rate(histogram(decided(Decision.ALLOW, GroundTruthLabel.SPAM), high, 11L), FeedbackAction.REPORT);

        assertThat(highRate).isGreaterThan(lowRate);
    }

    @Test
    void rescue_rate_is_higher_for_a_withheld_ham_false_positive_than_for_withheld_spam() {
        Persona p = persona("override-prone", 0.5, 0.3, 0.9);

        double hamRescues = rate(histogram(decided(Decision.QUARANTINE, GroundTruthLabel.HAM), p, 13L), FeedbackAction.RESCUE);
        double spamRescues = rate(histogram(decided(Decision.QUARANTINE, GroundTruthLabel.SPAM), p, 13L), FeedbackAction.RESCUE);

        assertThat(hamRescues).isGreaterThan(0.5);     // wants its wrongly-quarantined real mail back
        assertThat(spamRescues).isLessThan(0.25);      // rarely rescues actual spam
        assertThat(hamRescues).isGreaterThan(spamRescues);
    }

    @Test
    void a_fixed_seed_reproduces_the_exact_action_sequence() {
        Persona p = persona("repro", 0.5, 0.5, 0.5);
        DecidedEmail email = decided(Decision.ALLOW, GroundTruthLabel.SPAM);

        Random a = new Random(99L);
        Random b = new Random(99L);
        for (int i = 0; i < 200; i++) {
            SampledAction sa = sampler.sample(email, p, a);
            SampledAction sb = sampler.sample(email, p, b);
            assertThat(sb).isEqualTo(sa);
        }
    }
}
