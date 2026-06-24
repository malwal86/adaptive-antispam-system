package com.antispam.arena;

import com.antispam.seed.GroundTruthLabel;
import java.util.Locale;
import java.util.Set;

/**
 * The two regimes a red-team run can attack (story 08.02b, PRD §Subsystem 6): the arena is
 * <em>two-track</em> so hardening one dimension of the defender cannot silently wreck the other.
 *
 * <ul>
 *   <li>{@link #SPAM} (Track A) mutates real abuse seeds (spam/phish) and stresses <b>recall</b>: a
 *       variant <em>wins</em> by being delivered to the inbox — a bypass.</li>
 *   <li>{@link #LEGIT} (Track B) mutates real legit mail (ham) and stresses <b>precision</b>: a
 *       variant <em>wins</em> by being withheld — a false positive, the defender blocking good mail
 *       under pressure. Wrongly-blocked legit variants are the precision-floor retrain corpus
 *       (Epic 10/11).</li>
 * </ul>
 *
 * <p>A variant's track is exactly its ground-truth class ({@link #of}), so the two are never
 * confused and the track need not be stored redundantly — the preserved label already carries it.
 * Each track {@link #accepts accepts} only the seeds whose label it can preserve and applies only the
 * strategies that keep that label intact: Track B is restricted to the meaning-preserving
 * perturbations ({@link MutationStrategy#SYNONYM paraphrase} and {@link MutationStrategy#STRUCTURE
 * reformat}), because reframing or homoglyph evasion would not leave a good message ham.
 */
public enum Track {

    /** Track A — mutate abuse seeds, measure bypass (recall stress). A win is a delivered variant. */
    SPAM(Set.of(MutationStrategy.SYNONYM, MutationStrategy.HOMOGLYPH,
            MutationStrategy.STRUCTURE, MutationStrategy.REFRAME)) {
        @Override
        boolean accepts(GroundTruthLabel label) {
            return label != GroundTruthLabel.HAM;
        }

        @Override
        boolean attackerWon(boolean delivered) {
            return delivered;
        }
    },

    /** Track B — mutate legit mail, measure false positives (precision stress). A win is a blocked variant. */
    LEGIT(Set.of(MutationStrategy.SYNONYM, MutationStrategy.STRUCTURE)) {
        @Override
        boolean accepts(GroundTruthLabel label) {
            return label == GroundTruthLabel.HAM;
        }

        @Override
        boolean attackerWon(boolean delivered) {
            return !delivered;
        }
    };

    private final Set<MutationStrategy> applicableStrategies;

    Track(Set<MutationStrategy> applicableStrategies) {
        this.applicableStrategies = applicableStrategies;
    }

    /** Whether this track may mutate a seed of {@code label} — i.e. it can preserve that ground truth. */
    abstract boolean accepts(GroundTruthLabel label);

    /**
     * Whether the attacker won this variant given the defender's delivery verdict: a {@link #SPAM}
     * variant wins by being {@code delivered} (a bypass), a {@link #LEGIT} variant wins by <em>not</em>
     * being delivered (a false positive). This is also the gap the next generation re-attacks.
     */
    abstract boolean attackerWon(boolean delivered);

    /** The strategies this track may apply without breaking the seed's ground-truth label. */
    Set<MutationStrategy> applicableStrategies() {
        return applicableStrategies;
    }

    /** The track a variant belongs to, derived from its preserved ground-truth class. */
    public static Track of(GroundTruthLabel label) {
        return label == GroundTruthLabel.HAM ? LEGIT : SPAM;
    }

    /** The stable lowercase token the wire view tags a variant's track with (not stored — derived). */
    public String token() {
        return name().toLowerCase(Locale.ROOT);
    }
}
