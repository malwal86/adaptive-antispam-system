package com.antispam.reputation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The orchestration contract above the SQL (story 03.01): recording a signal
 * <em>appends</em> an event and then recomputes the score <em>from the events</em>
 * and refreshes the cache; reading recomputes from the events too. The repository
 * is mocked so this pins the wiring — append, recompute, cache — in isolation; the
 * real SQL and the events-are-truth guarantee are exercised against Postgres in
 * {@link ReputationAccrualIntegrationTest}.
 */
@ExtendWith(MockitoExtension.class)
class ReputationServiceTest {

    private static final String SENDER = "alice@example.com";
    private static final ReputationProperties PRIORS = new ReputationProperties(1.0, 1.0);

    @Mock
    private ReputationRepository repository;

    private ReputationService service;

    @BeforeEach
    void setUp() {
        service = new ReputationService(repository, PRIORS);
    }

    @Test
    void recording_appends_a_full_weight_event_carrying_signal_and_source() {
        when(repository.countsFor(SENDER)).thenReturn(new ReputationCounts(1, 0));

        service.record(SENDER, ReputationSignal.GOOD, 1.0, "decision");

        ArgumentCaptor<ReputationEvent> appended = ArgumentCaptor.forClass(ReputationEvent.class);
        verify(repository).append(appended.capture());
        ReputationEvent event = appended.getValue();
        assertThat(event.senderKey()).isEqualTo(SENDER);
        assertThat(event.signal()).isEqualTo(ReputationSignal.GOOD);
        assertThat(event.weight()).isEqualTo(1.0);
        assertThat(event.source()).isEqualTo("decision");
        assertThat(event.decayFactor()).isEqualTo(1.0);
    }

    @Test
    void recording_recomputes_from_events_and_caches_the_mean() {
        // After this signal the log holds 8 good / 2 bad: mean (8+1)/(8+2+1+1)=0.75.
        when(repository.countsFor(SENDER)).thenReturn(new ReputationCounts(8, 2));

        BetaReputation result = service.record(SENDER, ReputationSignal.GOOD, 1.0, "decision");

        assertThat(result.mean()).isCloseTo(0.75, within(1e-12));
        // The senders cache is refreshed with the recomputed mean, never an authority.
        verify(repository).saveScore(eq(SENDER), eq(result.mean()));
    }

    @Test
    void reading_an_unseen_sender_returns_the_high_variance_prior() {
        // No events recorded: counts are zero, so the read is the pure prior -- a
        // wide Beta, not a falsely-confident neutral.
        when(repository.countsFor(SENDER)).thenReturn(new ReputationCounts(0, 0));

        BetaReputation reputation = service.currentReputation(SENDER);

        assertThat(reputation.mean()).isEqualTo(0.5);
        assertThat(reputation.variance()).isCloseTo(1.0 / 12.0, within(1e-12));
        assertThat(reputation.count()).isZero();
    }
}
