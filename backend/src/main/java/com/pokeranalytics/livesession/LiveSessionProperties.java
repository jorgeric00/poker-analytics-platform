package com.pokeranalytics.livesession;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * docs/decisions/ADR-010-live-session-detection.md: {@code watchedDirectory} unset/blank disables
 * the whole feature (no bean does anything) — there is no in-app Settings UI to configure this yet
 * ({@code 05-product-map.md} has no defined Settings module), so this is {@code application.yml}
 * config only, same pattern as {@code com.pokeranalytics.storage.StorageProperties}.
 *
 * @param watchedDirectory   folder to watch for {@code *.txt} PokerStars cash hand-history files;
 *                           blank/unset disables live detection entirely
 * @param idleTimeoutMinutes minutes of no new hand before an open session auto-closes
 */
@ConfigurationProperties(prefix = "app.live-session")
public record LiveSessionProperties(String watchedDirectory, int idleTimeoutMinutes) {

    public boolean enabled() {
        return watchedDirectory != null && !watchedDirectory.isBlank();
    }
}
