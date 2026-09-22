// Shared between HandsPage and HandDetailModal — renders poker notation ("Ah", "Td") as a
// rank + colored-suit pair matching the approved Nocturne mockups' card badge styling.

const SUITS: Record<string, { symbol: string; red: boolean }> = {
  h: { symbol: "♥", red: true },
  d: { symbol: "♦", red: true },
  c: { symbol: "♣", red: false },
  s: { symbol: "♠", red: false },
};

export interface ParsedCard {
  rank: string;
  suitSymbol: string;
  red: boolean;
}

export function parseCard(card: string): ParsedCard {
  const rank = card.slice(0, -1);
  const suitLetter = card.slice(-1).toLowerCase();
  const suit = SUITS[suitLetter] ?? { symbol: suitLetter, red: false };
  return { rank, suitSymbol: suit.symbol, red: suit.red };
}
