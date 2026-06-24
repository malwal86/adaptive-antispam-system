package com.antispam.arena;

import static org.assertj.core.api.Assertions.assertThat;

import com.antispam.seed.GroundTruthLabel;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The gap-targeting selection (story 08.02, AC 2): the next generation attacks only what bypassed,
 * re-applying the very strategy that worked — so attacker effort concentrates on previously-successful
 * strategies/variants rather than restarting. A variant the defender caught yields no target, so a
 * generation that catches everything leaves no gap (and the loop converges).
 */
class AttackLoopTargetingTest {

    private static final UUID SEED = UUID.randomUUID();

    @Test
    void next_generation_targets_only_the_variants_that_bypassed() {
        AdversarialEmail slipped = variant(MutationStrategy.HOMOGLYPH);
        AdversarialEmail caught = variant(MutationStrategy.SYNONYM);

        List<AttackTarget> next = AttackLoopService.targetGaps(List.of(
                new ScoredVariant(slipped, true), new ScoredVariant(caught, false)));

        // Only the bypassing variant becomes a target, and it carries that variant as its parent so the
        // next generation builds on it (an iterative attack), keeping the family's root seed.
        assertThat(next).singleElement().satisfies(target -> {
            assertThat(target.parentVariant()).isEqualTo(slipped);
            assertThat(target.seedEmailId()).isEqualTo(SEED);
            assertThat(target.strategy()).isEqualTo(MutationStrategy.HOMOGLYPH);
        });
    }

    @Test
    void reuses_the_strategy_that_bypassed_so_effort_concentrates_on_what_works() {
        AdversarialEmail byReframe = variant(MutationStrategy.REFRAME);
        AdversarialEmail byStructure = variant(MutationStrategy.STRUCTURE);

        List<AttackTarget> next = AttackLoopService.targetGaps(List.of(
                new ScoredVariant(byReframe, true), new ScoredVariant(byStructure, true)));

        assertThat(next).extracting(AttackTarget::strategy)
                .containsExactly(MutationStrategy.REFRAME, MutationStrategy.STRUCTURE);
    }

    @Test
    void a_generation_that_caught_everything_leaves_no_gap_to_target() {
        List<AttackTarget> next = AttackLoopService.targetGaps(List.of(
                new ScoredVariant(variant(MutationStrategy.SYNONYM), false),
                new ScoredVariant(variant(MutationStrategy.HOMOGLYPH), false)));

        assertThat(next).isEmpty();
    }

    private static AdversarialEmail variant(MutationStrategy strategy) {
        return new AdversarialEmail(UUID.randomUUID(), UUID.randomUUID(), SEED, null, strategy,
                GroundTruthLabel.SPAM, "attacker-x", UUID.randomUUID(), 1, Instant.EPOCH);
    }
}
