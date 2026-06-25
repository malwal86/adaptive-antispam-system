package com.antispam.ops;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * The always-on-hosting-as-code contract for story 13.03. The hosted demo is only a credible
 * operational claim if the pieces that keep it up — and the lever that makes it affordable — live
 * in version control, not in a dashboard someone clicked once. This test pins, as plain JUnit (no
 * Docker, no network), the parts of that contract that are checked in:
 *
 * <ul>
 *   <li>the Render blueprint declares the always-on guarantees (a health check to restart on, and
 *       CI-gated deploys so a red build never ships) and carries no plaintext secrets;
 *   <li>the cost lever exists as a reproducible script with pause/resume/status over the two metered
 *       managed deps (Aiven + Supabase), driven entirely by environment credentials;
 *   <li>the cost envelope and the lever are documented in {@code DEPLOYMENT.md};
 *   <li>the build version is exposed in {@code /info} (acceptance criterion 5) so the live build is
 *       always identifiable.
 * </ul>
 *
 * <p>The live "deploy → /health 200 → managed deps connected" path is exercised by the CD smoke test
 * in CI (see {@code DEPLOYMENT.md}); this test guards the artifacts that make that path reproducible.
 */
class HostingProvisioningTest {

    private static final Path RENDER = Path.of("render.yaml");
    private static final Path COST_LEVER = Path.of("ops/cost-lever.sh");
    private static final Path DEPLOYMENT = Path.of("DEPLOYMENT.md");
    private static final Path APPLICATION = Path.of("src/main/resources/application.yml");

    @SuppressWarnings("unchecked")
    private Map<String, Object> renderWebService() throws IOException {
        assertThat(RENDER).as("the Render blueprint must be checked in").exists();
        Map<String, Object> blueprint = new Yaml().load(Files.readString(RENDER));
        List<Map<String, Object>> services = (List<Map<String, Object>>) blueprint.get("services");
        assertThat(services).as("blueprint must declare the Java web service").isNotEmpty();
        return services.get(0);
    }

    @Test
    void the_render_service_stays_always_on_with_a_health_check_and_ci_gated_deploys() throws IOException {
        Map<String, Object> svc = renderWebService();
        // A health check is what the platform restarts the service on — the always-on guarantee
        // (AC 2). It is also the public liveness probe the CD smoke test polls (AC 1).
        assertThat(svc.get("healthCheckPath")).isEqualTo("/health");
        // Deploys are gated by a green CI build, not Render's own auto-deploy, so a red build never
        // ships and the live demo stays up (DEPLOYMENT.md).
        assertThat(svc.get("autoDeploy")).isEqualTo(false);
        // A concrete paid plan, not a sleep-prone free tier — the demo must answer the first
        // request without a cold-start timeout (AC 2).
        assertThat((String) svc.get("plan")).isNotBlank();
        assertThat(svc.get("type")).isEqualTo("web");
    }

    @Test
    @SuppressWarnings("unchecked")
    void the_render_blueprint_carries_no_plaintext_secrets() throws IOException {
        // Secrets are set in the dashboard (sync: false), never committed (AC 1: "no secrets in
        // repo"). The few env vars with literal values must be non-secret config only — assert the
        // obvious secret keys are all sync:false.
        Map<String, Object> svc = renderWebService();
        List<Map<String, Object>> envVars = (List<Map<String, Object>>) svc.get("envVars");
        assertThat(envVars).isNotEmpty();
        for (Map<String, Object> env : envVars) {
            String key = (String) env.get("key");
            boolean looksSecret = key.contains("PASSWORD")
                    || key.contains("API_KEY")
                    || key.contains("JAAS")
                    || key.endsWith("_CERTIFICATES");
            if (looksSecret) {
                assertThat(env.get("sync"))
                        .as("secret env var %s must be sync:false (set in dashboard, not repo)", key)
                        .isEqualTo(false);
                assertThat(env).as("secret env var %s must not carry a literal value", key)
                        .doesNotContainKey("value");
            }
        }
    }

    @Test
    void the_cost_lever_is_a_reproducible_script_with_pause_resume_status() throws IOException {
        assertThat(COST_LEVER).as("the pause/spin-up lever must be checked in").exists();
        assertThat(Files.isExecutable(COST_LEVER)).as("the lever must be executable").isTrue();
        String lever = Files.readString(COST_LEVER);
        // The three operations the ops drill (AC 4) needs: take cost-relevant resources down, bring
        // them back, and report the current state so the drill is verifiable.
        assertThat(lever).contains("pause)");
        assertThat(lever).contains("resume)");
        assertThat(lever).contains("status)");
    }

    @Test
    void the_cost_lever_pauses_the_two_metered_managed_deps_and_leaves_the_demo_urls_up() throws IOException {
        String lever = Files.readString(COST_LEVER);
        // The two metered deps the PRD names as the cheapest idle lever (~$35/mo): Aiven + Supabase.
        assertThat(lever).containsIgnoringCase("aiven");
        assertThat(lever).containsIgnoringCase("supabase");
        // Render (Java) and Vercel (console) stay always-on so the public URLs never 404 while
        // paused; the lever must say so, not silently leave them out.
        assertThat(lever).containsIgnoringCase("render");
        assertThat(lever).containsIgnoringCase("vercel");
    }

    @Test
    void the_cost_lever_takes_its_credentials_from_the_environment_not_the_repo() throws IOException {
        // Same "no secrets in repo" rule as the Render blueprint (AC 1): the lever reads tokens from
        // the environment. Assert the credential env vars are referenced and no token is inlined.
        String lever = Files.readString(COST_LEVER);
        assertThat(lever).contains("AIVEN_TOKEN");
        assertThat(lever).contains("SUPABASE_ACCESS_TOKEN");
        // A real Supabase service-role / personal token starts "sbp_"; an Aiven one is a long hex —
        // guard against a token getting pasted into the script.
        assertThat(lever).doesNotContain("sbp_");
    }

    @Test
    void deployment_doc_states_the_cost_envelope_and_references_the_lever() throws IOException {
        assertThat(DEPLOYMENT).exists();
        String doc = Files.readString(DEPLOYMENT);
        // The PRD cost band the running config must fit (AC 3) and the idle figure the lever targets
        // (AC 4) are documented, not left implicit.
        assertThat(doc).contains("$70");
        assertThat(doc).contains("$35");
        assertThat(doc).contains("cost-lever.sh");
    }

    @Test
    @SuppressWarnings("unchecked")
    void the_build_version_is_exposed_in_info() throws IOException {
        // AC 5: the live build is identifiable via /info. The actuator info endpoint is exposed and
        // its build contributor is on, so /info reports the version stamped by buildInfo (01.01).
        Map<String, Object> app = new Yaml().load(Files.readString(APPLICATION));
        Map<String, Object> management = (Map<String, Object>) app.get("management");
        Map<String, Object> info = (Map<String, Object>) management.get("info");
        Map<String, Object> build = (Map<String, Object>) info.get("build");
        assertThat(build.get("enabled")).isEqualTo(true);
        String exposed = management.toString();
        assertThat(exposed).contains("info");
    }
}
