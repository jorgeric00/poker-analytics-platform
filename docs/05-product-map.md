# Product Map

## Overview

This document defines the functional structure of the Poker Analytics Platform.

Its purpose is to describe the application's modules, their responsibilities and the user navigation between them.

The Product Map focuses on **what the user can do**, not **how it is implemented**.

Technical details are documented in the architecture and implementation documents.

---

# Design Principles

The application is organized around **user intentions**, not around technical components.

Each module should answer a single user question.

Examples:

- **Overview** → *How is my overall poker progress?*
- **Live Session** → *How is my current session going?*
- **Hands** → *I want to review specific hands.*
- **Players** → *I want to analyze a player.*
- **Statistics** → *I want to study my game.*

Modules should avoid overlapping responsibilities.

---

# Application Modules

## Overview

### Purpose

The Overview is the application's landing page.

Its purpose is to provide a high-level summary of the player's historical performance.

It should answer the following question:

> **"How is my overall progress as a poker player?"**

The Overview should provide an immediate understanding of the player's results and evolution.

It is intended to be read in less than **30 seconds**.

The Overview is **not** intended for deep analysis. Every element displayed should provide a quick summary and allow the user to navigate to more detailed modules.

---

### Information Displayed

#### Career Summary

High-level performance metrics calculated over the currently selected filter context.

Examples include:

- Profit / Loss
- Expected Value (EV)
- Win Rate (BB/100) *(Cash Games)*
- ROI *(Tournaments / Spins)*
- Total Rake Paid
- Hands Played
- Hours Played
- Sessions Played

The exact metrics may vary depending on the selected game type.

---

#### Performance Trends

Visual summaries of the player's long-term performance.

Examples include:

- Profit vs EV
- Profit over time
- Playing volume
- Win rate evolution

The final set of charts will be defined during the UI design phase.

---

#### Recent Activity

A compact summary of recent activity.

Examples:

- Last session
- Last import
- Recently played poker room
- Database size

---

### Global Filters

The Overview uses the application's **Global Filter Context**.

It does not define its own filters.

Possible global filters include:

- Game Type
- Poker Room
- Stakes
- Date Range
- Currency

Changing a global filter updates every metric shown in the Overview.

---

### Navigation

The Overview acts as the application's entry point.

From here, users should be able to quickly access more detailed modules, such as:

- Live Session
- Hands
- Players
- Statistics
- Reports

It can be done through a sidebar menu

---

### Out of Scope

The Overview must **not** include:

- Live session information
- HUD functionality
- Detailed statistical analysis
- Hand replay
- Opponent analysis
- Leak detection

Those features belong to dedicated modules.

---

## Live Session

### Purpose

The Live Session module shows the player how their **current** session is going while they are still playing.

It should answer the following question:

> **"How is my current session going?"**

Unlike the Overview (historical, read in under 30 seconds), this module is only relevant while a session is actively in progress. Once the session ends, it is saved into a session history.

---

### Information Displayed

#### Current Session Stats

- P&L (Profit & Loss)
- All-in Adjusted P&L (EV)
- Hands played
- VPIP % (hands played)
- PFR % (hands opened)
- 3-Bet %

> Further breakdowns (e.g. profit/loss by position) are being considered for a later iteration of this module — not part of its initial definition. Implemented this pass (`10-api-design.md`): P&L, Hands played, VPIP, PFR, 3-Bet. Not yet real: All-in Adjusted P&L (EV) — needs the hand-equity engine `07-analytics-engine.md` already flags as a separate, undesigned component, shown as an honest "—" — and the per-table breakdown/filter/P&L-over-time chart the original design canvas (`12-ui-designs.md`) shows, deliberately cut from this pass, see `decisions/ADR-010-live-session-detection.md`'s Context.

---

### Session Lifecycle

> Resolved directly with the user (2026-09-23) — the open question that had blocked this module since early Phase 9. Full rationale in `decisions/ADR-010-live-session-detection.md`.

- **Start** / **End session** buttons control the session explicitly.
- A session **auto-starts** the moment a new hand is detected while none is currently open.
- A session **auto-ends after an idle timeout** (no new hand for a configurable number of minutes, default 30) if not ended manually first.
- "Detecting a new hand" means watching the hand-history file(s) the poker client is actively writing to, in real time (currently PokerStars cash only, `app.live-session.watched-directory` config) — not waiting for a manual import.

---

### Filters

- **Active table filter** — when the player is running multiple tables at once, the view can be filtered down to a single active table. Not implemented this pass — see `decisions/ADR-010-live-session-detection.md`'s Context for the scope cut.

---

### Navigation

- Reachable from the sidebar menu; the sidebar shows a live pulsing indicator next to this entry while a session is active.
- On session end, the session is saved into the session history for later review — see Reports → Session History. **Not yet true**: Session History itself isn't built yet (`11-development-tracking.md`), so ended sessions aren't visible anywhere yet beyond this screen's own history while it was open.

---

### Out of Scope

The Live Session module must **not** include:

- The real-time table overlay (opponent stats rendered on top of the poker room's own window) — stays out of scope until its legality is studied (see the ToS risk already tracked in `11-development-tracking.md`).
- Hand replay (belongs to Hands)
- Historical trends / long-term analysis (belongs to Overview / Statistics)

---

## Hands

### Purpose

The Hands module lets the player review specific hands they've played.

It should answer the following question:

> **"I want to review specific hands."**

---

### Information Displayed

#### Hands Table

A table listing hands, one row per hand, with:

- Basic data: date/time, poker room, format, stakes, result (won/lost), amount won/lost
- Game context: hero position, hero cards, number of players in the hand, hand type (e.g. 3-bet pot, limped pot) — implemented taxonomy (`10-api-design.md`): `WALKED_POT`, `LIMPED_POT`, `RAISED_POT`, `THREE_BET_POT`, `FOUR_BET_PLUS_POT`, classified from the count of PREFLOP raises

#### Row Actions

Each row has two buttons:

- **Replay** — opens the hand replayed visually, street by street
- **Detail** — opens a more detailed view of the hand (e.g. the street-by-street action summary and other data that doesn't fit the table)

> The exact contents of the Detail view will be refined later — not part of this initial definition.

---

### Filters

- **Result & size** — won/lost, all-in or not, above a given pot/profit-loss size
- **Situation** — position, 3-bet pot, street the hand ended on
- **Global filters** — Poker Room, Stakes, Game Type, Date Range, applied to the hand list

> No filter by opponent player yet — that connects to the future Players module, not this one.

---

### Navigation

- Open **Replay** for a hand
- Open **Detail** for a hand
- Tag / mark a hand, to come back to it later

---

### Out of Scope

The Hands module must **not** include:

- Aggregated analytics (belongs to Statistics)
- Player/opponent profiles (belongs to Players)
- Hands from the session currently in progress (belongs to Live Session)
- Editing imported hands — raw hand histories are the immutable source of truth (see Architecture Principles in `CLAUDE.md`)

---

## Players

### Purpose

The Players module lets the player analyze **opponents** they've played against.

It should answer the following question:

> **"I want to analyze a player."**

This module is scoped to opponents only. Hero's own performance is not part of it — that belongs to Overview and Statistics.

---

### Information Displayed

#### Player List

A searchable, sortable list of every opponent Hero has hands against.

- A player is identified by **nickname only** — hands played under the same nickname are aggregated into a single profile, regardless of which poker room they were played on (no per-room split).
- Columns: nickname, hands played (vs. Hero), and at least one quick reference stat.

> The exact set of list columns will be refined later — not part of this initial definition.

#### Player Profile

Opening a player from the list shows their full profile:

- **Stats vs. Hero** — aggregated tendencies such as VPIP, PFR, 3-Bet %, Fold to 3-Bet, C-Bet, WTSD.

  > The exact stat set will be refined later — not part of this initial definition.

- **Hands** — the list of hands played against this player, linking into the Hands module (Replay/Detail).
- **Notes** — predefined quick tags (e.g. Nit, Maniac, Calling Station, Regular) plus free-text notes.

  > Implemented predefined set (`10-api-design.md`, sourced from the approved Players.dc.html mockup since this doc deferred the exact list): Regular, Nit, Maniac, Calling station, Folds to 3-bet, Aggressive river — plus free-form custom tags via the same `player_tags` mechanism, no predefined/custom distinction in the data.

---

### Filters

- **Minimum sample size** — a minimum-hands-played threshold, to hide players with too small a sample to be reliable.
- **Global filters** — Poker Room, Stakes, Game Type, Date Range, applied to both the player list and the stats/hands shown in a profile.

---

### Navigation

- Reachable from the sidebar menu, opening the searchable player list.
- Selecting a player in the list opens their profile.
- From a player's profile, opening a hand goes to that hand's Replay/Detail in the Hands module.

---

### Out of Scope

The Players module must **not** include:

- The real-time table overlay / HUD (belongs to Live Session).
- AI-assisted exploit suggestions (deferred to a future phase, see "AI-assisted insights" in `03-product-vision.md`).
- Editing imported hands — raw hand histories are the immutable source of truth (see Architecture Principles in `CLAUDE.md`).
- Hero's own stats/profile (belongs to Overview / Statistics).

---

## Statistics

### Purpose

The Statistics module lets the player study their own game in depth.

It should answer the following question:

> **"I want to study my game."**

Unlike the Overview (a fixed, high-level summary read in under 30 seconds), Statistics is **configurable** and detailed — built to surface information a player couldn't spot at a glance. Its design will draw on **PokerTracker 4** as a reference for which stats are worth including.

---

### Information Displayed

#### Configurable Stats

- The player chooses which stats/columns to display, picked from a stats library.
- The player defines their own breakdowns by combining dimensions (e.g. position, street, stakes, hand type).

> The exact stat library and breakdown dimensions are being researched against PokerTracker 4 and documented separately in `13-statistics-module.md` — not part of this initial definition.

#### Saved Views

A chosen combination of stats + breakdown can be saved as a named view, to reopen later without reconfiguring it.

---

### Filters

- **Minimum sample size** — a minimum-hands-played threshold, to hide breakdowns with too small a sample to be reliable.
- **Global filters** — Poker Room, Stakes, Game Type, Date Range. Game Type in particular determines which stats apply (e.g. BB/100 for Cash, ROI/ICM for Tournament/Spin & Go) — there are no separate cash/tournament views, the same dashboard adapts to the filter.

---

### Navigation

- Reachable from the sidebar menu.
- Drill-down — clicking a stat or a bar in a breakdown opens the corresponding filtered hand list in the Hands module.
- Save or load a custom view.

---

### Out of Scope

The Statistics module must **not** include:

- Opponent-specific analysis (belongs to Players — Statistics is about Hero's own game).
- Hand replay (belongs to Hands; Statistics only drills down to the filtered list, it does not replay hands).
- The real-time table overlay / HUD (belongs to Live Session).
- Exporting / printable reports (belongs to the future Reports module).

---

## Reports

### Purpose

The Reports module covers two related needs:

> **"I want to review my session history."**
>
> **"I want to generate an exportable report of my results."**

It is reachable as **two separate sidebar entries** — Session History and Reports — while remaining a single module in the product map.

---

### Information Displayed

#### Session History

A table/log of past sessions — the destination of the "session history" that the Live Session module refers to once a session ends.

Clicking a row opens that session's detail (P&L, duration, hands played, etc.).

> Implemented (`10-api-design.md`'s `GET /api/v1/sessions`/`{sessionId}`): real for every session `sessions`/`session_hands` actually has a row for, which today means live-detected sessions only (`decisions/ADR-010-live-session-detection.md`) — hands imported by a plain historical/bulk import are never assigned to a session (that retroactive grouping algorithm is a separate, still-open question, `08-storage-strategy.md`), so Session History is empty until at least one Live Session has been started and ended. Not built this pass: the P&L-over-time curve, All-in Adjusted (EV — needs the still-undesigned hand-equity engine), Rake paid (needs `hero_rake_share`, deferred since the Import Engine), per-table "best table" breakdown, and Report export from a session row (belongs to Report Generation below).

#### Report Generation

Creating and exporting a formal report. Content is defined in two combined ways:

- **Predefined templates** — e.g. tax summary, session log, performance by period.
- **A saved Statistics view** — exporting a stats + breakdown combination the user already configured in Statistics.

> The exact set of predefined templates will be refined later, during screen design (3.2) — not part of this initial definition.

Export formats: **PDF** and **CSV**.

---

### Filters

- **Global filters** — Poker Room, Stakes, Game Type.
- **Date range** — the period a report covers.

---

### Navigation

- Two sidebar entries: **Session History** and **Reports**.
- From Session History, clicking a row opens that session's detail.
- From Reports, generate/export a report from a template or from a saved Statistics view.

---

### Out of Scope

The Reports module must **not** include:

- Interactive/configurable dashboards (belongs to Statistics — Reports only exports what's already calculated or a saved view).
- Live session data (belongs to Live Session).
- Editing imported hands — raw hand histories are the immutable source of truth (see Architecture Principles in `CLAUDE.md`).

---