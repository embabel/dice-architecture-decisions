package example.architecture;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DecisionHttpTest {

    @LocalServerPort
    int port;

    @Test
    void servesTheReaderAndScopedReviewEvidence() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var home = get(client, "/").body();
            assertThat(home).contains("Poll for outbound", "Latest review").doesNotContain("src=\"/app.js\"");
            var assets = java.util.regex.Pattern.compile("(/assets/architecture/[^\\\"\\s]+\\.(?:js|css))").matcher(home)
                    .results().map(result -> result.group(1)).toList();
            assertThat(assets).hasSize(2);
            for (var asset : assets) {
                assertThat(get(client, asset).statusCode()).isEqualTo(200);
            }

            var review = get(client, "/api/decision?revision=review");
            assertThat(review.statusCode()).isEqualTo(200);
            assertThat(review.body()).contains("Revisit polling", "Replay safety unproven",
                    "infra/eu-west-1-queue-pilot.md", "2026-11-21T15:45:00Z").doesNotContain("foreign-replay");

            var adopted = get(client, "/api/decision?revision=adopted");
            assertThat(adopted.body()).contains("Retain polling", "2026-09-14T16:10:00Z")
                    .doesNotContain("infra/eu-west-1-queue-pilot.md");
            assertThat(get(client, "/api/decision?revision=unknown").statusCode()).isEqualTo(404);
        }
    }

    @Test
    void streamsScopedAgentMessagesAndCitationsInAgUiOrder() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var uri = URI.create("http://127.0.0.1:" + port + "/api/decision/agent");
            var request = HttpRequest.newBuilder(uri)
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString("""
                            {"threadId":"decision-test","runId":"review-run",
                            "state":{"revision":"review","replayDrill":false},"tools":[],"context":[],
                            "messages":[{"id":"u1","role":"user","content":"What is stopping us from migrating?"}]}
                            """)).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            var json = JsonMapper.builder().build();
            var events = response.body().lines().filter(line -> line.startsWith("data: "))
                    .map(line -> json.readTree(line.substring("data: ".length()))).toList();
            assertThat(events).extracting(event -> event.get("type").asString())
                    .containsExactly("RUN_STARTED", "STATE_SNAPSHOT", "TEXT_MESSAGE_START",
                            "TEXT_MESSAGE_CONTENT", "TEXT_MESSAGE_END", "CUSTOM", "RUN_FINISHED");
            assertThat(events.get(3).get("delta").asString()).contains("Replay recovery");
            assertThat(events.get(5).get("name").asString()).isEqualTo("decision.citations");
            assertThat(events.get(5).get("value").get("nodeIds").get(0).asString()).isEqualTo("migrate");
        }
    }

    private HttpResponse<String> get(HttpClient client, String path) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
