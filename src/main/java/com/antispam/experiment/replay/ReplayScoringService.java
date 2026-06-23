package com.antispam.experiment.replay;

import com.antispam.decision.policy.Policy;
import com.antispam.decision.policy.PolicyRepository;
import com.antispam.decision.policy.PolicyScorer;
import com.antispam.decision.policy.ScoredDecision;
import com.antispam.event.ReplayEmailEvent;
import com.antispam.experiment.ExperimentContext;
import com.antispam.ingest.Email;
import com.antispam.ingest.EmailRepository;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Scores one replayed email and records the result (story 09.01): the work the experimental
 * consumer does per {@link ReplayEmailEvent}. It loads the immutable email and the run's chosen
 * policy, scores them through the read-only {@link PolicyScorer}, and writes a {@link ReplayDecision}
 * — and nothing else. It reads live state (the email, the policy, the sender's reputation via the
 * scorer) but mutates none of it: the only write is to the experiment-scoped {@code replay_decisions}
 * table, which is the side-effect isolation the arena and shadow paths depend on (story 09.03).
 *
 * <p>The save is idempotent on {@code (run_id, email_id)}, so a Kafka redelivery scores again
 * (deterministically) and the duplicate write is dropped — replay stays exactly-once in its output
 * even though delivery is at-least-once.
 */
@Service
public class ReplayScoringService {

    private static final Logger log = LoggerFactory.getLogger(ReplayScoringService.class);

    private final EmailRepository emails;
    private final PolicyRepository policies;
    private final PolicyScorer scorer;
    private final ReplayDecisionRepository decisions;

    @Autowired
    public ReplayScoringService(EmailRepository emails, PolicyRepository policies,
            PolicyScorer scorer, ReplayDecisionRepository decisions) {
        this.emails = emails;
        this.policies = policies;
        this.scorer = scorer;
        this.decisions = decisions;
    }

    /**
     * Scores the email named by {@code event} under the run's policy and records the verdict.
     *
     * @return {@code true} if a new replay decision was written, {@code false} if the email or
     *         policy could not be resolved, or the decision was already recorded (a redelivery)
     */
    public boolean score(ReplayEmailEvent event) {
        // Read-only scope (story 09.03): replay reads the immutable email, the run's policy, and the
        // sender's reputation, and writes only replay_decisions; any stray write to live state
        // underneath is blocked at the repository rather than left to convention.
        return ExperimentContext.callReadOnly(() -> {
            Optional<Email> email = emails.findById(event.emailId());
            if (email.isEmpty()) {
                log.warn("replay skipped: email not found run={} id={}", event.runId(), event.emailId());
                return false;
            }
            Optional<Policy> policy = policies.findByVersion(event.policyVersion());
            if (policy.isEmpty()) {
                log.error("replay skipped: policy not found run={} policy={} id={}",
                        event.runId(), event.policyVersion(), event.emailId());
                return false;
            }

            ScoredDecision scored = scorer.score(email.get(), policy.get());
            boolean written = decisions.save(event.runId(), event.emailId(), scored);
            log.debug("replay scored run={} id={} policy={} decision={} route={} written={}",
                    event.runId(), event.emailId(), scored.policyVersion(), scored.decision(),
                    scored.route(), written);
            return written;
        });
    }
}
