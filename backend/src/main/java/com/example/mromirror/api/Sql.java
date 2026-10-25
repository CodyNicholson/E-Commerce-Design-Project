package com.example.mromirror.api;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.SqlArrayValue;
import org.springframework.stereotype.Component;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Thin JDBC helpers. Inside a transaction (TransactionTemplate) every call joins
 * it; outside one each call runs on its own pooled connection.
 */
@Component
public class Sql {
    private final JdbcTemplate jdbc;

    public Sql(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public <T> List<T> list(String sql, RowMapper<T> mapper, Object... args) {
        return jdbc.query(sql, mapper, args);
    }

    /** The first row; EmptyResultDataAccessException (a 404) when there is none. */
    public <T> T one(String sql, RowMapper<T> mapper, Object... args) {
        List<T> rows = list(sql, mapper, args);
        if (rows.isEmpty()) {
            throw new EmptyResultDataAccessException(1);
        }
        return rows.get(0);
    }

    public <T> Optional<T> maybe(String sql, RowMapper<T> mapper, Object... args) {
        List<T> rows = list(sql, mapper, args);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public int exec(String sql, Object... args) {
        return jdbc.update(sql, args);
    }

    /** Binds a list as a Postgres text[] for {@code = ANY(?)}. */
    public static SqlArrayValue textArray(Collection<String> values) {
        return new SqlArrayValue("text", values.toArray());
    }

    public static LocalDate date(ResultSet rs, int col) throws SQLException {
        return rs.getObject(col, LocalDate.class);
    }

    public static Instant instant(ResultSet rs, int col) throws SQLException {
        OffsetDateTime t = rs.getObject(col, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }

    public static Double nullableDouble(ResultSet rs, int col) throws SQLException {
        return rs.getObject(col, Double.class);
    }

    public static List<String> strings(ResultSet rs, int col) throws SQLException {
        Array a = rs.getArray(col);
        return a == null ? List.of() : List.of((String[]) a.getArray());
    }
}
