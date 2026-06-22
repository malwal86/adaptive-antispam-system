package com.antispam.eval.web;

import com.antispam.eval.BootstrapEvalSplitService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Triggers and inspects the bootstrap train/eval split (stories 11.01 / 11.03).
 *
 * <p>{@code POST /eval/split} rebuilds the split over the whole labeled corpus and
 * returns its leakage-free audit — the observable proof that no family spans the
 * boundary and the holdout is time-forward. {@code GET /eval/split} returns the
 * class balance of the split currently materialized, so a viewer can confirm the
 * held-out set exists without recomputing it.
 */
@RestController
@RequestMapping("/eval/split")
public class EvalSplitController {

    private final BootstrapEvalSplitService service;

    @Autowired
    public EvalSplitController(BootstrapEvalSplitService service) {
        this.service = service;
    }

    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public EvalSplitAuditResponse rebuild() {
        return EvalSplitAuditResponse.from(service.rebuild(), service.configuration());
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public EvalSplitSummaryResponse current() {
        return EvalSplitSummaryResponse.from(service.currentCountsByLabel(), service.configuration());
    }
}
