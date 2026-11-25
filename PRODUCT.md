# Product

<!-- impeccable:product-schema 1 -->

## Platform

web

## Stack

Java 21, Spring Boot, DICE, Embabel, and a TypeScript workbench with D3 and the AG-UI HTTP client. Maven packages the workbench with Spring Boot.

## Users

Engineers revisiting an architecture decision after its operating conditions change.

## Product Purpose

Show why an architecture decision made sense on the day it was made, then show why its current posture may differ. The synthetic ADR compares database polling with a managed queue for outbound webhooks.

## Operating Context

A reviewer switches between the adoption and review checkpoints, explores the graph, and asks questions about the selected evidence. A what-if replay drill shows how an extra proven condition would change the result. The team can record decisions connected to evidence, revise them, and restore a prior version.

## Capabilities and Constraints

The app reconstructs evidence within one ADR context. The workbench shows the approved rationale, rule-derived posture, and missing proof. It supports guided chat offline and live Embabel chat with a configured OpenAI key. DICE propositions persist the local decision log across restarts.

## Product Principles

- Keep the approved decision and the rule's recommendation separate.
- Preserve the evidence a reviewer would have had at the chosen time.
- Make every material premise traceable to its source.
