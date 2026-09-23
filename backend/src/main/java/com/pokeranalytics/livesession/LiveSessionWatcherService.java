package com.pokeranalytics.livesession;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.pokeranalytics.importing.engine.HandImportService;
import com.pokeranalytics.importing.model.ParsedHand;
import com.pokeranalytics.importing.parser.pokerstars.PokerStarsParser;

import jakarta.annotation.PreDestroy;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;

/**
 * Watches {@link LiveSessionProperties#watchedDirectory()} for PokerStars cash hand-history files
 * being written to, per docs/decisions/ADR-010-live-session-detection.md. A no-op bean when the
 * feature isn't configured (blank/unset {@code watchedDirectory}) — existing tests that don't set
 * it are entirely unaffected.
 *
 * <p>Whole-file re-parse on every change (point 1) and an in-memory per-file baseline seeded at
 * startup (point 2) — see the ADR for why. Runs its watch loop on a single dedicated daemon thread
 * so file-change events are always processed strictly in order, which is what makes the
 * seed-then-diff baseline logic race-free without extra locking.
 *
 * <p>Implements {@link ApplicationRunner} (default order, so it runs after {@code
 * SchemaMigrationRunner}'s {@code Integer.MIN_VALUE}) rather than using {@code @PostConstruct} —
 * bean construction happens before the application context finishes starting, which is before
 * Flyway migrations have run; an eager DB read at that point fails against a not-yet-migrated
 * database.
 */
@Service
public class LiveSessionWatcherService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LiveSessionWatcherService.class);
    private static final String ROOM = "PokerStars";

    private final LiveSessionProperties properties;
    private final HandImportService handImportService;
    private final LiveSessionService liveSessionService;
    private final JdbcTemplate duckDb;

    private final Map<Path, Set<String>> baselineExternalIdsByFile = new ConcurrentHashMap<>();
    private volatile WatchService watchService;
    private volatile WatchKey watchKey;
    private volatile Thread watchThread;

    public LiveSessionWatcherService(LiveSessionProperties properties, HandImportService handImportService,
            LiveSessionService liveSessionService, @Qualifier("duckdb") JdbcTemplate duckDb) {
        this.properties = properties;
        this.handImportService = handImportService;
        this.liveSessionService = liveSessionService;
        this.duckDb = duckDb;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.enabled()) {
            log.info("Live session file watching disabled (app.live-session.watched-directory not set)");
            return;
        }
        Path dir = Path.of(properties.watchedDirectory());
        if (!Files.isDirectory(dir)) {
            log.warn("Live session watched directory does not exist, watching disabled: {}", dir);
            return;
        }
        try {
            for (Path file : matchingFiles(dir)) {
                processFile(file, true);
            }
            watchService = FileSystems.getDefault().newWatchService();
            watchKey = dir.register(watchService, ENTRY_CREATE, ENTRY_MODIFY);
        } catch (IOException e) {
            log.warn("Failed to start live session file watcher on {}", dir, e);
            return;
        }
        watchThread = new Thread(() -> watchLoop(dir), "live-session-watcher");
        watchThread.setDaemon(true);
        watchThread.start();
    }

    @PreDestroy
    void stop() {
        // Cancel the directory registration explicitly before closing the service — on Windows,
        // WatchService's ReadDirectoryChangesW-based implementation can otherwise hold the
        // watched directory's OS handle open a moment past close() returning, which raced a test
        // framework's temp-directory cleanup immediately after (DirectoryNotEmptyException).
        WatchKey key = watchKey;
        if (key != null) {
            key.cancel();
        }
        WatchService ws = watchService;
        if (ws != null) {
            try {
                ws.close();
            } catch (IOException e) {
                log.debug("Error closing live session watch service", e);
            }
        }
        Thread thread = watchThread;
        if (thread != null) {
            thread.interrupt();
            // Join with a bound rather than fire-and-forget: closing the WatchService above makes
            // a blocked take() throw immediately, but a thread already mid-processFile() (an
            // in-flight DB write) needs a moment to finish that one call. Without this, @PreDestroy
            // returns before it does and the thread can keep running against a DataSource/temp
            // directory the test framework (or a real shutdown) may tear down right after —
            // reproduced in CI as a "Cannot open file" race once the temp dir was already gone.
            try {
                thread.join(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void watchLoop(Path dir) {
        try {
            while (true) {
                WatchKey key = watchService.take();
                for (WatchEvent<?> event : key.pollEvents()) {
                    Object context = event.context();
                    if (context instanceof Path fileName && fileName.toString().endsWith(".txt")) {
                        processFile(dir.resolve(fileName), false);
                    }
                }
                if (!key.reset()) {
                    break;
                }
            }
        } catch (ClosedWatchServiceException | InterruptedException e) {
            log.info("Live session file watcher stopped");
        }
    }

    /** @param isBaselineSeed true only for the initial scan at startup — those hands are imported
     *                        (so they're queryable like any other imported hand) but deliberately
     *                        NOT reported to {@link LiveSessionService} as "new" (ADR-010 point 2) */
    private void processFile(Path file, boolean isBaselineSeed) {
        List<ParsedHand> parsedHands;
        try {
            parsedHands = PokerStarsParser.parseFile(file);
        } catch (IOException | RuntimeException e) {
            log.warn("Failed to parse live-watched file {}", file, e);
            return;
        }

        Set<String> currentExternalIds = new HashSet<>();
        for (ParsedHand hand : parsedHands) {
            currentExternalIds.add(hand.handId);
        }
        Set<String> baseline = baselineExternalIdsByFile.getOrDefault(file, Set.of());
        Set<String> newExternalIds = new HashSet<>(currentExternalIds);
        newExternalIds.removeAll(baseline);
        baselineExternalIdsByFile.put(file, currentExternalIds);

        if (newExternalIds.isEmpty()) {
            return; // nothing new since last time this file was touched — nothing to import
        }
        // Import ONLY the genuinely new hands, not the whole re-parsed file: HandImportService's
        // reprocessing (docs/08-storage-strategy.md's Update Strategy) deletes and reinserts a hand
        // under a brand-new hand_id every time it sees that external_hand_id again, even if nothing
        // about the hand changed — including deleting its session_hands row. Re-importing every hand
        // on every file change would silently orphan every previously-detected hand's session
        // membership the moment one more hand appeared in the same file. Filtering to the delta
        // avoids ever touching a hand this watcher has already imported.
        List<ParsedHand> newHands = parsedHands.stream()
                .filter(hand -> newExternalIds.contains(hand.handId))
                .toList();
        handImportService.importHands(newHands, ROOM, file.toString());

        if (!isBaselineSeed) {
            liveSessionService.onNewHandsDetected(resolveHandIds(newExternalIds));
        }
    }

    private List<Long> resolveHandIds(Set<String> externalIds) {
        String placeholders = String.join(",", externalIds.stream().map(id -> "?").toList());
        Object[] args = externalIds.toArray();
        return duckDb.queryForList(
                "SELECT hand_id FROM hands WHERE room = CAST('POKERSTARS' AS room_t) AND external_hand_id IN (" + placeholders + ")",
                Long.class, args);
    }

    private static List<Path> matchingFiles(Path dir) throws IOException {
        List<Path> files = new java.util.ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.txt")) {
            for (Path file : stream) {
                files.add(file);
            }
        }
        return files;
    }
}
