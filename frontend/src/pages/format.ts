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
