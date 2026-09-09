# Poker Analytics Platform - Product Vision

## 1. Vision

Create a next-generation poker analytics platform that combines the power of traditional poker trackers with modern UX, advanced analytics and AI-powered insights.

The goal is to build a professional tool that helps poker players understand their game, identify weaknesses and make better decisions.

---

# 2. Problem Statement

Current poker tracking software provides powerful statistics but has several limitations:

- Outdated user interfaces.
- Complex configuration.
- Steep learning curve.
- Limited analytical capabilities.
- Little automation.
- Lack of actionable insights.
- Limited cross-device experience.

Players have access to enormous amounts of data but extracting useful conclusions still requires significant manual effort.

---

# 3. Target Users

## Primary users

Professional and semi-professional poker players:

- Cash game players.
- Tournament players.
- Regular online grinders.
- Players who currently use tracking software.

Characteristics:

- Play thousands of hands per month.
- Care about improving performance.
- Understand poker statistics.
- Are willing to pay for tools that provide an advantage.

## Business Ambition

This is intended to become a real commercial product, not a personal tool or a portfolio project — the explicit goal is to compete with PokerTracker and Hand2Note in the market.

### Monetization Model

Subscription-only, billed monthly — no permanent free tier.

- **Basic**: full core tracking/analytics across every supported room (see "Target Poker Rooms & Formats" below) — stats, reports, replay. Limited by hand-import volume and by history retention window (exact caps for both still TBD).
- **Pro**: everything in Basic without those limits, plus AI-assisted game analysis and further capabilities to be defined over time as the product matures.

The subscription is validated periodically online (e.g. on app start), with an offline grace period in between checks, so day-to-day use (importing, analyzing, playing) keeps working without an active internet connection — consistent with the Local First principle in `04-architecture.md`. The exact validation cadence, grace-period length, and the licensing/entitlement mechanism itself still need their own technical design, before Phase 8 (API Design) and the Future Cloud Platform work.

Exact price points for each tier are not yet decided.

---

# 4. Product Goals

## Main goals

### 1. Best-in-class user experience

Create a modern alternative to traditional poker trackers.

Focus on:

- Clean interface.
- Fast navigation.
- Powerful visualizations.
- Simple workflows.

---

### 2. Advanced poker analytics

Provide deeper analysis:

- Player statistics.
- Leak detection.
- Session analysis.
- Position analysis.
- Situation-based filters.

---

### 3. Hand analysis and replay

Allow players to:

- Browse previous hands.
- Replay hands visually.
- Analyze decisions.
- Filter specific situations.

---

### 4. Live HUD & Real-Time Assistance

Give players relevant information while they are actually playing, not only after the session.

Two complementary pieces:

- **In-app live dashboards**: dedicated screens inside the desktop app showing live session stats, separate from the poker room's own window.
- **Real-time table overlay**: opponent statistics displayed directly on top of the poker room's table window while playing, in the style of traditional HUDs (PokerTracker/Hand2Note).

> ⚠️ Known risk: several poker rooms restrict or prohibit real-time HUD overlays in their Terms of Service. Winamax in particular has historically banned real-time HUD/datamining tools (French ANJ regulation); PokerStars has also restricted live overlays in multiple jurisdictions. This is tracked as an open risk to validate against each target room's current ToS before building the overlay (see `11-development-tracking.md`) — it does not block earlier phases, but it can constrain or reshape this goal once investigated.
>
> The overlay is also a distinct technical problem from the rest of the app: it renders on top of a *different* application's window, which requires OS-native APIs (transparent/always-on-top windows on Windows, Accessibility API + NSWindow overlays on macOS, X11/Wayland on Linux) rather than what Tauri provides out of the box for its own window.

---

### 5. AI-powered coaching

Transform raw statistics into actionable information.

Examples:

- Detect leaks.
- Explain losing trends.
- Suggest areas for improvement.
- Summarize sessions.

---

### 6. Community intelligence

Create a system where aggregated anonymous data can provide better opponent insights.

Possible future capabilities:

- Larger statistical samples.
- Advanced player profiles.
- Global tendencies.

---

# 5. Product Principles

## Local first

The player's computer is the primary source of data.

Benefits:

- Performance.
- Privacy.
- Offline usage.
- Lower infrastructure costs.

---

## Data ownership

Users own their raw hand histories.

Cloud services should add value without requiring users to upload everything.

---

## Performance

The application must handle:

- Millions of hands.
- Large databases.
- Complex analytical queries.

---

## Extensibility

The system should support:

- Multiple poker rooms.
- Future game formats.
- New statistics.
- New analysis features.

---

# 6. Initial Scope (MVP)

The first version should focus on replacing the core functionality of existing trackers.

## Target Poker Rooms & Formats (MVP)

- **Rooms**: PokerStars, Winamax, PartyPoker, 888 (including its historical "Pacific" brand), iPoker Network, and Winning Poker Network — every currently-operating room covered by the reference parser this project's import layer is based on (see `decisions/ADR-005-early-import-parser.md`).
- **Formats**: Cash games, MTTs (tournaments), and Spin&Go / Expresso.

This is a broad MVP surface (6 rooms × 3 formats) — the parser and analytics engine both need to handle all of it from the start, including tournament-specific analytics (ICM, bubble factor), not just cash game stats. Room support is being built and validated one room at a time (see `11-development-tracking.md`); PokerStars cash is the first fully validated against real data.

## Included

- Import hand histories.
- Store hands locally.
- Basic statistics (cash and tournament).
- Graphs.
- Filters.
- Hand replay.
- Live HUD: in-app live dashboards, and a real-time table overlay (see Product Goal 4 — the overlay carries an open ToS risk being tracked, not yet a blocker).

## Not included initially

- Cloud synchronization.
- Mobile application.
- Community database.
- AI coaching.

---

# 7. Long Term Vision

The platform evolves into a complete poker intelligence system:

Desktop application:

- Tracking.
- Analysis.
- Replay.

Cloud:

- Synchronization.
- Dashboards.
- Intelligence.

AI:

- Coaching.
- Recommendations.
- Automated analysis.

Community:

- Aggregated player intelligence.

---

# 8. Competitive Advantage

The product should differentiate through:

1. Modern UX.
2. Better analytics.
3. AI-powered insights.
4. Local-first architecture.
5. Community intelligence.