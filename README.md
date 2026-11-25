# DICE Architecture Decisions

We picked a database poller for outbound webhooks because a durable queue was missing in one region and consumers had no stable delivery ID. A few months later both gaps had closed. Does that mean we should migrate? Walk the decision graph, open the original evidence, and ask what still needs to be true.

## Run the workbench

Use Java 21. Set `OPENAI_API_KEY` in your environment to start with a live Embabel agent. The same workbench opens with guided chat when the key is absent.

```sh
./mvnw -B verify
java -jar target/dice-architecture-decisions.jar
```

Open the local workbench. Live chat sends the selected graph, its source excerpts, and your conversation to the configured OpenAI model. Use `DECISIONS_CHAT_MODEL` to change the default from `gpt-4.1-mini`. To run guided chat with a key in your environment, pass `--spring.profiles.active=offline`. [Open the workbench](http://127.0.0.1:8082)

## Walk the decision

1. Pick **At adoption**. Follow the queue and idempotency nodes into **Retain polling**. Select **Approved ADR** to read the original decision.
2. Switch to **Latest review**. Open the source nodes to compare the original excerpts with the queue pilot and new delivery contract. Select **Revisit polling** and expand **View Prolog rule**.
3. Ask **What blocks migration?** Follow up with **What evidence is missing?** Click an answer citation to open the relevant node in the inspector.
4. Turn on **Assume a passing replay drill**. At the latest review that opens the path to migration review. Try the same scenario at adoption and the queue and delivery gaps still block it. The approved ADR stays in force in both scenarios.

Ask in your own words. When the selected record cannot answer, the assistant offers a relevant question it can answer.

## How it works

DICE reconstructs the facts for one ADR at the selected transaction time. Its Prolog projection evaluates the review and migration rules, and checks the individual inputs so the graph can show exactly which edges are proven. The replay drill switch adds a hypothetical fact to that evaluation for one request. The projection, graph, and chat all use the same scoped record. [DecisionNotebook](src/main/java/example/architecture/DecisionNotebook.java)

Live chat uses a read-only Embabel action and receives the graph and recent conversation turns. The app validates any referenced node IDs against the selected graph before returning the answer. Guided chat uses that same graph to answer questions about the decision, evidence, and prerequisites. The workbench starts a fresh conversation when you switch checkpoints or change the scenario. [DecisionAgent](src/main/java/example/architecture/DecisionAgent.java) · [DecisionChat](src/main/java/example/architecture/DecisionChat.java)

| Endpoint | What it returns |
| --- | --- |
| `GET /api/decision?revision=adopted` | The ADR and proof graph at adoption |
| `GET /api/decision?revision=review&replayDrill=true` | The latest review evaluated with a hypothetical passing drill |
| `GET /api/decision/chat` | The configured chat mode |
| `POST /api/decision/chat` | An answer and graph node IDs for the requested checkpoint, scenario, and conversation |

The graph's node IDs can be used as a focus hint when you ask a question. The returned `nodeIds` identify the nodes to open as citations. Send `replayDrill: false` for the recorded checkpoint.

```json
{
  "revision": "review",
  "replayDrill": false,
  "focus": "migrate",
  "turns": [{"role": "user", "content": "What evidence would let us review migrating?"}]
}
```

The source excerpts and decisions come from the included architecture case. The notebook rebuilds an in-memory proposition store and audit log at startup. An app that records actual decisions can use the DICE SQLite starter and an audit log on the same datasource. Install a complete per-ADR baseline before offering historical reads. [DecisionNotebook](src/main/java/example/architecture/DecisionNotebook.java)

To see the effect of insufficient evidence on the graph, start with a stricter floor.

```sh
java -jar target/dice-architecture-decisions.jar --decisions.minimum-evidence-confidence=0.99
```

## Tests

`./mvnw -B verify` runs the HTTP integration tests, scope and replay tests, and a real Embabel action against a scripted provider. No model key is needed for the test suite.

The browser test starts the packaged app in guided mode and exercises graph selection, chat, citations, checkpoint switching, and the replay scenario at desktop and mobile widths.

```sh
bun install --frozen-lockfile
bunx playwright install chromium
bun run test:browser
```
