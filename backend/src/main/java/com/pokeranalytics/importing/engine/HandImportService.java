package com.pokeranalytics.importing.engine;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.pokeranalytics.importing.model.HandActionType;
import com.pokeranalytics.importing.model.HandFormat;
import com.pokeranalytics.importing.model.ParsedHand;
import com.pokeranalytics.importing.model.Seat;
import com.pokeranalytics.importing.model.SummarySeat;

/**
 * Persists {@link ParsedHand}s into the DuckDB schema (docs/09-data-model.md) and logs the
 * import into SQLite's {@code import_history} (docs/08-storage-strategy.md's Update Strategy).
 *
 * <p><b>PokerStars cash only, for now</b> — the same tiered, one-room-at-a-time rollout already
 * used for the parser itself (docs/11-development-tracking.md). Every other room's actions still
 * carry {@code null} {@code player}/{@code amount} (see {@link com.pokeranalytics.importing.model.HandAction}),
 * which the {@code actions} table's {@code NOT NULL} columns would reject outright — rather than
 * let that surface as an opaque constraint-violation exception, hands from an unrecognized room
 * are rejected up front with a clear message. Tournament hands are recognized but skipped (see
 * {@link ImportResult#handsSkipped()}): {@code ParsedHand} doesn't capture a tournament id today,
 * and {@code hands.currency} is never set on the tournament header branch (chip-denominated, not
 * currency) — both are follow-ups, not silent data loss (skipped hands are counted, not dropped
 * unnoticed).
 */
@Service
public class HandImportService {

    private static final Logger log = LoggerFactory.getLogger(HandImportService.class);

    private final JdbcTemplate duckDb;
    private final JdbcTemplate sqlite;
    private final TransactionTemplate transactionTemplate;

    public HandImportService(@Qualifier("duckdb") JdbcTemplate duckDb, @Qualifier("sqlite") JdbcTemplate sqlite,
            @Qualifier("duckdb") PlatformTransactionManager duckDbTransactionManager) {
        this.duckDb = duckDb;
        this.sqlite = sqlite;
        // Programmatic, not @Transactional: this class calls importOneHand on itself in a loop,
        // and @Transactional only takes effect through Spring's proxy — a same-class call bypasses
        // it silently. TransactionTemplate sidesteps that self-invocation pitfall entirely.
        this.transactionTemplate = new TransactionTemplate(duckDbTransactionManager);
    }

    public ImportResult importHands(List<ParsedHand> hands, String room, String filePath) {
        if (!"PokerStars".equals(room)) {
            throw new IllegalArgumentException(
                    "Import Engine only supports PokerStars so far (got '" + room + "') — see class doc");
        }

        PlayerResolver players = new PlayerResolver(duckDb);
        int imported = 0;
        int skipped = 0;
        int failed = 0;
        int unparsedLines = 0;

        for (ParsedHand hand : hands) {
            unparsedLines += hand.unparsedLines.size();
            if (hand.format != HandFormat.CASH) {
                skipped++;
                continue;
            }
            try {
                importOneHand(hand, players);
                imported++;
            } catch (RuntimeException e) {
                log.warn("Failed to import hand #{}: {}", hand.handId, e.getMessage(), e);
                failed++;
            }
        }

        ImportResult result = new ImportResult(imported, skipped, failed, unparsedLines);
        recordImportHistory(filePath, room, result);
        return result;
    }

    private void recordImportHistory(String filePath, String room, ImportResult result) {
        sqlite.update("""
                INSERT INTO import_history (file_path, room, hand_count, status, unparsed_line_count)
                VALUES (?, ?, ?, ?, ?)
                """,
                filePath, room, result.handsImported(), result.status(), result.unparsedLineCount());
    }

    private void importOneHand(ParsedHand hand, PlayerResolver players) {
        String roomEnum = "POKERSTARS";
        // Delete and insert are deliberately two separate transactions, not one: DuckDB has a known
        // bug/limitation deleting a parent row in the same transaction as the child-row deletes that
        // clear its foreign-key references (github.com/duckdb/duckdb issues #4309/#25388 — "Violates
        // foreign key constraint because key is still referenced" even though the children were just
        // deleted). Splitting them means the delete is durably committed before the insert starts.
        transactionTemplate.executeWithoutResult(status -> deleteExistingHand(roomEnum, hand.handId));
        transactionTemplate.executeWithoutResult(status -> insertHand(hand, roomEnum, players));
    }

    private void insertHand(ParsedHand hand, String roomEnum, PlayerResolver players) {
        long handId = nextHandId();
        String gameFamily = GameClassifier.classifyFamily(hand.gameType);
        String betLimit = GameClassifier.classifyLimit(hand.gameType);
        Map<Integer, String> positions = PositionAssigner.assign(hand.seats, hand.buttonSeat);

        duckDb.update("""
                INSERT INTO hands (hand_id, room, external_hand_id, format, game_family, bet_limit,
                    game_type_raw, currency, small_blind, big_blind, ante, table_name, max_seats,
                    button_seat, tournament_id, tournament_level, hand_timestamp_raw, hand_timestamp,
                    run_it_twice, total_pot, rake, main_pot)
                VALUES (?, CAST(? AS room_t), ?, CAST(? AS hand_format_t), CAST(? AS game_family_t),
                    CAST(? AS bet_limit_t), ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, ?, NULL, ?, ?, ?, ?)
                """,
                handId, roomEnum, hand.handId, hand.format.name(), gameFamily, betLimit,
                hand.gameType, hand.currency, hand.smallBlind, hand.bigBlind, hand.ante,
                hand.tableName, hand.maxSeats, hand.buttonSeat, hand.timestampRaw,
                hand.runItTwice, hand.totalPot, hand.rake, hand.mainPot);

        // player name -> id, for this hand only — actions/results below look players up by name.
        Map<String, Long> playerIdByName = new java.util.HashMap<>();
        for (Seat seat : hand.seats) {
            long playerId = seat.playerName().equals(hand.heroName)
                    ? players.resolveHero()
                    : players.resolveOpponent(seat.playerName());
            playerIdByName.put(seat.playerName(), playerId);

            BigDecimal effectiveBb = seat.stack().divide(hand.bigBlind, 2, RoundingMode.HALF_UP);
            // hole_cards: only Hero's own hand is known here (opponents' cards are only knowable at
            // showdown — results.cards_shown, not yet extracted, see docs/09-data-model.md's note).
            String holeCards = seat.playerName().equals(hand.heroName) && hand.heroHoleCards != null
                    ? String.join(" ", hand.heroHoleCards)
                    : null;
            duckDb.update("""
                    INSERT INTO seats (hand_id, seat_number, player_id, starting_stack, stack_currency,
                        bounty, position, effective_bb, hole_cards)
                    VALUES (?, ?, ?, ?, ?, ?, CAST(? AS position_t), ?, ?)
                    """,
                    handId, seat.seatNumber(), playerId, seat.stack(), seat.currency(),
                    seat.bounty(), positions.get(seat.seatNumber()), effectiveBb, holeCards);
        }

        for (int i = 0; i < hand.actions.size(); i++) {
            var action = hand.actions.get(i);
            Long playerId = playerIdByName.get(action.player());
            if (playerId == null) {
                throw new IllegalStateException("Hand #" + hand.handId + ": action player '"
                        + action.player() + "' is not among this hand's seats " + playerIdByName.keySet());
            }
            duckDb.update("""
                    INSERT INTO actions (action_id, hand_id, player_id, street, action_type, amount,
                        sequence_no, raw_line)
                    VALUES (?, ?, ?, CAST(? AS street_t), CAST(? AS action_type_t), ?, ?, ?)
                    """,
                    nextActionId(), handId, playerId, action.street().name(), action.type().name(),
                    action.amount(), i, action.rawLine());
        }

        for (int runIndex = 0; runIndex < hand.boards.size(); runIndex++) {
            List<String> board = hand.boards.get(runIndex);
            insertBoardStreet(handId, runIndex, "FLOP", board, 0, 3);
            insertBoardStreet(handId, runIndex, "TURN", board, 3, 4);
            insertBoardStreet(handId, runIndex, "RIVER", board, 4, 5);
        }

        for (Seat seat : hand.seats) {
            long playerId = playerIdByName.get(seat.playerName());
            String outcomeRaw = hand.summarySeats.stream()
                    .filter(s -> s.playerName().equals(seat.playerName()))
                    .map(SummarySeat::outcome)
                    .findFirst().orElse(null);
            boolean wentToShowdown = outcomeRaw != null
                    && (outcomeRaw.contains("showed") || outcomeRaw.contains("mucked"));
            BigDecimal amountWon = hand.actions.stream()
                    .filter(a -> seat.playerName().equals(a.player()) && isCollectAction(a.type()))
                    .map(a -> a.amount() == null ? BigDecimal.ZERO : a.amount())
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            duckDb.update("""
                    INSERT INTO results (hand_id, player_id, went_to_showdown, cards_shown, amount_won,
                        outcome_raw, hero_rake_share)
                    VALUES (?, ?, ?, NULL, ?, ?, NULL)
                    """,
                    handId, playerId, wentToShowdown, amountWon, outcomeRaw);
        }

        for (int i = 0; i < hand.sidePots.size(); i++) {
            duckDb.update("INSERT INTO hand_side_pots (hand_id, side_pot_index, amount) VALUES (?, ?, ?)",
                    handId, i + 1, hand.sidePots.get(i));
        }
    }

    private void insertBoardStreet(long handId, int runIndex, String street, List<String> board,
            int fromIndex, int toIndex) {
        if (board.size() < toIndex) {
            return; // hand didn't reach this street
        }
        String cards = String.join(" ", board.subList(fromIndex, toIndex));
        duckDb.update("INSERT INTO boards (hand_id, run_index, street, cards) VALUES (?, ?, CAST(? AS street_t), ?)",
                handId, runIndex, street, cards);
    }

    private static boolean isCollectAction(HandActionType type) {
        return type == HandActionType.COLLECTED
                || type == HandActionType.COLLECTED_MAIN_POT
                || type == HandActionType.COLLECTED_SIDE_POT;
    }

    /** Reprocessing (docs/08-storage-strategy.md's Update Strategy): delete-then-reinsert by natural key. */
    private void deleteExistingHand(String roomEnum, String externalHandId) {
        List<Long> existing = duckDb.queryForList(
                "SELECT hand_id FROM hands WHERE room = CAST(? AS room_t) AND external_hand_id = ?",
                Long.class, roomEnum, externalHandId);
        if (existing.isEmpty()) {
            return;
        }
        long handId = existing.get(0);
        // No ON DELETE CASCADE in DuckDB (docs/09-data-model.md) — children first, in dependency order.
        duckDb.update("DELETE FROM hand_side_pots WHERE hand_id = ?", handId);
        duckDb.update("DELETE FROM results WHERE hand_id = ?", handId);
        duckDb.update("DELETE FROM boards WHERE hand_id = ?", handId);
        duckDb.update("DELETE FROM actions WHERE hand_id = ?", handId);
        duckDb.update("DELETE FROM seats WHERE hand_id = ?", handId);
        duckDb.update("DELETE FROM session_hands WHERE hand_id = ?", handId);
        duckDb.update("DELETE FROM hands WHERE hand_id = ?", handId);
    }

    private long nextHandId() {
        return duckDb.queryForObject("SELECT nextval('seq_hand_id')", Long.class);
    }

    private long nextActionId() {
        return duckDb.queryForObject("SELECT nextval('seq_action_id')", Long.class);
    }
}
