import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { CalendarBlank, Coins, MagnifyingGlass, Plus, Spade } from "@phosphor-icons/react";
import {
  ApiError,
  addPlayerTag,
  fetchHands,
  fetchPlayerDetail,
  fetchPlayers,
  queryStats,
  removePlayerTag,
  savePlayerNotes,
} from "../api/client";
import type { HandListItemDto, PlayerDetailDto, PlayerListItemDto } from "../api/types";
import { GlobalFilters } from "../components/GlobalFilters";
import { CardBadges } from "../components/CardBadge";
import { formatMoney, HAND_TYPE_LABELS } from "./format";
import { HandDetailModal } from "./HandDetailModal";

// Matches Players.dc.html (docs/12-ui-designs.md) — a single master-detail screen (opponent list
// on the left, profile on the right), read via the Claude Design MCP; there's no separate
// "Player Detail" canvas, unlike Hands/Hand Detail. Predefined tag palette sourced directly from
// the mockup itself, since docs/05-product-map.md explicitly leaves "the exact predefined tag set"
// for later — this is that later decision, made here rather than invented separately.
const PREDEFINED_TAGS = ["Regular", "Nit", "Maniac", "Calling station", "Folds to 3-bet", "Aggressive river"];
const MIN_HANDS_MAX = 20; // this app's real dataset is small (81 hands) — the mockup's "300 hands"
// illustrative slider max would never move for real data, so scaled down rather than copied as-is.

export function PlayersPage() {
  const navigate = useNavigate();
  const [search, setSearch] = useState("");
  const [minHands, setMinHands] = useState(0);
  const [players, setPlayers] = useState<PlayerListItemDto[]>([]);
  const [total, setTotal] = useState(0);
  const [listLoading, setListLoading] = useState(true);
  const [listError, setListError] = useState<string | null>(null);
  const [selectedPlayerId, setSelectedPlayerId] = useState<number | null>(null);

  const [detail, setDetail] = useState<PlayerDetailDto | null>(null);
  const [tendencies, setTendencies] = useState<Record<string, number> | null>(null);
  const [tendenciesSampleSize, setTendenciesSampleSize] = useState<number | null>(null);
  const [handsVsOpponent, setHandsVsOpponent] = useState<HandListItemDto[]>([]);
  const [handsVsOpponentTotal, setHandsVsOpponentTotal] = useState(0);
  const [profileLoading, setProfileLoading] = useState(false);
  const [profileError, setProfileError] = useState<string | null>(null);
  const [notesDraft, setNotesDraft] = useState("");
  const [notesSavedAt, setNotesSavedAt] = useState<Date | null>(null);
  const [newTagInput, setNewTagInput] = useState("");
  const [selectedHandId, setSelectedHandId] = useState<number | null>(null);

  useEffect(() => {
    let cancelled = false;
    setListLoading(true);
    setListError(null);
    fetchPlayers({ search: search || undefined, minHands, pageSize: 500 })
      .then((res) => {
        if (cancelled) return;
        setPlayers(res.items);
        setTotal(res.total);
        setSelectedPlayerId((current) => current ?? res.items[0]?.playerId ?? null);
      })
      .catch((e) => {
        if (!cancelled) setListError(e instanceof ApiError ? e.body.message : "Failed to load players");
      })
      .finally(() => {
        if (!cancelled) setListLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [search, minHands]);

  useEffect(() => {
    if (selectedPlayerId == null) return;
    let cancelled = false;
    setProfileLoading(true);
    setProfileError(null);
    Promise.all([
      fetchPlayerDetail(selectedPlayerId),
      queryStats({
        scope: { type: "OPPONENT", playerId: selectedPlayerId },
        statSet: ["HANDS", "VPIP", "PFR", "THREE_BET", "FLOP_CBET"],
        breakdown: [],
      }),
      fetchHands({ opponentPlayerId: selectedPlayerId, pageSize: 6 }),
    ])
      .then(([detailRes, statsRes, handsRes]) => {
        if (cancelled) return;
        setDetail(detailRes);
        setNotesDraft(detailRes.notes);
        const row = statsRes.rows[0];
        setTendencies((row?.values as Record<string, number>) ?? {});
        setTendenciesSampleSize(row?.sampleSize ?? 0);
        setHandsVsOpponent(handsRes.items);
        setHandsVsOpponentTotal(handsRes.total);
      })
      .catch((e) => {
        if (!cancelled) setProfileError(e instanceof ApiError ? e.body.message : "Failed to load player profile");
      })
      .finally(() => {
        if (!cancelled) setProfileLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [selectedPlayerId]);

  function refreshDetail() {
    if (selectedPlayerId == null) return;
    fetchPlayerDetail(selectedPlayerId).then(setDetail);
    fetchPlayers({ search: search || undefined, minHands, pageSize: 500 }).then((res) => setPlayers(res.items));
  }

  async function toggleTag(tag: string) {
    if (selectedPlayerId == null || !detail) return;
    if (detail.tags.includes(tag)) {
      await removePlayerTag(selectedPlayerId, tag);
    } else {
      await addPlayerTag(selectedPlayerId, tag);
    }
    refreshDetail();
  }

  async function addCustomTag() {
    if (selectedPlayerId == null || !newTagInput.trim()) return;
    await addPlayerTag(selectedPlayerId, newTagInput.trim());
    setNewTagInput("");
    refreshDetail();
  }

  async function saveNotes() {
    if (selectedPlayerId == null) return;
    await savePlayerNotes(selectedPlayerId, notesDraft);
    setNotesSavedAt(new Date());
  }

  return (
    <>
      <GlobalFilters
        pills={[
          { icon: Spade, label: "PokerStars, Winamax" },
          { icon: Coins, label: "NL25 – NL100" },
          { icon: CalendarBlank, label: "Last 12 months" },
        ]}
      />

      <div className="page">
        <div className="players-layout">
          <aside className="players-list-rail">
            <div className="search-input">
              <MagnifyingGlass size={14} />
              <input
                type="text"
                placeholder="Search nickname"
                value={search}
                onChange={(e) => setSearch(e.currentTarget.value)}
              />
            </div>

            <div className="filter-group">
              <div className="slider-row">
                <span>Minimum sample</span>
                <span>{minHands === 0 ? "No minimum" : `${minHands} hands`}</span>
              </div>
              <input
                type="range"
                min={0}
                max={MIN_HANDS_MAX}
                value={minHands}
                onChange={(e) => setMinHands(Number(e.currentTarget.value))}
              />
            </div>

            <div className="players-list-header">
              <span className="kicker">{total.toLocaleString()} opponents</span>
              <span className="placeholder-note">Hands ↓</span>
            </div>

            {listError && <div className="banner banner-error">{listError}</div>}
            {listLoading && <p className="placeholder-note">Loading…</p>}

            <div className="players-list">
              {players.map((p) => (
                <div
                  key={p.playerId}
                  className={p.playerId === selectedPlayerId ? "player-row player-row-active" : "player-row"}
                  onClick={() => setSelectedPlayerId(p.playerId)}
                >
                  <div style={{ minWidth: 0 }}>
                    <div>{p.nickname}</div>
                    <div className="player-row-sub">{p.tags.length ? p.tags.join(", ") : "—"}</div>
                  </div>
                  <div>{p.handsVsHero}</div>
                  <div>{p.quickStat ? `${p.quickStat.value.toFixed(1)}` : "—"}</div>
                </div>
              ))}
            </div>

            <div className="players-list-footnote">Nicknames are aggregated across poker rooms — one profile per nickname.</div>
          </aside>

          <div className="player-profile">
            {profileError && <div className="banner banner-error">{profileError}</div>}
            {profileLoading && !detail && <p>Loading…</p>}

            {detail && (
              <>
                <div className="page-header">
                  <div>
                    <div className="kicker">Player profile · stats vs Hero</div>
                    <h1>{detail.nickname}</h1>
                    <div className="page-header-meta" style={{ textAlign: "left" }}>
                      {detail.handsVsHero.toLocaleString()} hands together · {detail.roomsPlayed.join(", ") || "—"} · last
                      seen{" "}
                      <span title="Last time this nickname was seen by an import run, not necessarily the last real hand played (hand dates aren't normalized yet)">
                        {detail.lastSeenAt.split(" ")[0]}
                      </span>
                    </div>
                  </div>
                  <button
                    className="btn-quiet"
                    onClick={() => navigate(`/hands?opponentPlayerId=${detail.playerId}&opponentNickname=${encodeURIComponent(detail.nickname)}`)}
                  >
                    Open all hands vs {detail.nickname}
                  </button>
                </div>

                <div className="panel">
                  <div className="panel-header">
                    <div className="kicker">Tendencies vs Hero</div>
                    <span className="panel-header-note">{tendenciesSampleSize ?? 0} hands together</span>
                  </div>
                  <div className="tendencies-grid">
                    <TendencyStat label="VPIP" value={tendencies?.VPIP} />
                    <TendencyStat label="PFR" value={tendencies?.PFR} />
                    <TendencyStat label="3-Bet" value={tendencies?.THREE_BET} />
                    <TendencyStat label="Fold to 3-Bet" value={undefined} note="not implemented yet" />
                    <TendencyStat label="C-Bet flop" value={tendencies?.FLOP_CBET} />
                    <TendencyStat label="WTSD" value={undefined} note="not implemented yet" />
                  </div>
                </div>

                <div className="player-profile-columns">
                  <div className="panel" style={{ minWidth: 0 }}>
                    <div className="panel-header">
                      <div className="kicker">Hands vs {detail.nickname}</div>
                      <a
                        href={`#/hands?opponentPlayerId=${detail.playerId}&opponentNickname=${encodeURIComponent(detail.nickname)}`}
                        className="drill-link"
                      >
                        Open in Hands →
                      </a>
                    </div>
                    <table className="data-table">
                      <thead>
                        <tr>
                          <th>Date</th>
                          <th>Stakes</th>
                          <th>Pos</th>
                          <th>Cards</th>
                          <th>Hand type</th>
                          <th style={{ textAlign: "right" }}>Result</th>
                          <th style={{ textAlign: "right" }}>Actions</th>
                        </tr>
                      </thead>
                      <tbody>
                        {handsVsOpponent.map((h) => (
                          <tr key={h.handId}>
                            <td>{h.handTimestampRaw}</td>
                            <td>
                              {formatMoney(h.stakes.smallBlind)}/{formatMoney(h.stakes.bigBlind)}
                            </td>
                            <td>{h.heroPosition}</td>
                            <td>
                              <CardBadges cards={h.heroHoleCards} />
                            </td>
                            <td>{HAND_TYPE_LABELS[h.handType] ?? h.handType}</td>
                            <td
                              style={{ textAlign: "right" }}
                              className={h.result === "WON" ? "result-positive" : h.result === "LOST" ? "result-negative" : undefined}
                            >
                              {formatMoney(h.amount, true)}
                            </td>
                            <td style={{ textAlign: "right" }}>
                              <button className="icon-button" onClick={() => setSelectedHandId(h.handId)} title="Detail">
                                <MagnifyingGlass size={13} />
                              </button>
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                    <span className="placeholder-note">
                      Showing the {handsVsOpponent.length} most recent of {handsVsOpponentTotal}
                    </span>
                  </div>

                  <div className="panel" style={{ minWidth: 0 }}>
                    <div className="kicker">Notes</div>
                    <div className="tag-row">
                      {PREDEFINED_TAGS.map((tag) => (
                        <span
                          key={tag}
                          className={detail.tags.includes(tag) ? "tag-toggle tag-toggle-active" : "tag-toggle"}
                          onClick={() => toggleTag(tag)}
                        >
                          {tag}
                        </span>
                      ))}
                      {detail.tags
                        .filter((t) => !PREDEFINED_TAGS.includes(t))
                        .map((tag) => (
                          <span key={tag} className="tag-toggle tag-toggle-active" onClick={() => toggleTag(tag)}>
                            {tag}
                          </span>
                        ))}
                      <span className="tag-toggle tag-toggle-input">
                        <Plus size={9} />
                        <input
                          type="text"
                          placeholder="New tag"
                          value={newTagInput}
                          onChange={(e) => setNewTagInput(e.currentTarget.value)}
                          onKeyDown={(e) => e.key === "Enter" && addCustomTag()}
                        />
                      </span>
                    </div>
                    <textarea
                      className="notes-textarea"
                      value={notesDraft}
                      onChange={(e) => setNotesDraft(e.currentTarget.value)}
                      placeholder="Free-text notes about this opponent…"
                      rows={4}
                    />
                    <div className="table-footer" style={{ paddingTop: 0 }}>
                      <span className="placeholder-note">
                        {notesSavedAt ? `Saved ${notesSavedAt.toLocaleTimeString()}` : "Not saved this session"}
                      </span>
                      <button className="btn-quiet" onClick={saveNotes}>
                        Save note
                      </button>
                    </div>
                  </div>
                </div>
              </>
            )}
          </div>
        </div>
      </div>

      {selectedHandId != null && (
        <HandDetailModal
          handId={selectedHandId}
          onClose={() => setSelectedHandId(null)}
          onNavigate={setSelectedHandId}
        />
      )}
    </>
  );
}

function TendencyStat({ label, value, note }: { label: string; value: number | undefined; note?: string }) {
  const hasValue = value != null;
  return (
    <div className="stat-block">
      <div className="stat-block-header">
        <span>{label}</span>
        <span className="stat-block-value">{hasValue ? `${value.toFixed(1)}%` : "—"}</span>
      </div>
      <div className="slider-track">
        <div className="slider-fill" style={{ width: hasValue ? `${Math.min(value, 100)}%` : "0%" }} />
      </div>
      {!hasValue && <div className="meta-label">{note}</div>}
    </div>
  );
}
