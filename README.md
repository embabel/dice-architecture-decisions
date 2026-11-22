# DICE Architecture Decisions

Why did we choose polling over a queue? Does the reason still hold? This offline demo lets an engineer review an architecture decision against the information on file at two points in time.

## What you’ll see

In **Latest review**, the decision record pairs each original premise with the later evidence. Open **Inspect the rule trace** to see why DICE recommends revisiting polling. Follow an input to its source excerpt. The replay drill is still unproven, so the approved ADR stays in force.

![Architecture decision review with paired source excerpts, the Revisit polling rule, and the unproven replay drill](https://github.com/embabel/dice-architecture-decisions/releases/download/readme-screenshots/decision-review-desktop.png)

<details>
<summary>Mobile view</summary>

![Mobile architecture decision record with the revision controls and source evidence](https://github.com/embabel/dice-architecture-decisions/releases/download/readme-screenshots/decision-review-mobile.png)

</details>

## Run it

Install Java 21, then use the Maven wrapper.

```sh
./mvnw verify
java -jar target/dice-architecture-decisions.jar
```

Open [http://127.0.0.1:8082](http://127.0.0.1:8082). Maven resolves dependencies on the first build. The app runs offline after that. It uses the DICE snapshot published by Embabel, a Spring Boot API, and browser-native UI code. You do not need a database, an agent model, or an API key.

## Walk the decision

1. Open **At adoption**. The September record shows why Payments Platform chose to poll its outbox every 30 seconds. A managed durable queue was unavailable in one region, and the consumer contract had no stable delivery identifier.
2. Switch to **Latest review**. The queue pilot and revised delivery contract supersede both premises. DICE now derives **Revisit polling**.
3. Open **Inspect the rule trace**. Follow each input to its source file, line, and excerpt. The original reasoning stays available at both dates.
4. Inspect **Replay safety unproven**. The migration rule also needs a completed replay drill. Its absence leaves the result unproven. The approved ADR stays in force.

The story and source excerpts are synthetic. They are included in [the notebook fixture](src/main/java/example/architecture/DecisionNotebook.java).

## How the demo uses DICE

[The notebook](src/main/java/example/architecture/DecisionNotebook.java) seeds a complete empty audit baseline, then records observations through `AuditingPropositionRepository`. A replay clock assigns fixture transaction times. `beliefSetAsOf` reconstructs which claims were present and their status at the time selected by the reader.

The notebook uses `ScopedReadSpec` to fetch one ADR’s fixture propositions. It joins their immutable text and source references to the statuses reconstructed at the selected time. It projects only the claims active at the selected time to Prolog and asks a declared rule for a conclusion. DICE returns the inputs and rule identity for a derived conclusion. The browser renders those sources separately from the approved decision. The fixture seeds a replay claim under a different ADR to exercise the scope boundary.

Use `GET /api/decision?revision=adopted` or `GET /api/decision?revision=review` to inspect the response. Both return the recorded ADR, premises, review posture, and migration finding. The packaged reader calls the same endpoint.

## Adapting the example

The shipped decision record is a read-only fixture. The notebook rebuilds its DICE proposition store and audit log at startup. Use the DICE SQLite starter and an audit log on the same datasource when users can add evidence or decisions that must survive restart. Install a complete per-ADR baseline before offering historical reads.

- **Scope before projecting.** Build the Prolog projection from claims active in the authorized ADR at the selected time. The rule goal names that context. The scoped input set controls which facts the rule can use.
- **Keep sources stable.** The fixture joins historical statuses to immutable evidence by ID. When evidence can be edited, retain immutable source revisions and use the version recorded at the selected time. Resolve citations from the same scoped evidence used by the rule.
- **Keep findings separate from approvals.** A rule can recommend revisiting a decision. The application owns the approval and can cite the inputs behind the recommendation.

[DecisionProperties](src/main/java/example/architecture/DecisionProperties.java) binds the evidence floor from `decisions.minimum-evidence-confidence`. It defaults to `0.8`. To see what happens when the evidence falls below the floor, start the packaged app with a stricter policy.

```sh
java -jar target/dice-architecture-decisions.jar --decisions.minimum-evidence-confidence=0.99
```

The review shows **No source-backed conclusion** and still displays the approved ADR.

## Tests

```sh
./mvnw verify
```

The service tests verify time reconstruction, source citations, derived rule identity, and the missing replay result. Configuration tests verify the stricter floor and reject an out-of-range value at startup. The HTTP test exercises both revisions and checks that the packaged API rejects an unknown revision.
