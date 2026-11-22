# DICE Architecture Decisions

Why did we choose polling over a queue? Does the reason still hold? This offline demo lets an engineer review an architecture decision against the information on file at two points in time.

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

The notebook loads the immutable fixture text and source excerpts through `ScopedReadSpec` for one ADR. It projects only the claims active at the selected time to Prolog and asks a declared rule for a conclusion. DICE returns the inputs and rule identity for a derived conclusion. The browser renders those sources separately from the approved decision. The fixture seeds a replay claim under a different ADR to exercise the scope boundary.

Use `GET /api/decision?revision=adopted` or `GET /api/decision?revision=review` to inspect the response. Both return the recorded ADR, premises, review posture, and migration finding. The packaged reader calls the same endpoint.

## Tests

```sh
./mvnw verify
```

The service tests verify time reconstruction, source citations, derived rule identity, and the missing replay result. The HTTP test exercises both revisions and checks that the packaged API rejects an unknown revision.
