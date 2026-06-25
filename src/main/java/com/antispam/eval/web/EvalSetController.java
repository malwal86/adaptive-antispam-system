package com.antispam.eval.web;

import com.antispam.eval.EvalSetService;
import com.antispam.eval.GoldenSetVersion;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Freezes and inspects the two eval sets of story 11.02 — the frozen golden benchmark and the rolling
 * fresh challenge set.
 *
 * <ul>
 *   <li>{@code POST /eval/golden/{version}} freezes the current held-out eval side into a new immutable
 *       golden version, returning its provenance and class balance. Re-using a version label is
 *       rejected ({@code 409}) — a frozen benchmark is never redefined.</li>
 *   <li>{@code GET /eval/golden} lists the frozen versions, newest first, so a report can identify the
 *       benchmark the gate measures against.</li>
 *   <li>{@code POST /eval/fresh} appends one reported attack to the rolling fresh set; {@code GET
 *       /eval/fresh} returns its class balance. The fresh set never touches the golden set.</li>
 * </ul>
 */
@RestController
@RequestMapping("/eval")
public class EvalSetController {

    private final EvalSetService service;

    @Autowired
    public EvalSetController(EvalSetService service) {
        this.service = service;
    }

    @PostMapping(path = "/golden/{version}", produces = MediaType.APPLICATION_JSON_VALUE)
    public FreezeGoldenResponse freezeGolden(@PathVariable("version") String version) {
        GoldenSetVersion frozen = service.freezeGolden(version);
        return FreezeGoldenResponse.from(frozen, service.goldenCountsByLabel(frozen.version()));
    }

    @GetMapping(path = "/golden", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<GoldenSetVersionResponse> goldenVersions() {
        return service.goldenVersions().stream().map(GoldenSetVersionResponse::from).toList();
    }

    @PostMapping(path = "/fresh", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public FreshSetResponse addFreshChallenge(@RequestBody AddFreshChallengeRequest request) {
        service.addFreshChallenge(request.emailId(), request.label(), request.source());
        return FreshSetResponse.from(service.freshCountsByLabel());
    }

    @GetMapping(path = "/fresh", produces = MediaType.APPLICATION_JSON_VALUE)
    public FreshSetResponse freshSet() {
        return FreshSetResponse.from(service.freshCountsByLabel());
    }
}
