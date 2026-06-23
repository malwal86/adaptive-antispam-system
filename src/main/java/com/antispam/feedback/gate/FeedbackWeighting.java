package com.antispam.feedback.gate;

import com.antispam.decision.Probabilities;
import com.antispam.feedback.Persona;

/**
 * The per-item feedback weighting function (story 07.03): how much one persona's one action counts
 * toward moving state, before corroboration. Kept pure and in one place so the rule is auditable
 * and unit-testable without a database (test plan: "weighting function (trust × confidence)").
 *
 * <p><b>Weight = trust × confidence.</b> {@code confidence} is the sampler's certainty in the
 * action (07.02, {@code action_confidence}); {@code trust} is how much the producing persona is
 * believed. A good-faith persona has full trust ({@code 1.0}); a malicious one — a report/rescue
 * bomber (07.04) — is down-weighted to {@code maliciousTrust}, a small fraction, so its reports
 * contribute little to any aggregate. This is the "single high-bias report is down-weighted" half
 * of the defence (AC 1); the corroboration count is the other half ({@link CorroborationGate}).
 *
 * <p>Trust keys on the persona's {@link Persona#malicious()} flag rather than on its biases: a
 * good-faith user with a high report bias is still trusted (their reports are honest), whereas a
 * malicious persona is suppressed however it is parameterised. Biases shape <em>what</em> a persona
 * does (07.02); trust is <em>whether we believe it</em>.
 */
public final class FeedbackWeighting {

    private FeedbackWeighting() {
    }

    /**
     * The trust placed in this persona's feedback: {@code 1.0} for a good-faith persona, else
     * {@code maliciousTrust}.
     *
     * @param persona        the producing persona (07.01)
     * @param maliciousTrust the down-weighted trust of a malicious persona, in {@code [0,1]}
     */
    public static double trust(Persona persona, double maliciousTrust) {
        Probabilities.requireUnit("maliciousTrust", maliciousTrust);
        return persona.malicious() ? maliciousTrust : 1.0;
    }

    /**
     * The weight one action contributes: {@code trust × confidence}. Both inputs are in
     * {@code [0,1]}, so the result is too — a per-item contribution that corroboration then sums.
     *
     * @param trust      the producing persona's trust, in {@code [0,1]}
     * @param confidence the sampler's confidence in the action, in {@code [0,1]}
     */
    public static double weight(double trust, double confidence) {
        Probabilities.requireUnit("trust", trust);
        Probabilities.requireUnit("confidence", confidence);
        return trust * confidence;
    }
}
