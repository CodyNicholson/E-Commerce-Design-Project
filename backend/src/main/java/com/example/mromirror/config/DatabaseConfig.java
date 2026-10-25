package com.example.mromirror.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import javax.sql.DataSource;
import java.net.URI;

/**
 * Builds the connection pool from DATABASE_URL (postgres://user:pass@host:port/db?sslmode=disable)
 * or, when that is unset, from DB_HOST, DB_PORT, DB_USER, DB_PASSWORD and DB_NAME.
 */
@Configuration
public class DatabaseConfig {

    @Bean
    public DataSource dataSource(Environment env) {
        HikariDataSource ds = new HikariDataSource();
        String url = env(env, "DATABASE_URL", "");
        if (!url.isEmpty()) {
            URI u = URI.create(url);
            ds.setJdbcUrl("jdbc:postgresql://" + u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "") + u.getPath()
                    + (u.getQuery() != null ? "?" + u.getQuery() : ""));
            String info = u.getUserInfo();
            if (info != null) {
                int colon = info.indexOf(':');
                ds.setUsername(colon < 0 ? info : info.substring(0, colon));
                if (colon >= 0) {
                    ds.setPassword(info.substring(colon + 1));
                }
            }
        } else {
            ds.setJdbcUrl("jdbc:postgresql://" + env(env, "DB_HOST", "localhost") + ":" + env(env, "DB_PORT", "5432")
                    + "/" + env(env, "DB_NAME", "mro") + "?sslmode=disable");
            ds.setUsername(env(env, "DB_USER", "postgres"));
            ds.setPassword(env(env, "DB_PASSWORD", "postgres"));
        }
        return ds;
    }

    /** An environment variable, or def when it is unset or empty. */
    private static String env(Environment env, String key, String def) {
        String v = env.getProperty(key);
        return v == null || v.isEmpty() ? def : v;
    }
}
