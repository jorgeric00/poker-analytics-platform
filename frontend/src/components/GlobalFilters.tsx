import type { Icon } from "@phosphor-icons/react";
import { ArrowCounterClockwise, CaretDown } from "@phosphor-icons/react";

export interface FilterPillSpec {
  icon: Icon;
  label: string;
}

interface GlobalFiltersProps {
  pills: FilterPillSpec[];
}

// Matches the mockups' "Global filters" bar (docs/12-ui-designs.md's canvases). Visual only for
// now — docs/06-query-catalog.md's Global Filter Parameters (Room, Stakes, Game Type, Date Range,
// Currency) aren't accepted by POST /api/v1/stats/query yet (see docs/10-api-design.md /
// CashStatsCalculator's own scope notes), so none of these controls actually filter anything —
// they render the approved design without pretending to be wired up.
export function GlobalFilters({ pills }: GlobalFiltersProps) {
  return (
    <div className="global-filters">
      <div className="global-filters-controls">
        <span className="kicker">Global filters</span>
        <div className="segmented">
          <span className="on">Cash</span>
          <span>MTT</span>
          <span>Spin &amp; Go</span>
          <span>All</span>
        </div>
        {pills.map((pill) => (
          <div className="filter-pill" key={pill.label}>
            <pill.icon size={12} weight="regular" />
            {pill.label}
            <CaretDown size={12} weight="regular" />
          </div>
        ))}
      </div>
      <div className="filter-pill filter-pill-reset">
        <ArrowCounterClockwise size={12} weight="regular" />
        Reset
      </div>
    </div>
  );
}
