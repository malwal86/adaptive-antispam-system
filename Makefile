# Convenience targets. The build itself is Gradle; these wrap common workflows.
.PHONY: seed seed-download test

# Load the vendored sample corpus through the ingest path (offline, instant).
seed:
	./scripts/seed.sh

# Download the real public corpora (SpamAssassin / Enron / phishing), normalize,
# and load them. See seed-corpus/README.md for sources and licensing.
seed-download:
	./scripts/seed.sh --download

test:
	./gradlew test
