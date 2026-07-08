# Poker Analytics Platform - Development Roadmap

## Overview

This document defines the official development roadmap for the Poker Analytics Platform.

The purpose of this roadmap is to ensure that the project follows a structured design-first approach. Every architectural decision should be completed and documented before implementation begins.

The roadmap is considered the **single source of truth** for the project's development phases.

---

# Guiding Principles

The project follows these principles:

- Design before implementation.
- Freeze architectural decisions before moving forward.
- Avoid premature optimization.
- Build the product around user workflows.
- Design the storage model based on queries, not entities.
- Keep documentation synchronized with implementation.

Once a phase is completed and accepted, it should only be revisited if a major requirement changes or an architectural issue is discovered.

---

# Development Phases

## Phase 0 — Project Setup

### Objectives

Prepare the project infrastructure.

### Deliverables

- GitHub repository
- Branch strategy
- Development tools
- CLAUDE.md
- Initial documentation structure

### Status

✅ Completed

---

## Phase 1 — Product Vision

### Objectives

Define the product vision and business goals.

### Deliverables

- Product vision
- Product objectives
- Competitive analysis
- Long-term roadmap

### Status

✅ Completed

---

## Phase 2 — Software Architecture

### Objectives

Define the complete technical architecture.

### Deliverables

- Technology stack
- Backend architecture
- Frontend architecture
- Database strategy
- High-level diagrams
- Architectural Decision Records (ADRs)

### Decisions

- Java
- Spring Boot
- React
- TypeScript
- Tauri
- ClickHouse
- SQLite
- Modular Monolith
- Pragmatic Domain-Driven Design (DDD)

### Status

✅ Completed

---

# Phase 3 — Product Design

## Goal

Design the product before designing the data model.

The objective is to understand exactly what the user can do inside the application.

No implementation details are discussed during this phase.

---

## 3.1 Product Map

Define:

- Application modules
- Navigation
- Main sections
- Relationships between modules

Output:

`docs/03-product-map.md`

---

## 3.2 Screen Design

For every screen define:

- Purpose
- Information displayed
- User actions
- Navigation flow

Simple wireframes are encouraged.

---

### Exit Criteria

The complete application structure is defined.

---

# Phase 4 — Query Catalog

## Goal

Identify every query required by the application.

Each query should include:

- Name
- Description
- Filters
- Frequency
- Target latency
- Expected result

Output:

`docs/04-query-catalog.md`

---

### Exit Criteria

Every screen has an associated list of queries.

---

# Phase 5 — Analytics Engine

## Goal

Design the poker analytics engine independently from the database.

Define:

- Poker facts
- Opportunities
- Events
- Metrics
- Aggregations
- Statistical calculations

This phase defines **how statistics work**, not how they are stored.

Output:

`docs/05-analytics-engine.md`

---

### Exit Criteria

Every statistic can be derived from the defined analytics model.

---

# Phase 6 — Storage Strategy

## Goal

Design how information is stored.

Topics:

- Raw data
- Derived data
- Aggregates
- Caching
- Denormalization
- ClickHouse optimization
- Update strategy

Questions answered:

- What is stored?
- What is calculated?
- What is cached?
- What is duplicated?
- What is immutable?

Output:

`docs/06-storage-strategy.md`

---

### Exit Criteria

The storage strategy is completely defined.

---

# Phase 7 — Data Model

## Goal

Transform the storage strategy into a database schema.

Define:

- Domain entities
- Database tables
- Columns
- Relationships
- Primary keys
- Ordering keys
- Partitions
- Materialized views (if required)

Output:

`docs/07-data-model.md`

---

### Exit Criteria

The complete database model is defined.

---

# Phase 8 — API Design

## Goal

Design the communication between frontend and backend.

Define:

- API endpoints
- DTOs
- Requests
- Responses
- Error handling

Output:

`docs/08-api-design.md`

---

### Exit Criteria

The frontend can be implemented independently.

---

# Phase 9 — Implementation

## Goal

Implement the application.

Suggested implementation order:

1. Backend project
2. Database
3. Parser
4. Import engine
5. Analytics engine
6. REST API
7. Frontend
8. Replay
9. HUD
10. Testing
11. Packaging

---

# Phase Freeze Policy

Each phase is considered **frozen** once completed.

A frozen phase should only be modified if:

- A new business requirement appears.
- A critical architectural flaw is discovered.
- A design decision becomes technically unfeasible.

This policy minimizes unnecessary redesigns and keeps the project stable.

---

# Success Criteria

The project will prioritize:

- Maintainability
- Scalability
- Performance
- Clear architecture
- Excellent user experience
- Long-term evolution

Every technical decision should support these objectives.