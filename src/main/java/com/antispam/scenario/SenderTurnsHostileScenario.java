package com.antispam.scenario;

import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The original thunderclap (story 12.05): a hero sender warms up with authenticated, benign mail,
 * then that <em>same</em> still-authenticated account is compromised and fires a phishing burst — so
 * the reputation curve that rose is the one that collapses, a spoof of the warmed domain gets none of
 * the earned trust, and a misconfigured-legit sender still earns trust slowly. The email content and
 * its exact reproducibility live in {@link ThunderclapScript}; this class only names it for the
 * {@link ScenarioCatalog}.
 */
@Component
public class SenderTurnsHostileScenario implements Scenario {

    @Override
    public String name() {
        return ThunderclapScript.NAME;
    }

    @Override
    public List<ScenarioEmail> build(long seed) {
        return ThunderclapScript.build(seed);
    }
}
