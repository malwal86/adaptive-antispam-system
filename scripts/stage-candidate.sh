#!/usr/bin/env bash
# Stage a retrain candidate to Supabase Storage (story 10.02 AC 5): upload the candidate
# artifact — ONNX, metadata, calibration report, parity fixture — under
# candidates/<model_version>/ so the promotion gate (10.03) and registry (10.04) can pull
# it. This is staging, NOT activation: nothing here flips an active flag or touches the
# serving path; the model only becomes live when 10.04 promotes it.
#
# Like the deploy job, this no-ops cleanly when its secrets are absent (e.g. on forks / PRs
# without storage credentials), so the workflow stays green while still uploading the GitHub
# Actions artifact as the always-available staging copy.
#
# Required env:
#   SUPABASE_URL  — project base URL (https://<ref>.supabase.co)
#   SUPABASE_KEY  — service-role key (write access to Storage)
#   BUCKET        — destination storage bucket (e.g. "models")
#   CANDIDATE_DIR — local directory holding the candidate files (default: candidate)
set -euo pipefail

CANDIDATE_DIR="${CANDIDATE_DIR:-candidate}"

if [ -z "${SUPABASE_URL:-}" ] || [ -z "${SUPABASE_KEY:-}" ] || [ -z "${BUCKET:-}" ]; then
  echo "Supabase Storage credentials not set — skipping upload (the GitHub artifact is the staging copy)."
  exit 0
fi

# Derive the model version from the metadata filename: spam-classifier-<version>.metadata.json
metadata_file="$(ls "${CANDIDATE_DIR}"/spam-classifier-*.metadata.json 2>/dev/null | head -n1 || true)"
if [ -z "$metadata_file" ]; then
  echo "::error::no candidate metadata found in ${CANDIDATE_DIR} — nothing to stage."
  exit 1
fi
base="$(basename "$metadata_file")"
version="${base#spam-classifier-}"
version="${version%.metadata.json}"
prefix="candidates/${version}"

echo "Staging candidate ${version} to ${BUCKET}/${prefix} …"
for f in "${CANDIDATE_DIR}"/*; do
  name="$(basename "$f")"
  url="${SUPABASE_URL%/}/storage/v1/object/${BUCKET}/${prefix}/${name}"
  # x-upsert:true so re-running the same nightly overwrites rather than 409s.
  curl -fsS -X POST "$url" \
    -H "Authorization: Bearer ${SUPABASE_KEY}" \
    -H "x-upsert: true" \
    -H "Content-Type: application/octet-stream" \
    --data-binary "@${f}" >/dev/null
  echo "  uploaded ${prefix}/${name}"
done
echo "✅ Candidate ${version} staged (not activated — promotion is story 10.04)."
