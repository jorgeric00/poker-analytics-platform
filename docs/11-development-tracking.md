# Development Tracking

## Overview

This document is the granular, continuously-updated task tracker for the project.

`00-development-roadmap.md` defines the official phases and freezes them once accepted — it should change rarely. This document breaks each phase into concrete subtasks and tracks their status day to day. It is expected to change often, on every PR that completes a piece of work.

Question answered:

> What is the current status of the project, task by task?

---

# How to Use This File

- Every subtask maps to a deliverable from `00-development-roadmap.md` or to a follow-up item raised during review.
- Check a box (`[x]`) only when the corresponding work is merged, not just started.
- When a phase's subtasks are all checked, update that phase's `Status` in `00-development-roadmap.md` to ✅ Completed.
- Update this file in the same Pull Request that completes the task (see "Phase 6 — Documentation Update" in `02-development-workflow.md`). A task is not done until this file reflects it.
- If a subtask turns out to be wrong or unnecessary, strike it through and note why, instead of silently deleting it — this file is also a history of how the plan evolved.

Status legend:

| Symbol | Meaning |
|---|---|
| ✅ | Completed |
| 🚧 | In progress |
| ⬜ | Not started |
| 🧪 | Technical spike (exploratory, not the frozen implementation) |

---

# Phase 0 — Project Setup — ✅ Completed

- [x] GitHub repository
- [x] Branch strategy (Git Flow: `main` / `develop` / `feature/*`)
- [x] Development tools
- [x] `CLAUDE.md`
- [x] Initial documentation structure

---

# Phase 1 — Product Vision — ✅ Completed

- [x] Product vision (`03-product-vision.md`)
- [x] Product objectives
- [x] Competitive analysis
- [x] Long-term roadmap

---

# Phase 2 — Software Architecture — ✅ Completed

- [x] Technology stack decision (`decisions/ADR-001-stack-selection.md`)
- [x] Backend architecture (high-level, `04-architecture.md`)
- [x] Frontend architecture (high-level, `04-architecture.md`)
- [x] Database strategy (SQLite + DuckDB split, `04-architecture.md`)
- [x] High-level diagrams
- [x] ADR for "Modular Monolith + Pragmatic DDD" (`decisions/ADR-002-modular-monolith.md`)
- [x] ADR for local analytics storage engine (`decisions/ADR-003-local-analytics-storage.md`) — also revisits ADR-001's original ClickHouse choice, replacing it with DuckDB for the local desktop store
- [x] ADR for backend packaging/startup (`decisions/ADR-004-backend-packaging.md`)

---

# Phase 3 — Product Design — 🚧 In Progress

## 3.1 Product Map (`05-product-map.md`)

- [x] Overview module
- [x] Live Session module
- [x] Hands module
- [x] Players module
- [x] Statistics module
- [x] Reports module

## 3.2 Screen Design

- [x] Overview screen wireframe (canvas, see `12-ui-designs.md`)
- [x] Live Session screen wireframe (canvas, see `12-ui-designs.md`)
- [x] Hands screen wireframe (canvas, see `12-ui-designs.md`)
- [x] Hand Detail screen wireframe (canvas, see `12-ui-designs.md`)
- [x] Players screen wireframe (canvas, see `12-ui-designs.md`)
- [x] Session History screen wireframe (canvas, see `12-ui-designs.md`)
- [x] Reports screen wireframe (canvas, see `12-ui-designs.md`)
- [ ] Statistics screen wireframe — deferred until the PokerTracker 4 stats research follow-up below is done

**Exit criteria (from roadmap):** the complete application structure is defined — not yet met.

---

# Phase 4 — Query Catalog — ⬜ Not Started

- [ ] `06-query-catalog.md` written, one entry per screen/module

---

# Phase 5 — Analytics Engine — ⬜ Not Started

- [ ] `07-analytics-engine.md` written (facts, opportunities, events, metrics, aggregations)
- [ ] Must cover both cash game stats (BB/100, etc.) and tournament stats (ICM, bubble factor, ROI) from the start — MVP scope now explicitly includes MTTs and Spin&Go/Expresso, not just cash (see `03-product-vision.md`)

---

# Phase 6 — Storage Strategy — ⬜ Not Started

- [ ] `08-storage-strategy.md` written (engine already decided: DuckDB, see `decisions/ADR-003-local-analytics-storage.md`; this phase covers the detailed strategy on top of it — raw vs. derived data, caching, denormalization)

---

# Phase 7 — Data Model — ⬜ Not Started

- [ ] `09-data-model.md` written

---

# Phase 8 — API Design — ⬜ Not Started

- [ ] `10-api-design.md` written

---

# Phase 9 — Implementation — ⬜ Not Started (early spike done ahead of schedule)

- [x] 🧪 Backend scaffold: Spring Boot 3.5.16 + Java 25 skeleton in `backend/` (builds, one passing test — see `decisions/ADR-006-java-25.md` for the version history)
- [x] 🧪 Frontend scaffold: Tauri + React + TypeScript in `frontend/` (generated via `create-tauri-app`, `npm install` works)
- [ ] Hand history parser — real, kept import layer built early per `decisions/ADR-005-early-import-parser.md` (ported from the MIT-licensed `HHSmithy/PokerHandHistoryParser` reference, see `THIRD-PARTY-NOTICES.md`), scoped to currently-operating rooms only, one room at a time:
  - [x] PokerStars cash — `backend/src/main/java/com/pokeranalytics/importing/{model,parser/pokerstars}/`, validated against both 62 ported reference sample fixtures (128 hands: side pots, run-it-twice, antes, Cap games, Hold'em/Omaha/Omaha Hi-Lo, Fixed/Pot/No Limit, multi-currency, weird player names) and 81 real Zoom hands kept in `backend/src/main/resources/hand-history/poker-stars/cash/` — 8 JUnit tests, all green
  - [x] PokerStars regular tournaments (MTT/SNG) — same `PokerStarsParser` class, extended rather than split out (the tournament action/street/summary grammar turned out nearly identical to the cash grammar already ported: ante posts, "and is all-in" suffixes, numbered side pots, the summary section all just work as-is). Per `decisions/ADR-007-port-tournament-formats-without-fixtures.md`, validated with **weaker-than-usual evidence**: the reference library ships no tournament sample fixtures at all (for any room), so this was instead validated against 2 real, verbatim PokerStars tournament hands published on Wikipedia's "Hand history" article (a knockout hand with a bounty/finish-place line, and an older multi-way-all-in hand with numbered side pots), cross-checked against a further real ante-posting example quoted in arXiv:2312.11753 — kept at `backend/src/test/resources/hand-history/pokerstars/Tournament/`, 3 JUnit tests, all green. New: `HandActionType.WINS_BOUNTY`/`TOURNAMENT_FINISH`, Roman-numeral level parsing. **Known gap:** no signal in the hand-history text distinguishes a regular MTT from a 3-max Spin&Go (the reference library has the same limitation — its own format detection is hardcoded to "SitAndGo" for anything non-cash); every non-cash PokerStars hand is classified as `HandFormat.TOURNAMENT` until a real Spin&Go sample turns up
  - [ ] PokerStars Spin&Go — see gap noted above; needs a real sample hand history to find whatever distinguishes it from a regular MTT in the text (if anything)
  - [x] Winamax cash — `backend/src/main/java/com/pokeranalytics/importing/parser/winamax/`, validated against 25 ported reference sample fixtures (34 hands: dead-money blinds posted "out of position", heads-up seats carrying both the small-blind and button tags at once, all-ins, showdowns/mucks, the summary "Board:" line overriding street-marker board text) — 5 JUnit tests, all green. Not yet validated against real Winamax cash hands (none available yet — `backend/src/main/resources/hand-history/winamax/cash/` exists, ready for them — see follow-up below)
  - [x] Winamax tournaments and Expresso (Winamax's own Spin&Go-style format) — `WinamaxTournamentParser`, distinct from the cash parser above (chip-only blind/ante amounts, seats can carry a real-money knockout bounty, no space between the two bracket groups on turn/river lines). A parsed hand's new `ParsedHand.format` field (`HandFormat.TOURNAMENT` vs `EXPRESSO`) tells the two apart — set from whether the room's own tournament name is literally "Expresso". Validated against real 2025 hands kept in `backend/src/main/resources/hand-history/winamax/expresso/` (1 file) and `backend/src/main/resources/hand-history/winamax/mtt/` (3 regular knockout tournaments — "TRIDENT SPACE KO", "ROTATION", "SPACE KO"): 68 hands total, antes, bounties, main/side pot splits, table reseating — 6 JUnit tests, all green. The `_summary.txt` files alongside them are a different, non-hand-history document (end-of-tournament results: buy-in breakdown, level structure, finishing place) and are out of scope for this parser — see follow-up below. No sample exhibited a bounty-collected-on-elimination announcement line, so that isn't recognized either.
  - [x] PartyPoker cash — `backend/src/main/java/com/pokeranalytics/importing/parser/partypoker/`, validated against 40 ported reference sample fixtures (53 hands: side pots, Omaha Hi/Lo low-hand reveals, all-ins, dead-blind re-entries, players joining/leaving/sitting out and time-bank messages mid-hand, table chat, multi-hand exports wrapped in "#Game No"/"Game # starts." bookkeeping) — 6 JUnit tests, all green. A meaningfully different grammar from PokerStars/Winamax: no colon after the player name in action lines, street markers only ever show their own new card(s) (never the already-dealt board), and there's no summary section at all — `totalPot` is computed by summing every "wins" line (a real, traceable value), and `rake`/`summarySeats` are left unset since the room simply never states them. Added `HandActionType.ALL_IN` (its own verb here, not a bet/call/raise suffix) and `POST_DEAD_BLIND` (a late/re-entering player's catch-up post, distinct from the hand's real big blind). Not yet validated against real PartyPoker hands (none available yet)
  - [ ] PartyPoker MTT — not portable at all right now: the reference library has no tournament support for this room whatsoever (`ParsePokerFormat` is hardcoded to always return `CashGame`, `ParseTournamentId` throws `NotImplementedException`), confirmed while surveying scope for `decisions/ADR-007-port-tournament-formats-without-fixtures.md`. Blocked on a different source entirely (real PartyPoker tournament hand histories to reverse-engineer from scratch), not on validation data for an existing port
  - [x] 888 (incl. "Pacific" brand) cash — `backend/src/main/java/com/pokeranalytics/importing/parser/poker888/`, validated against 35 ported reference sample fixtures (35 hands: multiple white-label brand headers sharing the same format — "888poker", "Cassava", "LuckyAcePoker.com" — Omaha Hi/Lo split pots, dead-blind and catch-up big-blind postings, Jackpot tables, an incomplete/no-summary hand) — 8 JUnit tests, all green. Not yet validated against real 888 hands (none available yet — see follow-up below)
  - [ ] 888 MTT — same situation as PartyPoker MTT above: the reference library has no tournament-parsing support at all for this room either, confirmed the same way
  - [ ] iPoker Network cash / MTT
  - [x] Winning Poker Network cash — `backend/src/main/java/com/pokeranalytics/importing/parser/winningpoker/`, validated against 40 ported reference sample fixtures (49 hands: player names containing spaces/periods/hyphens/parentheses, straddles, dead-blind catch-up posts, all-ins, an anonymized empty-name system line, mucked-but-won hands with and without revealed cards) — 7 JUnit tests, all green. Needed a genuinely different technique from every other room so far, not just different regexes: action lines put no delimiter between the player name and the verb ("Player Cellar door folds"), so names are resolved via a first pass over the "Seat N: name (stack)." lines building a known-name list (longest match first) before any action line is parsed — the summary section doesn't have this problem, since its lines each end in a distinctive fixed phrase a plain regex can anchor on directly. New `HandActionType.POSTS` (the room's own generic "posts (N)", used both for a dead blind's non-tagged follow-up post and for late-entry catch-up posts) and `STRADDLE`. No side pots at all — neither the reference nor any fixture shows one for this room; every summary is a single aggregate "Pot: N. Rake M" line. Not yet validated against real Winning Poker Network hands (none available yet)
  - [ ] Winning Poker Network MTT — same situation as PartyPoker/888 MTT above: the reference library has no tournament-parsing support at all for this room either
- [ ] Backend project (real, following the frozen architecture/data model)
- [ ] Database setup
- [ ] Parser (PokerStars, Winamax, PartyPoker, 888, iPoker, Winning — cash + MTT + Spin&Go/Expresso)
- [ ] Import engine
- [ ] Analytics engine (cash and tournament stats)
- [ ] REST API
- [ ] Frontend (real screens, following the product map)
- [ ] Replay
- [ ] HUD — in-app live dashboards
- [ ] Testing
- [ ] Packaging
- [ ] HUD — real-time table overlay — moved to the very last task of the whole project: depends on the Statistics module (opponent stat computation) and the Players module (opponent profiles), and its legality is still unconfirmed (blocked on the ToS review and overlay feasibility spike in Follow-ups below)

> The two scaffold items are a deliberate technical spike, done out of the strict roadmap order to validate the stack. They are not the frozen Phase 9 implementation and should not be extended with real domain logic until Phases 4-8 are documented.

---

# Follow-ups / Technical Debt

Items raised during review that don't block the current phase but should be resolved before relying on them:

- [x] ~~Write missing ADRs: Modular Monolith + DDD, SQLite/ClickHouse split, backend architecture (see Phase 2)~~ — done: `ADR-002-modular-monolith.md`, `ADR-003-local-analytics-storage.md`, `ADR-004-backend-packaging.md`
- [x] ~~Fix naming drift between `01-documentation-structure.md`'s expected ADR filenames and the actual `ADR-001-stack-selection.md`~~ — done, structure doc now lists the real filenames
- [x] ~~Decide ClickHouse vs. DuckDB for local-first analytics storage before Phase 6 is written~~ — decided: DuckDB locally (`ADR-003-local-analytics-storage.md`); ClickHouse deferred to a future cloud-platform ADR, see below
- [ ] Legal/ToS review for the real-time table overlay HUD specifically against **PokerStars** and **Winamax**'s current Terms of Service before building it (Winamax has historically banned real-time HUD/datamining tools under French ANJ regulation; PokerStars restricts live overlays in several jurisdictions) — also covers opponent-data aggregation ("Community Intelligence") described in `03-product-vision.md`
- [ ] Technical spike: cross-platform feasibility of a real-time overlay rendered on top of another application's window (Windows/macOS/Linux each need different native APIs — this is outside what Tauri provides for its own window) — needed before committing to the overlay's implementation approach
- [x] ~~Define the monetization/pricing model — ambition is now explicitly a commercial product (see `03-product-vision.md`), but the business model itself (license, subscription, freemium cloud tier...) is still undecided~~ — decided: monthly subscription only, Basic (limited by hand volume + history retention, exact caps TBD) and Pro (unlimited + AI-assisted analysis + future extras) tiers, see `03-product-vision.md`'s new "Monetization Model" section
- [ ] Design the licensing/entitlement validation mechanism for the subscription (periodic online check + offline grace period, per `03-product-vision.md`'s Monetization Model) — needed before Phase 8 (API Design) and the Future Cloud Platform work; exact validation cadence and grace-period length also still TBD
- [x] ~~Upgrade local Node.js to `^20.19` or `>=22.12` (`frontend/` currently fails `npm run build` on Node 18.17.1)~~ — done: Node.js 24.19.0 (LTS) installed, `npm install` + `npm run build` verified working
- [x] ~~Install the Rust toolchain (`rustc`/`cargo`) — required for `npm run tauri dev` / desktop builds~~ — done: installed via `rustup` (1.98.1); `npm run tauri dev` itself (full desktop app launch) not yet exercised
- [ ] Community/cloud analytics platform storage decision — deferred; only relevant if the product succeeds and that phase actually starts (explicitly optional per `03-product-vision.md`). Revisit with its own ADR then; ClickHouse remains the leading candidate given its multi-tenant/large-scale strengths.
- [ ] Players module: exact stat set for the player profile, exact predefined tag set, and exact player-list columns are still TBD — refine during screen design (3.2)
- [ ] Statistics module: PokerTracker 4 research done and an MVP stat catalog + breakdown-dimension proposal drafted in `13-statistics-module.md`; remaining before its screen design (3.2) can start: verify the doc's "not yet confirmed" stat group and resolve its Open Questions
- [ ] Reports module: PT4 research surfaced adjacent concepts worth considering when Reports' predefined templates are defined (3.2) — its "My Reports" import/export file format, and LeakTracker's benchmark-vs-winning-players deviation view (see `13-statistics-module.md`'s Future Expansion section)
- [ ] Reports module: exact set of predefined report templates is still TBD — refine during screen design (3.2)
- [ ] Implementation priority: Statistics and Reports are the most complex modules in the product map (confirmed by the PT4 research in `13-statistics-module.md`) and should be built last in Phase 9, after Overview, Live Session, Hands and Players
- [ ] Winamax cash parser: validate against real Winamax cash hand histories once available (only ported reference-library fixtures so far — see Phase 9 above)
- [ ] Winamax "Tournament summary" files (e.g. `20250904_Expresso(985283788)_real_holdem_no-limit_summary.txt`) are a different document from a hand history — one record per tournament entered (buy-in breakdown, registered players, level/speed structure, prizepool, finishing place), not a sequence of hands. Out of scope for `WinamaxTournamentParser`; needs its own design (and a decision on whether/how it feeds the future Statistics/Reports modules) if this data is worth capturing
- [ ] PokerStars MTT / Spin&Go parser: now that Winamax's tournament/Expresso distinction is ported (`HandFormat.TOURNAMENT` vs `EXPRESSO`), worth checking whether PokerStars' real-money-tournament vs. Spin&Go split can reuse the same `HandFormat` modeling once real PokerStars tournament samples are available
- [ ] PartyPoker cash parser: validate against real PartyPoker cash hand histories once available (only ported reference-library fixtures so far — see Phase 9 above)
- [ ] 888 cash parser: validate against real 888 hand histories once available (only ported reference-library fixtures so far — see Phase 9 above). Also: one reference fixture (`Limits/Limit5.txt`) uses a French/European number format the parser doesn't handle yet — non-breaking-space thousands separator, comma decimal, currency symbol *after* the amount (e.g. "3 023,50 $") — and was excluded from the port rather than supported, since no reference test locks in its expected values either; worth revisiting if real 888 hands turn out to use it. Two other reference fixtures (`Seats/4 Max.txt`, `Seats/Full Ring (10 Handed).txt`) were empty (3-byte BOM-only) in the upstream repo itself and were skipped as broken, not ported.

---

# Last Updated

2026-09-10 — Built the real Winning Poker Network cash parser, `WinningPokerParser`, in `com.pokeranalytics.importing.parser.winningpoker` (per ADR-005, continuing through the room list without waiting for real user hand histories, per ADR-007). Ported 40 sample hand histories into `backend/src/test/resources/hand-history/winningpoker/` and validated against them (49 hands) — 7 JUnit tests, all green. This room needed a structurally different parsing technique, not just new regexes: its action lines ("Player Cellar door folds") put no delimiter between a player name and the verb that follows it, and names can contain spaces/periods/hyphens/parentheses, so a single anchored regex can't tell them apart reliably. Resolved the same way the reference library does it: a first pass collects the hand's own seated-player names from its "Seat N: name (stack)." lines, sorted longest-first, and every action line's prefix is matched against that list before the remaining suffix is parsed as a verb. The summary section doesn't have this ambiguity (each line ends in one of a few fixed, distinctive phrases), so it's parsed directly by regex without the name-list lookup. Added `HandActionType.POSTS` (this room's own generic, unqualified "posts (N)" — used for a dead blind's untagged follow-up post and for late-entry catch-up posts alike) and `HandActionType.STRADDLE`. Confirmed no side-pot support exists in the reference for this room and no fixture shows one either — every hand states a single aggregate "Pot: N. Rake M". Not yet validated against real Winning Poker Network hands — tracked as a follow-up, same as every other room ported without real samples so far.

2026-09-10 — Extended `PokerStarsParser` to handle regular PokerStars tournaments (MTT/SNG), and added `decisions/ADR-007-port-tournament-formats-without-fixtures.md` to document a methodology change the user asked for: stop gating the remaining ADR-005 rooms/formats on real hand histories or reference fixtures, and port whatever's actually available, being explicit about which validation tier each one got. For PokerStars this meant discovering the reference library ships **no tournament sample fixtures for any room** (only `CashGame/` folders exist under `SampleHandHistories/*/`), and that PartyPoker/888 don't even have tournament-parsing *code* to port (`ParsePokerFormat` hardcoded to `CashGame`, `ParseTournamentId` throws `NotImplementedException` — nothing there, tracked as blocked on a real sample instead). For PokerStars, real tournament-parsing code does exist in the reference, and turned out to need almost no new grammar beyond what the already-ported cash parser handles (ante posts, "and is all-in", numbered side pots, the summary section) — extended the same `PokerStarsParser` class rather than splitting out a second one. Validated against 2 real, verbatim PokerStars tournament hands published on Wikipedia's "Hand history" article (a knockout hand with a bounty/finish-place line, an older multi-way all-in hand with numbered side pots) plus a real ante-posting example cross-checked from arXiv:2312.11753, since no reference-library fixtures exist to validate against instead — 3 new JUnit tests, all green, alongside the existing 4 cash tests (still green). Added `HandActionType.WINS_BOUNTY` and `HandActionType.TOURNAMENT_FINISH`. Known gap, shared with the reference library itself: nothing in the hand-history text distinguishes a regular MTT from a 3-max Spin&Go, so both classify as `HandFormat.TOURNAMENT` for now. Also backfilled `01-documentation-structure.md`'s stale ADR file listing (was missing ADR-005/006, now also has 007).

2026-09-10 — Built the real 888 cash parser, `Poker888Parser`, in `com.pokeranalytics.importing.parser.poker888` (per ADR-005, next room after PartyPoker, one at a time — "888" is skipped for PartyPoker MTT since, like PokerStars MTT, the reference library has no tournament sample fixtures for it either). Ported 35 sample hand histories from the reference library into `backend/src/test/resources/hand-history/poker888/` and validated against them (35 hands: Omaha Hi/Lo split pots, dead-blind and catch-up big-blind postings worded identically to a real big-blind post, Jackpot tables, an incomplete/no-summary hand) — 8 JUnit tests, all green. Notable finding while porting: the reference's own "Pacific" sample folder mixes in fixtures from an entirely different room's grammar (`GameTypeTests/PotLimitHoldem.txt`, `PlayerTests/WithSittingOut.txt` — PartyPoker-shaped header and action lines) — confirmed as dead/unused fixtures by checking the reference library's own test suite, which explicitly ignores those specific cases for Pacific; excluded both from the port rather than trying to parse a different site's format under this room's parser. Two further fixtures (`Seats/4 Max.txt`, `Seats/Full Ring (10 Handed).txt`) were empty (BOM-only) in the upstream repo and were skipped as broken. One fixture (`Limits/Limit5.txt`) uses an unsupported French/European number format (space thousands separator, comma decimal, trailing currency symbol) and was excluded rather than adding untested parsing logic for it — tracked as a follow-up. Also confirmed real, non-broken multi-brand coverage: the same format is shared by white-label skins on the network ("888poker", "Cassava", "LuckyAcePoker.com" all appear in the header line), which the parser handles generically rather than hardcoding "888poker". Not yet validated against real 888 hands — tracked as a follow-up, same as Winamax/PartyPoker cash.

2026-09-10 — Built the real PartyPoker cash parser, `PartyPokerParser`, in `com.pokeranalytics.importing.parser.partypoker` (per ADR-005, next room after Winamax, one at a time). Ported 40 sample hand histories from the reference library into `backend/src/test/resources/hand-history/partypoker/` and validated against them (53 hands) — 6 JUnit tests, all green. This room's grammar differs from PokerStars/Winamax enough to need two small shared-model additions: `HandActionType.ALL_IN` (PartyPoker reports "is all-In" as its own verb, not a bet/call/raise suffix) and `POST_DEAD_BLIND` (a late/re-entering player's catch-up post). Also notable: no colon after the player name in action lines (same lazy-name-match technique as Winamax), street markers only ever show their own new card(s) rather than repeating the board, and there is no summary section at all — the room never states a total pot or rake anywhere, so `totalPot` is computed here by summing every "wins" line (traceable straight from the same text, not invented), while `rake` and `summarySeats` are simply left unset. Along the way fixed two real bugs the design alone hadn't caught: a wrong capture-group index that fed the player's *name* into `smallBlind`/`bigBlind` instead of the amount (would have silently produced nonsense values whenever a name had digits, and crashed otherwise), and a leading "#Game No :" bookkeeping line in multi-hand exports that was flushed as a bogus empty hand before the real first header — both caught by the reference-fixture tests, not by inspection. Not yet validated against real PartyPoker hands — tracked as a follow-up, same as Winamax cash.

2026-09-10 — The user reorganized `backend/src/main/resources/hand-history/` into a `<room>/<mode>/` layout (`poker-stars/cash/`, `poker-stars/mtt/`, `winamax/cash/`, `winamax/mtt/`, `winamax/expresso/`), replacing the flatter `poker-stars/` and `winamax-expresso/` directories from earlier today. Updated `PokerStarsParserRealDataTest` and `WinamaxTournamentParserRealDataTest` to the new paths — the new layout incidentally confirms `WinamaxTournamentParser`'s Expresso-vs-tournament classification was already correct, since the user's own manual sorting of the same files into `winamax/expresso/` vs `winamax/mtt/` matches it exactly.

2026-09-10 — Built the real Winamax tournament/Expresso parser, `WinamaxTournamentParser`, in `com.pokeranalytics.importing.parser.winamax` (per ADR-005) — a different grammar from the cash parser (chip-only blind/ante amounts, real-money knockout bounties per seat, no space between the two bracket groups on turn/river street markers), not covered by the reference library this port is otherwise based on. Added `HandFormat` (CASH/TOURNAMENT/EXPRESSO) to `importing.model` so a parsed hand can tell a regular multi-table tournament apart from Winamax's own fast 3-max "Expresso" format (Winamax's name for what PokerStars calls Spin&Go) — set from whether the room's own tournament name is literally "Expresso". Also added a nullable `bounty` field to `Seat` (needed one-line updates to the two existing cash parsers, passing `null`). Validated against real 2025 hands already kept in `backend/src/main/resources/hand-history/winamax-expresso/`: 1 Expresso file and 3 regular knockout tournaments ("TRIDENT SPACE KO", "ROTATION", "SPACE KO") — 68 hands total, covering antes from level 1, bounties, main/side pot splits, and mid-file table reseating — 6 JUnit tests, all green. The `_summary.txt` files alongside them turned out to be a different, non-hand-history document (end-of-tournament results, not a hand sequence) and are explicitly out of scope here — tracked as a new follow-up.

2026-09-10 — Built the real Winamax cash parser in `com.pokeranalytics.importing.parser.winamax` (per ADR-005, following the PokerStars parser's established pattern): `WinamaxParser`, reusing the existing `importing.model` types. Ported 25 sample hand histories from the reference library into `backend/src/test/resources/hand-history/winamax/` and validated against them (34 hands: dead-money blinds posted "out of position", heads-up seats carrying both the small-blind and button tags at once — `SummarySeat.positionTag`'s doc comment now reflects that comma-joined case — all-ins, showdowns/mucks, and the summary "Board:" line overriding street-marker board text) — 5 JUnit tests, all green. Two of the reference fixtures were Windows-1252-encoded (not UTF-8 like the rest) and needed re-encoding while porting; one further fixture (`OmahaHiLo.txt`) turned out to be an old, untested XML export format unrelated to the plain-text format this parser targets, and was skipped. Not yet validated against real Winamax cash hands — tracked as a follow-up below, same as PokerStars MTT/Spin&Go.

2026-09-10 — Relocated the real PokerStars sample hand histories from `docs/hand-history/poker-stars/` to `backend/src/main/resources/hand-history/poker-stars/`, and updated `PokerStarsParserRealDataTest` accordingly. Real Winamax tournament hands (2025 Expresso/KO, contributed for validating a future Winamax MTT/Spin&Go parser) were added at `backend/src/main/resources/hand-history/winamax-expresso/` — confirmed to be a different, unparsed format from the cash parser (tournament header shape, chip-only blind amounts, no space between the two bracket groups on turn/river lines), so they don't validate today's cash-only `WinamaxParser`; tracked as a follow-up below.

2026-09-10 — Decided the monetization model: monthly subscription only, no permanent free tier. Basic covers full core tracking/analytics across every supported room, limited by hand-import volume and history retention (exact caps TBD); Pro removes those limits and adds AI-assisted game analysis plus future extras. Subscription validated via periodic online checks with an offline grace period, keeping day-to-day use working without internet per the Local First principle — the licensing/entitlement mechanism itself is a new follow-up, needed before Phase 8 API Design. Documented in `03-product-vision.md`'s new "Monetization Model" section.

2026-09-10 — Set up frontend tooling: installed Node.js 24.19.0 (LTS, replacing the EOL 18.17.1) and the Rust toolchain via `rustup` (1.98.1). Verified `npm install` and `npm run build` both work now (previously `npm run build` crashed on Node 18). `npm run tauri dev` (full desktop app launch) not yet exercised.

2026-09-10 — Upgraded the backend from Java 17 to Java 25 (`decisions/ADR-006-java-25.md`), skipping 21 since there's no reason to target a non-latest LTS pre-1.0. Installed Zulu 25 alongside the existing Zulu 8/17/21. Discovered and fixed a real compatibility gap along the way: Spring Boot 3.3.4's ASM classpath scanner can't read Java 25's class file format, so also bumped Spring Boot to 3.5.16 — verified empirically (full 9-test suite green on Java 25 + Spring Boot 3.5.16, including the Spring context test) rather than assumed. Updated `CLAUDE.md`'s Technology Stack section and `JAVA_HOME` guidance accordingly.

2026-09-10 — Built the real PokerStars cash parser in `com.pokeranalytics.importing` (per ADR-005): `model` package (`ParsedHand`, `Seat`, `HandAction`, `HandActionType`, `SummarySeat`, `Street`) plus `parser.pokerstars.PokerStarsParser`. Ported 62 sample hand histories from the reference library into `backend/src/test/resources/hand-history/pokerstars/` and validated against them (128 hands) plus the existing 81 real hands — all 8 tests green. Along the way, fixed real bugs the reference research alone hadn't surfaced: numbered/multiple side pots in the same summary line, "Hand was run twice" appearing after `*** SUMMARY ***` (not before), Cap-game headers/raises with trailing descriptive text, lone-CR (classic Mac) line endings in one fixture, table and player names containing their own apostrophes/colons, and Omaha Hi/Lo partial "shows" lines with no hand description. Retired `com.pokeranalytics.spike.handhistory` (both `main` and `test`), fully superseded.

2026-09-09 — Decided to port the MIT-licensed `HHSmithy/PokerHandHistoryParser` (C#) reference library into Java as the real, kept hand-history import layer, ahead of Phases 4-8 — documented as a deliberate, scoped exception in new `decisions/ADR-005-early-import-parser.md`, with `CLAUDE.md` updated to name the exception explicitly. Expanded the MVP room list in `03-product-vision.md` from PokerStars+Winamax to every currently-operating room the reference library supports (PokerStars, Winamax, PartyPoker, 888/Pacific, iPoker, Winning Poker Network) — rooms are ported and validated one at a time, PokerStars first. Added `THIRD-PARTY-NOTICES.md` for attribution.

2026-09-09 — Started the hand history parser feasibility spike: wrote a prototype parser (`com.pokeranalytics.spike.handhistory`, explicitly marked as a spike, not the frozen import module) and JUnit tests validating it against 81 real PokerStars Zoom cash hands dropped in `docs/hand-history/poker-stars/`. All 4 tests pass (`mvn test`, run with `JAVA_HOME` set to Zulu 17). Confirmed feasible for this format, including edge cases found along the way (disconnect/timeout notices interleaved in the action log, "mucks hand"). Still open: PokerStars MTT/Spin&Go and all of Winamax, pending sample hand histories.

2026-09-09 — Moved `HUD — real-time table overlay` to the very last task in Phase 9's implementation order (previously listed right before Testing/Packaging): it depends on the Statistics and Players modules, and its legality is still unconfirmed pending the ToS-review follow-up.

2026-09-09 — Noted a new follow-up: Statistics and Reports, being the most complex modules, should be built last in Phase 9 implementation order (after Overview, Live Session, Hands and Players). The `13-statistics-module.md` stat catalog's "not yet confirmed" group stays a base to work from until the user brings real PokerTracker 4 screenshots to verify/refine it.

2026-09-09 — Researched PokerTracker 4 (8 official doc pages: Quick Start, Creating My Reports, Custom Statistics Guide, Global Filters, Equity Calculator, Net Adjusted Winnings, BB/100 vs bb/100, LeakTracker) and populated `13-statistics-module.md` with a provenance-tagged Stat Catalog (PT4-confirmed / project-mandated / unconfirmed-convention), an MVP stat table (10 stats), MVP + Future breakdown dimensions (PT4's 6-category Global Filters framework), a simplified Saved Views scope, and a Future Expansion backlog (custom stat builder, Equity Calculator, ICM-based Net Adjusted Winnings, LeakTracker-style analysis). Reworded the Statistics follow-up accordingly and added a new Reports follow-up for adjacent concepts found during this research.

2026-09-09 — Created `13-statistics-module.md` as a skeleton deep-dive doc for the Statistics module's stat catalog and breakdown dimensions (Research Status, Stat Catalog, Breakdown Dimensions, Open Questions and Screen Design sections, all TODO pending the PokerTracker 4 research), registered in `01-documentation-structure.md`; `05-product-map.md`'s Statistics section now points to it instead of just deferring the detail in place.

2026-09-09 — Published Phase 3.2 screen-design canvases (Claude Design) for Overview, Live Session, Hands, Hand Detail, Players, Session History and Reports; recorded their links in new doc `12-ui-designs.md` (registered in `01-documentation-structure.md`). Statistics's screen design stays open pending the PokerTracker 4 stats research follow-up.

2026-09-09 — Reports module defined in `05-product-map.md`: Session History (log of past sessions + session detail) and Report Generation (predefined templates + exporting a saved Statistics view, PDF/CSV) as two separate sidebar entries within one module; global filters plus a date-range period; out-of-scope boundaries against Statistics, Live Session and hand editing. Also linked Live Session's existing "session history" reference to this module. Phase 3.1 (Product Map) is now complete — Phase 3 overall stays in progress pending 3.2 (Screen Design).

2026-09-09 — Statistics module defined in `05-product-map.md`: configurable stats (user-chosen columns + user-defined breakdowns, exact library TBD pending PokerTracker 4 research), saved views, single dashboard adapting to the Game Type filter (cash vs. tournament stats), global filters plus a minimum-sample-size filter, drill-down navigation into Hands, and out-of-scope boundaries against Players, Hands, Live Session and the future Reports module. Also added a general "ask before deciding" rule to `CLAUDE.md` and refreshed its stale product-map module-count note.

2026-09-09 — Players module defined in `05-product-map.md`: opponents-only scope, nickname-only player identity (aggregated across rooms), profile with stats vs. Hero + linked hands list + predefined tags/free notes, searchable player list, global filters plus a minimum-sample-size filter, and out-of-scope boundaries against Live Session, AI-assisted insights, hand editing and Hero's own stats.

2026-09-09 — Hands module defined in `05-product-map.md`: hands table (basic data + game context columns), Replay/Detail row actions, result/situation/global filters, tagging, and out-of-scope boundaries against Statistics, Players, Live Session and hand editing.

2026-09-09 — Relaxed Git workflow: direct commits to `develop` are now allowed and `feature/*` branches are optional (`main` stays PR-protected); added the rule that every commit logs a dated entry here explaining what it did. Updated in `CLAUDE.md`, `02-development-workflow.md` and `01-documentation-structure.md`.

2026-09-09 — Phase 3 started: Live Session module defined in `05-product-map.md` (in-app live dashboard half of the Live HUD goal; the real-time overlay stays out of scope for this module, tracked separately). Remaining Phase 3.1 modules (Hands, Players, Statistics, Reports) and all of 3.2 (screen design) still open.

2026-09-08 — Product vision clarified: commercial ambition, MVP rooms/formats (PokerStars + Winamax, cash/MTT/Spin&Go), Live HUD goal added (in-app dashboards + real-time overlay) with its ToS and technical-feasibility risks tracked as follow-ups.
