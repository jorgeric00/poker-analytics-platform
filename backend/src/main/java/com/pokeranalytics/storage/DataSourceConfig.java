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
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;

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

    /**
     * Named-parameter variant for queries that reference the same bind value (typically
     * {@code playerId}) more than once — e.g. the Analytics Engine's breakdown-dimension queries
     * (docs/06-query-catalog.md's QF-1), which join a per-hand "breakdown key" CTE that itself
     * filters by player into a stat query that also filters by player. Positional {@code ?}
     * placeholders would require passing the same value multiple times in the right order by hand;
     * {@code :playerId} lets the CTE and the outer query both bind it once.
     */
    @Bean
    @Qualifier("duckdb")
    public NamedParameterJdbcTemplate duckDbNamedParameterJdbcTemplate(@Qualifier("duckdb") DataSource duckDbDataSource) {
        return new NamedParameterJdbcTemplate(duckDbDataSource);
    }

    /**
     * Named (not just qualified) because {@code @Transactional}'s {@code transactionManager}
     * attribute takes a bean name, not a qualifier — used by the Import Engine so a hand's
     * delete-then-reinsert (docs/08-storage-strategy.md's Update Strategy) commits or rolls back
     * as one unit.
     */
    @Bean("duckDbTransactionManager")
    @Qualifier("duckdb")
    public PlatformTransactionManager duckDbTransactionManager(@Qualifier("duckdb") DataSource duckDbDataSource) {
        return new DataSourceTransactionManager(duckDbDataSource);
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
