package example.architecture;

import com.embabel.agent.spi.LlmService;
import com.embabel.agent.spi.support.springai.SpringAiLlmService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DecisionAgentTest {

    @Test
    void runsAnEmbabelActionWithTheSelectedEvidenceAndValidatesItsCitations() {
        try (var context = new SpringApplicationBuilder(ArchitectureDecisionsApplication.class, Fixture.class)
                .web(WebApplicationType.NONE).profiles("live")
                .run("--spring.main.banner-mode=off", "--logging.level.root=WARN",
                        "--spring.autoconfigure.exclude=com.embabel.agent.autoconfigure.models.openai.AgentOpenAiAutoConfiguration",
                        "--decisions.chat-model=decision-test", "--embabel.models.default-llm=decision-test")) {
            var chat = context.getBean(DecisionChat.class);
            var model = context.getBean(Model.class);
            var question = new DecisionChat.Request("adopted", false, "queue", List.of(
                    new DecisionChat.Turn("user", "Why use polling?"),
                    new DecisionChat.Turn("assistant", "I'd start with the queue."),
                    new DecisionChat.Turn("user", "What did we know about it?")));
            var reply = chat.ask(question);

            assertThat(reply.mode()).isEqualTo("live");
            assertThat(reply.nodeIds()).containsExactly("queue");
            assertThat(model.prompt.getInstructions()).anySatisfy(message -> assertThat(message.getText())
                    .contains("DECISION_EVIDENCE_JSON", "\"adopted\"", "\"focus\":\"queue\"", "region-capabilities")
                    .doesNotContain("queue-pilot", "foreign-replay"));
            assertThat(model.prompt.getInstructions()).anySatisfy(message ->
                    assertThat(message.getText()).contains("I'd start with the queue."));
            model.result = """
                    {"text":"The queue is ready.","nodeIds":["unknown-node"]}
                    """;
            assertThatThrownBy(() -> chat.ask(question)).isInstanceOfSatisfying(ResponseStatusException.class,
                    failure -> assertThat(failure.getStatusCode().value()).isEqualTo(502));
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Fixture {
        @Bean
        Model model() {
            return new Model();
        }

        @Bean
        LlmService<?> decisionModel(Model model) {
            return new SpringAiLlmService("decision-test", "fixture", model::call);
        }
    }

    static class Model {
        private Prompt prompt;
        private String result = """
                {"text":"At adoption EU West had no approved managed queue.","nodeIds":["queue","queue"]}
                """;

        ChatResponse call(Prompt input) {
            prompt = input;
            return new ChatResponse(List.of(new Generation(new AssistantMessage(result))));
        }
    }
}
