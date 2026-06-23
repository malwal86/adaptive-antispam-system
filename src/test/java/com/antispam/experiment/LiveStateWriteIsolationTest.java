package com.antispam.experiment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.antispam.decision.ClassificationRepository;
import com.antispam.decision.Decision;
import com.antispam.decision.DecisionOutcome;
import com.antispam.decision.ModelScores;
import com.antispam.decision.RouteUsed;
import com.antispam.feedback.FeedbackEventRepository;
import com.antispam.reputation.ReputationBucket;
import com.antispam.reputation.ReputationEvent;
import com.antispam.reputation.ReputationRepository;
import com.antispam.reputation.ReputationSignal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.ResultSetExtractor;

/**
 * Side-effect isolation enforced at the write chokepoints (story 09.03): every repository that
 * mutates live state — {@code reputation_events} and the {@code senders} cache, {@code feedback_events},
 * and enforced {@code classifications} — refuses the write when the current thread is inside a
 * read-only experiment scope, and the refusal happens <b>before</b> the database is touched (a
 * blocked write, not a rolled-back one). Outside the scope the same call proceeds normally, so the
 * guard costs the live path nothing. This is the architectural backstop the shadow, replay, and
 * arena paths rely on: a stray live-state write from an experiment is prevented, not merely
 * discouraged (AC 4).
 */
@ExtendWith(MockitoExtension.class)
class LiveStateWriteIsolationTest {

    @Mock
    private JdbcTemplate jdbc;

    private static final ReputationEvent EVENT = ReputationEvent.of(
            "stranger@elsewhere.test", ReputationSignal.BAD, 1.0, "decision", ReputationBucket.AUTHENTICATED);

    @Test
    void blocks_a_reputation_event_append_inside_a_read_only_scope() {
        ReputationRepository repo = new ReputationRepository(jdbc);

        ExperimentContext.runReadOnly(() ->
                assertThatThrownBy(() -> repo.append(EVENT))
                        .isInstanceOf(LiveStateWriteForbiddenException.class)
                        .hasMessageContaining("reputation_events"));

        verifyNoInteractions(jdbc);
    }

    @Test
    void blocks_a_sender_score_upsert_inside_a_read_only_scope() {
        ReputationRepository repo = new ReputationRepository(jdbc);

        ExperimentContext.runReadOnly(() ->
                assertThatThrownBy(() -> repo.saveScore("stranger@elsewhere.test", 0.1))
                        .isInstanceOf(LiveStateWriteForbiddenException.class)
                        .hasMessageContaining("senders"));

        verifyNoInteractions(jdbc);
    }

    @Test
    void blocks_a_feedback_event_save_inside_a_read_only_scope() {
        FeedbackEventRepository repo = new FeedbackEventRepository(jdbc);

        ExperimentContext.runReadOnly(() ->
                assertThatThrownBy(() -> repo.saveAll(List.of()))
                        .isInstanceOf(LiveStateWriteForbiddenException.class)
                        .hasMessageContaining("feedback_events"));

        verifyNoInteractions(jdbc);
    }

    @Test
    void blocks_a_classification_save_inside_a_read_only_scope() {
        ClassificationRepository repo = new ClassificationRepository(jdbc);

        ExperimentContext.runReadOnly(() ->
                assertThatThrownBy(() -> repo.save(UUID.randomUUID(), modelOutcome(), null, "active-v1", null))
                        .isInstanceOf(LiveStateWriteForbiddenException.class)
                        .hasMessageContaining("classifications"));

        verifyNoInteractions(jdbc);
    }

    @Test
    void permits_the_same_writes_on_the_live_path_outside_any_scope() {
        // The control: with no read-only scope active the guard is transparent and each write reaches
        // the database. A guard that always threw would pass the blocked-cases above but fail here.
        OffsetDateTime createdAt = OffsetDateTime.ofInstant(Instant.EPOCH, ZoneOffset.UTC);
        when(jdbc.query(any(PreparedStatementCreator.class), this.<OffsetDateTime>extractor()))
                .thenReturn(createdAt);

        ReputationRepository reputation = new ReputationRepository(jdbc);
        FeedbackEventRepository feedback = new FeedbackEventRepository(jdbc);
        ClassificationRepository classifications = new ClassificationRepository(jdbc);

        assertThatCode(() -> {
            reputation.append(EVENT);
            reputation.saveScore("stranger@elsewhere.test", 0.1);
            feedback.saveAll(List.of());
            classifications.save(UUID.randomUUID(), modelOutcome(), null, "active-v1", null);
        }).doesNotThrowAnyException();

        verify(jdbc, atLeastOnce()).update(any(String.class), any(Object[].class)); // writes reached the DB
        assertThat(ExperimentContext.isReadOnly()).isFalse();
    }

    @SuppressWarnings("unchecked")
    private <T> ResultSetExtractor<T> extractor() {
        return any(ResultSetExtractor.class);
    }

    private static DecisionOutcome modelOutcome() {
        ModelScores scores = new ModelScores(0.55, 0.05, "bootstrap-v1", 0.97);
        return new DecisionOutcome(Decision.ALLOW, List.of(), RouteUsed.MODEL, 1L, scores);
    }
}
