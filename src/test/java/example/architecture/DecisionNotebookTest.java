package example.architecture;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class DecisionNotebookTest {

    private final DecisionNotebook notebook = new DecisionNotebook(new DecisionProperties(0.8));

    @Test
    void preservesTheRecordedRationaleAndProvesWhyItMatteredAtAdoption() {
        var adopted = notebook.inspect("adopted");

        assertThat(adopted.posture().label()).isEqualTo("Retain polling");
        assertThat(adopted.posture().evidence()).extracting(DecisionNotebook.EvidenceSource::filename)
                .containsExactlyInAnyOrder("infra/region-capabilities.md", "webhooks/consumer-contract.md");
        assertThat(adopted.posture().rule()).isEqualTo("retain_polling/1");
        assertThat(adopted.decision().filename()).isEqualTo("docs/adr/0024-webhook-dispatch.md");
        assertThat(adopted.assumptions()).extracting(DecisionNotebook.Assumption::status)
                .containsExactly("holding", "holding", "unproven");
    }

    @Test
    void configuredProjectionFloorCanWithholdTheRecommendation() {
        new ApplicationContextRunner()
                .withUserConfiguration(ArchitectureDecisionsApplication.class)
                .withPropertyValues("decisions.minimum-evidence-confidence=0.99")
                .run(context -> {
                    var review = context.getBean(DecisionNotebook.class).inspect("review");
                    assertThat(review.posture().label()).isEqualTo("No source-backed conclusion");
                    assertThat(review.posture().rule()).isNull();
                    assertThat(review.posture().evidence()).isEmpty();
                    assertThat(review.decision()).isEqualTo(notebook.inspect("review").decision());
                });
    }

    @Test
    void anInvalidProjectionFloorFailsAtStartup() {
        new ApplicationContextRunner()
                .withUserConfiguration(ArchitectureDecisionsApplication.class)
                .withPropertyValues("decisions.minimum-evidence-confidence=1.1")
                .run(context -> assertThat(context.getStartupFailure())
                        .hasRootCauseMessage("Minimum evidence confidence must be between 0 and 1"));
    }

    @Test
    void latestReviewRevisesBothPremisesWithoutApprovingMigration() {
        var latest = notebook.inspect("review");
        var adoptionAgain = notebook.inspect("adopted");

        assertThat(latest.decision()).isEqualTo(adoptionAgain.decision());
        assertThat(latest.assumptions()).extracting(DecisionNotebook.Assumption::status)
                .containsExactly("superseded", "superseded", "unproven");
        assertThat(latest.posture().rule()).isEqualTo("revisit_polling/1");
        assertThat(latest.posture().evidence()).extracting(DecisionNotebook.EvidenceSource::filename)
                .containsExactlyInAnyOrder("infra/eu-west-1-queue-pilot.md", "webhooks/delivery-contract-v2.md");
        assertThat(latest.migration().label()).isEqualTo("Replay safety unproven");
        assertThat(latest.migration().evidence()).isEmpty();
        assertThat(adoptionAgain.assumptions().getFirst().status()).isEqualTo("holding");
    }
}
