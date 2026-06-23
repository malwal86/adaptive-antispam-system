package com.antispam.decision.hardrule;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.antispam.decision.Decision;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * The circuit-breaker precedence (story 05.05): the LLM (or any later stage) can raise severity but
 * never lower it past the hard-rule floor, and a softening attempt is logged for the feedback loop.
 */
class HardRuleCircuitBreakerTest {

    private final HardRuleCircuitBreaker breaker = new HardRuleCircuitBreaker();
    private final UUID emailId = UUID.randomUUID();

    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    @BeforeEach
    void captureLogs() {
        logger = (Logger) LoggerFactory.getLogger(HardRuleCircuitBreaker.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachLogs() {
        logger.detachAppender(appender);
    }

    @Test
    void holds_the_hard_rule_floor_when_the_candidate_would_soften_it() {
        // AC 2: an LLM verdict tries to mark a hard-rule BLOCK as ALLOW — the breaker refuses.
        Decision held = breaker.floorAtHardRule(emailId, Decision.BLOCK, Decision.ALLOW);

        assertThat(held).isEqualTo(Decision.BLOCK);
    }

    @Test
    void logs_the_conflict_when_it_refuses_a_softening() {
        // AC 5: the LLM-vs-hard-rule conflict is logged so the shadow/feedback loop can use it.
        breaker.floorAtHardRule(emailId, Decision.QUARANTINE, Decision.WARN);

        assertThat(appender.list)
                .anySatisfy(event -> {
                    assertThat(event.getLevel()).isEqualTo(Level.WARN);
                    assertThat(event.getFormattedMessage())
                            .contains("circuit breaker")
                            .contains(emailId.toString())
                            .contains("QUARANTINE");
                });
    }

    @Test
    void honors_a_more_severe_candidate_so_the_llm_can_still_escalate() {
        // AC 3: the breaker only blocks softening — escalation past the floor is allowed.
        Decision held = breaker.floorAtHardRule(emailId, Decision.QUARANTINE, Decision.BLOCK);

        assertThat(held).isEqualTo(Decision.BLOCK);
        assertThat(appender.list).noneSatisfy(
                event -> assertThat(event.getLevel()).isEqualTo(Level.WARN));
    }

    @Test
    void passes_an_equal_candidate_through_without_logging_a_conflict() {
        Decision held = breaker.floorAtHardRule(emailId, Decision.BLOCK, Decision.BLOCK);

        assertThat(held).isEqualTo(Decision.BLOCK);
        assertThat(appender.list).isEmpty();
    }

    @Test
    void an_allow_floor_floors_nothing_so_any_candidate_stands() {
        // No hard rule fired (floor ALLOW): the pipeline's decision is used verbatim, no conflict.
        assertThat(breaker.floorAtHardRule(emailId, Decision.ALLOW, Decision.ALLOW))
                .isEqualTo(Decision.ALLOW);
        assertThat(breaker.floorAtHardRule(emailId, Decision.ALLOW, Decision.WARN))
                .isEqualTo(Decision.WARN);
        assertThat(appender.list).isEmpty();
    }
}
