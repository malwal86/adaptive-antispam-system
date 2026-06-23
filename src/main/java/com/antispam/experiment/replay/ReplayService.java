package com.antispam.experiment.replay;

import com.antispam.decision.policy.PolicyRepository;
import com.antispam.event.ReplayEmailEvent;
import com.antispam.event.SenderKey;
import com.antispam.ingest.EmailRepository;
import com.antispam.ingest.EmailRepository.EmailIdentity;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Triggers a replay (story 09.01): re-publishes the immutable Postgres corpus onto
 * {@code emails.replay} for an experimental consumer to score under a chosen policy. This is the
 * "real Kafka path, not an in-memory shortcut" the PRD asks for — the corpus flows through the same
 * serialization and sender-keyed partitioning as live mail, but on a separate topic and consumer
 * group so it is isolated from production (PRD §Subsystem 8).
 *
 * <p>The chosen policy is validated up front: a replay against an unknown policy version fails fast
 * here, rather than publishing a run that every consumer would reject. The policy is then baked into
 * each event so the run is reproducible regardless of which regime is enforcing at consume time.
 */
@Service
public class ReplayService {

    private static final Logger log = LoggerFactory.getLogger(ReplayService.class);

    private final EmailRepository emails;
    private final PolicyRepository policies;
    private final ReplayEmailPublisher publisher;

    @Autowired
    public ReplayService(
            EmailRepository emails, PolicyRepository policies, ReplayEmailPublisher publisher) {
        this.emails = emails;
        this.policies = policies;
        this.publisher = publisher;
    }

    /**
     * Starts a replay of the whole corpus scored under {@code policyVersion}.
     *
     * @param policyVersion the policy to score every replayed email under
     * @return the run summary (its id, the policy, and the count published)
     * @throws IllegalArgumentException if no policy has that version
     */
    public ReplayRun startReplay(String policyVersion) {
        if (policies.findByVersion(policyVersion).isEmpty()) {
            throw new IllegalArgumentException("no policy with version " + policyVersion);
        }

        UUID runId = UUID.randomUUID();
        List<EmailIdentity> corpus = emails.findAllIdentities();
        for (EmailIdentity email : corpus) {
            String senderKey = SenderKey.of(email.sender(), email.senderDomain());
            publisher.publish(ReplayEmailEvent.of(runId, policyVersion, email.id(), senderKey));
        }

        log.info("replay started run={} policy={} published={}", runId, policyVersion, corpus.size());
        return new ReplayRun(runId, policyVersion, corpus.size());
    }
}
