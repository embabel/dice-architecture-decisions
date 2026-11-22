package example.architecture;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

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
            assertThat(get(client, "/").body()).contains("Poll for outbound", "Latest review");

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

    private HttpResponse<String> get(HttpClient client, String path) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
