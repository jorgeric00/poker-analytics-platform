import { useEffect, useState } from "react";
import { CaretLeft, CaretRight, Play, X } from "@phosphor-icons/react";
import { ApiError, fetchHandDetail } from "../api/client";
import type { HandActionDto, HandBoardDto, HandDetailDto } from "../api/types";
import { CardBadges } from "../components/CardBadge";
import { formatMoney, HAND_TYPE_LABELS, STREET_LABELS } from "./format";
import { HandReplayModal } from "./HandReplayModal";

interface HandDetailModalProps {
  handId: number;
  onClose: () => void;
  onNavigate: (handId: number) => void;
}

const ROOM_LABELS: Record<string, string> = {
  POKERSTARS: "PokerStars",
  WINAMAX: "Winamax",
  PARTYPOKER: "PartyPoker",
  POKER888: "888poker",
  IPOKER: "iPoker",
  WINNINGPOKER: "Winning Poker",
};

function actionLabel(action: HandActionDto): string {
  const amount = action.amount ? formatMoney(action.amount) : "";
  switch (action.actionType) {
    case "FOLD":
      return "Folds";
    case "CHECK":
      return "Checks";
    case "CALL":
      return `Calls ${amount}`;
    case "BET":
      return `Bets ${amount}`;
    case "RAISE":
      return `Raises to ${amount}`;
    case "ALL_IN":
      return `All-in ${amount}`;
    case "POST_SMALL_BLIND":
      return `Posts small blind ${amount}`;
    case "POST_BIG_BLIND":
      return `Posts big blind ${amount}`;
    case "POST_SMALL_AND_BIG_BLIND":
      return `Posts small + big blind ${amount}`;
    case "POST_DEAD_BLIND":
      return `Posts dead blind ${amount}`;
    case "POST_ANTE":
      return `Posts ante ${amount}`;
    case "STRADDLE":
      return `Straddles ${amount}`;
    case "POSTS":
      return `Posts ${amount}`;
    case "UNCALLED_RETURN":
      return `Uncalled bet returned ${amount}`;
    case "COLLECTED":
    case "COLLECTED_MAIN_POT":
    case "COLLECTED_SIDE_POT":
      return `Collects ${amount}`;
    case "SHOWS":
      return "Shows";
    case "MUCKS":
      return "Mucks";
    case "DOESNT_SHOW":
      return "Doesn't show";
    default:
      return action.actionType;
  }
}

function boardCards(boards: HandBoardDto[], street: string, runIndex: number): string[] {
  // boards.cards is cumulative per street per docs/09-data-model.md (FLOP="Ah Kd 2c", TURN adds the
  // turn card only) — this view wants the full board visible as of each street, so it concatenates
  // every street up to and including the requested one within the same run.
  const order = ["FLOP", "TURN", "RIVER"];
  const upTo = order.slice(0, order.indexOf(street) + 1);
  return boards
    .filter((b) => b.runIndex === runIndex && upTo.includes(b.street))
    .sort((a, b) => order.indexOf(a.street) - order.indexOf(b.street))
    .flatMap((b) => b.cards.split(" "));
}

export function HandDetailModal({ handId, onClose, onNavigate }: HandDetailModalProps) {
  const [detail, setDetail] = useState<HandDetailDto | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [replaying, setReplaying] = useState(false);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);
    fetchHandDetail(handId)
      .then((d) => {
        if (!cancelled) setDetail(d);
      })
      .catch((e) => {
        if (!cancelled) setError(e instanceof ApiError ? e.body.message : "Failed to load hand detail");
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [handId]);

  useEffect(() => {
    function onKeyDown(e: KeyboardEvent) {
      if (e.key === "Escape") onClose();
    }
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [onClose]);

  const hero = detail?.results.find((r) => r.isHero);
  const heroSeat = detail?.seats.find((s) => s.isHero);
  const streetsWithActions = detail
    ? Array.from(new Set(detail.actions.map((a) => a.street))).filter((s) => s !== "SHOWDOWN")
    : [];

  return (
    <>
      <div className="modal-backdrop" onClick={onClose}>
        <div className="modal-dialog" onClick={(e) => e.stopPropagation()}>
        {loading && <div className="modal-body-loading">Loading…</div>}
        {error && <div className="banner banner-error">{error}</div>}

        {detail && (
          <>
            <div className="modal-header">
              <div>
                <div className="kicker">
                  Hand detail · {ROOM_LABELS[detail.room] ?? detail.room} #{detail.externalHandId}
                </div>
                <h2>
                  {detail.handTimestampRaw} · {formatMoney(detail.stakes.smallBlind)}/{formatMoney(detail.stakes.bigBlind)}{" "}
                  {detail.maxSeats}-max · {HAND_TYPE_LABELS[detail.handType] ?? detail.handType}
                </h2>
              </div>
              <div style={{ display: "flex", gap: "var(--space-3)" }}>
                <button className="icon-button icon-button-lg" onClick={onClose} title="Close">
                  <X size={15} />
                </button>
              </div>
            </div>

            <div className="modal-body">
              <div className="modal-actions-column">
                <div className="kicker">Action by street</div>
                {streetsWithActions.map((street) => (
                  <div className="street-block" key={street}>
                    <div className="street-header">
                      <span className="street-name">{STREET_LABELS[street] ?? street}</span>
                      {street !== "PREFLOP" && (
                        <span className="street-board">
                          <CardBadges cards={boardCards(detail.boards, street, 0)} />
                        </span>
                      )}
                      {street === "PREFLOP" && (
                        <span className="street-board">
                          <CardBadges cards={heroSeat?.holeCards ?? null} />
                        </span>
                      )}
                    </div>
                    {detail.actions
                      .filter((a) => a.street === street)
                      .map((a) => (
                        <div className={a.isHero ? "action-row action-row-hero" : "action-row"} key={a.sequenceNo}>
                          <span className="who-label">
                            {a.isHero ? "Hero" : (a.nickname ?? "—")}
                          </span>
                          <span>{actionLabel(a)}</span>
                          <span>{a.amount ? formatMoney(a.amount) : ""}</span>
                        </div>
                      ))}
                  </div>
                ))}

                {detail.results.some((r) => r.wentToShowdown) && (
                  <div style={{ paddingTop: "var(--space-4)", display: "flex", flexDirection: "column", gap: "var(--space-2)" }}>
                    <span style={{ font: "500 12px var(--font-heading)" }}>Showdown</span>
                    {detail.results
                      .filter((r) => r.wentToShowdown)
                      .map((r) => (
                        <div key={r.nickname ?? "hero"} className="activity-text">
                          {r.isHero ? "Hero" : (r.nickname ?? "—")} went to showdown
                          <span className="placeholder-note"> (cards shown aren&apos;t extracted yet)</span>
                        </div>
                      ))}
                  </div>
                )}
              </div>

              <div className="modal-side-column">
                <div>
                  <div className="kicker">Result</div>
                  <div className="modal-result-row">
                    <span className={hero && Number(hero.netResult?.amount) > 0 ? "modal-result-amount result-positive" : "modal-result-amount"}>
                      {formatMoney(hero?.netResult, true)}
                    </span>
                  </div>
                  <div className="modal-result-stats">
                    <div>
                      <div className="modal-result-stat-value">{formatMoney(detail.totalPot)}</div>
                      <div className="meta-label">Final pot</div>
                    </div>
                    <div>
                      <div className="modal-result-stat-value">{formatMoney(detail.rake)}</div>
                      <div className="meta-label">Rake</div>
                    </div>
                    <div>
                      <div className="modal-result-stat-value">{STREET_LABELS[detail.endedOnStreet] ?? detail.endedOnStreet}</div>
                      <div className="meta-label">Ended on</div>
                    </div>
                  </div>
                </div>

                <div>
                  <div className="kicker">Seats</div>
                  <div className="seat-list">
                    {detail.seats.map((s) => (
                      <div key={s.seatNumber} className={s.isHero ? "seat-row seat-row-hero" : "seat-row"}>
                        <span>
                          {s.position} · {s.isHero ? "Hero" : (s.nickname ?? "—")}
                        </span>
                        <span>{formatMoney(s.startingStack)}</span>
                      </div>
                    ))}
                  </div>
                </div>

                <div>
                  <div className="kicker">Table</div>
                  <div className="activity-text">
                    {detail.tableName ?? "—"} · {detail.maxSeats}-max · {formatMoney(detail.stakes.smallBlind)}/
                    {formatMoney(detail.stakes.bigBlind)}
                  </div>
                </div>
              </div>
            </div>

            <div className="modal-footer">
              <div style={{ display: "flex", alignItems: "center", gap: "var(--space-3)" }}>
                <button
                  className="icon-button"
                  disabled={detail.previousHandId == null}
                  onClick={() => detail.previousHandId != null && onNavigate(detail.previousHandId)}
                  title="Previous hand"
                >
                  <CaretLeft size={13} />
                </button>
                <span className="placeholder-note">Adjacent hands, by import order (not the list&apos;s active filters)</span>
                <button
                  className="icon-button"
                  disabled={detail.nextHandId == null}
                  onClick={() => detail.nextHandId != null && onNavigate(detail.nextHandId)}
                  title="Next hand"
                >
                  <CaretRight size={13} />
                </button>
              </div>
              <button className="btn-outline" onClick={() => setReplaying(true)}>
                <Play size={13} /> Replay hand
              </button>
            </div>
          </>
        )}
      </div>
      </div>
      {replaying && detail && <HandReplayModal handId={detail.handId} onClose={() => setReplaying(false)} />}
    </>
  );
}
