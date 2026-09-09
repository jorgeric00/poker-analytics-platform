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
- [ ] Statistics module
- [ ] Reports module

## 3.2 Screen Design

- [ ] Wireframes / screen definitions for each module above

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
- [ ] Hand history parser spike (validate feasibility against PokerStars and Winamax — cash, MTT and Spin&Go/Expresso formats — before Phases 4-8 are frozen)
- [ ] Backend project (real, following the frozen architecture/data model)
- [ ] Database setup
- [ ] Parser (PokerStars + Winamax, cash + MTT + Spin&Go/Expresso)
- [ ] Import engine
- [ ] Analytics engine (cash and tournament stats)
- [ ] REST API
- [ ] Frontend (real screens, following the product map)
- [ ] Replay
- [ ] HUD — in-app live dashboards
- [ ] HUD — real-time table overlay (blocked on the ToS review and overlay feasibility spike above)
- [ ] Testing
- [ ] Packaging

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

---

# Last Updated

2026-09-09 — Players module defined in `05-product-map.md`: opponents-only scope, nickname-only player identity (aggregated across rooms), profile with stats vs. Hero + linked hands list + predefined tags/free notes, searchable player list, global filters plus a minimum-sample-size filter, and out-of-scope boundaries against Live Session, AI-assisted insights, hand editing and Hero's own stats.

2026-09-09 — Hands module defined in `05-product-map.md`: hands table (basic data + game context columns), Replay/Detail row actions, result/situation/global filters, tagging, and out-of-scope boundaries against Statistics, Players, Live Session and hand editing.

2026-09-09 — Relaxed Git workflow: direct commits to `develop` are now allowed and `feature/*` branches are optional (`main` stays PR-protected); added the rule that every commit logs a dated entry here explaining what it did. Updated in `CLAUDE.md`, `02-development-workflow.md` and `01-documentation-structure.md`.

2026-09-09 — Phase 3 started: Live Session module defined in `05-product-map.md` (in-app live dashboard half of the Live HUD goal; the real-time overlay stays out of scope for this module, tracked separately). Remaining Phase 3.1 modules (Hands, Players, Statistics, Reports) and all of 3.2 (screen design) still open.

2026-09-08 — Product vision clarified: commercial ambition, MVP rooms/formats (PokerStars + Winamax, cash/MTT/Spin&Go), Live HUD goal added (in-app dashboards + real-time overlay) with its ToS and technical-feasibility risks tracked as follow-ups.
