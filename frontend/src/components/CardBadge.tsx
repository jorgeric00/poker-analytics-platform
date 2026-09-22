import { parseCard } from "../pages/cards";

export function CardBadge({ card }: { card: string }) {
  const { rank, suitSymbol, red } = parseCard(card);
  return (
    <span className="card-badge">
      {rank}
      <span className={red ? "card-badge-red" : undefined}>{suitSymbol}</span>
    </span>
  );
}

export function CardBadges({ cards }: { cards: string[] | null }) {
  if (!cards || cards.length === 0) {
    return <span className="placeholder-note">—</span>;
  }
  return (
    <span style={{ display: "inline-flex", gap: 4 }}>
      {cards.map((c) => (
        <CardBadge key={c} card={c} />
      ))}
    </span>
  );
}
