package com.antispam.controls.web;

import com.antispam.controls.ControlsService;
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
 * The console left-rail control surface (story 12.02): list/switch policies, apply threshold changes,
 * and read/set the live LLM budget caps. Each write reconfigures the running system, so the effect
 * shows up in the live decision stream — these are real levers, not cosmetic UI state.
 */
@RestController
@RequestMapping("/controls")
public class ControlsController {

    private final ControlsService controls;

    @Autowired
    public ControlsController(ControlsService controls) {
        this.controls = controls;
    }

    @GetMapping(value = "/policies", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<PolicyView> policies() {
        return controls.listPolicies();
    }

    @PostMapping(value = "/policies/{version}/activate", produces = MediaType.APPLICATION_JSON_VALUE)
    public PolicyView activatePolicy(@PathVariable("version") String version) {
        return controls.activatePolicy(version);
    }

    @PostMapping(
            value = "/thresholds",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public PolicyView applyThresholds(@RequestBody ThresholdsRequest request) {
        return controls.applyThresholds(request);
    }

    @GetMapping(value = "/budget", produces = MediaType.APPLICATION_JSON_VALUE)
    public BudgetView budget() {
        return controls.budget();
    }

    @PostMapping(
            value = "/budget",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public BudgetView updateBudget(@RequestBody BudgetCapsRequest request) {
        return controls.updateBudget(request);
    }
}
