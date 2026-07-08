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