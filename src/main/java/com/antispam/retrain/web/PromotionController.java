package com.antispam.retrain.web;

import com.antispam.decision.model.ModelRegistry;
import com.antispam.decision.model.ServedModel;
import com.antispam.decision.policy.Policy;
import com.antispam.decision.policy.PolicyRepository;
import com.antispam.retrain.ModelPromotionService;
import com.antispam.retrain.ModelVersionRepository;
import com.antispam.retrain.PromotionResult;
import com.antispam.retrain.RollbackResult;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The promotion / rollback / observability endpoints of the retrain loop (story 10.04).
 *
 * <ul>
 *   <li>{@code POST /retrain/promote?run=<replay-run-id>&by=<actor>} — re-checks the precision gate
 *       for the run and, only if it passed, registers the candidate model and flips its policy live.
 *   <li>{@code POST /retrain/rollback?to=<policy-version>&by=<actor>} — reactivates a prior policy.
 *   <li>{@code GET /retrain/active-model} — reports what is served right now (AC 5).
 * </ul>
 *
 * <p>Both mutating endpoints flip the active flag in {@code policies}; the model change itself is then
 * observed lazily by the serving path on the next decision (no redeploy, no per-email swap). They sit
 * alongside {@code /retrain/gate} so the scheduled retrain pipeline drives the whole loop — grade, then
 * promote — through one REST surface.
 */
@RestController
@RequestMapping("/retrain")
public class PromotionController {

    private final ModelPromotionService promotionService;
    private final ServedModel servedModel;
    private final PolicyRepository policies;
    private final ModelRegistry registry;
    private final ModelVersionRepository modelVersions;

    @Autowired
    public PromotionController(
            ModelPromotionService promotionService,
            ServedModel servedModel,
            PolicyRepository policies,
            ModelRegistry registry,
            ModelVersionRepository modelVersions) {
        this.promotionService = promotionService;
        this.servedModel = servedModel;
        this.policies = policies;
        this.registry = registry;
        this.modelVersions = modelVersions;
    }

    @PostMapping(value = "/promote", produces = MediaType.APPLICATION_JSON_VALUE)
    public PromotionResult promote(
            @RequestParam("run") UUID run,
            @RequestParam(value = "by", defaultValue = "system") String by) {
        return promotionService.promote(run, by);
    }

    @PostMapping(value = "/rollback", produces = MediaType.APPLICATION_JSON_VALUE)
    public RollbackResult rollback(
            @RequestParam("to") String to,
            @RequestParam(value = "by", defaultValue = "system") String by) {
        return promotionService.rollback(to, by);
    }

    @GetMapping(value = "/active-model", produces = MediaType.APPLICATION_JSON_VALUE)
    public ActiveModelView activeModel() {
        String activePolicy = policies.findActive().map(Policy::version).orElse(null);
        List<String> loaded = registry.loadedVersions().stream().sorted().toList();
        return new ActiveModelView(
                servedModel.activeModelVersion(),
                activePolicy,
                loaded,
                modelVersions.findLatest().orElse(null));
    }
}
