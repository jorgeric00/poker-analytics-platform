import { useEffect, useState } from "react";
import { useSearchParams } from "react-router-dom";
import {
  BookmarkSimple,
  CalendarBlank,
  CaretLeft,
  CaretRight,
  Check,
  Coins,
  MagnifyingGlass,
  Play,
  Spade,
  X,
} from "@phosphor-icons/react";
import { ApiError, fetchHands } from "../api/client";
import type { HandListItemDto, HandListResponse } from "../api/types";
import { GlobalFilters } from "../components/GlobalFilters";
import { CardBadges } from "../components/CardBadge";
import { formatMoney, HAND_TYPE_LABELS, STREET_LABELS } from "./format";
import { HandDetailModal } from "./HandDetailModal";

// Matches Hands.dc.html (docs/12-ui-designs.md) — left filter rail + hand table, read via the
// Claude Design MCP. Only the filters backed by real, already-stored data are wired to
// GET /api/v1/hands (see backend/.../hands/HandListFilter.java's own doc for exactly which):
// Result (won/lost), Situation position tags, 3-bet/limped pot, Ended on street, Showdown only.
// All-in-only, the two size sliders, and "only tagged hands" render per the design but are inert —
// no room parser records a structured all-in flag yet, min-pot/min-result filtering just isn't
// built, and hand tagging (docs/10-api-design.md's POST/DELETE /hands/{id}/tags) doesn't exist —
// same "visual only for now" pattern GlobalFilters already established.

const POSITIONS = ["BTN", "SB", "BB", "UTG", "UTG1", "UTG2", "LJ", "HJ", "CO"];
const STREETS = ["PREFLOP", "FLOP", "TURN", "RIVER"];
const ROOM_LABELS: Record<string, string> = {
  POKERSTARS: "PokerStars",
  WINAMAX: "Winamax",
  PARTYPOKER: "PartyPoker",
  POKER888: "888poker",
  IPOKER: "iPoker",
  WINNINGPOKER: "Winning Poker",
};
const PAGE_SIZE = 20;

function toggle(list: string[], value: string): string[] {
  return list.includes(value) ? list.filter((v) => v !== value) : [...list, value];
}

function stakesLabel(row: HandListItemDto): string {
  if (!row.stakes.smallBlind || !row.stakes.bigBlind) return "—";
  return `${formatMoney(row.stakes.smallBlind)}/${formatMoney(row.stakes.bigBlind)}`;
}

export function HandsPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const opponentPlayerIdParam = searchParams.get("opponentPlayerId");
  const opponentPlayerId = opponentPlayerIdParam ? Number(opponentPlayerIdParam) : undefined;
  const opponentNickname = searchParams.get("opponentNickname");

  const [wonLost, setWonLost] = useState<"ALL" | "WON" | "LOST">("ALL");
  const [positions, setPositions] = useState<string[]>([]);
  const [handTypes, setHandTypes] = useState<string[]>([]);
  const [endedOnStreets, setEndedOnStreets] = useState<string[]>([]);
  const [showdownOnly, setShowdownOnly] = useState(false);
  const [page, setPage] = useState(1);
  const [data, setData] = useState<HandListResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [selectedHandId, setSelectedHandId] = useState<number | null>(null);

  useEffect(() => {
    setPage(1);
  }, [wonLost, positions, handTypes, endedOnStreets, showdownOnly, opponentPlayerId]);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);
    fetchHands({
      wonLost: wonLost === "ALL" ? undefined : wonLost,
      position: positions.length ? positions : undefined,
      handType: handTypes.length ? handTypes : undefined,
      endedOnStreet: endedOnStreets.length ? endedOnStreets : undefined,
      showdownOnly: showdownOnly || undefined,
      opponentPlayerId,
      page,
      pageSize: PAGE_SIZE,
    })
      .then((res) => {
        if (!cancelled) setData(res);
      })
      .catch((e) => {
        if (!cancelled) setError(e instanceof ApiError ? e.body.message : "Failed to load hands");
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [wonLost, positions, handTypes, endedOnStreets, showdownOnly, opponentPlayerId, page]);

  const totalPages = data ? Math.max(1, Math.ceil(data.total / PAGE_SIZE)) : 1;

  return (
    <>
      <GlobalFilters
        pills={[
          { icon: Spade, label: "PokerStars, Winamax" },
          { icon: Coins, label: "NL25 – NL100" },
          { icon: CalendarBlank, label: "Last 30 days" },
        ]}
      />

      <div className="page">
        <div className="hands-layout">
          <aside className="hands-filters">
            <div className="filter-group">
              <div className="kicker">Result</div>
              <div className="segmented" style={{ alignSelf: "flex-start" }}>
                {(["ALL", "WON", "LOST"] as const).map((v) => (
                  <span
                    key={v}
                    className={wonLost === v ? "on" : undefined}
                    style={{ cursor: "pointer" }}
                    onClick={() => setWonLost(v)}
                  >
                    {v === "ALL" ? "All" : v === "WON" ? "Won" : "Lost"}
                  </span>
                ))}
              </div>
              <label className="checkbox-row checkbox-row-disabled" title="No room's parser records a structured all-in flag yet">
                <span className="checkbox-box" />
                All-in hands only
              </label>
              <label className="checkbox-row" onClick={() => setShowdownOnly((v) => !v)}>
                <span className={showdownOnly ? "checkbox-box checkbox-box-on" : "checkbox-box"}>
                  {showdownOnly && <Check size={9} weight="bold" />}
                </span>
                Showdown only
              </label>
            </div>

            <div className="filter-group">
              <div className="kicker">Size</div>
              <div className="slider-disabled" title="Not wired yet — no min-pot/min-result filter built">
                <div className="slider-row">
                  <span>Min pot</span>
                  <span>—</span>
                </div>
                <div className="slider-track">
                  <div className="slider-fill" style={{ width: "0%" }} />
                </div>
              </div>
              <div className="slider-disabled" title="Not wired yet — no min-pot/min-result filter built">
                <div className="slider-row">
                  <span>Min won / lost</span>
                  <span>—</span>
                </div>
                <div className="slider-track">
                  <div className="slider-fill" style={{ width: "0%" }} />
                </div>
              </div>
            </div>

            <div className="filter-group">
              <div className="kicker">Situation</div>
              <div className="tag-row">
                {POSITIONS.map((p) => (
                  <span
                    key={p}
                    className={positions.includes(p) ? "tag-toggle tag-toggle-active" : "tag-toggle"}
                    onClick={() => setPositions(toggle(positions, p))}
                  >
                    {p}
                  </span>
                ))}
              </div>
              <label className="checkbox-row" onClick={() => setHandTypes(toggle(handTypes, "THREE_BET_POT"))}>
                <span className={handTypes.includes("THREE_BET_POT") ? "checkbox-box checkbox-box-on" : "checkbox-box"}>
                  {handTypes.includes("THREE_BET_POT") && <Check size={9} weight="bold" />}
                </span>
                3-bet pot
              </label>
              <label className="checkbox-row" onClick={() => setHandTypes(toggle(handTypes, "LIMPED_POT"))}>
                <span className={handTypes.includes("LIMPED_POT") ? "checkbox-box checkbox-box-on" : "checkbox-box"}>
                  {handTypes.includes("LIMPED_POT") && <Check size={9} weight="bold" />}
                </span>
                Limped pot
              </label>
            </div>

            <div className="filter-group">
              <div className="kicker">Ended on street</div>
              <div className="tag-row">
                {STREETS.map((s) => (
                  <span
                    key={s}
                    className={endedOnStreets.includes(s) ? "tag-toggle tag-toggle-active" : "tag-toggle"}
                    onClick={() => setEndedOnStreets(toggle(endedOnStreets, s))}
                  >
                    {STREET_LABELS[s]}
                  </span>
                ))}
              </div>
            </div>

            <div className="filter-group">
              <div className="kicker">Marked</div>
              <label className="checkbox-row checkbox-row-disabled" title="Hand tagging isn't built yet">
                <span className="checkbox-box" />
                Only tagged hands
              </label>
            </div>
          </aside>

          <div className="hands-main">
            <div className="page-header">
              <div>
                <div className="kicker">Hands</div>
                <h1>{data ? `${data.total.toLocaleString()} hands match` : "Loading…"}</h1>
              </div>
              <span className="page-header-meta">
                Sorted by import order, newest first
                <br />
                (hand date isn&apos;t normalized yet — see docs/11-development-tracking.md)
              </span>
            </div>

            {error && <div className="banner banner-error">{error}</div>}

            {opponentPlayerId != null && (
              <div className="banner opponent-filter-banner">
                Showing hands vs {opponentNickname ?? `player #${opponentPlayerId}`}
                <button className="icon-button" onClick={() => setSearchParams({})} title="Clear opponent filter">
                  <X size={12} />
                </button>
              </div>
            )}

            <table className="data-table hands-table">
              <thead>
                <tr>
                  <th style={{ width: 22 }}></th>
                  <th>Date</th>
                  <th>Room</th>
                  <th>Stakes</th>
                  <th>Pos</th>
                  <th>Cards</th>
                  <th style={{ textAlign: "center" }}>Plrs</th>
                  <th>Hand type</th>
                  <th>Ended</th>
                  <th style={{ textAlign: "right" }}>Pot</th>
                  <th style={{ textAlign: "right" }}>Result</th>
                  <th style={{ textAlign: "right" }}>Actions</th>
                </tr>
              </thead>
              <tbody>
                {loading && (
                  <tr>
                    <td colSpan={11}>Loading…</td>
                  </tr>
                )}
                {!loading && data?.items.length === 0 && (
                  <tr>
                    <td colSpan={11} className="placeholder-note">
                      No hands match these filters.
                    </td>
                  </tr>
                )}
                {!loading &&
                  data?.items.map((row) => (
                    <tr key={row.handId}>
                      <td title="Hand tagging isn't built yet">
                        <BookmarkSimple size={13} style={{ color: "var(--color-neutral-700)" }} />
                      </td>
                      <td>{row.handTimestampRaw}</td>
                      <td>{ROOM_LABELS[row.room] ?? row.room}</td>
                      <td>{stakesLabel(row)}</td>
                      <td>{row.heroPosition}</td>
                      <td>
                        <CardBadges cards={row.heroHoleCards} />
                      </td>
                      <td style={{ textAlign: "center" }}>{row.playerCount}</td>
                      <td>{HAND_TYPE_LABELS[row.handType] ?? row.handType}</td>
                      <td>{STREET_LABELS[row.endedOnStreet] ?? row.endedOnStreet}</td>
                      <td style={{ textAlign: "right" }}>{formatMoney(row.pot)}</td>
                      <td
                        style={{ textAlign: "right" }}
                        className={row.result === "WON" ? "result-positive" : row.result === "LOST" ? "result-negative" : undefined}
                      >
                        {formatMoney(row.amount, true)}
                      </td>
                      <td style={{ textAlign: "right" }}>
                        <span className="icon-button-group">
                          <button className="icon-button" disabled title="Replay isn't built yet">
                            <Play size={13} />
                          </button>
                          <button className="icon-button" onClick={() => setSelectedHandId(row.handId)} title="Detail">
                            <MagnifyingGlass size={13} />
                          </button>
                        </span>
                      </td>
                    </tr>
                  ))}
              </tbody>
            </table>

            <div className="table-footer">
              <span className="placeholder-note">
                {data && data.total > 0
                  ? `Showing ${(page - 1) * PAGE_SIZE + 1}–${Math.min(page * PAGE_SIZE, data.total)} of ${data.total}`
                  : "No hands"}
              </span>
              <div className="pagination-controls">
                <button className="icon-button" disabled={page <= 1} onClick={() => setPage((p) => p - 1)} title="Previous page">
                  <CaretLeft size={13} />
                </button>
                <span>
                  Page {page} of {totalPages}
                </span>
                <button
                  className="icon-button"
                  disabled={page >= totalPages}
                  onClick={() => setPage((p) => p + 1)}
                  title="Next page"
                >
                  <CaretRight size={13} />
                </button>
              </div>
            </div>
          </div>
        </div>
      </div>

      {selectedHandId != null && (
        <HandDetailModal handId={selectedHandId} onClose={() => setSelectedHandId(null)} onNavigate={setSelectedHandId} />
      )}
    </>
  );
}
