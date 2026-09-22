package com.pokeranalytics.importing.engine;

/**
 * Outcome of one file import (docs/09-data-model.md's {@code import_history} table), room-agnostic.
 *
 * @param handsImported     hands actually written (or re-written, on reprocessing) to DuckDB
 * @param handsSkipped      hands recognized by the parser but not yet importable — currently only
 *                          non-{@code CASH} hands, since tournament persistence (tournament_id
 *                          capture, {@code tournament_results} linkage) isn't wired up yet
 * @param handsFailed       hands that threw while being persisted (e.g. an unsupported table size
 *                          in {@link PositionAssigner}) — caught per hand so one bad hand doesn't
 *                          abort the rest of a large file; each failure is still a real problem
 *                          worth surfacing, just not a whole-import one
 * @param unparsedLineCount total {@code ParsedHand.unparsedLines} across every hand in the file —
 *                          mirrors the parser's own feasibility signal into {@code import_history}
 */
public record ImportResult(int handsImported, int handsSkipped, int handsFailed, int unparsedLineCount) {

    public String status() {
        if (handsFailed > 0 && handsImported == 0) {
            return "failed";
        }
        return (handsSkipped > 0 || handsFailed > 0) ? "partial" : "success";
    }
}
