package com.antispam.experiment.shadow.web;

import com.antispam.decision.policy.PolicyRepository;
import com.antispam.experiment.shadow.ShadowDecisionRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The live shadow-testing endpoints (story 09.02).
 *
 * <p>{@code POST /shadow/policy} designates the shadow (logged-only) policy — once set, every live
 * decision is also scored under it and the diff recorded; {@code DELETE /shadow/policy} turns shadow
 * testing off. {@code GET /shadow/agreement} returns the agreement/direction rates for an
 * active-vs-shadow pairing (the promotion evidence Epic 10 consumes), and
 * {@code GET /shadow/decisions/email/{emailId}} returns the per-email diffs. None of these enforce
 * anything: shadow is observation only.
 */
@RestController
@RequestMapping("/shadow")
public class ShadowController {

    private final PolicyRepository policies;
    private final ShadowDecisionRepository shadowDecisions;

    @Autowired
    public ShadowController(PolicyRepository policies, ShadowDecisionRepository shadowDecisions) {
        this.policies = policies;
        this.shadowDecisions = shadowDecisions;
    }

    @PostMapping(path = "/policy", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ShadowPolicyResponse designate(@RequestBody DesignateShadowRequest request) {
        policies.markShadow(request.version());
        return new ShadowPolicyResponse(request.version());
    }

    @DeleteMapping(path = "/policy")
    public ResponseEntity<Void> clear() {
        policies.clearShadow();
        return ResponseEntity.noContent().build();
    }

    @GetMapping(path = "/agreement", produces = MediaType.APPLICATION_JSON_VALUE)
    public AgreementStatsResponse agreement(
            @RequestParam("active") String activePolicyVersion,
            @RequestParam("shadow") String shadowPolicyVersion) {
        return AgreementStatsResponse.from(
                shadowDecisions.agreementStats(activePolicyVersion, shadowPolicyVersion));
    }

    @GetMapping(path = "/decisions/email/{emailId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<ShadowDecisionResponse> byEmail(@PathVariable("emailId") UUID emailId) {
        return shadowDecisions.findByEmailId(emailId).stream()
                .map(ShadowDecisionResponse::from).toList();
    }
}
