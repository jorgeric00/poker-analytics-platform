import { useEffect, useState } from "react";
import { ApiError, fetchSystemMeta, queryStats } from "../api/client";
import type { StatRowDto, SystemMetaResponse } from "../api/types";
import { GlobalFilters } from "../components/GlobalFilters";
import { CalendarBlank, Coins, CurrencyEur, Spade } from "@phosphor-icons/react";

// Matches Overview.dc.html (docs/12-ui-designs.md) field-for-field where the backend actually has
// the data; every metric this project can't compute yet (docs/07-analytics-engine.md's still-open
// stats: P&L, EV, Rake, Hours/Sessions Played, and anything needing DATE_BUCKET breakdown) shows
// an honest empty state instead of the mockup's illustrative numbers — see each section below.

function formatBytes(bytes: number): string {
  if (bytes === 0) return "0 B";
  const units = ["B", "KB", "MB", "GB"];
  const exponent = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), units.length - 1);
  return `${(bytes / 1024 ** exponent).toFixed(2)} ${units[exponent]}`;
}

export function OverviewPage() {
  const [meta, setMeta] = useState<SystemMetaResponse | null>(null);
  const [summary, setSummary] = useState<StatRowDto | null>(null);
  const [roomRows, setRoomRows] = useState<StatRowDto[]>([]);
  const [hasHeroData, setHasHeroData] = useState(true);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;

    async function load() {
      setLoading(true);
      setError(null);
      try {
        const metaResponse = await fetchSystemMeta();
        if (cancelled) return;
        setMeta(metaResponse);

        try {
          const [summaryResponse, roomResponse] = await Promise.all([
            queryStats({
              scope: { type: "HERO" },
              statSet: ["HANDS", "VPIP", "PFR", "STACK_SIZE_BB", "BB_100"],
              breakdown: [],
            }),
            queryStats({
              scope: { type: "HERO" },
              statSet: ["HANDS"],
              breakdown: ["ROOM"],
            }),
          ]);
          if (cancelled) return;
          setSummary(summaryResponse.rows[0] ?? null);
          setRoomRows(roomResponse.rows);
          setHasHeroData(true);
        } catch (statsError) {
          if (statsError instanceof ApiError && statsError.body.code === "NO_HERO_DATA") {
            setHasHeroData(false);
          } else {
            throw statsError;
          }
        }
      } catch (e) {
        if (!cancelled) setError(e instanceof Error ? e.message : "Failed to load overview");
      } finally {
        if (!cancelled) setLoading(false);
      }
    }

    load();
    return () => {
      cancelled = true;
    };
  }, []);

  const totalRoomHands = roomRows.reduce((sum, row) => sum + row.sampleSize, 0);

  return (
    <>
      <GlobalFilters
        pills={[
          { icon: Spade, label: "PokerStars, Winamax" },
          { icon: Coins, label: "NL25 – NL100" },
          { icon: CalendarBlank, label: "Last 12 months" },
          { icon: CurrencyEur, label: "EUR" },
        ]}
      />

      <div className="page">
        <div className="page-header">
          <div>
            <div className="kicker">Career summary</div>
            <h1>Your progress at a glance</h1>
          </div>
          {summary && (
            <div className="page-header-meta">
              {summary.sampleSize} hands
              <br />
              {meta?.lastImportAt ? `updated as of last import (${meta.lastImportAt})` : ""}
            </div>
          )}
        </div>

        {error && <div className="banner banner-error">{error}</div>}
        {loading && <p>Loading…</p>}

        {!loading && !hasHeroData && (
          <div className="panel">
            <p>
              No hands imported yet — use <a href="#/import">Import hands</a> to get started.
            </p>
          </div>
        )}

        {!loading && hasHeroData && (
          <>
            <div className="metric-grid">
              <div className="metric-card">
                <div className="metric-label">Profit / Loss</div>
                <div className="metric-value">—</div>
                <div className="metric-sub">not computed yet — needs money-valued stats</div>
              </div>
              <div className="metric-card">
                <div className="metric-label">Expected value (EV)</div>
                <div className="metric-value">—</div>
                <div className="metric-sub">needs the all-in equity engine</div>
              </div>
              <div className="metric-card">
                <div className="metric-label">Win rate</div>
                <div className={`metric-value ${Number(summary?.values.BB_100) > 0 ? "metric-value-positive" : ""}`}>
                  {summary?.values.BB_100 != null ? `${Number(summary.values.BB_100).toFixed(1)} bb/100` : "—"}
                </div>
                <div className="metric-sub">cash hands only</div>
              </div>
              <div className="metric-card">
                <div className="metric-label">Rake paid</div>
                <div className="metric-value">—</div>
                <div className="metric-sub">not computed yet</div>
              </div>
            </div>

            <div className="stat-grid">
              <div className="small-stat">
                <div className="small-stat-value">{summary?.values.HANDS ?? "—"}</div>
                <div className="metric-label">Hands played</div>
              </div>
              <div className="small-stat">
                <div className="small-stat-value">—</div>
                <div className="metric-label">Hours played</div>
              </div>
              <div className="small-stat">
                <div className="small-stat-value">—</div>
                <div className="metric-label">Sessions played</div>
              </div>
              <div className="small-stat">
                <div className="small-stat-value">—</div>
                <div className="metric-label">Hourly rate</div>
              </div>
            </div>

            <div className="charts-row">
              <div className="panel">
                <div className="panel-header">
                  <div>
                    <div className="kicker">Profit vs EV</div>
                    <div className="panel-header-note">Requires date-range breakdown</div>
                  </div>
                </div>
                <div className="chart-placeholder">
                  Not available yet — DATE_BUCKET breakdown isn't implemented
                  (docs/11-development-tracking.md)
                </div>
              </div>
              <div className="charts-column">
                <div className="panel" style={{ gap: "var(--space-3)" }}>
                  <div className="panel-header">
                    <div className="kicker">Win rate evolution</div>
                  </div>
                  <div className="chart-placeholder">Requires date-range breakdown</div>
                </div>
                <div className="panel" style={{ gap: "var(--space-3)" }}>
                  <div className="panel-header">
                    <div className="kicker">Volume</div>
                  </div>
                  <div className="chart-placeholder">Requires date-range breakdown</div>
                </div>
              </div>
            </div>

            <div className="panel" style={{ gap: "var(--space-6)" }}>
              <div className="panel-header">
                <div className="kicker">Recent activity</div>
              </div>
              <div className="activity-grid">
                <div className="activity-block">
                  <div className="metric-label">Last session</div>
                  <div className="activity-text placeholder-note">
                    Not available yet — session detection isn't implemented
                  </div>
                </div>
                <div className="activity-block">
                  <div className="metric-label">Last import</div>
                  <div className="activity-text">
                    {meta?.lastImportAt ? (
                      <>
                        {meta.lastImportAt}
                        <br />
                        {meta.lastImportRoom}
                      </>
                    ) : (
                      "never"
                    )}
                  </div>
                  <a className="drill-link" href="#/import">
                    Import more →
                  </a>
                </div>
                <div className="activity-block">
                  <div className="metric-label">Rooms in database</div>
                  <table className="mini-table">
                    <tbody>
                      {roomRows.map((row) => (
                        <tr key={row.breakdownKey?.ROOM}>
                          <td>{row.breakdownKey?.ROOM}</td>
                          <td style={{ textAlign: "right" }}>
                            {totalRoomHands > 0 ? `${Math.round((row.sampleSize / totalRoomHands) * 100)}%` : "—"}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
                <div className="activity-block">
                  <div className="metric-label">Database</div>
                  <div className="activity-text">
                    {meta ? `${formatBytes(meta.databaseSizeBytes)} · ${summary?.values.HANDS ?? 0} hands` : "—"}
                  </div>
                </div>
              </div>
            </div>
          </>
        )}
      </div>
    </>
  );
}
