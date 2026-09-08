# Documentation Structure

## Overview

This document defines the documentation structure of the Poker Analytics Platform.

Documentation is considered a first-class part of the project.

Every architectural decision, design choice and implementation strategy should be documented before implementation whenever possible.

The documentation should always reflect the current state of the project.

---

# Documentation Principles

The project follows these principles:

- Documentation is part of the source code.
- Every document has a single responsibility.
- Documentation should evolve together with the implementation.
- Design decisions must be documented before implementation.
- Large architectural decisions should be recorded as ADRs.
- Documentation should be understandable by both developers and AI assistants.

---

# Documentation Structure

```
docs/

00-development-roadmap.md

01-documentation-structure.md
02-development-workflow.md

03-product-vision.md
04-architecture.md

05-product-map.md
06-query-catalog.md
07-analytics-engine.md
08-storage-strategy.md
09-data-model.md
10-api-design.md
11-development-tracking.md

decisions/

ADR-001-stack-selection.md
ADR-002-modular-monolith.md
ADR-003-local-analytics-storage.md
ADR-004-backend-packaging.md
```

---

# Document Responsibilities

## 00-development-roadmap.md

Defines the official development phases of the project.

Question answered:

> What is the development plan?

---

## 01-documentation-structure.md

Defines how documentation is organized.

Question answered:

> How is the documentation organized?

---

## 02-development-workflow.md

Defines the project's development process.

Question answered:

> How should we work?

---

## 03-product-vision.md

Defines the business vision of the project.

Question answered:

> Why are we building this product?

---

## 04-architecture.md

Defines the high-level software architecture.

Question answered:

> How is the system built?

---

## 05-product-map.md

Defines the product structure and user navigation.

Question answered:

> What features does the application provide?

---

## 06-query-catalog.md

Lists every query required by the application.

Question answered:

> What information does every screen require?

---

## 07-analytics-engine.md

Defines how poker statistics are calculated.

Question answered:

> How does the analytics engine work?

---

## 08-storage-strategy.md

Defines how information is stored.

Question answered:

> How should data be persisted?

---

## 09-data-model.md

Defines the database schema.

Question answered:

> How is information represented?

---

## 10-api-design.md

Defines communication between frontend and backend.

Question answered:

> How do the application layers communicate?

---

## 11-development-tracking.md

Breaks each roadmap phase into concrete subtasks and tracks their status (done / in progress / not started). Unlike the other documents, it is not frozen once written — it is expected to change on every commit that completes a piece of work, whether that commit lands via a Pull Request or directly on `develop`.

Question answered:

> What is the current status of the project, task by task?

---

# Architectural Decision Records

The `decisions/` folder contains Architecture Decision Records (ADRs).

Each ADR documents:

- Context
- Decision
- Motivation
- Consequences

ADRs should never be deleted.

If a decision changes, a new ADR should be created explaining the change.

---

# Documentation Rules

## Single Responsibility

Each document should answer a single question.

Avoid mixing unrelated topics.

---

## Living Documentation

Documentation must evolve together with the project.

When implementation changes:

- Update the documentation.
- Update related diagrams.
- Update ADRs if necessary.

---

## Stable Numbering

Document numbers should remain stable.

If new documents are added, append them after the existing ones.

Avoid renumbering existing documents.

---

## English Language

All technical documentation should be written in English.

Reasons:

- Better compatibility with AI tools.
- Consistency with source code.
- Easier collaboration in the future.

---

# Relationship Between Documents

The documentation follows a top-down approach:

```
Roadmap

↓

Vision

↓

Architecture

↓

Product Design

↓

Queries

↓

Analytics

↓

Storage

↓

Data Model

↓

API

↓

Implementation
```

Each document depends on the previous one.

Implementation should always follow the documented design.

`11-development-tracking.md` sits outside this chain: it is a cross-cutting document, updated continuously alongside every other phase rather than produced once and frozen.

---

# Documentation Quality

Good documentation should be:

- Clear
- Concise
- Up to date
- Easy to navigate
- Easy to maintain

Documentation is considered part of the project's architecture.