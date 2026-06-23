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
 * Malicious bomber action generation (story 07.04): a malicious persona attacks <em>against</em>
 * ground truth — a report bomber preferentially reports delivered <em>ham</em>, a rescue bomber
 * preferentially rescues withheld <em>spam</em> — the inverse of the good-faith, truth-tracking
 * baseline (covered by {@link ActionSamplerTest}). Probabilistic, so asserted on directional
 * properties over many seeded draws, never a single draw.
 */
class MaliciousActionSamplerTest {

    private static final int DRAWS = 5_000;

    private final ActionSampler sampler = new ActionSampler();

    private static Persona bomber(String name, double click, double report, double risk) {
        return new Persona(Persona.idForName(name), name, click, report, risk, new PersonaConfig(true));
    }

    private static Persona goodFaith(String name, double click, double report, double risk) {
        return new Persona(Persona.idForName(name), name, click, report, risk, new PersonaConfig(false));
    }

    private static DecidedEmail decided(Decision shown, GroundTruthLabel truth) {
        return new DecidedEmail(UUID.randomUUID(), shown, truth);
    }

    private double rate(Persona persona, DecidedEmail email, FeedbackAction action, long seed) {
        Random rng = new Random(seed);
        Map<FeedbackAction, Integer> counts = new EnumMap<>(FeedbackAction.class);
        for (int i = 0; i < DRAWS; i++) {
            counts.merge(sampler.sample(email, persona, rng).action(), 1, Integer::sum);
        }
        return counts.getOrDefault(action, 0) / (double) DRAWS;
    }

    @Test
    void a_report_bomber_reports_delivered_ham_far_more_than_delivered_spam() {
        Persona reportBomber = bomber("report-bomber", 0.05, 0.98, 0.10);

        double hamReports = rate(reportBomber, decided(Decision.ALLOW, GroundTruthLabel.HAM), FeedbackAction.REPORT, 7L);
        double spamReports = rate(reportBomber, decided(Decision.ALLOW, GroundTruthLabel.SPAM), FeedbackAction.REPORT, 7L);

        assertThat(hamReports).isGreaterThan(0.5);          // attacks legitimate mail
        assertThat(hamReports).isGreaterThan(spamReports);  // the inverse of a good-faith reporter
    }

    @Test
    void a_rescue_bomber_rescues_withheld_spam_far_more_than_withheld_ham() {
        Persona rescueBomber = bomber("rescue-bomber", 0.90, 0.02, 0.95);

        double spamRescues = rate(rescueBomber, decided(Decision.QUARANTINE, GroundTruthLabel.SPAM), FeedbackAction.RESCUE, 9L);
        double hamRescues = rate(rescueBomber, decided(Decision.QUARANTINE, GroundTruthLabel.HAM), FeedbackAction.RESCUE, 9L);

        assertThat(spamRescues).isGreaterThan(0.5);         // whitewashes the spam campaign
        assertThat(spamRescues).isGreaterThan(hamRescues);  // the inverse of a good-faith rescuer
    }

    @Test
    void the_malicious_flag_inverts_the_report_direction_at_equal_bias() {
        // Same biases, opposite intent: the bomber reports ham where the good-faith user reports spam.
        Persona bomber = bomber("inv-bad", 0.3, 0.9, 0.3);
        Persona honest = goodFaith("inv-good", 0.3, 0.9, 0.3);

        double bomberHam = rate(bomber, decided(Decision.ALLOW, GroundTruthLabel.HAM), FeedbackAction.REPORT, 21L);
        double honestHam = rate(honest, decided(Decision.ALLOW, GroundTruthLabel.HAM), FeedbackAction.REPORT, 21L);

        assertThat(bomberHam).isGreaterThan(honestHam);
    }
}
