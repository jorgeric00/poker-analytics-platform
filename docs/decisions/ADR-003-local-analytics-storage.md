# ADR-003: Local Analytics Storage — DuckDB

## Status

Accepted

## Context

The application needs a local database fast enough to store and query statistics computed from extracted hand history data — the core need is speed for analytical calculations (aggregations, filters, leaderboards over hands/sessions/actions), at the scale of a single player's data (from opponent tracking, actions taken across the hands the user has played: on the order of a few million hands, not billions).

ADR-001 originally selected ClickHouse for this role. ClickHouse is built for distributed, server-based deployments handling very large multi-tenant datasets; it does not have a mature embedded mode, its mutations (updates/deletes) are heavyweight and asynchronous, and running it means bundling and operating a database server process inside a desktop application. That operational cost buys scale the local, single-user use case does not need.

The product vision (`03-product-vision.md`) explicitly lists cloud synchronization and community-driven intelligence as **not included initially**, and only worth building if the desktop product succeeds and gains users — so this decision is scoped to local storage only. It intentionally does not design a cloud/multi-tenant storage architecture now.

## Decision

Use **DuckDB** as the local analytical database, replacing ClickHouse from ADR-001.

DuckDB is an embedded, columnar OLAP engine — effectively "SQLite for analytics": a single file, no server process to install or manage, a vectorized execution engine built for fast aggregations and filters over millions of rows on a single machine. This matches the actual local-first requirement directly.

SQLite remains the application database (config, preferences, UI state, import history) as already decided in `04-architecture.md` — this ADR only changes the analytics engine, not the two-database split itself. Both databases are now embedded, single-file, and require no server process, which also simplifies packaging the desktop app with Tauri.

Raw hand histories remain the single source of truth (per `04-architecture.md`); all statistics stored in DuckDB must stay reproducible from them, independent of which engine stores the derived data.

## Consequences

- Removes the operational complexity of packaging/running a ClickHouse server on end-user machines.
- DuckDB's mutation/update support is much better suited to a single-user desktop workload (e.g. reprocessing hands after a parser fix, deleting an imported session) than ClickHouse's.
- If the community/cloud platform described in the product vision is ever built, it will face a genuinely different scale problem (potentially very large multi-tenant datasets, shared across many users). That is a separate decision, out of scope here — it gets its own ADR if and when that phase actually starts. ClickHouse (or another distributed OLAP engine) remains a strong candidate for that future context; this ADR does not rule it out, it only removes it from the local desktop path.
- `04-architecture.md` is updated alongside this ADR to reflect DuckDB as the local analytics engine.
