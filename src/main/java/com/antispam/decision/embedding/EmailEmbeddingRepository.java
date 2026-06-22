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
 * in text form: it sends the embedding as pgvector's {@code [v1,v2,...]} literal
 * cast with {@code ?::vector}, and reads it back by parsing that same literal. The
 * format is locale-independent ({@link Float#toString} never emits a decimal
 * comma), so a row round-trips byte-for-byte regardless of the server locale.
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

    /** Inserts or refreshes the embedding row for {@code (emailId, modelVersion)}. */
    public void save(UUID emailId, String modelVersion, float[] embedding) {
        String literal = toVectorLiteral(embedding);
        jdbc.update(UPSERT_SQL, emailId, modelVersion, literal);
    }

    /** Returns the stored embedding for an email at a specific version, if present. */
    public Optional<float[]> find(UUID emailId, String modelVersion) {
        try {
            float[] embedding = jdbc.queryForObject(
                    SELECT_SQL, (rs, rowNum) -> parseVector(rs.getString("embedding")),
                    emailId, modelVersion);
            return Optional.ofNullable(embedding);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * Returns the {@code limit} embeddings most similar to {@code query} at the
     * given model version, nearest first, each with its cosine similarity in
     * {@code [-1, 1]}. The query vector is matched against rows of the same version
     * only, so similarities are always between comparable embeddings.
     */
    public List<EmbeddingNeighbor> nearestNeighbors(float[] query, String modelVersion, int limit) {
        String literal = toVectorLiteral(query);
        return jdbc.query(NEAREST_SQL, NEIGHBOR_MAPPER, literal, modelVersion, literal, limit);
    }

    private static final RowMapper<EmbeddingNeighbor> NEIGHBOR_MAPPER = (rs, rowNum) ->
            new EmbeddingNeighbor(rs.getObject("email_id", UUID.class), rs.getDouble("similarity"));

    /** Formats an embedding as pgvector's {@code [v1,v2,...]} text literal. */
    private static String toVectorLiteral(float[] embedding) {
        StringBuilder sb = new StringBuilder(embedding.length * 8 + 2);
        sb.append('[');
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(Float.toString(embedding[i]));
        }
        return sb.append(']').toString();
    }

    /** Parses pgvector's {@code [v1,v2,...]} text literal back into a float array. */
    private static float[] parseVector(String literal) {
        String body = literal.substring(1, literal.length() - 1); // strip [ ]
        if (body.isEmpty()) {
            return new float[0];
        }
        String[] parts = body.split(",");
        float[] embedding = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            embedding[i] = Float.parseFloat(parts[i]);
        }
        return embedding;
    }
}
