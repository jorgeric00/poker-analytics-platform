# Product Map

## Overview

This document defines the functional structure of the Poker Analytics Platform.

Its purpose is to describe the application's modules, their responsibilities and the user navigation between them.

The Product Map focuses on **what the user can do**, not **how it is implemented**.

Technical details are documented in the architecture and implementation documents.

---

# Design Principles

The application is organized around **user intentions**, not around technical components.

Each module should answer a single user question.

Examples:

- **Overview** → *How is my overall poker progress?*
- **Live Session** → *How is my current session going?*
- **Hands** → *I want to review specific hands.*
- **Players** → *I want to analyze a player.*
- **Statistics** → *I want to study my game.*

Modules should avoid overlapping responsibilities.

---

# Application Modules

## Overview

### Purpose

The Overview is the application's landing page.

Its purpose is to provide a high-level summary of the player's historical performance.

It should answer the following question:

> **"How is my overall progress as a poker player?"**

The Overview should provide an immediate understanding of the player's results and evolution.

It is intended to be read in less than **30 seconds**.

The Overview is **not** intended for deep analysis. Every element displayed should provide a quick summary and allow the user to navigate to more detailed modules.

---

### Information Displayed

#### Career Summary

High-level performance metrics calculated over the currently selected filter context.

Examples include:

- Profit / Loss
- Expected Value (EV)
- Win Rate (BB/100) *(Cash Games)*
- ROI *(Tournaments / Spins)*
- Total Rake Paid
- Hands Played
- Hours Played
- Sessions Played

The exact metrics may vary depending on the selected game type.

---

#### Performance Trends

Visual summaries of the player's long-term performance.

Examples include:

- Profit vs EV
- Profit over time
- Playing volume
- Win rate evolution

The final set of charts will be defined during the UI design phase.

---

#### Recent Activity

A compact summary of recent activity.

Examples:

- Last session
- Last import
- Recently played poker room
- Database size

---

### Global Filters

The Overview uses the application's **Global Filter Context**.

It does not define its own filters.

Possible global filters include:

- Game Type
- Poker Room
- Stakes
- Date Range
- Currency

Changing a global filter updates every metric shown in the Overview.

---

### Navigation

The Overview acts as the application's entry point.

From here, users should be able to quickly access more detailed modules, such as:

- Live Session
- Hands
- Players
- Statistics
- Reports

It can be done through a sidebar menu

---

### Out of Scope

The Overview must **not** include:

- Live session information
- HUD functionality
- Detailed statistical analysis
- Hand replay
- Opponent analysis
- Leak detection

Those features belong to dedicated modules.

---

## Live Session

### Purpose

The Live Session module shows the player how their **current** session is going while they are still playing.

It should answer the following question:

> **"How is my current session going?"**

Unlike the Overview (historical, read in under 30 seconds), this module is only relevant while a session is actively in progress. Once the session ends, it is saved into a session history.

---

### Information Displayed

#### Current Session Stats

- P&L (Profit & Loss)
- All-in Adjusted P&L (EV)
- Hands played
- VPIP % (hands played)
- PFR % (hands opened)
- 3-Bet %

> Further breakdowns (e.g. profit/loss by position) are being considered for a later iteration of this module — not part of its initial definition.

---

### Filters

- **Active table filter** — when the player is running multiple tables at once, the view can be filtered down to a single active table.

---

### Navigation

- Reachable from the sidebar menu.
- On session end, the session is saved into a session history for later review.

---

### Out of Scope

The Live Session module must **not** include:

- The real-time table overlay (opponent stats rendered on top of the poker room's own window) — stays out of scope until its legality is studied (see the ToS risk already tracked in `11-development-tracking.md`).
- Hand replay (belongs to Hands)
- Historical trends / long-term analysis (belongs to Overview / Statistics)

---

## Hands

### Purpose

The Hands module lets the player review specific hands they've played.

It should answer the following question:

> **"I want to review specific hands."**

---

### Information Displayed

#### Hands Table

A table listing hands, one row per hand, with:

- Basic data: date/time, poker room, format, stakes, result (won/lost), amount won/lost
- Game context: hero position, hero cards, number of players in the hand, hand type (e.g. 3-bet pot, limped pot)

#### Row Actions

Each row has two buttons:

- **Replay** — opens the hand replayed visually, street by street
- **Detail** — opens a more detailed view of the hand (e.g. the street-by-street action summary and other data that doesn't fit the table)

> The exact contents of the Detail view will be refined later — not part of this initial definition.

---

### Filters

- **Result & size** — won/lost, all-in or not, above a given pot/profit-loss size
- **Situation** — position, 3-bet pot, street the hand ended on
- **Global filters** — Poker Room, Stakes, Game Type, Date Range, applied to the hand list

> No filter by opponent player yet — that connects to the future Players module, not this one.

---

### Navigation

- Open **Replay** for a hand
- Open **Detail** for a hand
- Tag / mark a hand, to come back to it later

---

### Out of Scope

The Hands module must **not** include:

- Aggregated analytics (belongs to Statistics)
- Player/opponent profiles (belongs to Players)
- Hands from the session currently in progress (belongs to Live Session)
- Editing imported hands — raw hand histories are the immutable source of truth (see Architecture Principles in `CLAUDE.md`)

---