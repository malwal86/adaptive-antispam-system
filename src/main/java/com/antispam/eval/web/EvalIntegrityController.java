package com.antispam.eval.web;

import com.antispam.eval.EvalIntegrityService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Surfaces the eval-integrity report (story 11.03).
 *
 * <p>{@code GET /eval/integrity} returns the time-forward leakage evidence of the materialized split
 * and the anti-circularity evidence of the judging sets — the numbers that make every accuracy claim
 * defensible: the holdout is past→future, no family straddles the split, and simulator feedback never
 * judges. It is a pure, read-only re-derivation from the live database, so a demo or the promotion
 * pipeline can curl it the same way it reads the split and gate endpoints.
 */
@RestController
@RequestMapping("/eval/integrity")
public class EvalIntegrityController {

    private final EvalIntegrityService service;

    @Autowired
    public EvalIntegrityController(EvalIntegrityService service) {
        this.service = service;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public EvalIntegrityResponse report() {
        return EvalIntegrityResponse.from(service.report());
    }
}
