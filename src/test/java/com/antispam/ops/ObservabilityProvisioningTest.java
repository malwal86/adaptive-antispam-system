package com.antispam.ops;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * The dashboards-and-provisioning-as-code contract for story 13.02. Grafana panels are only an
 * operational asset if they (a) come up populated from version-controlled config with no manual
 * clicking and (b) actually query the series story 13.01 exports — a dashboard that points at a
 * renamed or non-existent metric is worse than none. This test pins both: it parses the checked-in
 * Grafana dashboard and the Prometheus/Grafana provisioning files and asserts the panels, the
 * &lt;100ms budget marker, the cost-vs-cap reference, and the scrape wiring are all present and
 * consistent.
 *
 * <p>It is a plain JUnit test (no Docker), so it runs locally and in CI alike — the live
 * render-against-Prometheus path is covered separately by {@code PrometheusScrapeIntegrationTest}
 * (13.01), which pins the exact rendered metric names this dashboard depends on.
 */
class ObservabilityProvisioningTest {

    private static final Path OPS = Path.of("ops");
    private static final Path DASHBOARD = OPS.resolve("grafana/dashboards/antispam-ops.json");
    private static final Path DATASOURCE = OPS.resolve("grafana/provisioning/datasources/prometheus.yml");
    private static final Path PROVIDER = OPS.resolve("grafana/provisioning/dashboards/provider.yml");
    private static final Path PROMETHEUS = OPS.resolve("prometheus/prometheus.yml");

    private static final ObjectMapper JSON = new ObjectMapper();

    private JsonNode dashboard() throws IOException {
        assertThat(DASHBOARD).as("dashboard JSON must be checked in").exists();
        return JSON.readTree(Files.readString(DASHBOARD));
    }

    /** Every PromQL target expression across all panels (handles nested row panels). */
    private List<String> allExprs(JsonNode dashboard) {
        List<String> exprs = new ArrayList<>();
        collectExprs(dashboard.path("panels"), exprs);
        return exprs;
    }

    private void collectExprs(JsonNode panels, List<String> out) {
        for (JsonNode panel : panels) {
            for (JsonNode target : panel.path("targets")) {
                String expr = target.path("expr").asText("");
                if (!expr.isBlank()) {
                    out.add(expr);
                }
            }
            if (panel.has("panels")) {
                collectExprs(panel.path("panels"), out);
            }
        }
    }

    @Test
    void the_dashboard_is_valid_json_with_a_title_and_panels() throws IOException {
        JsonNode dashboard = dashboard();
        assertThat(dashboard.path("title").asText()).isNotBlank();
        assertThat(dashboard.path("panels")).isNotEmpty();
        assertThat(allExprs(dashboard)).isNotEmpty();
    }

    @Test
    void the_panels_query_the_series_story_13_01_exports() throws IOException {
        String exprs = String.join("\n", allExprs(dashboard()));

        // Fast-path latency percentiles, filtered to the synchronous (non-LLM) routes (AC 1).
        assertThat(exprs).contains("antispam_decision_latency_seconds_bucket");
        assertThat(exprs).contains("histogram_quantile");
        assertThat(exprs).contains("HARD_RULE|MODEL");
        // route_used mix over time (AC 2).
        assertThat(exprs).contains("antispam_decision_route_total");
        // LLM cost vs the configured cap (AC 2).
        assertThat(exprs).contains("antispam_llm_cost_usd_total");
        assertThat(exprs).contains("antispam_llm_budget_cap_usd");
        // decisions by tier (AC 3).
        assertThat(exprs).contains("antispam_decision_tier_total");
        // degraded-mode frequency (AC 3).
        assertThat(exprs).contains("antispam_decision_degraded_total");
    }

    @Test
    void the_latency_panel_marks_the_100ms_fast_path_budget() throws IOException {
        // The <100ms budget (PRD §Subsystem 1) must be a visible reference line, i.e. a threshold
        // step at 0.1s on a latency panel — not just left to the reader's arithmetic (AC 1).
        boolean budgetMarked = false;
        for (JsonNode panel : dashboard().path("panels")) {
            boolean isLatency = panel.path("targets").findValuesAsText("expr").stream()
                    .anyMatch(e -> e.contains("antispam_decision_latency_seconds"));
            if (!isLatency) {
                continue;
            }
            for (JsonNode step : panel.path("fieldConfig").path("defaults").path("thresholds").path("steps")) {
                if (step.path("value").isNumber() && step.path("value").asDouble() == 0.1) {
                    budgetMarked = true;
                }
            }
        }
        assertThat(budgetMarked)
                .as("a latency panel must mark the 0.1s (100ms) budget as a threshold")
                .isTrue();
    }

    @Test
    void every_panel_target_uses_the_provisioned_prometheus_datasource() throws IOException {
        // So the dashboard comes up populated with no manual datasource picking (AC 5): every target
        // must reference the datasource uid the provisioning file defines.
        List<JsonNode> uids = new ArrayList<>();
        collectTargetDatasourceUids(dashboard().path("panels"), uids);
        assertThat(uids).isNotEmpty();
        assertThat(uids).allSatisfy(uid -> assertThat(uid.asText()).isEqualTo("prometheus"));
    }

    private void collectTargetDatasourceUids(JsonNode panels, List<JsonNode> out) {
        for (JsonNode panel : panels) {
            for (JsonNode target : panel.path("targets")) {
                JsonNode ds = target.path("datasource").path("uid");
                if (!ds.isMissingNode()) {
                    out.add(ds);
                }
            }
            if (panel.has("panels")) {
                collectTargetDatasourceUids(panel.path("panels"), out);
            }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void the_datasource_is_provisioned_as_code_with_the_matching_uid() throws IOException {
        assertThat(DATASOURCE).exists();
        Map<String, Object> ds = new Yaml().load(Files.readString(DATASOURCE));
        List<Map<String, Object>> sources = (List<Map<String, Object>>) ds.get("datasources");
        assertThat(sources).hasSizeGreaterThanOrEqualTo(1);
        Map<String, Object> prometheus = sources.get(0);
        assertThat(prometheus.get("type")).isEqualTo("prometheus");
        assertThat(prometheus.get("uid")).isEqualTo("prometheus");
        assertThat((String) prometheus.get("url")).isNotBlank();
    }

    @Test
    @SuppressWarnings("unchecked")
    void the_dashboard_provider_loads_the_checked_in_dashboards_directory() throws IOException {
        assertThat(PROVIDER).exists();
        Map<String, Object> provider = new Yaml().load(Files.readString(PROVIDER));
        List<Map<String, Object>> providers = (List<Map<String, Object>>) provider.get("providers");
        assertThat(providers).hasSizeGreaterThanOrEqualTo(1);
        Map<String, Object> options = (Map<String, Object>) providers.get(0).get("options");
        assertThat((String) options.get("path")).isNotBlank();
    }

    @Test
    void prometheus_scrapes_the_root_remapped_metrics_path() throws IOException {
        // The actuator endpoint is root-remapped to /prometheus (not the Prometheus default
        // /metrics), so the scrape config MUST set metrics_path: /prometheus or it silently scrapes
        // nothing — the single most likely "dashboards are empty" mistake (story 13.01 remap).
        assertThat(PROMETHEUS).exists();
        String prometheus = Files.readString(PROMETHEUS);
        assertThat(prometheus).contains("/prometheus");
        assertThat(prometheus).contains("job_name");
    }
}
