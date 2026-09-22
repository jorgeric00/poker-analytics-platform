package com.pokeranalytics.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * File locations for the two embedded databases (docs/04-architecture.md, ADR-003). Defaults to
 * a relative {@code ./data/} directory for local dev; Phase 9 packaging is expected to override
 * both via the environment variables named in {@code application.yml}, once the Tauri sidecar
 * points these at the OS-appropriate per-user app-data directory.
 */
@ConfigurationProperties(prefix = "app.storage")
public record StorageProperties(String duckdbPath, String sqlitePath) {
}
