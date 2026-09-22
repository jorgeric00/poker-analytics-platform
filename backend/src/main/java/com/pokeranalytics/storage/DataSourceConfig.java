package com.pokeranalytics.storage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * The two embedded databases (docs/04-architecture.md, decisions/ADR-003-local-analytics-storage.md),
 * as plain, unpooled {@link DataSource} beans.
 *
 * <p>Deliberately not a connection pool (e.g. HikariCP): both databases are single embedded
 * files read by one local desktop process. DuckDB's own concurrency model (docs/08-storage-strategy.md's
 * Guiding Constraints — one writer, concurrent MVCC readers, all coordinated in-process per file
 * path) already gives every connection to the same path a consistent view without needing a pool
 * to manage contention that doesn't exist at this scale.
 */
@Configuration
@EnableConfigurationProperties(StorageProperties.class)
public class DataSourceConfig {

    @Bean
    @Qualifier("duckdb")
    public DataSource duckDbDataSource(StorageProperties properties) {
        Path path = ensureParentDirectory(properties.duckdbPath());
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.duckdb.DuckDBDriver");
        dataSource.setUrl("jdbc:duckdb:" + path);
        return dataSource;
    }

    @Bean
    @Qualifier("sqlite")
    public DataSource sqliteDataSource(StorageProperties properties) {
        Path path = ensureParentDirectory(properties.sqlitePath());
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.sqlite.JDBC");
        dataSource.setUrl("jdbc:sqlite:" + path);
        return dataSource;
    }

    @Bean
    @Qualifier("duckdb")
    public JdbcTemplate duckDbJdbcTemplate(@Qualifier("duckdb") DataSource duckDbDataSource) {
        return new JdbcTemplate(duckDbDataSource);
    }

    @Bean
    @Qualifier("sqlite")
    public JdbcTemplate sqliteJdbcTemplate(@Qualifier("sqlite") DataSource sqliteDataSource) {
        return new JdbcTemplate(sqliteDataSource);
    }

    private static Path ensureParentDirectory(String configuredPath) {
        Path path = Path.of(configuredPath).toAbsolutePath().normalize();
        Path parent = path.getParent();
        if (parent != null) {
            try {
                Files.createDirectories(parent);
            } catch (IOException e) {
                throw new UncheckedIOException("Could not create database directory: " + parent, e);
            }
        }
        return path;
    }
}
