package com.antispam.retrain.web;

import com.antispam.retrain.GateResult;
import com.antispam.retrain.PrecisionGateService;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The precision-floor promotion gate endpoint (story 10.03).
 *
 * <p>{@code GET /retrain/gate?run=<replay-run-id>} grades a completed candidate replay on the golden
 * set and returns the {@link GateResult}: whether the candidate cleared the precision floor, the
 * precision-vs-floor it turned on, and the reported (non-blocking) recall/bypass/cost evidence. It is a
 * pure, deterministic read, so the scheduled retrain pipeline (10.02/10.04) can poll it to decide
 * whether to promote a candidate the same way the deploy smoke tests curl the other endpoints.
 *
 * <p>An optional {@code &goldenVersion=<version>} pins the grading to a FROZEN golden version (story
 * 11.02) instead of the live eval side, so the precision is comparable across model versions. Without
 * it, the gate grades on the current eval-side golden set, preserving the 10.03 behavior.
 *
 * <p>The verdict is returned as the domain {@link GateResult} unchanged — the same shape the registry
 * + flag flip (10.04) acts on — so the REST surface and the promotion step read one shape, not two
 * that could drift.
 */
@RestController
@RequestMapping("/retrain/gate")
public class PrecisionGateController {

    private final PrecisionGateService service;

    @Autowired
    public PrecisionGateController(PrecisionGateService service) {
        this.service = service;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public GateResult evaluate(
            @RequestParam("run") UUID run,
            @RequestParam(value = "goldenVersion", required = false) String goldenVersion) {
        return goldenVersion == null ? service.evaluate(run) : service.evaluate(run, goldenVersion);
    }
}
