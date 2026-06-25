#!/usr/bin/env bash
#
# ops/cost-lever.sh — the documented pause/spin-up lever for story 13.03.
#
# The cheapest way to ride out a long search without tearing the demo down: pause
# the two *metered* managed dependencies between demos and bring them back when a
# demo is imminent. Those two are:
#
#   • Aiven (Kafka, the event spine)   — billed while powered on; "power off"
#     stops compute billing but preserves the service + its config.
#   • Supabase (Postgres, source store) — a paused project stops the compute meter
#     while keeping the database; "restore" brings it back.
#
# Render (the Java API) and Vercel (the Next.js console) are flat / effectively
# always-on, so this lever deliberately LEAVES THEM UP — the public demo URLs keep
# answering /health and serving the console even while the data plane is paused
# (the API fail-degrades: Redis is optional by design, and the reputation cache
# falls back to Postgres which simply errors loudly until resume). Pausing the two
# metered deps takes the idle bill from the active ~$100–140/mo band toward the
# PRD's ~$35/mo floor. See DEPLOYMENT.md › "Cost envelope & the pause lever".
#
# Usage:
#   ops/cost-lever.sh pause     # power off Aiven + pause Supabase  (idle ~$35/mo)
#   ops/cost-lever.sh resume    # power on  Aiven + restore Supabase (back to live)
#   ops/cost-lever.sh status    # report the current power state of both
#
# It is idempotent: pausing an already-paused service (or resuming a running one)
# is a no-op, so the ops drill can be re-run safely.
#
# Credentials come from the ENVIRONMENT — never the repo (same rule as render.yaml):
#   AIVEN_TOKEN            Aiven personal token        (https://console.aiven.io › Tokens)
#   AIVEN_PROJECT         Aiven project name
#   AIVEN_KAFKA_SERVICE   Aiven Kafka service name
#   SUPABASE_ACCESS_TOKEN Supabase access token       (https://supabase.com/dashboard/account/tokens)
#   SUPABASE_PROJECT_REF  Supabase project ref        (the <ref> in db.<ref>.supabase.co)
#
# Requires: bash, curl, python3 (for JSON parsing — no jq dependency).

set -euo pipefail

AIVEN_API="https://api.aiven.io/v1"
SUPABASE_API="https://api.supabase.com/v1"

die() { echo "error: $*" >&2; exit 1; }

require_env() {
  local missing=()
  for var in "$@"; do
    [[ -n "${!var:-}" ]] || missing+=("$var")
  done
  if [[ ${#missing[@]} -gt 0 ]]; then
    die "missing required env: ${missing[*]} (see the header of this script)"
  fi
}

# Extract a top-level-ish JSON field by dotted path, e.g. json_get '.service.state'.
json_get() {
  python3 -c 'import sys,json
d=json.load(sys.stdin)
for k in [p for p in sys.argv[1].split(".") if p]:
    d=d.get(k, {}) if isinstance(d, dict) else {}
print(d if isinstance(d,(str,int,float)) else "")' "$1"
}

# --- Aiven (Kafka) -----------------------------------------------------------
# The service "state" is RUNNING when powered on and POWEROFF when powered off;
# the update payload toggles {"powered": true|false}.

aiven_state() {
  curl -fsS -H "Authorization: aivenv1 ${AIVEN_TOKEN}" \
    "${AIVEN_API}/project/${AIVEN_PROJECT}/service/${AIVEN_KAFKA_SERVICE}" \
    | json_get '.service.state'
}

aiven_power() {
  local powered="$1" # true | false
  curl -fsS -X PUT -H "Authorization: aivenv1 ${AIVEN_TOKEN}" \
    -H 'Content-Type: application/json' \
    -d "{\"powered\": ${powered}}" \
    "${AIVEN_API}/project/${AIVEN_PROJECT}/service/${AIVEN_KAFKA_SERVICE}" >/dev/null
}

aiven_pause() {
  require_env AIVEN_TOKEN AIVEN_PROJECT AIVEN_KAFKA_SERVICE
  if [[ "$(aiven_state)" == "POWEROFF" ]]; then
    echo "  aiven: already powered off (no-op)"
  else
    aiven_power false
    echo "  aiven: powering off ${AIVEN_KAFKA_SERVICE}"
  fi
}

aiven_resume() {
  require_env AIVEN_TOKEN AIVEN_PROJECT AIVEN_KAFKA_SERVICE
  if [[ "$(aiven_state)" == "POWEROFF" ]]; then
    aiven_power true
    echo "  aiven: powering on ${AIVEN_KAFKA_SERVICE}"
  else
    echo "  aiven: already running (no-op)"
  fi
}

# --- Supabase (Postgres) -----------------------------------------------------
# Project "status" is ACTIVE_HEALTHY when up and INACTIVE when paused. Pausing is
# POST /projects/{ref}/pause; bringing it back is POST /projects/{ref}/restore.

supabase_status() {
  curl -fsS -H "Authorization: Bearer ${SUPABASE_ACCESS_TOKEN}" \
    "${SUPABASE_API}/projects/${SUPABASE_PROJECT_REF}" \
    | json_get '.status'
}

supabase_pause() {
  require_env SUPABASE_ACCESS_TOKEN SUPABASE_PROJECT_REF
  if [[ "$(supabase_status)" == "INACTIVE" ]]; then
    echo "  supabase: already paused (no-op)"
  else
    curl -fsS -X POST -H "Authorization: Bearer ${SUPABASE_ACCESS_TOKEN}" \
      "${SUPABASE_API}/projects/${SUPABASE_PROJECT_REF}/pause" >/dev/null
    echo "  supabase: pausing ${SUPABASE_PROJECT_REF}"
  fi
}

supabase_resume() {
  require_env SUPABASE_ACCESS_TOKEN SUPABASE_PROJECT_REF
  if [[ "$(supabase_status)" == "INACTIVE" ]]; then
    curl -fsS -X POST -H "Authorization: Bearer ${SUPABASE_ACCESS_TOKEN}" \
      "${SUPABASE_API}/projects/${SUPABASE_PROJECT_REF}/restore" >/dev/null
    echo "  supabase: restoring ${SUPABASE_PROJECT_REF}"
  else
    echo "  supabase: already active (no-op)"
  fi
}

# --- driver ------------------------------------------------------------------

case "${1:-}" in
  pause)
    echo "pausing the metered managed deps (Render + Vercel stay up):"
    aiven_pause
    supabase_pause
    echo "done — idle config now targets the PRD's ~\$35/mo floor."
    ;;
  resume)
    echo "spinning the metered managed deps back up:"
    aiven_resume
    supabase_resume
    echo "done — give Supabase a minute to report ACTIVE_HEALTHY, then hit /health."
    ;;
  status)
    require_env AIVEN_TOKEN AIVEN_PROJECT AIVEN_KAFKA_SERVICE SUPABASE_ACCESS_TOKEN SUPABASE_PROJECT_REF
    echo "  aiven    (${AIVEN_KAFKA_SERVICE}): $(aiven_state)"
    echo "  supabase (${SUPABASE_PROJECT_REF}): $(supabase_status)"
    echo "  render   (living-antispam): always-on (not toggled by this lever)"
    echo "  vercel   (console):         always-on (not toggled by this lever)"
    ;;
  *)
    die "usage: $0 {pause|resume|status}"
    ;;
esac
