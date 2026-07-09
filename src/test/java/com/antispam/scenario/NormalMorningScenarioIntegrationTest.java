package com.antispam.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import com.antispam.AbstractPostgresIntegrationTest;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * End-to-end proof of the everyday-inbox demo against the full stack: a single {@code start} pre-warms
 * the good senders, then drives the <em>real</em> pipeline so the run reads the way a viewer should see
 * it — good mail lands in the inbox instantly, and the flagrant scams are blocked outright by the hard
 * rule (never dwelling in quarantine-pending). This is the seam the unit tests can't cover: that
 * {@code application.yml}'s {@code url-denylist} is actually wired into the rule, and that the
 * pre-warm makes benign mail a confident model-route ALLOW rather than an LLM escalation.
 *
 * <p>The dispatcher is synchronous and the step delay zero (nested config) so the run completes inline
 * and its effects are observable deterministically. The good senders are pre-warmed by {@code start},
 * so — with the seed corpus scoring plain benign mail benign, as the thunderclap warm-ups already
 * prove — their mail is ALLOW.
 */
class NormalMorningScenarioIntegrationTest extends AbstractPostgresIntegrationTest {

    @TestConfiguration
    static class SynchronousRunnerConfig {
        @Bean
        @Primary
        ScenarioDispatcher inlineDispatcher() {
            return Runnable::run;
        }

        @Bean
        @Primary
        ScenarioProperties immediateScenarioProperties() {
            return new ScenarioProperties(Duration.ZERO, 1234L);
        }
    }

    @Autowired
    private ScenarioService scenarios;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void a_single_start_lands_good_mail_in_the_inbox_and_hard_blocks_the_scams() {
        int legitBefore = decisions("normal-morning-legit").size();
        int spamBefore = decisions("normal-morning-spam").size();

        ScenarioRun run = scenarios.start(NormalMorningScenario.NAME, 7L);

        // One control action drove the whole everyday inbox: six emails ingested and decided.
        assertThat(run.steps()).isEqualTo(6);

        // Good mail lands in the inbox instantly: pre-warmed, benign senders decide as ALLOW on the
        // model route — not withheld, not quarantine-pending. (Mom's note and the order receipt.)
        List<Decisioned> legit = decisions("normal-morning-legit");
        assertThat(legit).hasSize(legitBefore + 2);
        assertThat(legit).extracting(Decisioned::decision).allMatch("ALLOW"::equals);

        // The two flagrant scams are blocked outright by the hard rule — proving application.yml's
        // url-denylist is wired in — so they never reach the model or the LLM. (The third spam-beat
        // email is the borderline delivery-notice, whose verdict is left to the model, so it isn't
        // asserted here.)
        List<Decisioned> spam = decisions("normal-morning-spam");
        assertThat(spam).hasSize(spamBefore + 3);
        long hardBlocked = spam.stream()
                .filter(d -> d.decision().equals("BLOCK") && d.route().equals("HARD_RULE"))
                .count();
        assertThat(hardBlocked).as("the fake-bank and prize scams are hard-ruled to BLOCK").isEqualTo(2);
    }

    private record Decisioned(String decision, String route) {}

    /** The decision + route of every decided email from this ingest source, in decision order. */
    private List<Decisioned> decisions(String source) {
        return jdbc.query(
                "select c.decision, c.route_used from classifications c join emails e on c.email_id = e.id "
                        + "where e.ingest_source = ? order by c.created_at",
                (rs, i) -> new Decisioned(rs.getString("decision"), rs.getString("route_used")),
                source);
    }
}
