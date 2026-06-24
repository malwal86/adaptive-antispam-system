package com.antispam.arena;

import static org.assertj.core.api.Assertions.assertThat;

import com.antispam.seed.GroundTruthLabel;
import org.junit.jupiter.api.Test;

/**
 * The two-track distinction (story 08.02b, PRD §Subsystem 6): the arena stresses two dimensions of
 * the defender. Track {@link Track#SPAM} mutates abuse seeds and a variant "wins" by being delivered
 * (a bypass — recall stress); Track {@link Track#LEGIT} mutates legit (ham) seeds and a variant
 * "wins" by being withheld (a false positive — precision stress). A variant's track is its
 * ground-truth class, so the two are never confused, and each track only mutates seeds whose label it
 * can preserve.
 */
class TrackTest {

    @Test
    void a_variants_track_is_its_ground_truth_class() {
        assertThat(Track.of(GroundTruthLabel.SPAM)).isEqualTo(Track.SPAM);
        assertThat(Track.of(GroundTruthLabel.PHISH)).isEqualTo(Track.SPAM);
        assertThat(Track.of(GroundTruthLabel.HAM)).isEqualTo(Track.LEGIT);
    }

    @Test
    void each_track_accepts_only_the_seeds_whose_label_it_preserves() {
        // Track A perturbs abuse, never legit mail (mutating ham is Track B's job).
        assertThat(Track.SPAM.accepts(GroundTruthLabel.SPAM)).isTrue();
        assertThat(Track.SPAM.accepts(GroundTruthLabel.PHISH)).isTrue();
        assertThat(Track.SPAM.accepts(GroundTruthLabel.HAM)).isFalse();
        // Track B perturbs only legit mail: you cannot stress precision with an abuse seed.
        assertThat(Track.LEGIT.accepts(GroundTruthLabel.HAM)).isTrue();
        assertThat(Track.LEGIT.accepts(GroundTruthLabel.SPAM)).isFalse();
        assertThat(Track.LEGIT.accepts(GroundTruthLabel.PHISH)).isFalse();
    }

    @Test
    void the_attacker_wins_a_spam_variant_by_bypassing_and_a_legit_variant_by_being_blocked() {
        // Track A (recall): a bypass — the spam was delivered to the inbox.
        assertThat(Track.SPAM.attackerWon(true)).isTrue();
        assertThat(Track.SPAM.attackerWon(false)).isFalse();
        // Track B (precision): a false positive — the good mail was withheld, not delivered.
        assertThat(Track.LEGIT.attackerWon(false)).isTrue();
        assertThat(Track.LEGIT.attackerWon(true)).isFalse();
    }

    @Test
    void legit_track_uses_only_meaning_preserving_strategies_so_the_ham_stays_ham() {
        // Reframing ("the malicious ask does not change") and homoglyph evasion would not preserve a
        // good message's ground truth, so Track B is restricted to paraphrase and reformat.
        assertThat(Track.LEGIT.applicableStrategies())
                .containsExactlyInAnyOrder(MutationStrategy.SYNONYM, MutationStrategy.STRUCTURE);
        assertThat(Track.SPAM.applicableStrategies()).containsExactlyInAnyOrder(MutationStrategy.values());
    }

    @Test
    void renders_a_stable_lowercase_token_for_the_wire() {
        assertThat(Track.SPAM.token()).isEqualTo("spam");
        assertThat(Track.LEGIT.token()).isEqualTo("legit");
    }
}
