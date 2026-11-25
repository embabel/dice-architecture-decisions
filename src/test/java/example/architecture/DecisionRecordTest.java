package example.architecture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DecisionRecordTest {

    @TempDir
    Path dir;

    @Test
    void persistsDecisionVersionsAndRestoresAnEarlierDecisionWithItsEvidence() {
        var notebook = new DecisionNotebook(new DecisionProperties(0.8));
        var file = dir.resolve("decisions.json");
        var record = new DecisionRecord(notebook, file);
        var first = record.write(edit("Pilot", "Canary only", "replay", null));
        var second = record.write(edit("Expand pilot", "Two regions", "queue", first.id()));
        assertThatThrownBy(() -> record.write(edit("Stale", "Old draft", "adr", first.id())))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode().value()).isEqualTo(409));

        var reopened = new DecisionRecord(notebook, file);
        var restored = reopened.write(new DecisionRecord.Change("review", false, null, null, null,
                second.id(), first.id()));
        assertThat(restored.version()).isEqualTo(3);
        assertThat(restored.rationale()).isEqualTo(first.rationale());
        assertThat(restored.related()).isEqualTo(first.related());
        var latest = new DecisionRecord(notebook, file).inspect("review", false);
        assertThat(latest.history()).containsExactly(first, second, restored);
        assertThat(latest.graph().nodes()).anyMatch(node -> node.id().equals(restored.id()) && node.status().equals("Current"));
        assertThat(latest.graph().edges()).anyMatch(edge -> edge.from().equals(first.id()) && edge.to().equals(restored.id()));
        assertThat(reopened.inspect("adopted", false).history()).isEmpty();
        assertThatThrownBy(() -> reopened.write(new DecisionRecord.Change("review", true, "Scenario", "Hypothesis", null,
                null, null))).isInstanceOf(ResponseStatusException.class);
    }

    private static DecisionRecord.Change edit(String title, String rationale, String related, String predecessor) {
        return new DecisionRecord.Change("review", false, title, rationale, related, predecessor, null);
    }
}
