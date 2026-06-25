package com.antispam.scenario;

/**
 * The acknowledgement of a started scenario (story 12.05): what the runner accepted, returned
 * immediately while the emails are still being injected asynchronously onto the live stream.
 *
 * @param scenario            the scenario name that was started
 * @param steps               how many emails the run will inject in total
 * @param seed                the seed the script was built from — echo it back so a memorable run can
 *                            be reproduced exactly by passing the same seed
 * @param shadowPolicyVersion the shadow policy in force for this run (so the shadow-diff beat lights
 *                            up), or null when no active policy existed to derive one from
 */
public record ScenarioRun(String scenario, int steps, long seed, String shadowPolicyVersion) {
}
