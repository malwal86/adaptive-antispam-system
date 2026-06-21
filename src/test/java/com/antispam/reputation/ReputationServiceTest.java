package com.antispam.reputation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The orchestration contract above the SQL (stories 03.01–03.03): recording a signal
 * <em>appends</em> an event in the bucket its auth earns and then recomputes the gated
 * reputation <em>from the events</em>, caching the authenticated (earned) mean; reading
 * recomputes from the events too, as of the injected {@link Clock} instant with the
 * configured decay. The repository is mocked so this pins that wiring in isolation; the
 * real SQL, decay, and cap are exercised against Postgres in the integration tests.
 */
@ExtendWith(MockitoExtension.class)
class ReputationServiceTest {

    private static final String SENDER = "alice@example.com";
    private static final Instant NOW = Instant.parse("2026-06-21T12:00:00Z");
    private static final Duration HALF_LIFE = Duration.ofDays(7);
    private static final ReputationProperties PRIORS = new ReputationProperties(1.0, 1.0, HALF_LIFE);

    @Mock
    private ReputationRepository repository;

    private ReputationService service;

    @BeforeEach
    void setUp() {
        service = new ReputationService(repository, PRIORS, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static BucketedReputationCounts authenticated(double good, double bad) {
        return new BucketedReputationCounts(new ReputationCounts(good, bad), new ReputationCounts(0, 0));
    }

    @Test
    void recording_appends_an_event_carrying_signal_source_and_bucket() {
        when(repository.countsFor(eq(SENDER), any(), any())).thenReturn(authenticated(1, 0));

        service.record(SENDER, ReputationSignal.GOOD, 1.0, "decision", ReputationBucket.UNAUTHENTICATED);

        ArgumentCaptor<ReputationEvent> appended = ArgumentCaptor.forClass(ReputationEvent.class);
        verify(repository).append(appended.capture());
        ReputationEvent event = appended.getValue();
        assertThat(event.senderKey()).isEqualTo(SENDER);
        assertThat(event.signal()).isEqualTo(ReputationSignal.GOOD);
        assertThat(event.weight()).isEqualTo(1.0);
        assertThat(event.source()).isEqualTo("decision");
        assertThat(event.bucket()).isEqualTo(ReputationBucket.UNAUTHENTICATED);
        assertThat(event.decayFactor()).isEqualTo(1.0);
    }

    @Test
    void recording_recomputes_from_events_and_caches_the_authenticated_mean() {
        // After this signal the authenticated bucket holds 8 good / 2 bad: mean
        // (8+1)/(8+2+1+1)=0.75. The cache stores the earned (authenticated) mean.
        when(repository.countsFor(eq(SENDER), any(), any())).thenReturn(authenticated(8, 2));

        GatedReputation result = service.record(
                SENDER, ReputationSignal.GOOD, 1.0, "decision", ReputationBucket.AUTHENTICATED);

        assertThat(result.authenticated().mean()).isCloseTo(0.75, within(1e-12));
        verify(repository).saveScore(eq(SENDER), eq(result.authenticated().mean()));
    }

    @Test
    void reading_an_unseen_sender_returns_the_high_variance_prior_in_both_views() {
        when(repository.countsFor(eq(SENDER), any(), any())).thenReturn(authenticated(0, 0));

        GatedReputation reputation = service.gatedReputation(SENDER);

        assertThat(reputation.authenticated().mean()).isEqualTo(0.5);
        assertThat(reputation.authenticated().variance()).isCloseTo(1.0 / 12.0, within(1e-12));
        assertThat(reputation.unauthenticated().mean()).isEqualTo(0.5);
    }

    @Test
    void reputation_for_picks_the_capped_view_for_an_unauthenticated_email() {
        // Strong authenticated trust (8/2) but the unauthenticated bucket is all-good
        // spoof traffic: an aligned email gets the earned 0.75, an unauthenticated one
        // is capped at neutral and inherits none of it.
        when(repository.countsFor(eq(SENDER), any(), any()))
                .thenReturn(new BucketedReputationCounts(
                        new ReputationCounts(8, 2), new ReputationCounts(50, 0)));

        assertThat(service.reputationFor(SENDER, true).mean()).isCloseTo(0.75, within(1e-12));
        assertThat(service.reputationFor(SENDER, false).mean()).isCloseTo(0.5, within(1e-12));
    }

    @Test
    void reading_totals_the_log_as_of_the_clock_with_the_configured_decay() {
        // Decay is read-time: the score is summed as of the clock instant using the
        // configured half-life, so a later read decays older evidence without any write.
        when(repository.countsFor(eq(SENDER), any(), any())).thenReturn(authenticated(4, 1));

        service.gatedReputation(SENDER);

        ArgumentCaptor<Instant> readAt = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<ExponentialDecay> decay = ArgumentCaptor.forClass(ExponentialDecay.class);
        verify(repository).countsFor(eq(SENDER), readAt.capture(), decay.capture());
        assertThat(readAt.getValue()).isEqualTo(NOW);
        assertThat(decay.getValue().halfLife()).isEqualTo(HALF_LIFE);
    }
}
