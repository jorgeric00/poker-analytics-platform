package com.pokeranalytics.api.dto;

import java.util.List;

/**
 * docs/10-api-design.md's {@code POST /api/v1/imports} response — <b>implemented synchronously
 * for now</b>, a deliberate, documented deviation from that document's async (202 + poll) design.
 * That design is sized for bulk historical imports running well past interactive latency; every
 * import today is a handful of small single-room files well within it. Revisit (build the async
 * job table/polling endpoint) once bulk multi-file/multi-year imports are actually common —
 * tracked in docs/11-development-tracking.md, not silently diverged from without a note.
 *
 * <p>Cash games only, every room the Import Engine's own parsers cover (PokerStars, Winamax,
 * PartyPoker, 888, iPoker, Winning Poker Network) — each file's room is auto-detected from its
 * content ({@code com.pokeranalytics.importing.parser.RoomDetector}), not PokerStars-only as this
 * endpoint originally shipped.
 */
public record ImportResponse(String status, List<FileImportResult> results) {
}
