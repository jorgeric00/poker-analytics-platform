package com.pokeranalytics.api.dto;

import java.util.List;

/**
 * docs/10-api-design.md's {@code POST /api/v1/imports} response — <b>implemented synchronously
 * for now</b>, a deliberate, documented deviation from that document's async (202 + poll) design.
 * That design is sized for bulk historical imports running well past interactive latency; every
 * import today is a single small PokerStars file well within it. Revisit (build the async job
 * table/polling endpoint) once bulk multi-file/multi-year imports are actually common — tracked in
 * docs/11-development-tracking.md, not silently diverged from without a note.
 */
public record ImportResponse(String status, List<FileImportResult> results) {
}
