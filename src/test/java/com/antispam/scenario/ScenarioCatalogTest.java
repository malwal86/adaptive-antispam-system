package com.antispam.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The scenario registry: it resolves a name to its builder, reports the unknown name as empty (the
 * runner turns that into a 400), lists scenarios in declaration order, and refuses two scenarios that
 * claim the same name rather than letting one silently shadow the other.
 */
class ScenarioCatalogTest {

    private static Scenario named(String name) {
        return new Scenario() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public List<ScenarioEmail> build(long seed) {
                return List.of();
            }
        };
    }

    @Test
    void it_resolves_a_registered_scenario_by_name() {
        Scenario hostile = new SenderTurnsHostileScenario();
        Scenario morning = new NormalMorningScenario();
        ScenarioCatalog catalog = new ScenarioCatalog(List.of(hostile, morning));

        assertThat(catalog.find(ThunderclapScript.NAME)).containsSame(hostile);
        assertThat(catalog.find(NormalMorningScenario.NAME)).containsSame(morning);
    }

    @Test
    void an_unknown_name_resolves_to_empty() {
        ScenarioCatalog catalog = new ScenarioCatalog(List.of(new SenderTurnsHostileScenario()));

        assertThat(catalog.find("not_a_scenario")).isEmpty();
    }

    @Test
    void it_lists_every_scenario_in_declaration_order() {
        ScenarioCatalog catalog =
                new ScenarioCatalog(List.of(named("first"), named("second"), named("third")));

        assertThat(catalog.all().stream().map(Scenario::name)).containsExactly("first", "second", "third");
    }

    @Test
    void two_scenarios_with_the_same_name_are_rejected() {
        assertThatThrownBy(() -> new ScenarioCatalog(List.of(named("dup"), named("dup"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dup");
    }
}
