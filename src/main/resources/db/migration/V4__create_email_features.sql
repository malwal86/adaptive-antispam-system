-- Versioned feature vectors extracted from each email (story 02.02). Both the
-- live classifier and offline retrains read these, so the row is keyed by
-- (email_id, feature_version): re-extracting under a bumped feature_version
-- creates a new row alongside the old one rather than overwriting it, keeping a
-- retrained model honest about exactly which signals it consumed (PRD §Data Model).

create table email_features (
    -- The email these features describe. FK to the immutable canonical record.
    email_id        uuid        not null references emails (id),

    -- The extractor contract version (com.antispam.features.EmailFeatureExtractor
    -- FEATURE_VERSION) that produced `features`. Part of the key so versions coexist.
    feature_version int         not null,

    -- The extracted FeatureSet (header/link/text/timing/auth groups + embedding
    -- hook) as JSONB. JSONB rather than wide typed columns because the feature set
    -- evolves heavily across versions and epics (embeddings land in 04.03); a new
    -- signal is a serialization change, not a schema migration.
    features        jsonb       not null,

    extracted_at    timestamptz not null default now(),

    primary key (email_id, feature_version)
);

-- Features are looked up by the email they describe (the GET endpoint and, later,
-- the classifier feeding the model the current feature_version).
create index email_features_email_id_idx on email_features (email_id);
