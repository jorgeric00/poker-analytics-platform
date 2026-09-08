# ADR-002: Modular Monolith + Pragmatic DDD

## Status

Accepted

## Context

The backend must support many capabilities over time — hand history import, parsing, storage, statistics/analytics, replay, HUD data, and eventually optional cloud synchronization — while shipping as a single desktop application process alongside Tauri on Windows, macOS and Linux.

A microservices split is not viable here: the backend runs on the end user's machine as part of a desktop product, not as a distributed system with independent deployment targets. At the same time, an undifferentiated single-layer application (one big `service`/`repository` package) does not scale well as more modules are added — it tends to accumulate hidden coupling between unrelated concerns (e.g. the parser reaching into analytics internals).

## Decision

The backend is built as a **modular monolith**: one deployable Spring Boot application, internally organized by module rather than by technical layer.

- Packages are structured by module, one per bounded concern, e.g. `com.pokeranalytics.importing`, `.hands`, `.analytics`, `.players`, `.replay`. The exact set of modules will track the modules defined in `05-product-map.md` and the data flow in `04-architecture.md` as those are finalized.
- Each module exposes a small application-service API for other modules to call; domain and infrastructure classes inside a module are not accessed directly from outside it.
- Domain modeling follows **pragmatic DDD**: entities and value objects represent real poker concepts (`Hand`, `Action`, `Session`, `Player`, ...), and application services orchestrate use cases on top of them. This project does not adopt full DDD ceremony (no CQRS, no event sourcing, no aggregate-per-microservice) — the goal is a clear domain vocabulary and separation from infrastructure, not maximal process rigor.

## Consequences

- Module boundaries are enforced by convention and code review only for now, not by tooling. This is a reasonable trade-off while the team is small; if the codebase or team grows enough that boundary violations become a real recurring problem, add `ArchUnit` tests to enforce them mechanically.
- Because it's a single deployable, module boundaries can be tightened or reorganized without a distributed-systems migration — this keeps the option to extract a module into a separate service later, without paying for that flexibility today.
- Domain logic staying framework-agnostic (per `04-architecture.md`'s "Separation of Responsibilities" principle) is what keeps this modular monolith from decaying into a layered ball of mud as modules are added.
