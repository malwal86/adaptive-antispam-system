package com.antispam.controls;

import com.antispam.controls.web.BudgetCapsRequest;
import com.antispam.controls.web.BudgetView;
import com.antispam.controls.web.PolicyView;
import com.antispam.controls.web.ThresholdsRequest;
import com.antispam.decision.llm.LlmBudgetCaps;
import com.antispam.decision.llm.LlmBudgetProperties;
import com.antispam.decision.policy.Policy;
import com.antispam.decision.policy.PolicyRepository;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * The console left rail's reconfiguration logic (story 12.02): it lists and switches policies, applies
 * threshold changes by minting and activating a new policy version, and reads/sets the live LLM caps.
 * Every operation changes real running state — the active {@code policies} row and the in-force budget
 * caps — so the change takes effect on subsequent decisions, never just the UI.
 */
@Service
public class ControlsService {

    private final PolicyRepository policies;
    private final LlmBudgetCaps budgetCaps;
    private final LlmBudgetProperties budgetProperties;
    private final Clock clock;

    // Disambiguates two threshold edits within the same clock tick so the minted version is unique.
    private final AtomicLong mintSequence = new AtomicLong(0);

    @Autowired
    public ControlsService(
            PolicyRepository policies,
            LlmBudgetCaps budgetCaps,
            LlmBudgetProperties budgetProperties,
            Clock clock) {
        this.policies = policies;
        this.budgetCaps = budgetCaps;
        this.budgetProperties = budgetProperties;
        this.clock = clock;
    }

    /** Every policy, newest first, for the selector. */
    public List<PolicyView> listPolicies() {
        return policies.findAll().stream().map(PolicyView::from).toList();
    }

    /**
     * Makes {@code version} the enforcing regime.
     *
     * @throws IllegalArgumentException if no policy has that version
     */
    public PolicyView activatePolicy(String version) {
        policies.activate(version);
        return PolicyView.from(requireVersion(version));
    }

    /**
     * Applies new thresholds by minting a policy version that carries them (and the active policy's
     * burst threshold and model) and activating it, so subsequent decisions use the new cut-points.
     *
     * @throws IllegalStateException    if there is no active policy to derive from
     * @throws IllegalArgumentException if the thresholds are not a valid {@code [0,1]} ladder
     */
    public PolicyView applyThresholds(ThresholdsRequest request) {
        Policy active = policies.findActive().orElseThrow(
                () -> new IllegalStateException("no active policy to derive thresholds from"));
        String version = "console-" + clock.instant().toEpochMilli() + "-" + mintSequence.incrementAndGet();
        // The Policy constructor validates the ladder and unit ranges → IllegalArgumentException → 400.
        Policy candidate = new Policy(
                version,
                false,
                request.warnThreshold(),
                request.quarantineThreshold(),
                request.blockThreshold(),
                request.llmThreshold(),
                request.routingBandWidth(),
                active.burstThreshold(),
                active.modelVersion(),
                clock.instant());
        policies.save(candidate);
        policies.activate(version);
        return PolicyView.from(requireVersion(version));
    }

    /** The LLM spend caps in force. */
    public BudgetView budget() {
        LlmBudgetCaps.Caps caps = budgetCaps.current();
        return new BudgetView(budgetProperties.enabled(), caps.dailyCapUsd(), caps.monthlyCapUsd());
    }

    /**
     * Sets the live LLM spend caps; takes effect on the next LLM-routed decision.
     *
     * @throws IllegalArgumentException if a cap is negative or the daily cap exceeds the monthly cap
     */
    public BudgetView updateBudget(BudgetCapsRequest request) {
        budgetCaps.update(request.dailyCapUsd(), request.monthlyCapUsd());
        return budget();
    }

    private Policy requireVersion(String version) {
        return policies.findByVersion(version).orElseThrow(
                () -> new IllegalArgumentException("no policy with version " + version));
    }
}
