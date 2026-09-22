package com.pokeranalytics.storage;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Runs each database's own Flyway migrations on startup (docs/09-data-model.md).
 *
 * <p>Managed manually rather than via Spring Boot's Flyway auto-configuration: that
 * auto-configuration assumes one primary {@link DataSource}, but this application has two peer
 * databases with different migration locations and dialects (see {@link DataSourceConfig}) — see
 * {@code PokerAnalyticsApplication}'s {@code exclude} for where the auto-configuration is turned
 * off in favor of this.
 */
@Component
@Order(Integer.MIN_VALUE) // migrations must run before anything else touches either database
public class SchemaMigrationRunner implements ApplicationRunner {

    private final DataSource duckDbDataSource;
    private final DataSource sqliteDataSource;

    public SchemaMigrationRunner(@Qualifier("duckdb") DataSource duckDbDataSource,
            @Qualifier("sqlite") DataSource sqliteDataSource) {
        this.duckDbDataSource = duckDbDataSource;
        this.sqliteDataSource = sqliteDataSource;
    }

    @Override
    public void run(ApplicationArguments args) {
        Flyway.configure()
                .dataSource(duckDbDataSource)
                .locations("classpath:db/migration/duckdb")
                .load()
                .migrate();

        Flyway.configure()
                .dataSource(sqliteDataSource)
                .locations("classpath:db/migration/sqlite")
                .load()
                .migrate();
    }
}
