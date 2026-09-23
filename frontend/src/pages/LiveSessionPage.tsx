import { useEffect, useState } from "react";
import { Broadcast, Play, Stop } from "@phosphor-icons/react";
import { ApiError, fetchLiveSessionStatus, startLiveSession, stopLiveSession } from "../api/client";
import type { LiveSessionStatusDto } from "../api/types";
import { formatMoney } from "./format";

// Matches Live Session.dc.html (docs/12-ui-designs.md), read via the Claude Design MCP — but that
// mockup only designs the *active* state (an "End session" button, no Start, and a header that
// assumes a session is already running). Per docs/decisions/ADR-010-live-session-detection.md,
// this screen also needs a genuine empty state with a Start button, added here to match the design
// system but not literally present in the mockup. Deliberately NOT built this pass, per the ADR's
// own explicit scope cut: the per-table breakdown, the P&L-over-time chart, and All-in Adjusted
// (EV) — EV is a real "—" because the hand-equity engine behind it doesn't exist anywhere yet.
const POLL_INTERVAL_MS = 5000;

function formatDuration(startedAt: string | null): string {
  if (!startedAt) return "—";
  // Backend sends DuckDB's own TIMESTAMP text ("2026-09-23 02:35:15.57962"), not strict ISO —
  // normalize just enough for the Date constructor to parse it reliably cross-browser.
  const iso = startedAt.replace(" ", "T").replace(/(\.\d{3})\d*$/, "$1");
  const started = new Date(iso).getTime();
  if (Number.isNaN(started)) return "—";
  const elapsedSec = Math.max(0, Math.floor((Date.now() - started) / 1000));
  const h = Math.floor(elapsedSec / 3600);
  const m = Math.floor((elapsedSec % 3600) / 60);
  const s = elapsedSec % 60;
  return `${h}:${String(m).padStart(2, "0")}:${String(s).padStart(2, "0")}`;
}

export function LiveSessionPage() {
  const [status, setStatus] = useState<LiveSessionStatusDto | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [, forceTick] = useState(0);

  useEffect(() => {
    let cancelled = false;
    function load() {
      fetchLiveSessionStatus()
        .then((s) => {
          if (!cancelled) setStatus(s);
        })
        .catch((e) => {
          if (!cancelled) setError(e instanceof ApiError ? e.body.message : "Failed to load live session status");
        })
        .finally(() => {
          if (!cancelled) setLoading(false);
        });
    }
    load();
    const interval = setInterval(load, POLL_INTERVAL_MS);
    return () => {
      cancelled = true;
      clearInterval(interval);
    };
  }, []);

  // Re-render every second so the running-time clock ticks even between polls.
  useEffect(() => {
    const clock = setInterval(() => forceTick((t) => t + 1), 1000);
    return () => clearInterval(clock);
  }, []);

  async function handleStart() {
    setBusy(true);
    try {
      setStatus(await startLiveSession());
    } catch (e) {
      setError(e instanceof ApiError ? e.body.message : "Failed to start session");
    } finally {
      setBusy(false);
    }
  }

  async function handleStop() {
    setBusy(true);
    try {
      setStatus(await stopLiveSession());
    } catch (e) {
      setError(e instanceof ApiError ? e.body.message : "Failed to stop session");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="page">
      <div className="page-header">
        <div>
          <div className="kicker">Live Session</div>
          <h1>
            {status?.active ? (
              <span style={{ display: "inline-flex", alignItems: "center", gap: "var(--space-3)" }}>
                <span className="live-dot" />
                Session in progress
              </span>
            ) : (
              "No session in progress"
            )}
          </h1>
        </div>
        <div style={{ display: "flex", alignItems: "center", gap: "var(--space-4)" }}>
          <span className="page-header-meta">
            Real-time detection needs a configured watched folder (see docs/decisions/ADR-010-live-session-detection.md)
          </span>
          {status?.active ? (
            <button className="btn-quiet" onClick={handleStop} disabled={busy}>
              <Stop size={13} /> End session
            </button>
          ) : (
            <button className="btn-outline" onClick={handleStart} disabled={busy || loading}>
              <Play size={13} /> Start session
            </button>
          )}
        </div>
      </div>

      {error && <div className="banner banner-error">{error}</div>}
      {loading && <p className="placeholder-note">Loading…</p>}

      {!loading && status && !status.active && (
        <div className="panel">
          <p style={{ margin: 0 }}>
            <Broadcast size={16} style={{ verticalAlign: "-2px", marginRight: 6 }} />
            No live session right now. Click <strong>Start session</strong>, or one will start automatically the
            moment a new hand is detected in the watched folder (if configured).
          </p>
        </div>
      )}

      {!loading && status?.active && (
        <>
          <div className="metric-grid">
            <div className="metric-card">
              <div className="metric-label">Running time</div>
              <div className="metric-value">{formatDuration(status.startedAt)}</div>
              <div className="metric-sub">started {status.startedAt?.split(" ")[1]?.slice(0, 5) ?? "—"}</div>
            </div>
            <div className="metric-card">
              <div className="metric-label">Hands played</div>
              <div className="metric-value">{status.hands}</div>
            </div>
            <div className="metric-card">
              <div className="metric-label">Profit / Loss</div>
              <div
                className={
                  "metric-value" +
                  (status.pnl && Number(status.pnl.amount) > 0 ? " metric-value-positive" : "")
                }
              >
                {formatMoney(status.pnl, true)}
              </div>
            </div>
            <div className="metric-card">
              <div className="metric-label">All-in adjusted (EV)</div>
              <div className="metric-value">—</div>
              <div className="metric-sub">needs the all-in equity engine</div>
            </div>
          </div>

          <div className="panel">
            <div className="panel-header">
              <div className="kicker">Preflop tendencies this session</div>
              <span className="panel-header-note">Sample: {status.hands} hands</span>
            </div>
            <div className="tendencies-grid">
              <TendencyStat label="VPIP — hands played" value={status.vpip} />
              <TendencyStat label="PFR — hands opened" value={status.pfr} />
              <TendencyStat label="3-Bet" value={status.threeBet} />
            </div>
          </div>

          <p className="placeholder-note" style={{ margin: 0 }}>
            When you end the session it&apos;s saved to Session History — not built yet, see
            docs/11-development-tracking.md.
          </p>
        </>
      )}
    </div>
  );
}

function TendencyStat({ label, value }: { label: string; value: number | null }) {
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
    </div>
  );
}
