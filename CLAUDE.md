# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repository Status

The project is in the design phase described by `docs/00-development-roadmap.md` (currently Phase 3 — Product Design). `backend/` and `frontend/` currently hold only base scaffolding (bootstrap app, no domain code) created ahead of schedule as a technical spike; they are not yet organized around the modules described in `docs/05-product-map.md` because that doc is still incomplete. Treat any code here as scaffolding, not an implementation to extend, until the Phase 4-8 docs exist.

**Before starting any task, read `docs/11-development-tracking.md`** — it is the up-to-date, task-level checklist of what's done, in progress, or pending within each roadmap phase (the roadmap itself only tracks whole-phase status and changes rarely). After completing work, update it in the same change: check off the subtask(s) finished, and add any new follow-up items discovered along the way.

## Commands

### Backend (`backend/`, Java 17 + Spring Boot, Maven)

```
mvn compile          # compile
mvn test             # run tests
mvn spring-boot:run  # run the app locally (port 8080)
```

If `mvn` fails with a `JAVA_HOME` error, this machine has multiple JDKs installed (Zulu 8/17/21) and no default `JAVA_HOME` — prefix commands with `JAVA_HOME="/c/Program Files/Zulu/zulu-17" mvn ...` or set `JAVA_HOME` in the shell profile.

### Frontend (`frontend/`, React + TypeScript + Tauri, npm)

```
npm install       # install dependencies
npm run dev       # Vite dev server (web only, no Tauri shell)
npm run build     # tsc + vite build
npm run tauri dev # full desktop app (requires the Rust toolchain, not installed on this machine yet)
```

Known issue: the scaffolded `vite`/`rolldown` versions require Node `^20.19` or `>=22.12`; this machine has Node 18.17.1 (EOL), which makes `npm run build` crash. Upgrade Node before doing frontend work.

## Required Reading Before Making Changes

This project follows a strict **design-first** workflow (`docs/02-development-workflow.md`): documentation is written and reviewed before code is implemented, and undocumented architectural changes are not permitted. Before proposing or making any non-trivial change, read the relevant docs — they are the source of truth, not this file.

The docs form a top-down dependency chain (`docs/01-documentation-structure.md`); each depends on the one before it:

1. `docs/00-development-roadmap.md` — official phases and current status of the project
2. `docs/01-documentation-structure.md` — what each doc is responsible for
3. `docs/02-development-workflow.md` — how features are analyzed, documented, implemented and reviewed; Git workflow
4. `docs/03-product-vision.md` — why the product exists, target users, MVP scope
5. `docs/04-architecture.md` — how the system is built (components, data flow, database strategy)
6. `docs/05-product-map.md` — what the app does, module by module (currently only the "Overview" module is defined; the rest are still to be written)
7. `docs/decisions/` — ADRs; never delete or edit an accepted ADR, write a new one if a decision changes
8. `docs/11-development-tracking.md` — current task-level status; not part of the dependency chain, updated continuously (see "Repository Status" above)

Docs not yet written (per the roadmap, phases 4–8): `06-query-catalog.md`, `07-analytics-engine.md`, `08-storage-strategy.md`, `09-data-model.md`, `10-api-design.md`.

If an implementation need conflicts with what a doc says, resolve the conflict by updating the doc first — don't silently diverge from it in code.

## Project Vision

Create a modern poker tracking and analysis platform that can compete with PokerTracker and Hand2Note.

The product should provide:
- Professional poker tracking
- Advanced analytics
- Hand replay
- Modern UX
- AI-assisted insights
- Future cloud features

## Technology Stack (`docs/decisions/ADR-001-stack-selection.md`, superseded in part by ADR-003)

Backend:
- Java 17
- Spring Boot
- Packaged as an executable JAR on a bundled JRE, run as a Tauri sidecar process, talking to the frontend over local HTTP (`docs/decisions/ADR-004-backend-packaging.md`). GraalVM native-image is a deliberately deferred fallback, not the current plan.
- Internally organized as a **modular monolith** (one package per bounded module, e.g. `com.pokeranalytics.importing`, `.hands`, `.analytics`; modules only call each other through a small application-service API) following **pragmatic DDD** — no CQRS/event sourcing (`docs/decisions/ADR-002-modular-monolith.md`).

Database:
- DuckDB (analytical data: hands, actions, sessions, aggregated stats) — embedded, single-file, no server process. ADR-001 originally picked ClickHouse for this role; `docs/decisions/ADR-003-local-analytics-storage.md` replaced it with DuckDB because the actual scale is single-player/local, not multi-tenant. Don't reintroduce ClickHouse references for the local desktop path — it's still a candidate for a possible future cloud/community platform, but that's an explicitly out-of-scope, separate decision.
- SQLite (application data: preferences, config, UI state, import history)

Frontend:
- React
- TypeScript

Desktop:
- Tauri

## Architecture Principles

- Local-first architecture — the desktop app must be fully usable offline; cloud is an optional future extension, never a requirement.
- Raw hand histories are the single source of truth; every statistic, report and graph must be reproducible from them.
- Separate domain logic from infrastructure; the frontend contains no business logic.
- Statistics must be recalculable, not just stored.
- Design for future cloud synchronization without compromising the local-first model.

## Development Rules

- Do not make architectural changes without discussion; large decisions get a new ADR in `docs/decisions/` (never edit or delete an existing one).
- Git: never commit directly to `main` or `develop` — all work happens in `feature/*` branches merged via Pull Request.
- Prefer maintainable solutions over quick hacks.
- Document important technical decisions before implementing them, and update the relevant doc(s) as part of the same change — a change isn't done until its documentation matches it.
- Keep modules independent.
- Keep `docs/11-development-tracking.md` in sync with actual progress — check off subtasks as they're completed, and reflect a fully-completed phase's status back in `docs/00-development-roadmap.md`.
- After finishing any piece of work, re-review `docs/` for decisions that changed along the way (even ones not planned up front) — update the affected doc(s) and, if an accepted ADR's decision changed, add a new ADR rather than editing the old one, in the same change.

## Product Principles

- UX is a competitive advantage.
- Performance matters.
- The application should feel modern and professional.
