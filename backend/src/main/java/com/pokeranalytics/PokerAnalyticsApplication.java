package com.pokeranalytics;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Flyway auto-configuration is excluded because it assumes a single primary {@link
 * javax.sql.DataSource}; this application has two peer databases (DuckDB + SQLite, see {@code
 * com.pokeranalytics.storage.DataSourceConfig}), each migrated explicitly by {@code
 * com.pokeranalytics.storage.SchemaMigrationRunner} instead.
 *
 * <p>{@code @EnableScheduling}: added for {@code LiveSessionService}'s idle-timeout sweep
 * (docs/decisions/ADR-010-live-session-detection.md) — the first scheduled task in this app.
 */
@SpringBootApplication(exclude = FlywayAutoConfiguration.class)
@EnableScheduling
public class PokerAnalyticsApplication {

    public static void main(String[] args) {
        SpringApplication.run(PokerAnalyticsApplication.class, args);
    }
}
