package com.antispam.scenario.web;

import com.antispam.scenario.ScenarioRun;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The {@code POST /controls/scenarios/{name}/start} acknowledgement body: what the runner accepted,
 * returned with 202 while injection proceeds asynchronously. The shadow version is omitted when null
 * (no active policy to derive one from), so the JSON only carries it when the shadow beat is live.
 *
 * @param scenario            the started scenario's name
 * @param steps               how many emails the run will inject
 * @param seed                the seed the script was built from, echoed for reproducibility
 * @param shadowPolicyVersion the shadow policy designated for the run, or absent when none
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ScenarioRunResponse(String scenario, int steps, long seed, String shadowPolicyVersion) {

    public static ScenarioRunResponse from(ScenarioRun run) {
        return new ScenarioRunResponse(run.scenario(), run.steps(), run.seed(), run.shadowPolicyVersion());
    }
}
