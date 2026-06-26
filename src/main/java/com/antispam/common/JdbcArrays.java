package com.antispam.common;

import java.sql.Array;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

/**
 * Shared SQL-array mapping for JDBC row mappers. Postgres {@code text[]} columns are read as a
 * {@link Array}; the domain models hold typed {@link List}s. This collapses the repeated
 * "null → empty, else map each element" idiom into one call.
 */
public final class JdbcArrays {

    private JdbcArrays() {}

    /**
     * Reads a {@code text[]} column as a {@link List}, mapping each element through {@code parse}
     * and preserving SQL {@code NULL} as an empty list (never {@code null}).
     */
    public static <E> List<E> mapElements(Array array, Function<String, E> parse)
            throws SQLException {
        if (array == null) {
            return List.of();
        }
        String[] names = (String[]) array.getArray();
        return Arrays.stream(names).map(parse).toList();
    }
}
