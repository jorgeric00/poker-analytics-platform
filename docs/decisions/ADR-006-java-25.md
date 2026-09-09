# ADR-006: Upgrade to Java 25

## Status

Accepted

## Context

The backend targeted Java 17 (the LTS current when `ADR-001-stack-selection.md` was written). Java 17 remains fully supported, but two newer LTS releases exist since (21, 25), and the project is still pre-1.0 with no production commitment to a specific runtime — upgrading now costs little and gains real capability: virtual threads (finalized in 21) for the concurrent hand-import/parsing workload this app will have, and pattern matching for switch + record patterns (21, refined further in later releases), which suit a parser built as a chain of pattern-matched branches like `com.pokeranalytics.importing.parser.pokerstars.PokerStarsParser`.

## Decision

Move the backend to **Java 25** (`backend/pom.xml`'s `java.version` property), skipping 21 directly since there's no reason to target a non-latest LTS here.

This required also upgrading **Spring Boot from 3.3.4 to 3.5.16**: verified empirically that 3.3.4's bundled ASM-based classpath scanner cannot read Java 25's class file format (`Unsupported class file major version 69`), which surfaced as `PokerAnalyticsApplicationTests` failing to bootstrap the Spring context — a plain (non-Spring) compile/run against Java 25 worked fine, isolating the problem to Spring Boot's version, not the JDK itself. Confirmed the full suite (9 tests, including the Spring context test and both `com.pokeranalytics.importing` parser test classes) passes on Java 25 + Spring Boot 3.5.16.

Azul Zulu 25 was installed alongside the existing Zulu 8/17/21 on the dev machine (same distribution as those, for consistency) rather than replacing any of them, since other JDKs may still be needed for reference/comparison.

## Consequences

- `CLAUDE.md`'s `JAVA_HOME` workaround note and Technology Stack section are updated to reflect Java 25 as the version to build with (`JAVA_HOME="/c/Program Files/Zulu/zulu-25"`).
- Any future `backend/pom.xml` dependency additions should be checked for Java 25 compatibility the same empirical way (compile + run the test suite), not assumed from version numbers alone — this ADR's own discovery (Spring Boot needing a bump too) is the reason why.
- GraalVM native-image (ADR-004's deferred fallback) was not re-evaluated against Java 25 as part of this change — revisit if/when that fallback is picked up.
