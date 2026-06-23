package com.antispam.decision.embedding;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Access to the {@code email_embeddings} pgvector table (story 04.03). A row is
 * keyed by {@code (email_id, model_version)}; the embedding is stored as a
 * {@code vector(128)} so cosine-similarity and nearest-neighbor queries run in the
 * database.
 *
 * <p>The {@code vector} type has no JDBC binding, so this repository bridges to it
 * in text form via {@link PgVector}: it sends the embedding as pgvector's
 * {@code [v1,v2,...]} literal cast with {@code ?::vector}, and reads it back by
 * parsing that same literal.
 *
 * <p>{@link #save} is an upsert on the key — re-embedding an email at the same
 * version refreshes the (deterministically identical) vector in place rather than
 * duplicating it, mirroring {@code EmailFeaturesRepository}.
 */
@Repository
public class EmailEmbeddingRepository {

    private static final String UPSERT_SQL = """
            insert into email_embeddings (email_id, model_version, embedding)
            values (?, ?, ?::vector)
            on conflict (email_id, model_version)
            do update set embedding = excluded.embedding, created_at = now()
            """;

    private static final String SELECT_SQL = """
            select embedding
            from email_embeddings
            where email_id = ? and model_version = ?
            """;

    // Cosine distance (<=>) ascending = most similar first. We return similarity as
    // 1 - distance, exact because the stored vectors are L2-normalized.
    private static final String NEAREST_SQL = """
            select email_id, 1 - (embedding <=> ?::vector) as similarity
            from email_embeddings
            where model_version = ?
            order by embedding <=> ?::vector
            limit ?
            """;

    private final JdbcTemplate jdbc;

    @Autowired
    public EmailEmbeddingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String SELECT_ALL_SQL = """
            select email_id, embedding
            from email_embeddings
            where model_version = ?
            order by email_id
            """;

    /** Inserts or refreshes the embedding row for {@code (emailId, modelVersion)}. */
    public void save(UUID emailId, String modelVersion, float[] embedding) {
        jdbc.update(UPSERT_SQL, emailId, modelVersion, PgVector.toLiteral(embedding));
    }

    /** Returns the stored embedding for an email at a specific version, if present. */
    public Optional<float[]> find(UUID emailId, String modelVersion) {
        try {
            float[] embedding = jdbc.queryForObject(
                    SELECT_SQL, (rs, rowNum) -> PgVector.parse(rs.getString("embedding")),
                    emailId, modelVersion);
            return Optional.ofNullable(embedding);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * Returns every stored embedding at the given model version, ordered by
     * {@code email_id} so the read is deterministic. This is the offline clusterer's
     * input (story 06.03): the whole embedding corpus for one version, loaded once,
     * grouped in memory — never a per-email fast-path read.
     */
    public List<EmbeddedEmail> findAll(String modelVersion) {
        return jdbc.query(SELECT_ALL_SQL, EMBEDDED_EMAIL_MAPPER, modelVersion);
    }

    private static final RowMapper<EmbeddedEmail> EMBEDDED_EMAIL_MAPPER =
            (rs, rowNum) -> new EmbeddedEmail(
                    rs.getObject("email_id", UUID.class), PgVector.parse(rs.getString("embedding")));

    /**
     * Returns the {@code limit} embeddings most similar to {@code query} at the
     * given model version, nearest first, each with its cosine similarity in
     * {@code [-1, 1]}. The query vector is matched against rows of the same version
     * only, so similarities are always between comparable embeddings.
     */
    public List<EmbeddingNeighbor> nearestNeighbors(float[] query, String modelVersion, int limit) {
        String literal = PgVector.toLiteral(query);
        return jdbc.query(NEAREST_SQL, NEIGHBOR_MAPPER, literal, modelVersion, literal, limit);
    }

    private static final RowMapper<EmbeddingNeighbor> NEIGHBOR_MAPPER = (rs, rowNum) ->
            new EmbeddingNeighbor(rs.getObject("email_id", UUID.class), rs.getDouble("similarity"));
}
