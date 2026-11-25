package example.architecture;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
final class DecisionAgUi {

    private final DecisionChat chat;
    private final ObjectWriter json;

    DecisionAgUi(DecisionChat chat, JsonMapper json) {
        this.chat = chat;
        this.json = json.writer().without(SerializationFeature.INDENT_OUTPUT);
    }

    @PostMapping(path = "/api/decision/agent", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    ResponseEntity<StreamingResponseBody> run(@RequestBody RunAgentInput input) {
        if (!validId(input.threadId()) || !validId(input.runId())
                || input.messages() == null || input.messages().size() > 17) {
            throw badRequest("Supply a thread ID, a run ID, and up to 17 conversation messages.");
        }
        if (input.tools() != null && !input.tools().isEmpty() || input.context() != null && !input.context().isEmpty()) {
            throw badRequest("The decision agent loads its own evidence and accepts no client tools.");
        }
        var ids = new HashSet<String>();
        var turns = new ArrayList<DecisionChat.Turn>();
        for (var message : input.messages()) {
            if (message == null || !validId(message.id()) || !ids.add(message.id())) {
                throw badRequest("Supply a unique ID for each conversation message.");
            }
            turns.add(new DecisionChat.Turn(message.role(), message.content()));
        }
        var selection = input.state() == null ? new Selection("review", false) : input.state();
        var focus = input.forwardedProps() == null ? null : input.forwardedProps().focus();
        var prepared = chat.prepare(new DecisionChat.Request(selection.revision(), selection.replayDrill(), focus, turns));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.TEXT_EVENT_STREAM)
                .body(output -> stream(output, input, prepared));
    }

    private void stream(OutputStream output, RunAgentInput input, DecisionChat.Prepared prepared) throws IOException {
        write(output, Map.of("type", "RUN_STARTED", "threadId", input.threadId(), "runId", input.runId()));
        write(output, Map.of("type", "STATE_SNAPSHOT", "snapshot", Map.of(
                "revision", prepared.view().revision().key(), "replayDrill", prepared.view().graph().hypothetical())));
        DecisionChat.Reply answer;
        try {
            answer = chat.answer(prepared);
        } catch (ResponseStatusException failure) {
            write(output, Map.of("type", "RUN_ERROR", "code", "ANSWER_UNAVAILABLE", "message",
                    failure.getReason() == null ? "The decision agent could not answer. Try again." : failure.getReason()));
            return;
        }
        var messageId = UUID.randomUUID().toString();
        write(output, Map.of("type", "TEXT_MESSAGE_START", "messageId", messageId, "role", "assistant"));
        write(output, Map.of("type", "TEXT_MESSAGE_CONTENT", "messageId", messageId, "delta", answer.text()));
        write(output, Map.of("type", "TEXT_MESSAGE_END", "messageId", messageId));
        write(output, Map.of("type", "CUSTOM", "name", "decision.citations", "value",
                Map.of("messageId", messageId, "nodeIds", answer.nodeIds())));
        write(output, Map.of("type", "RUN_FINISHED", "threadId", input.threadId(), "runId", input.runId()));
    }

    private void write(OutputStream output, Map<String, ?> event) throws IOException {
        output.write(("data: " + json.writeValueAsString(event) + "\n\n").getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    private static boolean validId(String value) {
        return value != null && !value.isBlank() && value.length() <= 128
                && value.chars().noneMatch(Character::isISOControl);
    }

    private static ResponseStatusException badRequest(String detail) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, detail);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RunAgentInput(String threadId, String runId, Selection state, List<Message> messages,
                         List<JsonNode> tools, List<JsonNode> context, Focus forwardedProps) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Message(String id, String role, String content) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Selection(String revision, Boolean replayDrill) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Focus(String focus) { }
}
