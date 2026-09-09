# Development Tracking

## Overview

This document is the granular, continuously-updated task tracker for the project.

`00-development-roadmap.md` defines the official phases and freezes them once accepted — it should change rarely. This document breaks each phase into concrete subtasks and tracks their status day to day. It is expected to change often, on every PR that completes a piece of work.

Question answered:

> What is the current status of the project, task by task?

---

# How to Use This File

- Every subtask maps to a deliverable from `00-development-roadmap.md` or to a follow-up item raised during review.
- Check a box (`[x]`) only when the corresponding work is merged, not just started.
- When a phase's subtasks are all checked, update that phase's `Status` in `00-development-roadmap.md` to ✅ Completed.
- Update this file in the same Pull Request that completes the task (see "Phase 6 — Documentation Update" in `02-development-workflow.md`). A task is not done until this file reflects it.
- If a subtask turns out to be wrong or unnecessary, strike it through and note why, instead of silently deleting it — this file is also a history of how the plan evolved.

Status legend:

| Symbol | Meaning |
|---|---|
| ✅ | Completed |
| 🚧 | In progress |
| ⬜ | Not started |
| 🧪 | Technical spike (exploratory, not the frozen implementation) |

---

# Phase 0 — Project Setup — ✅ Completed

- [x] GitHub repository
- [x] Branch strategy (Git Flow: `main` / `develop` / `feature/*`)
- [x] Development tools
- [x] `CLAUDE.md`
- [x] Initial documentation structure

---

# Phase 1 — Product Vision — ✅ Completed

- [x] Product vision (`03-product-vision.md`)
- [x] Product objectives
- [x] Competitive analysis
- [x] Long-term roadmap

---

# Phase 2 — Software Architecture — ✅ Completed

- [x] Technology stack decision (`decisions/ADR-001-stack-selection.md`)
- [x] Backend architecture (high-level, `04-architecture.md`)
- [x] Frontend architecture (high-level, `04-architecture.md`)
- [x] Database strategy (SQLite + DuckDB split, `04-architecture.md`)
- [x] High-level diagrams
- [x] ADR for "Modular Monolith + Pragmatic DDD" (`decisions/ADR-002-modular-monolith.md`)
- [x] ADR for local analytics storage engine (`decisions/ADR-003-local-analytics-storage.md`) — also revisits ADR-001's original ClickHouse choice, replacing it with DuckDB for the local desktop store
- [x] ADR for backend packaging/startup (`decisions/ADR-004-backend-packaging.md`)

---

# Phase 3 — Product Design — 🚧 In Progress

## 3.1 Product Map (`05-product-map.md`)

- [x] Overview module
- [x] Live Session module
- [x] Hands module
- [x] Players module
- [x] Statistics module
- [x] Reports module

## 3.2 Screen Design

- [x] Overview screen wireframe (canvas, see `12-ui-designs.md`)
- [x] Live Session screen wireframe (canvas, see `12-ui-designs.md`)
- [x] Hands screen wireframe (canvas, see `12-ui-designs.md`)
- [x] Hand Detail screen wireframe (canvas, see `12-ui-designs.md`)
- [x] Players screen wireframe (canvas, see `12-ui-designs.md`)
- [x] Session History screen wireframe (canvas, see `12-ui-designs.md`)
- [x] Reports screen wireframe (canvas, see `12-ui-designs.md`)
- [ ] Statistics screen wireframe — deferred until the PokerTracker 4 stats research follow-up below is done

**Exit criteria (from roadmap):** the complete application structure is defined — not yet met.

---

# Phase 4 — Query Catalog — ⬜ Not Started

- [ ] `06-query-catalog.md` written, one entry per screen/module

---

# Phase 5 — Analytics Engine — ⬜ Not Started

- [ ] `07-analytics-engine.md` written (facts, opportunities, events, metrics, aggregations)
- [ ] Must cover both cash game stats (BB/100, etc.) and tournament stats (ICM, bubble factor, ROI) from the start — MVP scope now explicitly includes MTTs and Spin&Go/Expresso, not just cash (see `03-product-vision.md`)

---

# Phase 6 — Storage Strategy — ⬜ Not Started

- [ ] `08-storage-strategy.md` written (engine already decided: DuckDB, see `decisions/ADR-003-local-analytics-storage.md`; this phase covers the detailed strategy on top of it — raw vs. derived data, caching, denormalization)

---

# Phase 7 — Data Model — ⬜ Not Started

- [ ] `09-data-model.md` written

---

# Phase 8 — API Design — ⬜ Not Started

- [ ] `10-api-design.md` written

---

# Phase 9 — Implementation — ⬜ Not Started (early spike done ahead of schedule)

- [x] 🧪 Backend scaffold: Spring Boot 3.3.4 + Java 17 skeleton in `backend/` (builds, one passing test)
- [x] 🧪 Frontend scaffold: Tauri + React + TypeScript in `frontend/` (generated via `create-tauri-app`, `npm install` works)
- [ ] Hand history parser — real, kept import layer built early per `decisions/ADR-005-early-import-parser.md` (ported from the MIT-licensed `HHSmithy/PokerHandHistoryParser` reference, see `THIRD-PARTY-NOTICES.md`), scoped to currently-operating rooms only, one room at a time:
  - [x] PokerStars cash (Zoom, No Limit Hold'em) — `backend/src/main/java/com/pokeranalytics/importing/parser/pokerstars/`, validated against both the reference library's own sample fixtures and 81 real hands dropped in `docs/hand-history/poker-stars/`
  - [ ] PokerStars MTT / Spin&Go — reference library has tournament-parsing code but no sample-based tests of its own for it, and no Spin&Go/Expresso jackpot-format handling at all; needs real sample hand histories to validate properly
  - [ ] Winamax cash / MTT / Spin&Go (Expresso)
  - [ ] PartyPoker cash / MTT
  - [ ] 888 (incl. "Pacific" brand) cash / MTT
  - [ ] iPoker Network cash / MTT
  - [ ] Winning Poker Network cash / MTT
- [ ] Backend project (real, following the frozen architecture/data model)
- [ ] Database setup
- [ ] Parser (PokerStars, Winamax, PartyPoker, 888, iPoker, Winning — cash + MTT + Spin&Go/Expresso)
- [ ] Import engine
- [ ] Analytics engine (cash and tournament stats)
- [ ] REST API
- [ ] Frontend (real screens, following the product map)
- [ ] Replay
- [ ] HUD — in-app live dashboards
- [ ] Testing
- [ ] Packaging
- [ ] HUD — real-time table overlay — moved to the very last task of the whole project: depends on the Statistics module (opponent stat computation) and the Players module (opponent profiles), and its legality is still unconfirmed (blocked on the ToS review and overlay feasibility spike in Follow-ups below)

> The two scaffold items are a deliberate technical spike, done out of the strict roadmap order to validate the stack. They are not the frozen Phase 9 implementation and should not be extended with real domain logic until Phases 4-8 are documented.

---

# Follow-ups / Technical Debt

Items raised during review that don't block the current phase but should be resolved before relying on them:

- [x] ~~Write missing ADRs: Modular Monolith + DDD, SQLite/ClickHouse split, backend architecture (see Phase 2)~~ — done: `ADR-002-modular-monolith.md`, `ADR-003-local-analytics-storage.md`, `ADR-004-backend-packaging.md`
- [x] ~~Fix naming drift between `01-documentation-structure.md`'s expected ADR filenames and the actual `ADR-001-stack-selection.md`~~ — done, structure doc now lists the real filenames
- [x] ~~Decide ClickHouse vs. DuckDB for local-first analytics storage before Phase 6 is written~~ — decided: DuckDB locally (`ADR-003-local-analytics-storage.md`); ClickHouse deferred to a future cloud-platform ADR, see below
- [ ] Legal/ToS review for the real-time table overlay HUD specifically against **PokerStars** and **Winamax**'s current Terms of Service before building it (Winamax has historically banned real-time HUD/datamining tools under French ANJ regulation; PokerStars restricts live overlays in several jurisdictions) — also covers opponent-data aggregation ("Community Intelligence") described in `03-product-vision.md`
- [ ] Technical spike: cross-platform feasibility of a real-time overlay rendered on top of another application's window (Windows/macOS/Linux each need different native APIs — this is outside what Tauri provides for its own window) — needed before committing to the overlay's implementation approach
- [ ] Define the monetization/pricing model — ambition is now explicitly a commercial product (see `03-product-vision.md`), but the business model itself (license, subscription, freemium cloud tier...) is still undecided
- [ ] Upgrade local Node.js to `^20.19` or `>=22.12` (`frontend/` currently fails `npm run build` on Node 18.17.1)
- [ ] Install the Rust toolchain (`rustc`/`cargo`) — required for `npm run tauri dev` / desktop builds
- [ ] Community/cloud analytics platform storage decision — deferred; only relevant if the product succeeds and that phase actually starts (explicitly optional per `03-product-vision.md`). Revisit with its own ADR then; ClickHouse remains the leading candidate given its multi-tenant/large-scale strengths.
- [ ] Players module: exact stat set for the player profile, exact predefined tag set, and exact player-list columns are still TBD — refine during screen design (3.2)
- [ ] Statistics module: PokerTracker 4 research done and an MVP stat catalog + breakdown-dimension proposal drafted in `13-statistics-module.md`; remaining before its screen design (3.2) can start: verify the doc's "not yet confirmed" stat group and resolve its Open Questions
- [ ] Reports module: PT4 research surfaced adjacent concepts worth considering when Reports' predefined templates are defined (3.2) — its "My Reports" import/export file format, and LeakTracker's benchmark-vs-winning-players deviation view (see `13-statistics-module.md`'s Future Expansion section)
- [ ] Reports module: exact set of predefined report templates is still TBD — refine during screen design (3.2)
- [ ] Implementation priority: Statistics and Reports are the most complex modules in the product map (confirmed by the PT4 research in `13-statistics-module.md`) and should be built last in Phase 9, after Overview, Live Session, Hands and Players

---

# Last Updated

2026-09-09 — Decided to port the MIT-licensed `HHSmithy/PokerHandHistoryParser` (C#) reference library into Java as the real, kept hand-history import layer, ahead of Phases 4-8 — documented as a deliberate, scoped exception in new `decisions/ADR-005-early-import-parser.md`, with `CLAUDE.md` updated to name the exception explicitly. Expanded the MVP room list in `03-product-vision.md` from PokerStars+Winamax to every currently-operating room the reference library supports (PokerStars, Winamax, PartyPoker, 888/Pacific, iPoker, Winning Poker Network) — rooms are ported and validated one at a time, PokerStars first. Added `THIRD-PARTY-NOTICES.md` for attribution.

2026-09-09 — Started the hand history parser feasibility spike: wrote a prototype parser (`com.pokeranalytics.spike.handhistory`, explicitly marked as a spike, not the frozen import module) and JUnit tests validating it against 81 real PokerStars Zoom cash hands dropped in `docs/hand-history/poker-stars/`. All 4 tests pass (`mvn test`, run with `JAVA_HOME` set to Zulu 17). Confirmed feasible for this format, including edge cases found along the way (disconnect/timeout notices interleaved in the action log, "mucks hand"). Still open: PokerStars MTT/Spin&Go and all of Winamax, pending sample hand histories.

2026-09-09 — Moved `HUD — real-time table overlay` to the very last task in Phase 9's implementation order (previously listed right before Testing/Packaging): it depends on the Statistics and Players modules, and its legality is still unconfirmed pending the ToS-review follow-up.

2026-09-09 — Noted a new follow-up: Statistics and Reports, being the most complex modules, should be built last in Phase 9 implementation order (after Overview, Live Session, Hands and Players). The `13-statistics-module.md` stat catalog's "not yet confirmed" group stays a base to work from until the user brings real PokerTracker 4 screenshots to verify/refine it.

2026-09-09 — Researched PokerTracker 4 (8 official doc pages: Quick Start, Creating My Reports, Custom Statistics Guide, Global Filters, Equity Calculator, Net Adjusted Winnings, BB/100 vs bb/100, LeakTracker) and populated `13-statistics-module.md` with a provenance-tagged Stat Catalog (PT4-confirmed / project-mandated / unconfirmed-convention), an MVP stat table (10 stats), MVP + Future breakdown dimensions (PT4's 6-category Global Filters framework), a simplified Saved Views scope, and a Future Expansion backlog (custom stat builder, Equity Calculator, ICM-based Net Adjusted Winnings, LeakTracker-style analysis). Reworded the Statistics follow-up accordingly and added a new Reports follow-up for adjacent concepts found during this research.

2026-09-09 — Created `13-statistics-module.md` as a skeleton deep-dive doc for the Statistics module's stat catalog and breakdown dimensions (Research Status, Stat Catalog, Breakdown Dimensions, Open Questions and Screen Design sections, all TODO pending the PokerTracker 4 research), registered in `01-documentation-structure.md`; `05-product-map.md`'s Statistics section now points to it instead of just deferring the detail in place.

2026-09-09 — Published Phase 3.2 screen-design canvases (Claude Design) for Overview, Live Session, Hands, Hand Detail, Players, Session History and Reports; recorded their links in new doc `12-ui-designs.md` (registered in `01-documentation-structure.md`). Statistics's screen design stays open pending the PokerTracker 4 stats research follow-up.

2026-09-09 — Reports module defined in `05-product-map.md`: Session History (log of past sessions + session detail) and Report Generation (predefined templates + exporting a saved Statistics view, PDF/CSV) as two separate sidebar entries within one module; global filters plus a date-range period; out-of-scope boundaries against Statistics, Live Session and hand editing. Also linked Live Session's existing "session history" reference to this module. Phase 3.1 (Product Map) is now complete — Phase 3 overall stays in progress pending 3.2 (Screen Design).

2026-09-09 — Statistics module defined in `05-product-map.md`: configurable stats (user-chosen columns + user-defined breakdowns, exact library TBD pending PokerTracker 4 research), saved views, single dashboard adapting to the Game Type filter (cash vs. tournament stats), global filters plus a minimum-sample-size filter, drill-down navigation into Hands, and out-of-scope boundaries against Players, Hands, Live Session and the future Reports module. Also added a general "ask before deciding" rule to `CLAUDE.md` and refreshed its stale product-map module-count note.

2026-09-09 — Players module defined in `05-product-map.md`: opponents-only scope, nickname-only player identity (aggregated across rooms), profile with stats vs. Hero + linked hands list + predefined tags/free notes, searchable player list, global filters plus a minimum-sample-size filter, and out-of-scope boundaries against Live Session, AI-assisted insights, hand editing and Hero's own stats.

2026-09-09 — Hands module defined in `05-product-map.md`: hands table (basic data + game context columns), Replay/Detail row actions, result/situation/global filters, tagging, and out-of-scope boundaries against Statistics, Players, Live Session and hand editing.

2026-09-09 — Relaxed Git workflow: direct commits to `develop` are now allowed and `feature/*` branches are optional (`main` stays PR-protected); added the rule that every commit logs a dated entry here explaining what it did. Updated in `CLAUDE.md`, `02-development-workflow.md` and `01-documentation-structure.md`.

2026-09-09 — Phase 3 started: Live Session module defined in `05-product-map.md` (in-app live dashboard half of the Live HUD goal; the real-time overlay stays out of scope for this module, tracked separately). Remaining Phase 3.1 modules (Hands, Players, Statistics, Reports) and all of 3.2 (screen design) still open.

2026-09-08 — Product vision clarified: commercial ambition, MVP rooms/formats (PokerStars + Winamax, cash/MTT/Spin&Go), Live HUD goal added (in-app dashboards + real-time overlay) with its ToS and technical-feasibility risks tracked as follow-ups.
