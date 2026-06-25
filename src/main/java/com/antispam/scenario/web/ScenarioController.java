package com.antispam.scenario.web;

import com.antispam.scenario.ScenarioRun;
import com.antispam.scenario.ScenarioService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The console's scenario trigger (story 12.05): the single control action that runs the thunderclap
 * end-to-end. {@code POST /controls/scenarios/{name}/start} kicks off the named scenario and returns
 * 202 Accepted immediately — the emails are injected asynchronously onto the live decision stream, so
 * the centre stream and right panel animate the beats as they unfold rather than after a long block.
 */
@RestController
@RequestMapping("/controls/scenarios")
public class ScenarioController {

    private final ScenarioService scenarios;

    @Autowired
    public ScenarioController(ScenarioService scenarios) {
        this.scenarios = scenarios;
    }

    @PostMapping(value = "/{name}/start", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ScenarioRunResponse> start(
            @PathVariable("name") String name,
            @RequestParam(value = "seed", required = false) Long seed) {
        ScenarioRun run = scenarios.start(name, seed);
        return ResponseEntity.accepted().body(ScenarioRunResponse.from(run));
    }
}
