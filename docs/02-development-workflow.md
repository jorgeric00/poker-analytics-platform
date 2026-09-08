# Development Workflow

## Overview

This document defines the official development workflow for the Poker Analytics Platform.

The objective is to maintain a predictable, well-documented and scalable development process.

The project follows a **design-first** methodology.

Implementation begins only after the corresponding design has been documented and reviewed.

---

# Core Principles

The project follows these principles:

- Design before implementation.
- Documentation before code.
- Small incremental changes.
- Freeze architectural decisions once accepted.
- Keep documentation synchronized with implementation.
- Avoid premature optimization.

---

# Standard Development Workflow

Every new feature follows the same process.

```
Idea

↓

Analysis

↓

Documentation

↓

Architecture Decision (ADR if needed)

↓

Implementation

↓

Testing

↓

Documentation Update

↓

Pull Request

↓

Code Review

↓

Merge
```

---

# Phase 1 — Analysis

Understand the problem.

Questions:

- Why is this feature needed?
- What problem does it solve?
- Which modules are affected?
- Does it impact the architecture?

No code should be written during this phase.

---

# Phase 2 — Documentation

Document the solution before implementation.

Depending on the feature, update:

- Product documentation
- Architecture
- Query Catalog
- Analytics Engine
- Storage Strategy
- Data Model
- API Design

---

# Phase 3 — Architecture Decisions

If the feature introduces an important architectural decision:

Create a new ADR.

Examples:

- New storage strategy
- New parser architecture
- Cloud synchronization
- Plugin system

Small implementation details do not require ADRs.

---

# Phase 4 — Implementation

Implementation should follow the documented design.

Rules:

- Keep commits focused.
- Avoid mixing unrelated changes.
- Follow the existing architecture.
- Reuse existing modules whenever possible.

---

# Phase 5 — Testing

Every feature should be tested.

Testing may include:

- Unit tests
- Integration tests
- Manual testing
- Performance validation

---

# Phase 6 — Documentation Update

Implementation is not complete until documentation is updated.

Possible updates include:

- Architecture diagrams
- Data model
- API documentation
- ADRs
- Development guides
- `docs/11-development-tracking.md` — check off the subtask(s) this change completes

---

# Progress Tracking

The roadmap (`00-development-roadmap.md`) defines phases and freezes them once accepted. It is not meant to change often, so it cannot answer "what's left to do this week" on its own.

`docs/11-development-tracking.md` exists for that: it breaks every phase into concrete subtasks and tracks which are done, in progress, or not started.

Rules:

- Every Pull Request that completes a subtask updates `11-development-tracking.md` in the same PR (part of Phase 6 above).
- When every subtask of a phase is checked, update that phase's `Status` in `00-development-roadmap.md` to reflect it.
- Unplanned exploratory work (spikes) is still logged there, marked 🧪, so it's clear it is not the frozen implementation for that phase.
- Before starting work, check this file first to see current status and avoid duplicating or contradicting work in progress.

---

# Git Workflow

The project follows a Git Flow inspired strategy.

```
main

↑

develop

↑

feature/*
```

Rules:

- Never commit directly to `main`.
- Never commit directly to `develop`.
- All work is performed in `feature/*` branches.
- Every merge requires a Pull Request.

---

# Commit Guidelines

Commits should be:

- Small
- Focused
- Descriptive

Examples:

```
feat(parser): add PokerStars parser

fix(replay): correct pot calculation

refactor(storage): simplify repository layer

docs(architecture): update backend diagram
```

---

# Pull Request Guidelines

Each Pull Request should:

- Have a clear objective.
- Be limited in scope.
- Update documentation when required.
- Pass all tests.

---

# Documentation Policy

Documentation should always match the implementation.

If code changes but documentation does not:

The task is not considered complete.

---

# Architecture Freeze

Major architectural phases are considered frozen once accepted.

They should only be modified when:

- A new business requirement appears.
- A technical limitation is discovered.
- A better solution is clearly justified.

Frequent redesigns should be avoided.

---

# AI Collaboration

AI assistants are considered development partners.

Before implementing code, AI should use the available documentation to understand:

- Product vision
- Architecture
- Storage strategy
- Data model
- Existing decisions

If implementation conflicts with the documentation:

The documentation should be reviewed before changing the code.

---

# Project Philosophy

The project prioritizes:

1. Correct architecture.
2. Clean code.
3. Maintainability.
4. Performance.
5. User experience.

Fast implementation should never compromise long-term quality.

The goal is to build a professional poker analytics platform capable of evolving over many years.