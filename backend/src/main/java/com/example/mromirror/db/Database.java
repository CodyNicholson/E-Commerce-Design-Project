package com.example.mromirror.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** Waits for Postgres, then loads the schema and seed data on first start. */
@Component
public class Database implements InitializingBean {
    private static final Logger log = LoggerFactory.getLogger(Database.class);

    private final JdbcTemplate jdbc;
    private final String schemaSql = resource("db/schema.sql");
    private final String seedSql = resource("db/seed.sql");

    public Database(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void afterPropertiesSet() throws InterruptedException {
        awaitConnection();
        ensureSeeded();
    }

    private void awaitConnection() throws InterruptedException {
        RuntimeException last = null;
        for (int i = 0; i < 30; i++) {
            try {
                jdbc.queryForObject("SELECT 1", Integer.class);
                return;
            } catch (RuntimeException e) {
                last = e;
                Thread.sleep(1000);
            }
        }
        throw new IllegalStateException("connect to postgres: " + last.getMessage(), last);
    }

    /** Creates and seeds the schema on first start. */
    public void ensureSeeded() {
        Boolean exists = jdbc.queryForObject("SELECT to_regclass('pim.products') IS NOT NULL", Boolean.class);
        if (Boolean.TRUE.equals(exists)) {
            return;
        }
        log.info("seeding database");
        reset();
    }

    /** Drops every bounded-context schema and reloads the seed data. */
    public void reset() {
        jdbc.execute("DROP SCHEMA IF EXISTS keepstock, procurement, oms, pricing, accounts, pim CASCADE");
        jdbc.execute(schemaSql);
        jdbc.execute(seedSql);
    }

    private static String resource(String path) {
        try {
            return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
