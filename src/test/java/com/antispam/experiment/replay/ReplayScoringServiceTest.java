package com.antispam.experiment.replay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.antispam.decision.Decision;
import com.antispam.decision.RouteUsed;
import com.antispam.decision.policy.Policy;
import com.antispam.decision.policy.PolicyRepository;
import com.antispam.decision.policy.PolicyScorer;
import com.antispam.decision.policy.ScoredDecision;
import com.antispam.event.ReplayEmailEvent;
import com.antispam.ingest.Email;
import com.antispam.ingest.EmailRepository;
import com.antispam.ingest.ParsedEmail;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The per-email replay worker's contract (story 09.01): it scores the email under the policy named
 * by the event and records the verdict; it skips (records nothing) when the email or the policy
 * cannot be resolved; and it reports a duplicate save as "not written" so a redelivery is a no-op.
 */
@ExtendWith(MockitoExtension.class)
class ReplayScoringServiceTest {

    @Mock
    private EmailRepository emails;

    @Mock
    private PolicyRepository policies;

    @Mock
    private PolicyScorer scorer;

    @Mock
    private ReplayDecisionRepository decisions;

    private ReplayScoringService service() {
        return new ReplayScoringService(emails, policies, scorer, decisions);
    }

    private static final UUID RUN = UUID.randomUUID();
    private static final UUID EMAIL_ID = UUID.randomUUID();
    private static final ReplayEmailEvent EVENT =
            ReplayEmailEvent.of(RUN, "cand-v2", EMAIL_ID, "alice@a.test");

    @Test
    void scores_the_email_under_the_events_policy_and_records_the_verdict() {
        Email email = email();
        Policy policy = policy("cand-v2");
        ScoredDecision scored = scored("cand-v2");
        when(emails.findById(EMAIL_ID)).thenReturn(Optional.of(email));
        when(policies.findByVersion("cand-v2")).thenReturn(Optional.of(policy));
        when(scorer.score(email, policy)).thenReturn(scored);
        when(decisions.save(RUN, EMAIL_ID, scored)).thenReturn(true);

        boolean written = service().score(EVENT);

        assertThat(written).isTrue();
        verify(decisions).save(RUN, EMAIL_ID, scored);
    }

    @Test
    void skips_when_the_email_is_not_found() {
        when(emails.findById(EMAIL_ID)).thenReturn(Optional.empty());

        boolean written = service().score(EVENT);

        assertThat(written).isFalse();
        verify(decisions, never()).save(any(), any(), any());
    }

    @Test
    void skips_when_the_policy_is_not_found() {
        when(emails.findById(EMAIL_ID)).thenReturn(Optional.of(email()));
        when(policies.findByVersion("cand-v2")).thenReturn(Optional.empty());

        boolean written = service().score(EVENT);

        assertThat(written).isFalse();
        verify(decisions, never()).save(any(), any(), any());
    }

    @Test
    void reports_a_duplicate_save_as_not_written() {
        Email email = email();
        Policy policy = policy("cand-v2");
        ScoredDecision scored = scored("cand-v2");
        when(emails.findById(EMAIL_ID)).thenReturn(Optional.of(email));
        when(policies.findByVersion("cand-v2")).thenReturn(Optional.of(policy));
        when(scorer.score(email, policy)).thenReturn(scored);
        when(decisions.save(eq(RUN), eq(EMAIL_ID), any())).thenReturn(false);

        boolean written = service().score(EVENT);

        assertThat(written).isFalse();
    }

    private static Email email() {
        return new Email(EMAIL_ID, new byte[32], "body".getBytes(),
                new ParsedEmail("alice@a.test", "a.test", null, null, null, null), "api", Instant.EPOCH);
    }

    private static ScoredDecision scored(String policyVersion) {
        return new ScoredDecision(
                Decision.WARN, List.of(), RouteUsed.MODEL, List.of(), policyVersion, 0.6);
    }

    private static Policy policy(String version) {
        return new Policy(version, false, 0.5, 0.8, 0.95, 0.40, 0.05, 20, "bootstrap-v1", Instant.EPOCH);
    }
}
