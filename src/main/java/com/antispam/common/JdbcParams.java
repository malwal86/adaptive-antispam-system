package com.antispam.common;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;

/**
 * Shared {@link PreparedStatement} parameter binding for JDBC writers. Collapses the repeated
 * "null → {@code setNull}, else {@code setDouble}" idiom — used wherever a nullable {@code double
 * precision} column is written — into one call, so each call site reads as a single bind.
 */
public final class JdbcParams {

    private JdbcParams() {}

    /**
     * Binds a nullable {@link Double} to a {@code double precision} column, mapping a {@code null}
     * value to SQL {@code NULL} ({@link Types#DOUBLE}) rather than letting unboxing throw.
     */
    public static void setNullableDouble(PreparedStatement ps, int index, Double value)
            throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.DOUBLE);
        } else {
            ps.setDouble(index, value);
        }
    }
}
