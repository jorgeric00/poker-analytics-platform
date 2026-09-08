# ADR-004: Backend Packaging — JAR + Embedded JRE

## Status

Accepted

## Context

The Spring Boot backend needs to run alongside the Tauri frontend as part of a single desktop application distributed on Windows, macOS and Linux. Two realistic approaches exist: a conventional Spring Boot JAR run on a bundled JRE, or a GraalVM native-image build.

A native image gives near-instant startup and a much smaller memory footprint, which fits the product's "modern and professional" goal well — but it adds real build complexity up front (reflection/resource configuration for native-image compatibility, longer and more fragile native compilation, potential incompatibilities with libraries as the dependency set grows) before there is a working application to measure against.

## Decision

Start with a standard **Spring Boot executable JAR, run on a bundled JRE**, launched as a Tauri sidecar process. The frontend communicates with it over local HTTP, consistent with the `Frontend <--> Backend` relationship in `04-architecture.md`.

This is the simplest option to build, run and debug while the backend itself is still being built out (Phases 4-9), and it keeps the toolchain conventional (plain `mvn package`, no native-image toolchain to install and maintain yet).

## Consequences

- Cold start latency and idle memory usage will be noticeably higher than a native image (typical Spring Boot JVM startup, JVM heap overhead) — acceptable for now, but worth measuring once the app has real functionality.
- GraalVM native-image is the documented fallback: revisit this decision with a new ADR once Phase 9 produces real startup-time and memory measurements that justify (or rule out) the added build complexity. This is deliberately not decided now, to avoid optimizing before there's anything to measure.
- The JRE must be bundled per platform (Windows/macOS/Linux) as part of packaging the Tauri app — this is a packaging detail to work out during Phase 9, not part of this ADR.
