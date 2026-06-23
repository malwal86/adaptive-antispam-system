-- Story 06.03: the offline, semantic tier of campaign detection. The runtime tier (06.01/06.02)
-- catches templated blasts cheaply with Redis windows + SimHash; this catches the *reworded*
-- variants that defeat surface near-dup, by clustering email embeddings (V13 email_embeddings,
-- pgvector) into coordinated-campaign groups offline (PRD §Subsystem 4 offline layer, §Data Model).
--
-- Two tables: a cluster carries its centroid; a membership row ties one email to one cluster.
-- The clustering job is offline and replaceable — it deletes a model_version's clusters and
-- rewrites them deterministically from the current embeddings — so it never touches live decision
-- state (classifications/reputation) and is safe to re-run. Stable membership is what lets Epic 11
-- group eval splits by campaign so reworded near-duplicates don't leak across train/test, and what
-- Epic 08 references for arena variant lineage.

create table campaign_clusters (
    -- Deterministic id derived from the cluster's member set (UUIDv3 over the sorted member ids),
    -- so the same partition of emails yields the same cluster id across re-runs — reproducibility
    -- the grouped eval split (Epic 11) relies on.
    id                      uuid        primary key,

    -- The embedder whose vectors were clustered (OnnxEmbeddingModel.EMBEDDING_VERSION). Clusters
    -- are only comparable within one embedding space, so every query is scoped by version and a
    -- re-embedding under a new version clusters independently rather than mixing spaces.
    model_version           text        not null,

    -- The cluster centroid: the L2-normalized mean of its members' embeddings. Named
    -- `centroid_embedding_id` per the PRD data model; it stores the centroid vector inline (a
    -- pgvector value, not a foreign key) so "nearest campaign to this email" is a cosine query
    -- straight against this column. Width matches email_embeddings.embedding (vector(128)).
    centroid_embedding_id   vector(128) not null,

    -- Member count, denormalized for cheap reads (the size of a campaign is the headline signal).
    size                    integer     not null,

    created_at              timestamptz not null default now(),

    constraint campaign_clusters_size_positive check (size >= 1)
);

-- ANN index over centroids for "which campaign is this email nearest to" (arena lineage, Epic 08),
-- mirroring the email_embeddings HNSW index. Cosine ops because centroids are unit-length.
create index campaign_clusters_centroid_idx
    on campaign_clusters using hnsw (centroid_embedding_id vector_cosine_ops);

create table campaign_cluster_members (
    -- The cluster this email belongs to. Cascade so re-running the job (delete-then-rewrite the
    -- model_version's clusters) clears memberships with their parent in one step.
    cluster_id      uuid                not null references campaign_clusters (id) on delete cascade,

    -- The clustered email. FK to the immutable canonical record.
    email_id        uuid                not null references emails (id),

    -- The embedding version this membership was computed under. Carried on the row (not only via the
    -- cluster) so "which cluster is this email in, at this version" is a single-table lookup, and so
    -- the uniqueness rule below can be enforced directly.
    model_version   text                not null,

    -- Cosine similarity of the email's embedding to its cluster centroid, in [-1, 1]; how central
    -- the member is to the campaign.
    similarity      double precision    not null,

    -- An email belongs to exactly one cluster per embedding version — the partition is disjoint,
    -- which is what makes a membership a usable group label for the eval split.
    primary key (cluster_id, email_id),
    constraint campaign_cluster_members_one_per_version unique (email_id, model_version)
);

-- Membership is queried per email (the API's GET, and the eval-split group lookup), so index the
-- email side; (cluster_id, ...) is already covered by the primary key for member-listing.
create index campaign_cluster_members_email_idx
    on campaign_cluster_members (email_id);
