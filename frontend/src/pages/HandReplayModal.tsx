import { useEffect, useMemo, useState } from "react";
import { ArrowCounterClockwise, CaretLeft, CaretRight, Pause, Play, X } from "@phosphor-icons/react";
import { ApiError, fetchHandDetail } from "../api/client";
import type { HandActionDto, HandDetailDto, HandSeatDto, MoneyDto } from "../api/types";
import { CardBadges } from "../components/CardBadge";
import { formatMoney, HAND_TYPE_LABELS, STREET_LABELS, roomLabel } from "./format";

// No approved Claude Design mockup exists for Replay (docs/12-ui-designs.md only covers Hand
// Detail) — built directly from docs/05-product-map.md's own text ("opens the hand replayed
// visually, street by street") per the user's explicit choice, using the Nocturne tokens already
// in theme.css rather than a new canvas. Reuses GET /api/v1/hands/{handId} unchanged — no new
// backend endpoint, since HandDetailDto already carries every seat/action/board fact this needs.

interface HandReplayModalProps {
  handId: number;
  onClose: () => void;
}

const money = (amount: number, currency: string): MoneyDto => ({ amount: amount.toFixed(2), currency });

interface ReplayFrame {
  street: string;
  boardCards: string[];
  stacks: Record<number, number>;
  potTotal: number;
  folded: Set<number>;
  activeSeat: number | null;
  actionText: string | null;
  isResult: boolean;
}

const STREET_ORDER = ["PREFLOP", "FLOP", "TURN", "RIVER"];

function actionText(action: HandActionDto, who: string): string {
  const amt = action.amount ? formatMoney(action.amount) : "";
  switch (action.actionType) {
    case "FOLD":
      return `${who} folds`;
    case "CHECK":
      return `${who} checks`;
    case "CALL":
      return `${who} calls ${amt}`;
    case "BET":
      return `${who} bets ${amt}`;
    case "RAISE":
      return `${who} raises to ${amt}`;
    case "ALL_IN":
      return `${who} is all-in for ${amt}`;
    case "POST_SMALL_BLIND":
      return `${who} posts small blind ${amt}`;
    case "POST_BIG_BLIND":
      return `${who} posts big blind ${amt}`;
    case "POST_SMALL_AND_BIG_BLIND":
      return `${who} posts small + big blind ${amt}`;
    case "POST_DEAD_BLIND":
      return `${who} posts dead blind ${amt}`;
    case "POST_ANTE":
      return `${who} posts ante ${amt}`;
    case "STRADDLE":
      return `${who} straddles ${amt}`;
    case "POSTS":
      return `${who} posts ${amt}`;
    case "UNCALLED_RETURN":
      return `Uncalled ${amt} returned to ${who}`;
    case "COLLECTED":
    case "COLLECTED_MAIN_POT":
    case "COLLECTED_SIDE_POT":
      return `${who} collects ${amt}`;
    case "SHOWS":
      return `${who} shows${who === "Hero" ? "" : " (cards not available yet)"}`;
    case "MUCKS":
      return `${who} mucks`;
    case "DOESNT_SHOW":
      return `${who} doesn't show`;
    default:
      return `${who}: ${action.actionType}`;
  }
}

function buildFrames(detail: HandDetailDto): ReplayFrame[] {
  const heroSeat = detail.seats.find((s) => s.isHero) ?? null;
  const nicknameToSeat = new Map<string, number>();
  detail.seats.forEach((s) => {
    if (s.nickname) nicknameToSeat.set(s.nickname, s.seatNumber);
  });
  const stacks: Record<number, number> = {};
  detail.seats.forEach((s) => {
    stacks[s.seatNumber] = s.startingStack ? Number(s.startingStack.amount) : 0;
  });

  const folded = new Set<number>();
  let potTotal = 0;
  let boardCards: string[] = [];
  let streetContribution: Record<number, number> = {};
  let trackedStreet: string | null = null;

  const frames: ReplayFrame[] = [
    { street: "PREFLOP", boardCards: [], stacks: { ...stacks }, potTotal: 0, folded: new Set(), activeSeat: null, actionText: null, isResult: false },
  ];

  const resolveSeat = (action: HandActionDto): number | null => {
    if (action.isHero) return heroSeat?.seatNumber ?? null;
    if (action.nickname && nicknameToSeat.has(action.nickname)) return nicknameToSeat.get(action.nickname)!;
    return null;
  };

  const who = (action: HandActionDto): string => (action.isHero ? "Hero" : action.nickname ?? "Unknown");

  const sorted = [...detail.actions].sort((a, b) => a.sequenceNo - b.sequenceNo);

  for (const action of sorted) {
    if (action.street !== trackedStreet) {
      streetContribution = {};
      trackedStreet = action.street;
      if (STREET_ORDER.includes(action.street)) {
        const newCards = detail.boards
          .filter((b) => b.runIndex === 0 && b.street === action.street)
          .flatMap((b) => b.cards.split(" "));
        boardCards = [...boardCards, ...newCards];
      }
    }

    const seatNumber = resolveSeat(action);
    const amt = action.amount ? Number(action.amount.amount) : 0;

    switch (action.actionType) {
      case "RAISE": {
        const prev = seatNumber != null ? streetContribution[seatNumber] ?? 0 : 0;
        const delta = amt - prev;
        if (seatNumber != null) {
          stacks[seatNumber] -= delta;
          streetContribution[seatNumber] = amt;
        }
        potTotal += delta;
        break;
      }
      case "CALL":
      case "BET":
      case "ALL_IN":
      case "STRADDLE":
      case "POST_SMALL_BLIND":
      case "POST_BIG_BLIND":
      case "POST_SMALL_AND_BIG_BLIND":
      case "POST_DEAD_BLIND":
      case "POST_ANTE":
      case "POSTS":
        if (seatNumber != null) {
          stacks[seatNumber] -= amt;
          streetContribution[seatNumber] = (streetContribution[seatNumber] ?? 0) + amt;
        }
        potTotal += amt;
        break;
      case "UNCALLED_RETURN":
        if (seatNumber != null) stacks[seatNumber] += amt;
        potTotal -= amt;
        break;
      case "COLLECTED":
      case "COLLECTED_MAIN_POT":
      case "COLLECTED_SIDE_POT":
        if (seatNumber != null) stacks[seatNumber] += amt;
        potTotal -= amt;
        break;
      case "FOLD":
        if (seatNumber != null) folded.add(seatNumber);
        break;
      default:
        break;
    }

    frames.push({
      street: action.street,
      boardCards: [...boardCards],
      stacks: { ...stacks },
      potTotal,
      folded: new Set(folded),
      activeSeat: seatNumber,
      actionText: actionText(action, who(action)),
      isResult: false,
    });
  }

  const hero = detail.results.find((r) => r.isHero);
  frames.push({
    street: "SHOWDOWN",
    boardCards: [...boardCards],
    stacks: { ...stacks },
    potTotal,
    folded: new Set(folded),
    activeSeat: null,
    actionText: hero ? `Hand result: ${formatMoney(hero.netResult, true)}` : "Hand complete",
    isResult: true,
  });

  return frames;
}

function seatPosition(index: number, total: number): { left: string; top: string } {
  const cx = 50;
  const cy = 48;
  const rx = 42;
  const ry = 36;
  const theta = Math.PI / 2 + (index * 2 * Math.PI) / total;
  const left = cx + rx * Math.cos(theta);
  const top = cy + ry * Math.sin(theta);
  return { left: `${left}%`, top: `${top}%` };
}

export function HandReplayModal({ handId, onClose }: HandReplayModalProps) {
  const [detail, setDetail] = useState<HandDetailDto | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [step, setStep] = useState(0);
  const [playing, setPlaying] = useState(false);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);
    setStep(0);
    setPlaying(false);
    fetchHandDetail(handId)
      .then((d) => {
        if (!cancelled) setDetail(d);
      })
      .catch((e) => {
        if (!cancelled) setError(e instanceof ApiError ? e.body.message : "Failed to load hand");
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [handId]);

  const frames = useMemo(() => (detail ? buildFrames(detail) : []), [detail]);
  const lastIndex = Math.max(0, frames.length - 1);
  const frame = frames[Math.min(step, lastIndex)];

  useEffect(() => {
    if (!playing) return;
    if (step >= lastIndex) {
      setPlaying(false);
      return;
    }
    const t = setTimeout(() => setStep((s) => Math.min(s + 1, lastIndex)), 1100);
    return () => clearTimeout(t);
  }, [playing, step, lastIndex]);

  useEffect(() => {
    function onKeyDown(e: KeyboardEvent) {
      if (e.key === "Escape") onClose();
      if (e.key === "ArrowRight") setStep((s) => Math.min(s + 1, lastIndex));
      if (e.key === "ArrowLeft") setStep((s) => Math.max(s - 1, 0));
      if (e.key === " ") {
        e.preventDefault();
        setPlaying((p) => !p);
      }
    }
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [onClose, lastIndex]);

  const currency = detail?.stakes.bigBlind?.currency ?? detail?.totalPot?.currency ?? "USD";
  const orderedSeats: HandSeatDto[] = useMemo(() => {
    if (!detail) return [];
    const idx = detail.seats.findIndex((s) => s.isHero);
    if (idx <= 0) return detail.seats;
    return [...detail.seats.slice(idx), ...detail.seats.slice(0, idx)];
  }, [detail]);

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="modal-dialog replay-dialog" onClick={(e) => e.stopPropagation()}>
        {loading && <div className="modal-body-loading">Loading…</div>}
        {error && <div className="banner banner-error">{error}</div>}

        {detail && frame && (
          <>
            <div className="modal-header">
              <div>
                <div className="kicker">
                  Replay · {roomLabel(detail.room)} #{detail.externalHandId}
                </div>
                <h2>
                  {formatMoney(detail.stakes.smallBlind)}/{formatMoney(detail.stakes.bigBlind)} {detail.maxSeats}-max ·{" "}
                  {HAND_TYPE_LABELS[detail.handType] ?? detail.handType}
                </h2>
              </div>
              <button className="icon-button icon-button-lg" onClick={onClose} title="Close">
                <X size={15} />
              </button>
            </div>

            {detail.runItTwice && (
              <div className="banner opponent-filter-banner" style={{ margin: "0 var(--space-6)" }}>
                This hand was run twice — replay currently shows only the first run&apos;s board.
              </div>
            )}

            <div className="replay-table-wrap">
              <div className="replay-table">
                <div className="replay-center">
                  <div className="replay-board">
                    <CardBadges cards={frame.boardCards.length ? frame.boardCards : null} />
                  </div>
                  <div className="replay-pot">Pot: {formatMoney(money(frame.potTotal, currency))}</div>
                </div>

                {orderedSeats.map((seat, i) => {
                  const isActive = frame.activeSeat === seat.seatNumber;
                  const isFolded = frame.folded.has(seat.seatNumber);
                  const stack = frame.stacks[seat.seatNumber] ?? 0;
                  const pos = seatPosition(i, orderedSeats.length);
                  const classes = ["replay-seat"];
                  if (seat.isHero) classes.push("replay-seat-hero");
                  if (isActive) classes.push("replay-seat-active");
                  if (isFolded) classes.push("replay-seat-folded");
                  return (
                    <div key={seat.seatNumber} className={classes.join(" ")} style={{ left: pos.left, top: pos.top }}>
                      <div className="replay-seat-name">
                        {seat.position} · {seat.isHero ? "Hero" : seat.nickname ?? "—"}
                        {seat.seatNumber === detail.buttonSeat && <span className="replay-btn-chip">BTN</span>}
                      </div>
                      <div className="replay-seat-cards">
                        {seat.isHero ? (
                          <CardBadges cards={seat.holeCards} />
                        ) : isFolded ? (
                          <span className="placeholder-note">folded</span>
                        ) : (
                          <span className="replay-card-back" />
                        )}
                      </div>
                      <div className="replay-seat-stack">{formatMoney(money(stack, currency))}</div>
                    </div>
                  );
                })}
              </div>
            </div>

            <div className={frame.isResult ? "replay-action-banner replay-result-banner" : "replay-action-banner"}>
              {frame.actionText ?? "Hand starts"}
            </div>

            <div className="modal-footer replay-controls">
              <div style={{ display: "flex", alignItems: "center", gap: "var(--space-3)" }}>
                <button className="icon-button" onClick={() => { setStep(0); setPlaying(false); }} title="Restart">
                  <ArrowCounterClockwise size={13} />
                </button>
                <button className="icon-button" disabled={step <= 0} onClick={() => setStep((s) => Math.max(s - 1, 0))} title="Previous step">
                  <CaretLeft size={13} />
                </button>
                <button className="icon-button" onClick={() => setPlaying((p) => !p)} title={playing ? "Pause" : "Play"} disabled={step >= lastIndex}>
                  {playing ? <Pause size={13} /> : <Play size={13} />}
                </button>
                <button
                  className="icon-button"
                  disabled={step >= lastIndex}
                  onClick={() => setStep((s) => Math.min(s + 1, lastIndex))}
                  title="Next step"
                >
                  <CaretRight size={13} />
                </button>
              </div>
              <span className="placeholder-note">
                Step {Math.min(step, lastIndex) + 1} of {lastIndex + 1} · {STREET_LABELS[frame.street] ?? frame.street}
              </span>
            </div>
          </>
        )}
      </div>
    </div>
  );
}
