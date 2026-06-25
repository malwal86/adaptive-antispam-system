# Convenience targets. The build itself is Gradle; these wrap common workflows.
.PHONY: seed seed-download test observability observability-down

# Load the vendored sample corpus through the ingest path (offline, instant).
seed:
	./scripts/seed.sh

# Download the real public corpora (SpamAssassin / Enron / phishing), normalize,
# and load them. See seed-corpus/README.md for sources and licensing.
seed-download:
	./scripts/seed.sh --download

test:
	./gradlew test

# Bring up the provisioned Prometheus + Grafana ops stack (story 13.02). Run the API on :8080
# first; then Grafana is http://localhost:3001 (anonymous) and Prometheus http://localhost:9090.
# See ops/README.md.
observability:
	docker compose -f ops/docker-compose.observability.yml up

observability-down:
	docker compose -f ops/docker-compose.observability.yml down -v
