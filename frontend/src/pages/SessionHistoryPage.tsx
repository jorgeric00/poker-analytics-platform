import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { CalendarBlank, Cards, CaretLeft, CaretRight, Coins, FileText, Spade } from "@phosphor-icons/react";
import { ApiError, fetchSessionDetail, fetchSessions } from "../api/client";
import type { SessionDetailDto, SessionListItemDto } from "../api/types";
import { GlobalFilters } from "../components/GlobalFilters";
import { formatDurationMinutes, formatMoney, formatShortDateTime, roomLabel } from "./format";

// Matches Session History.dc.html (docs/12-ui-designs.md), read via the Claude Design MCP — a
// master-detail layout (session list + detail), same pattern as Players. Scoped to what's real:
// this project only has session rows for live-detected sessions (docs/decisions/ADR-010-live-
// session-detection.md) — historical batch-imported hands are never assigned to a session, so
// Session History is empty until at least one Live Session has been started and ended. Explicitly
// NOT built this pass, matching the design's own deferred/undesigned pieces: the P&L-over-time
// curve, All-in Adjusted (EV — needs the still-undesigned hand-equity engine), Rake paid (needs
// hero_rake_share, deferred since the Import Engine), "Best table" (no per-table breakdown yet),
// and both "Export as report" buttons (Report Generation is a separate, larger piece of the
// Reports module, out of scope here).
const PAGE_SIZE = 20;

export function SessionHistoryPage() {
  const navigate = useNavigate();
  const [sessions, setSessions] = useState<SessionListItemDto[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [summary, setSummary] = useState<{ totalSessions: number; totalPnl: SessionListItemDto["pnl"]; totalHands: number; totalDurationMinutes: number } | null>(null);
  const [listLoading, setListLoading] = useState(true);
  const [listError, setListError] = useState<string | null>(null);
  const [selectedSessionId, setSelectedSessionId] = useState<number | null>(null);

  const [detail, setDetail] = useState<SessionDetailDto | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [detailError, setDetailError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    setListLoading(true);
    setListError(null);
    fetchSessions(page, PAGE_SIZE)
      .then((res) => {
        if (cancelled) return;
        setSessions(res.items);
        setTotal(res.total);
        setSummary(res.summary);
        setSelectedSessionId((current) => current ?? res.items[0]?.sessionId ?? null);
      })
      .catch((e) => {
        if (!cancelled) setListError(e instanceof ApiError ? e.body.message : "Failed to load session history");
      })
      .finally(() => {
        if (!cancelled) setListLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [page]);

  useEffect(() => {
    if (selectedSessionId == null) return;
    let cancelled = false;
    setDetailLoading(true);
    setDetailError(null);
    fetchSessionDetail(selectedSessionId)
      .then((d) => {
        if (!cancelled) setDetail(d);
      })
      .catch((e) => {
        if (!cancelled) setDetailError(e instanceof ApiError ? e.body.message : "Failed to load session detail");
      })
      .finally(() => {
        if (!cancelled) setDetailLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [selectedSessionId]);

  const totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE));

  return (
    <>
      <GlobalFilters
        pills={[
          { icon: Spade, label: "PokerStars, Winamax" },
          { icon: Coins, label: "NL25 – NL100" },
          { icon: CalendarBlank, label: "All time" },
        ]}
      />

      <div className="page">
        <div className="page-header">
          <div>
            <div className="kicker">Session history</div>
            <h1>{summary ? `${summary.totalSessions.toLocaleString()} sessions` : "Loading…"}</h1>
          </div>
          {summary && (
            <div style={{ display: "flex", alignItems: "center", gap: "var(--space-6)" }}>
              <div style={{ textAlign: "right" }}>
                <div
                  className={
                    "small-stat-value" +
                    (summary.totalPnl && Number(summary.totalPnl.amount) > 0 ? " metric-value-positive" : "")
                  }
                >
                  {formatMoney(summary.totalPnl, true)}
                </div>
                <div className="metric-label">Profit in period</div>
              </div>
              <div style={{ textAlign: "right" }}>
                <div className="small-stat-value">{formatDurationMinutes(summary.totalDurationMinutes)}</div>
                <div className="metric-label">Time played</div>
              </div>
              <div style={{ textAlign: "right" }}>
                <div className="small-stat-value">{summary.totalHands.toLocaleString()}</div>
                <div className="metric-label">Hands</div>
              </div>
              <button className="btn-quiet" disabled title="Report export isn't built yet">
                <FileText size={13} /> Export as report
              </button>
            </div>
          )}
        </div>

        {listError && <div className="banner banner-error">{listError}</div>}

        <div className="player-profile-columns">
          <div className="panel" style={{ minWidth: 0 }}>
            <div className="panel-header">
              <div className="kicker">Sessions</div>
              <span className="panel-header-note">Newest first · click a row for its detail</span>
            </div>

            {listLoading && <p className="placeholder-note">Loading…</p>}
            {!listLoading && sessions.length === 0 && (
              <p className="placeholder-note" style={{ margin: 0 }}>
                No sessions yet — Session History only shows sessions detected by Live Session (see{" "}
                <a href="#/live-session">Live Session</a>). Historical hands imported in bulk aren&apos;t grouped
                into sessions yet.
              </p>
            )}

            {sessions.length > 0 && (
              <>
                <table className="data-table">
                  <thead>
                    <tr>
                      <th>Date</th>
                      <th>Room</th>
                      <th>Stakes</th>
                      <th style={{ textAlign: "center" }}>Tables</th>
                      <th style={{ textAlign: "right" }}>Duration</th>
                      <th style={{ textAlign: "right" }}>Hands</th>
                      <th style={{ textAlign: "right" }}>P&amp;L</th>
                      <th style={{ textAlign: "right" }}>bb/100</th>
                    </tr>
                  </thead>
                  <tbody>
                    {sessions.map((s) => (
                      <tr
                        key={s.sessionId}
                        onClick={() => setSelectedSessionId(s.sessionId)}
                        className={s.sessionId === selectedSessionId ? "player-row-active" : undefined}
                        style={{ cursor: "pointer" }}
                      >
                        <td>
                          {formatShortDateTime(s.startedAt)}
                          {s.active && (
                            <>
                              {" "}
                              <span className="live-dot" />
                            </>
                          )}
                        </td>
                        <td>{roomLabel(s.rooms)}</td>
                        <td>
                          {formatMoney(s.stakes.smallBlind)}/{formatMoney(s.stakes.bigBlind)}
                        </td>
                        <td style={{ textAlign: "center" }}>{s.tables}</td>
                        <td style={{ textAlign: "right" }}>{formatDurationMinutes(s.durationMinutes)}</td>
                        <td style={{ textAlign: "right" }}>{s.hands}</td>
                        <td
                          style={{ textAlign: "right" }}
                          className={s.pnl && Number(s.pnl.amount) > 0 ? "result-positive" : s.pnl && Number(s.pnl.amount) < 0 ? "result-negative" : undefined}
                        >
                          {formatMoney(s.pnl, true)}
                        </td>
                        <td style={{ textAlign: "right" }}>{s.bb100 != null ? Number(s.bb100).toFixed(1) : "—"}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>

                <div className="table-footer">
                  <span className="placeholder-note">
                    Showing {(page - 1) * PAGE_SIZE + 1}–{Math.min(page * PAGE_SIZE, total)} of {total}
                  </span>
                  <div className="pagination-controls">
                    <button className="icon-button" disabled={page <= 1} onClick={() => setPage((p) => p - 1)} title="Previous page">
                      <CaretLeft size={13} />
                    </button>
                    <span>
                      Page {page} of {totalPages}
                    </span>
                    <button className="icon-button" disabled={page >= totalPages} onClick={() => setPage((p) => p + 1)} title="Next page">
                      <CaretRight size={13} />
                    </button>
                  </div>
                </div>
              </>
            )}
          </div>

          <div className="panel" style={{ minWidth: 0 }}>
            {detailError && <div className="banner banner-error">{detailError}</div>}
            {detailLoading && !detail && <p className="placeholder-note">Loading…</p>}
            {!detailLoading && !detail && !selectedSessionId && (
              <p className="placeholder-note" style={{ margin: 0 }}>
                Select a session to see its detail.
              </p>
            )}

            {detail && (
              <>
                <div>
                  <div className="kicker">Session detail</div>
                  <h2 style={{ margin: "7px 0 0", font: "500 17px/1.25 var(--font-heading)" }}>
                    {formatShortDateTime(detail.startedAt)}
                    {detail.endedAt
                      ? ` — ${new Date(detail.endedAt.replace(" ", "T")).toLocaleTimeString(undefined, { hour: "2-digit", minute: "2-digit", hour12: false })}`
                      : " — in progress"}
                  </h2>
                  <div className="tag-row" style={{ marginTop: "var(--space-3)" }}>
                    <span className="tag-toggle tag-toggle-active">
                      {formatMoney(detail.stakes.smallBlind)}/{formatMoney(detail.stakes.bigBlind)}
                    </span>
                    <span className="tag-toggle">{roomLabel(detail.rooms)}</span>
                    <span className="tag-toggle">
                      {detail.tables} table{detail.tables === 1 ? "" : "s"}
                    </span>
                  </div>
                </div>

                <div style={{ display: "flex", alignItems: "baseline", gap: "var(--space-4)", paddingTop: "var(--space-2)" }}>
                  <span
                    className={
                      "modal-result-amount" +
                      (detail.pnl && Number(detail.pnl.amount) > 0 ? " result-positive" : "")
                    }
                  >
                    {formatMoney(detail.pnl, true)}
                  </span>
                  <span className="meta-label">{detail.bb100 != null ? `${Number(detail.bb100).toFixed(1)} bb/100` : "—"}</span>
                </div>

                <div style={{ display: "flex", flexDirection: "column" }}>
                  <DetailRow label="All-in adjusted (EV)" value="—" />
                  <DetailRow label="Duration" value={formatDurationMinutes(detail.durationMinutes)} />
                  <DetailRow label="Hands played" value={String(detail.hands)} />
                  <DetailRow label="Hands per hour" value={detail.handsPerHour != null ? Math.round(detail.handsPerHour).toString() : "—"} />
                  <DetailRow
                    label="VPIP / PFR"
                    value={`${detail.vpip != null ? detail.vpip.toFixed(1) + "%" : "—"} / ${detail.pfr != null ? detail.pfr.toFixed(1) + "%" : "—"}`}
                  />
                  <DetailRow label="3-Bet" value={detail.threeBet != null ? `${detail.threeBet.toFixed(1)}%` : "—"} />
                  <DetailRow label="Biggest pot won" value={formatMoney(detail.biggestPotWon, true)} />
                  <DetailRow label="Rake paid" value="—" />
                </div>

                <div style={{ display: "flex", flexDirection: "column", gap: "var(--space-3)", marginTop: "auto" }}>
                  <button
                    className="btn-outline"
                    style={{ width: "100%" }}
                    onClick={() => navigate(`/hands?sessionId=${detail.sessionId}`)}
                  >
                    <Cards size={13} /> Open this session&apos;s hands
                  </button>
                  <button className="btn-quiet" style={{ width: "100%" }} disabled title="Report export isn't built yet">
                    Export session as report
                  </button>
                </div>
              </>
            )}
          </div>
        </div>
      </div>
    </>
  );
}

function DetailRow({ label, value }: { label: string; value: string }) {
  return (
    <div className="dl-row">
      <span>{label}</span>
      <span>{value}</span>
    </div>
  );
}
