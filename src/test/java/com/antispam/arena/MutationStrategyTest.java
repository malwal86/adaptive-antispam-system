package com.antispam.arena;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * The mutation strategy vocabulary (story 08.01): the four seed-grounded perturbation categories the
 * PRD names (synonym, homoglyph, structure, reframe). Each round-trips through its lowercase db token
 * and carries a non-blank attacker instruction, since the instruction is what tells the attacker
 * model <em>which</em> perturbation to apply while the engine logs only the category.
 */
class MutationStrategyTest {

    @Test
    void exposes_exactly_the_four_prd_strategies() {
        assertThat(MutationStrategy.values())
                .extracting(Enum::name)
                .containsExactlyInAnyOrder("SYNONYM", "HOMOGLYPH", "STRUCTURE", "REFRAME");
    }

    @Test
    void every_strategy_has_a_distinct_lowercase_db_token() {
        assertThat(Arrays.stream(MutationStrategy.values()).map(MutationStrategy::dbValue))
                .containsExactlyInAnyOrder("synonym", "homoglyph", "structure", "reframe")
                .allMatch(token -> token.equals(token.toLowerCase()));
    }

    @Test
    void db_token_round_trips_back_to_the_strategy() {
        for (MutationStrategy strategy : MutationStrategy.values()) {
            assertThat(MutationStrategy.fromDbValue(strategy.dbValue())).isEqualTo(strategy);
        }
    }

    @Test
    void from_db_value_rejects_an_unknown_token() {
        assertThatThrownBy(() -> MutationStrategy.fromDbValue("typo"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void every_strategy_carries_a_non_blank_attacker_instruction() {
        assertThat(Arrays.stream(MutationStrategy.values()).map(MutationStrategy::attackerInstruction))
                .allSatisfy(instruction -> assertThat(instruction).isNotBlank());
    }
}
