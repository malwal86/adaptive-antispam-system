package com.antispam.decision.embedding;

/**
 * Bridges Java {@code float[]} embeddings to pgvector's text literal, since the
 * {@code vector} type has no JDBC binding. A vector is sent to the database as the
 * {@code [v1,v2,...]} literal cast with {@code ?::vector} and read back by parsing
 * that same literal.
 *
 * <p>The format is locale-independent — {@link Float#toString} never emits a
 * decimal comma — so a vector round-trips byte-for-byte regardless of the server
 * locale. Shared by every repository that stores a {@code vector} column
 * ({@code email_embeddings} in story 04.03, {@code campaign_clusters} centroids in
 * story 06.03) so the encoding is defined once.
 */
public final class PgVector {

    private PgVector() {
    }

    /** Formats an embedding as pgvector's {@code [v1,v2,...]} text literal. */
    public static String toLiteral(float[] vector) {
        StringBuilder sb = new StringBuilder(vector.length * 8 + 2);
        sb.append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(Float.toString(vector[i]));
        }
        return sb.append(']').toString();
    }

    /** Parses pgvector's {@code [v1,v2,...]} text literal back into a float array. */
    public static float[] parse(String literal) {
        String body = literal.substring(1, literal.length() - 1); // strip [ ]
        if (body.isEmpty()) {
            return new float[0];
        }
        String[] parts = body.split(",");
        float[] vector = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            vector[i] = Float.parseFloat(parts[i]);
        }
        return vector;
    }
}
