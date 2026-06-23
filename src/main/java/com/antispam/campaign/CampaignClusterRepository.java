package com.antispam.campaign;

import com.antispam.decision.embedding.PgVector;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Persists and queries the {@code campaign_clusters} / {@code campaign_cluster_members}
 * tables (story 06.03). Centroids are {@code vector(128)} columns, so this bridges to
 * pgvector's text literal via {@link PgVector}, exactly as {@code EmailEmbeddingRepository}
 * does for embeddings.
 *
 * <p><b>{@link #replaceAll} is a full, atomic rewrite of one model version's clusters.</b>
 * The offline job recomputes the whole partition deterministically from the current
 * embeddings, so persisting it as delete-then-insert (rather than a diff) keeps the stored
 * clustering an exact image of the latest run — no orphaned members, no stale clusters — and
 * makes a re-run idempotent. Members cascade-delete with their parent cluster. It runs in the
 * service's transaction, so a failure mid-write rolls back to the prior clustering rather than
 * leaving a half-replaced one.
 */
@Repository
public class CampaignClusterRepository {

    private static final String DELETE_CLUSTERS_SQL =
            "delete from campaign_clusters where model_version = ?";

    private static final String INSERT_CLUSTER_SQL = """
            insert into campaign_clusters (id, model_version, centroid_embedding_id, size)
            values (?, ?, ?::vector, ?)
            """;

    private static final String INSERT_MEMBER_SQL = """
            insert into campaign_cluster_members (cluster_id, email_id, model_version, similarity)
            values (?, ?, ?, ?)
            """;

    private static final String FIND_MEMBERSHIP_SQL = """
            select m.email_id, m.cluster_id, m.model_version, m.similarity, c.size
            from campaign_cluster_members m
            join campaign_clusters c on c.id = m.cluster_id
            where m.email_id = ? and m.model_version = ?
            """;

    private static final String COUNT_CLUSTERS_SQL =
            "select count(*) from campaign_clusters where model_version = ?";

    private final JdbcTemplate jdbc;

    @Autowired
    public CampaignClusterRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Replaces all clusters (and their memberships) for {@code modelVersion} with
     * {@code clusters}. Cluster ids are derived deterministically from each cluster's
     * member set ({@link EmailCluster#deterministicId()}), so the same partition writes
     * the same rows on every run.
     */
    public void replaceAll(String modelVersion, List<EmailCluster> clusters) {
        jdbc.update(DELETE_CLUSTERS_SQL, modelVersion); // members cascade
        for (EmailCluster cluster : clusters) {
            UUID clusterId = cluster.deterministicId();
            jdbc.update(INSERT_CLUSTER_SQL,
                    clusterId, modelVersion, PgVector.toLiteral(cluster.centroid()), cluster.size());
            jdbc.batchUpdate(INSERT_MEMBER_SQL, cluster.members(), cluster.members().size(),
                    (ps, member) -> {
                        ps.setObject(1, clusterId);
                        ps.setObject(2, member.emailId());
                        ps.setString(3, modelVersion);
                        ps.setDouble(4, member.cosineSimilarity());
                    });
        }
    }

    /** The cluster an email belongs to at the given version, or empty if it is unclustered. */
    public Optional<ClusterMembership> findMembership(UUID emailId, String modelVersion) {
        try {
            return Optional.of(jdbc.queryForObject(
                    FIND_MEMBERSHIP_SQL, MEMBERSHIP_MAPPER, emailId, modelVersion));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /** How many clusters are currently stored for the version. */
    public int countClusters(String modelVersion) {
        Integer count = jdbc.queryForObject(COUNT_CLUSTERS_SQL, Integer.class, modelVersion);
        return count == null ? 0 : count;
    }

    private static final RowMapper<ClusterMembership> MEMBERSHIP_MAPPER = (rs, rowNum) ->
            new ClusterMembership(
                    rs.getObject("email_id", UUID.class),
                    rs.getObject("cluster_id", UUID.class),
                    rs.getString("model_version"),
                    rs.getDouble("similarity"),
                    rs.getInt("size"));
}
