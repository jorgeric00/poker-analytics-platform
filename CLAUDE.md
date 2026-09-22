# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repository Status

The project is described by `docs/00-development-roadmap.md` (Phase 3 — Product Design is functionally done pending the deferred Statistics screen wireframe; Phases 4-8 — Query Catalog, Analytics Engine, Storage Strategy, Data Model, API Design — are all done, so the design-first gate that used to freeze most of `backend/` no longer applies). Phase 9 — Implementation has started: `backend/src/main/java/com/pokeranalytics/importing` (parsing, see Exception below, plus a new `importing.engine` subpackage — the Import Engine that persists parsed hands into the schema, PokerStars cash only so far) and `backend/src/main/java/com/pokeranalytics/storage` (the two embedded databases — DuckDB + SQLite, Flyway-migrated per `docs/09-data-model.md`) are both real, kept code, following the now-frozen design docs. Everything else backend-side (REST layer, the Analytics Engine, importing the other five rooms) is not yet built — treat its absence as "not started," not as "intentionally frozen." `frontend/` is still only the base scaffold from the original technical spike (`create-tauri-app`, no domain code) — not yet organized around `docs/05-product-map.md`'s modules.

**Exception:** `backend/src/main/java/com/pokeranalytics/importing/model` and `.../importing/parser` (including its per-room subpackages) are real, kept domain code — see `docs/decisions/ADR-005-early-import-parser.md`. This exception is scoped strictly to hand-history *parsing*, predating the rest of Phase 9 by design (see ADR-005's own rationale for building the parser ahead of the doc chain). Its parsing behavior and test fixtures are ported from the MIT-licensed `HHSmithy/PokerHandHistoryParser`; keep `THIRD-PARTY-NOTICES.md` (repo root) current if more of that reference is ported. `TournamentSummary`/`WinamaxTournamentSummaryParser` extend this same exception to a second, non-hand-history Winamax document (buy-in/prizepool/finish-place/winnings) — see `docs/decisions/ADR-008-tournament-summary-parser.md`; this one isn't ported from the reference library, it's reverse-engineered from real sample files.

Each room lives in its own subpackage (`importing/parser/pokerstars`, `.../winamax`, `.../partypoker`, `.../poker888`, `.../ipoker`, `.../winningpoker`) as a standalone `final class` with static `parse(String)` / `parseFile(Path)` methods returning `List<ParsedHand>` — there is no shared parser interface/base class across rooms yet. All rooms produce the same shared output types in `importing/model` (`ParsedHand`, `HandAction`, `Seat`, `Street`, `SummarySeat`, `HandFormat`). Not every room/format is validated the same way: `docs/decisions/ADR-007-port-tournament-formats-without-fixtures.md` relaxes ADR-005's fixture-validation bar per format (reference fixtures / public real sample text / no automated test at all) where the upstream reference library has no tournament fixtures — check a parser's class-level doc and `docs/11-development-tracking.md` for which tier a given room/format actually has before trusting it.

**Before starting any task, read `docs/11-development-tracking.md`** — it is the up-to-date, task-level checklist of what's done, in progress, or pending within each roadmap phase (the roadmap itself only tracks whole-phase status and changes rarely). After completing work, update it in the same change: check off the subtask(s) finished, and add any new follow-up items discovered along the way.

## Commands

### Backend (`backend/`, Java 25 + Spring Boot, Maven)

```
mvn compile                     # compile
mvn test                        # run tests
mvn test -Dtest=ClassName       # run a single test class (e.g. PokerAnalyticsApplicationTests)
mvn spring-boot:run             # run the app locally (port 8080)
```

If `mvn` fails with a `JAVA_HOME` error, this machine has multiple JDKs installed (Zulu 8/17/21/25) and no default `JAVA_HOME` — prefix commands with `JAVA_HOME="/c/Program Files/Zulu/zulu-25" mvn ...` (the backend targets Java 25, see `decisions/ADR-006-java-25.md`) or set `JAVA_HOME` in the shell profile.

### Frontend (`frontend/`, React + TypeScript + Tauri, npm)

```
npm install       # install dependencies
npm run dev       # Vite dev server (web only, no Tauri shell)
npm run build     # tsc + vite build
npm run tauri dev # full desktop app (requires the Rust toolchain)
```

Node.js `24.19.0` (LTS) and the Rust toolchain (via `rustup`, `1.98.1`) are installed on this machine. If a freshly-opened terminal doesn't see `rustc`/`cargo` on `PATH`, start a new shell — `rustup`'s installer only updates `PATH` for new processes.

No `test` or `lint` script exists yet in `package.json` — don't assume or invent one.

## Required Reading Before Making Changes

This project follows a strict **design-first** workflow (`docs/02-development-workflow.md`): documentation is written and reviewed before code is implemented, and undocumented architectural changes are not permitted. Before proposing or making any non-trivial change, read the relevant docs — they are the source of truth, not this file.

The docs form a top-down dependency chain (`docs/01-documentation-structure.md`); each depends on the one before it:

1. `docs/00-development-roadmap.md` — official phases and current status of the project
2. `docs/01-documentation-structure.md` — what each doc is responsible for
3. `docs/02-development-workflow.md` — how features are analyzed, documented, implemented and reviewed; Git workflow
4. `docs/03-product-vision.md` — why the product exists, target users, MVP scope
5. `docs/04-architecture.md` — how the system is built (components, data flow, database strategy)
6. `docs/05-product-map.md` — what the app does, module by module (written incrementally, one module at a time; see `docs/11-development-tracking.md` for which modules are currently defined)
7. `docs/06-query-catalog.md` — every logical query the application needs, organized by reusable family rather than one entry per screen
8. `docs/07-analytics-engine.md` — how each statistic is derived (Facts → Opportunities → Events → Metrics → Aggregations), independent of the database
9. `docs/08-storage-strategy.md` — what's physically stored vs. computed at query time, on top of the already-decided DuckDB/SQLite split
10. `docs/09-data-model.md` — the real DuckDB/SQLite schema (tables, columns, keys)
11. `docs/10-api-design.md` — the REST/JSON contract between frontend and backend
12. `docs/decisions/` — ADRs; never delete or edit an accepted ADR, write a new one if a decision changes
13. `docs/11-development-tracking.md` — current task-level status; not part of the dependency chain, updated continuously (see "Repository Status" above)
14. `docs/12-ui-designs.md` — screen wireframe canvas links from Phase 3.2 Screen Design; depends on `05-product-map.md`
15. `docs/13-statistics-module.md` — deep-dive on the Statistics module's stat catalog and breakdown dimensions (too detailed for `05-product-map.md`); depends on `05-product-map.md`, feeds into `07-analytics-engine.md`

Docs 06 through 10 (query catalog, analytics engine, storage strategy, data model, API design — roadmap Phases 4-8) are all written. Remaining docs before Phase 9 Implementation can formally start: none — the design-phase chain is complete except for Phase 3.2's deferred Statistics screen wireframe (see Repository Status above).

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

## Technology Stack (`docs/decisions/ADR-001-stack-selection.md`, superseded in part by ADR-003 and ADR-006)

Backend:
- Java 25 (`docs/decisions/ADR-006-java-25.md`)
- Spring Boot (3.5.16+ — earlier 3.3.x doesn't support Java 25's class file format, see ADR-006)
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
- Git: never commit directly to `main` — merge into it via Pull Request. Direct commits to `develop` are allowed; `feature/*` branches are optional, for isolating larger or riskier changes.
- Prefer maintainable solutions to quick hacks.
- Document important technical decisions before implementing them, and update the relevant doc(s) as part of the same change — a change isn't done until its documentation matches it.
- Keep modules independent.
- Keep `docs/11-development-tracking.md` in sync with actual progress — check off subtasks as they're completed, and reflect a fully-completed phase's status back in `docs/00-development-roadmap.md`.
- Every commit adds a new entry (dated) to the "Last Updated" section of `docs/11-development-tracking.md` explaining what that commit did.
- When writing or rewriting a module/screen in `docs/05-product-map.md`, always ask the user for that screen's specifications first (what it shows, its filters, its actions/navigation, what's out of scope) — don't draft it unprompted from the product vision alone.
- More generally: whenever a task involves any doubt, ambiguity, or a decision about scope/design/approach — not just product-map specs — ask the user before proceeding, rather than assuming.
- After finishing any piece of work, re-review `docs/` for decisions that changed along the way (even ones not planned up front) — update the affected doc(s) and, if an accepted ADR's decision changed, add a new ADR rather than editing the old one, in the same change.

## Product Principles

- UX is a competitive advantage.
- Performance matters.
- The application should feel modern and professional.
