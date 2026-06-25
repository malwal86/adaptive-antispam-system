package com.antispam.eval;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Assembles the eval-integrity report (story 11.03): the time-forward leakage evidence of the
 * materialized split together with the anti-circularity evidence of the judging sets. Both halves are
 * read-only re-derivations from the live database — the split audit re-checks the persisted
 * assignment, the judging audit re-checks the label sources — so the report measures what is actually
 * stored, never what the producers intended. It is the single read the gate, a README, or the console
 * cites to back "the eval isn't leaking and isn't grading itself".
 */
@Service
public class EvalIntegrityService {

    private final EvalSplitRepository split;
    private final EvalIntegrityRepository integrity;

    @Autowired
    public EvalIntegrityService(EvalSplitRepository split, EvalIntegrityRepository integrity) {
        this.split = split;
        this.integrity = integrity;
    }

    /** The combined time-forward + no-self-judge report over the currently materialized eval state. */
    public EvalIntegrityReport report() {
        return new EvalIntegrityReport(split.storedAudit(), integrity.auditJudgingSources());
    }
}
