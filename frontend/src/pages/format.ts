import type { MoneyDto } from "../api/types";

const CURRENCY_SYMBOLS: Record<string, string> = { EUR: "€", USD: "$", GBP: "£" };

export function formatMoney(money: MoneyDto | null | undefined, signed = false): string {
  if (!money) return "—";
  const symbol = CURRENCY_SYMBOLS[money.currency] ?? money.currency + " ";
  const value = Number(money.amount);
  const abs = Math.abs(value).toFixed(2);
  if (value < 0) return `-${symbol}${abs}`;
  if (signed && value > 0) return `+${symbol}${abs}`;
  return `${symbol}${abs}`;
}

export const HAND_TYPE_LABELS: Record<string, string> = {
  WALKED_POT: "Walked",
  LIMPED_POT: "Limped pot",
  RAISED_POT: "Raised pot",
  THREE_BET_POT: "3-bet pot",
  FOUR_BET_PLUS_POT: "4-bet+ pot",
};

export const STREET_LABELS: Record<string, string> = {
  PREFLOP: "Preflop",
  FLOP: "Flop",
  TURN: "Turn",
  RIVER: "River",
  SHOWDOWN: "Showdown",
};

const ROOM_LABELS: Record<string, string> = {
  POKERSTARS: "PokerStars",
  WINAMAX: "Winamax",
  PARTYPOKER: "PartyPoker",
  POKER888: "888poker",
  IPOKER: "iPoker",
  WINNINGPOKER: "Winning Poker",
};

export function roomLabel(room: string): string {
  return ROOM_LABELS[room] ?? room;
}

/** Backend timestamps are DuckDB's own TIMESTAMP text ("2026-09-23 02:35:15.57962"), not strict
 *  ISO — normalize just enough for the Date constructor to parse it reliably cross-browser. */
export function parseDbTimestamp(raw: string | null | undefined): Date | null {
  if (!raw) return null;
  const iso = raw.replace(" ", "T").replace(/(\.\d{3})\d*$/, "$1");
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? null : date;
}

export function formatShortDateTime(raw: string | null | undefined): string {
  const date = parseDbTimestamp(raw);
  if (!date) return "—";
  const datePart = date.toLocaleDateString(undefined, { month: "short", day: "numeric" });
  const timePart = date.toLocaleTimeString(undefined, { hour: "2-digit", minute: "2-digit", hour12: false });
  return `${datePart} · ${timePart}`;
}

export function formatDurationMinutes(minutes: number | null | undefined): string {
  if (minutes == null) return "—";
  const h = Math.floor(minutes / 60);
  const m = Math.round(minutes % 60);
  return h > 0 ? `${h}h ${m}m` : `${m}m`;
}
