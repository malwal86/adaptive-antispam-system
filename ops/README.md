# Observability stack (Prometheus + Grafana)

The ops surface for the Living Anti-Spam System: Micrometer (story 13.01) → Prometheus → Grafana
(story 13.02). The console tells the *story*; this shows the *operations* — the latency budget, the
route mix, LLM cost vs cap, and decision quality — all as **code**, so it reproduces in a clean
environment with no hand-building.

```
Spring app  /prometheus  ──scrape──▶  Prometheus  ──query──▶  Grafana dashboard
(Micrometer, story 13.01)             (prometheus/)            (grafana/, provisioned)
```

## Run it

```bash
# 1. Run the API on :8080 with metrics enabled
PORT=8080 java -jar build/libs/living-antispam-*.jar
#    (the /prometheus endpoint is on by default — story 13.01)

# 2. Bring up Prometheus + Grafana (provisioned from this directory)
docker compose -f ops/docker-compose.observability.yml up
```

- **Grafana** → http://localhost:3001 — opens anonymously on the **“Living Anti-Spam — Ops”**
  dashboard, already populated. No login, no datasource picking, no dashboard import.
- **Prometheus** → http://localhost:9090 — check **Status → Targets**; `antispam-api` should be
  `UP`.

> **The scrape path is `/prometheus`, not `/metrics`.** The actuator endpoints are root-remapped
> (see `application.yml`), so `prometheus/prometheus.yml` sets `metrics_path: /prometheus`. This is
> the one setting that, if wrong, leaves every panel empty.

## What the dashboard shows

| Panel | Series | Answers |
|---|---|---|
| Fast-path p95 latency (stat) | `antispam_decision_latency_seconds` (HARD_RULE\|MODEL) | Are we under the **100ms** budget? (red past 0.1s) |
| Fast-path latency vs budget (graph) | p50/p95/p99 with a **0.1s line** | How much headroom against the budget? |
| LLM-routed fraction | `antispam_decision_route_total{route="LLM"}` / total | Is the cost lever near the ~5% target? |
| Route mix over time | `antispam_decision_route_total` by `route` | The fast-vs-LLM split |
| Decisions by tier | `antispam_decision_tier_total` by `tier` | allow/warn/quarantine/block distribution |
| LLM cost vs cap | `antispam_llm_cost_usd_total` vs `antispam_llm_budget_cap_usd{scope}` | Spend against the daily/monthly cap; the gap is budget-remaining |
| Degraded-mode frequency | `antispam_decision_degraded_total`, `antispam_llm_resolution_total{state="degraded"}`, `antispam_llm_sla_timeout_total` | How often we fail-degrade |

## Files (all version-controlled)

```
ops/
├── docker-compose.observability.yml      # Prometheus + Grafana, provisioning mounted
├── prometheus/prometheus.yml             # scrape config (metrics_path: /prometheus)
└── grafana/
    ├── provisioning/
    │   ├── datasources/prometheus.yml    # datasource (uid: prometheus)
    │   └── dashboards/provider.yml        # loads /var/lib/grafana/dashboards
    └── dashboards/antispam-ops.json       # the dashboard
```

The panels query exactly the series story 13.01 exports; `ObservabilityProvisioningTest` pins that
contract (panels present, the 100ms budget marked, the cost-vs-cap reference, and the `/prometheus`
scrape path) so the dashboard can't silently drift from the metrics.

## Hosted demo

To watch the live Render deployment, uncomment the `antispam-api-hosted` job in
`prometheus/prometheus.yml` and set the host (story 13.03 wires the always-on deploy). The remapped
path and per-process counters behave identically there.
