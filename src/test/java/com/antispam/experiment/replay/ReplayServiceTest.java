package com.antispam.experiment.replay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.antispam.decision.policy.Policy;
import com.antispam.decision.policy.PolicyRepository;
import com.antispam.event.ReplayEmailEvent;
import com.antispam.event.SenderKey;
import com.antispam.ingest.EmailRepository;
import com.antispam.ingest.EmailRepository.EmailIdentity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The replay trigger's contract (story 09.01): it publishes one {@code emails.replay} event per
 * corpus email, each tagged with the run id and the chosen policy and keyed by sender; and it fails
 * fast on an unknown policy without publishing anything (so no consumer is handed a doomed run).
 */
@ExtendWith(MockitoExtension.class)
class ReplayServiceTest {

    @Mock
    private EmailRepository emails;

    @Mock
    private PolicyRepository policies;

    @Mock
    private ReplayEmailPublisher publisher;

    private ReplayService service() {
        return new ReplayService(emails, policies, publisher);
    }

    private static final EmailIdentity ALICE =
            new EmailIdentity(UUID.randomUUID(), "alice@a.test", "a.test");
    private static final EmailIdentity BOB =
            new EmailIdentity(UUID.randomUUID(), "bob@b.test", "b.test");

    @Test
    void publishes_one_event_per_corpus_email_tagged_with_the_run_and_policy() {
        when(policies.findByVersion("cand-v2")).thenReturn(Optional.of(policy("cand-v2")));
        when(emails.findAllIdentities()).thenReturn(List.of(ALICE, BOB));

        ReplayRun run = service().startReplay("cand-v2");

        ArgumentCaptor<ReplayEmailEvent> published = ArgumentCaptor.forClass(ReplayEmailEvent.class);
        verify(publisher, org.mockito.Mockito.times(2)).publish(published.capture());
        assertThat(published.getAllValues())
                .allSatisfy(e -> {
                    assertThat(e.runId()).isEqualTo(run.runId());
                    assertThat(e.policyVersion()).isEqualTo("cand-v2");
                })
                .extracting(ReplayEmailEvent::emailId)
                .containsExactly(ALICE.id(), BOB.id());
        assertThat(run.publishedCount()).isEqualTo(2);
    }

    @Test
    void keys_each_event_by_the_senders_partition_key() {
        when(policies.findByVersion("cand-v2")).thenReturn(Optional.of(policy("cand-v2")));
        when(emails.findAllIdentities()).thenReturn(List.of(ALICE));

        service().startReplay("cand-v2");

        ArgumentCaptor<ReplayEmailEvent> published = ArgumentCaptor.forClass(ReplayEmailEvent.class);
        verify(publisher).publish(published.capture());
        assertThat(published.getValue().senderKey())
                .isEqualTo(SenderKey.of(ALICE.sender(), ALICE.senderDomain()));
    }

    @Test
    void rejects_an_unknown_policy_without_publishing_anything() {
        when(policies.findByVersion("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().startReplay("ghost"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ghost");

        verify(publisher, never()).publish(org.mockito.ArgumentMatchers.any());
        verify(emails, never()).findAllIdentities();
    }

    private static Policy policy(String version) {
        return new Policy(version, false, 0.5, 0.8, 0.95, 0.40, 0.05, 20, "bootstrap-v1", Instant.EPOCH);
    }
}
