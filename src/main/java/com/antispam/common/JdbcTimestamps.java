package com.antispam.common;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;

/**
 * Shared {@link ResultSet} timestamp mapping for JDBC row mappers. Postgres {@code timestamptz}
 * columns are read as {@link OffsetDateTime}; the domain models hold {@link Instant}. This collapses
 * the repeated "read, then null-safe convert" idiom into one call.
 */
public final class JdbcTimestamps {

    private JdbcTimestamps() {}

    /**
     * Reads a {@code timestamptz} column as an {@link Instant}, preserving SQL {@code NULL} as
     * {@code null}. Use only for nullable columns; columns declared {@code NOT NULL} are read
     * directly so a null would surface as a bug rather than be silently tolerated.
     */
    public static Instant instantOrNull(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
