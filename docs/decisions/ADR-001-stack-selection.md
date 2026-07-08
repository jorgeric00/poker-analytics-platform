# ADR-001: Technology Stack Selection

## Status

Accepted

## Context

The application requires:
- Desktop support on Windows, macOS and Linux.
- Large-scale poker hand analytics.
- Modern user interface.
- Future cloud capabilities.

## Decision

Selected stack:

Backend:
- Java

Analytics Database:
- ClickHouse

Frontend:
- React + TypeScript

Desktop Framework:
- Tauri

## Rationale

Java provides strong backend capabilities and matches existing developer expertise.

ClickHouse is optimized for analytical workloads over large datasets.

React provides a modern UI ecosystem.

Tauri enables a lightweight cross-platform desktop application.