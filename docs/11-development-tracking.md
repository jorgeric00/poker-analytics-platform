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
- [x] Database strategy (SQLite + ClickHouse split, `04-architecture.md`)
- [x] High-level diagrams
- [ ] ADR for "Modular Monolith + Pragmatic DDD" (decision is listed in the roadmap but has no ADR yet)
- [ ] `ADR-002-sqlite-clickhouse.md` (referenced in `01-documentation-structure.md`, not yet written)
- [ ] `ADR-003-backend-architecture.md` (referenced in `01-documentation-structure.md`, not yet written)

> Phase is marked Completed in the roadmap, but the three missing ADRs above are tracked as follow-up debt — see "Follow-ups" below.

---

# Phase 3 — Product Design — 🚧 In Progress

## 3.1 Product Map (`05-product-map.md`)

- [x] Overview module
- [ ] Live Session module
- [ ] Hands module
- [ ] Players module
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

---

# Phase 6 — Storage Strategy — ⬜ Not Started

- [ ] `08-storage-strategy.md` written
- [ ] Re-evaluate ClickHouse vs. DuckDB for the embedded/local analytics store (raised during architecture review, not yet decided)

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
- [ ] Hand history parser spike (validate feasibility against 1-2 real poker rooms before Phases 4-8 are frozen)
- [ ] Backend project (real, following the frozen architecture/data model)
- [ ] Database setup
- [ ] Parser
- [ ] Import engine
- [ ] Analytics engine
- [ ] REST API
- [ ] Frontend (real screens, following the product map)
- [ ] Replay
- [ ] HUD
- [ ] Testing
- [ ] Packaging

> The two scaffold items are a deliberate technical spike, done out of the strict roadmap order to validate the stack. They are not the frozen Phase 9 implementation and should not be extended with real domain logic until Phases 4-8 are documented.

---

# Follow-ups / Technical Debt

Items raised during review that don't block the current phase but should be resolved before relying on them:

- [ ] Write missing ADRs: Modular Monolith + DDD, SQLite/ClickHouse split, backend architecture (see Phase 2)
- [ ] Fix naming drift between `01-documentation-structure.md`'s expected ADR filenames and the actual `ADR-001-stack-selection.md`
- [ ] Decide ClickHouse vs. DuckDB for local-first analytics storage before Phase 6 is written
- [ ] Legal/ToS review for opponent-data aggregation ("Community Intelligence") and real-time HUD features described in `03-product-vision.md`
- [ ] Upgrade local Node.js to `^20.19` or `>=22.12` (`frontend/` currently fails `npm run build` on Node 18.17.1)
- [ ] Install the Rust toolchain (`rustc`/`cargo`) — required for `npm run tauri dev` / desktop builds

---

# Last Updated

2026-09-08 — Initial version, created alongside the `backend/`/`frontend/` scaffold spike.
