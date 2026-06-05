#!/usr/bin/env bash
#
# Seed the corpus (story 01.03).
#
#   scripts/seed.sh                 # offline: load the vendored sample in seed-corpus/
#   scripts/seed.sh --download      # fetch the real public corpora, normalize, then load
#
# Either way the chosen corpus is loaded through the app's normal ingest path
# (emails.ingest_source = 'seed') and labeled in ground_truth_labels. Loading is
# idempotent — re-running adds zero duplicate emails.
#
# Prerequisites: a reachable Postgres (see docker-compose.yml: `docker compose up -d db`)
# and the `local` Spring profile's datasource settings, or APP_POSTGRES_* in the env.
#
# Real-corpus provenance & licensing: see seed-corpus/README.md.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

DOWNLOAD=false
[[ "${1:-}" == "--download" ]] && DOWNLOAD=true

# Per-source sample cap so a demo seed stays bounded (Enron alone is ~500k mails).
SAMPLE_LIMIT="${SEED_SAMPLE_LIMIT:-200}"

die() { echo "seed: ERROR: $*" >&2; exit 1; }
note() { echo "seed: $*"; }

require() { command -v "$1" >/dev/null 2>&1 || die "required tool '$1' not found on PATH"; }

# Fetch $1 -> $2, failing loudly (AC: a flaky/unavailable download stops with a
# clear message rather than loading a partial, ambiguous corpus).
fetch() {
  local url="$1" out="$2"
  note "downloading $url"
  curl --fail --location --silent --show-error --retry 3 --retry-delay 2 -o "$out" "$url" \
    || die "download failed: $url (check connectivity / source availability)"
  [[ -s "$out" ]] || die "downloaded file is empty: $out"
}

normalize_spamassassin() {
  local work="$1" dest="$2"
  require tar
  local base="https://spamassassin.apache.org/old/publiccorpus"
  declare -A archives=(
    [easy_ham]="20030228_easy_ham.tar.bz2"
    [hard_ham]="20030228_hard_ham.tar.bz2"
    [spam]="20030228_spam.tar.bz2"
  )
  for cls in "${!archives[@]}"; do
    local tarball="$work/${archives[$cls]}"
    fetch "$base/${archives[$cls]}" "$tarball"
    tar -xjf "$tarball" -C "$work" || die "could not extract $tarball"
  done
  # Extracted dirs are named easy_ham/ hard_ham/ spam/. Copy a capped sample into
  # the dataset layout; the class dir name carries the label.
  for cls in easy_ham hard_ham spam; do
    [[ -d "$work/$cls" ]] || continue
    mkdir -p "$dest/spamassassin/$cls"
    find "$work/$cls" -type f | head -n "$SAMPLE_LIMIT" \
      | while read -r f; do cp "$f" "$dest/spamassassin/$cls/"; done
  done
}

normalize_enron() {
  local work="$1" dest="$2"
  require tar
  local tarball="$work/enron_mail.tar.gz"
  fetch "https://www.cs.cmu.edu/~enron/enron_mail_20150507.tar.gz" "$tarball"
  tar -xzf "$tarball" -C "$work" || die "could not extract $tarball"
  # Enron is all legitimate corporate mail -> ham. Sample inbox files to stay bounded.
  mkdir -p "$dest/enron/ham"
  find "$work/maildir" -type f -path '*/inbox/*' 2>/dev/null | head -n "$SAMPLE_LIMIT" \
    | while read -r f; do cp "$f" "$dest/enron/ham/enron-$(basename "$(dirname "$(dirname "$f")")")-$(basename "$f").eml"; done
}

normalize_phish() {
  local work="$1" dest="$2"
  mkdir -p "$dest/phishtank/phish"
  # Nazario phishing corpus ships as mbox files; the loader splits them.
  for name in phishing-2015 phishing-2016 phishing-2017; do
    local mbox="$work/$name.mbox"
    if curl --fail --location --silent --show-error -o "$mbox" \
         "https://monkey.org/~jose/phishing/$name.mbox" 2>/dev/null && [[ -s "$mbox" ]]; then
      cp "$mbox" "$dest/phishtank/phish/$name.mbox"
    else
      note "phish source $name unavailable, skipping (need at least one phish source)"
    fi
  done
  find "$dest/phishtank/phish" -type f | grep -q . \
    || die "no phishing corpus could be downloaded; phish class would be empty"
}

if $DOWNLOAD; then
  require curl
  WORK="$(mktemp -d)"
  trap 'rm -rf "$WORK"' EXIT
  CORPUS_DIR="$REPO_ROOT/build/seed-corpus"
  rm -rf "$CORPUS_DIR"; mkdir -p "$CORPUS_DIR"
  note "normalizing real corpora into $CORPUS_DIR (sample limit $SAMPLE_LIMIT/source)"
  normalize_spamassassin "$WORK" "$CORPUS_DIR"
  normalize_enron        "$WORK" "$CORPUS_DIR"
  normalize_phish        "$WORK" "$CORPUS_DIR"
else
  CORPUS_DIR="$REPO_ROOT/seed-corpus"
  note "using vendored sample corpus at $CORPUS_DIR (run with --download for the real corpora)"
fi

note "loading corpus via the ingest path..."
./gradlew bootRun \
  --args="--spring.profiles.active=${SPRING_PROFILES_ACTIVE:-local} --antispam.seed.enabled=true --antispam.seed.corpus-dir=$CORPUS_DIR"
