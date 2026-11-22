# Product

<!-- impeccable:product-schema 1 -->

## Platform

web

## Stack

Delegated. Java 21, Spring Boot, DICE, and browser-native HTML, CSS, and JavaScript. A single Maven build produces the runnable app.

## Users

Engineers revisiting an architecture decision after its operating conditions change.

## Product Purpose

Show why an architecture decision made sense on the day it was made, then show why its current posture may differ. The synthetic ADR compares database polling with a managed queue for outbound webhooks.

## Operating Context

A reviewer compares the adoption state with the later review state. Historical reconstruction follows audit transaction time. Source observations carry their own files, lines, and excerpts.

## Capabilities and Constraints

The app reconstructs evidence within one ADR context. Recorded rationale stays distinct from rule-derived posture. Missing proof stays unproven. The demonstration is deterministic and runs offline. No fixture describes a real system.

## Product Principles

- Keep the approved decision and the rule's recommendation separate.
- Preserve the evidence a reviewer would have had at the chosen time.
- Make every material premise traceable to its source.
