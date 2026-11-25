package example.architecture;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;

@Service
final class DecisionChat {

    private final DecisionNotebook notebook;
    private final ObjectProvider<DecisionAgent> agent;

    DecisionChat(DecisionNotebook notebook, ObjectProvider<DecisionAgent> agent) {
        this.notebook = notebook;
        this.agent = agent;
    }

    String mode() {
        return agent.getIfAvailable() == null ? "guided" : "live";
    }

    Reply ask(Request request) {
        var turns = request.turns();
        if (turns == null || turns.isEmpty() || turns.size() > 17
                || turns.stream().anyMatch(DecisionChat::invalidTurn) || !"user".equals(turns.getLast().role())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Send up to 17 conversation turns, ending with a question. Each turn can use up to 1,000 characters.");
        }
        DecisionNotebook.DecisionView view;
        try {
            view = notebook.inspect(request.revision(), Boolean.TRUE.equals(request.replayDrill()));
        } catch (IllegalArgumentException error) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown decision revision");
        }
        if (request.focus() != null && view.graph().nodes().stream().noneMatch(n -> n.id().equals(request.focus()))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown decision graph node");
        }
        var model = agent.getIfAvailable();
        if (model == null) {
            return guided(view, request.focus(), turns);
        }
        try {
            var draft = model.answer(view, request.focus(), turns);
            if (draft == null || draft.text() == null || draft.text().isBlank() || draft.nodeIds() == null
                    || draft.nodeIds().stream().anyMatch(id -> view.graph().nodes().stream().noneMatch(n -> n.id().equals(id)))) {
                throw new IllegalStateException("The model returned an invalid decision answer.");
            }
            if (draft.nodeIds().isEmpty()) {
                return outsideRecord(view, "live");
            }
            return new Reply(draft.text(), draft.nodeIds().stream().distinct().toList(), "live");
        } catch (RuntimeException error) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "The live model could not answer from this record. Check the provider configuration and try again.");
        }
    }

    private static boolean invalidTurn(Turn turn) {
        return turn == null || !("user".equals(turn.role()) || "assistant".equals(turn.role()))
                || turn.content() == null || turn.content().isBlank() || turn.content().length() > 1000;
    }

    private static Reply guided(DecisionNotebook.DecisionView view, String focus, List<Turn> turns) {
        var question = turns.getLast().content().toLowerCase(Locale.ROOT);
        var subject = topic(question);
        var followUp = question.strip().matches("(why|how so|what about (it|that|this)|what (does|did|is) (this|that|it).*|what evidence is missing|show (me )?the (evidence|source)|which source)[?.!]*");
        if (subject == null && followUp) {
            subject = focus;
            for (var i = turns.size() - 2; subject == null && i >= 0; i--) {
                if ("user".equals(turns.get(i).role())) {
                    subject = topic(turns.get(i).content().toLowerCase(Locale.ROOT));
                }
            }
        }
        if (subject == null) {
            return outsideRecord(view, "guided");
        }
        if ("posture".equals(subject)) {
            subject = switch (view.posture().label()) {
                case "Retain polling" -> "retain";
                case "Revisit polling" -> "revisit";
                default -> "adr";
            };
        }
        var selected = find(view, subject);
        if ("migrate".equals(subject)) {
            var required = view.graph().edges().stream().filter(edge -> edge.to().equals("migrate")).toList();
            var blocked = required.stream().filter(edge -> "blocked".equals(edge.status()))
                    .map(edge -> find(view, edge.from()).title()).toList();
            var text = blocked.isEmpty()
                    ? "The selected scenario satisfies the three prerequisites for migration review. "
                    : "Migration review still needs evidence for " + String.join(", ", blocked) + ". ";
            return new Reply(text + "Polling remains the approved decision.", List.of("migrate", "adr"), "guided");
        }
        if ("adr".equals(subject)) {
            var historicalPremises = view.assumptions().stream().map(DecisionNotebook.Assumption::original)
                    .filter(java.util.Objects::nonNull).map(DecisionNotebook.EvidenceSource::excerpt).toList();
            return new Reply(selected.description() + "\n\nAt adoption: " + String.join(" ", historicalPremises),
                    List.of("adr", "queue-source", "idempotency-source"), "guided");
        }
        var evidence = selected.sources().stream().map(DecisionNotebook.EvidenceSource::excerpt).toList();
        var explanation = "outcome".equals(selected.kind())
                ? selected.status() + ". " + selected.description() : selected.description();
        return new Reply(explanation + (evidence.isEmpty() || evidence.contains(explanation)
                ? "" : "\n\n" + String.join("\n\n", evidence)), List.of(selected.id()), "guided");
    }

    private static Reply outsideRecord(DecisionNotebook.DecisionView view, String mode) {
        var direction = view.graph().hypothetical()
                ? "how the assumed replay drill changes the review"
                : "what still blocks migration review";
        return new Reply("I can’t answer that from this decision record. Would you like to know " + direction + "?",
                List.of(), mode);
    }

    private static DecisionGraph.Node find(DecisionNotebook.DecisionView view, String id) {
        return view.graph().nodes().stream().filter(node -> node.id().equals(id)).findFirst().orElseThrow();
    }

    private static String topic(String question) {
        if (question.matches(".*\\b(migrat\\w*|prerequisite\\w*|block\\w*|ready|switch|move)\\b.*")) {
            return "migrate";
        }
        if (question.matches(".*\\b(replay|drill|recover\\w*)\\b.*")) {
            return "replay";
        }
        if (question.matches(".*\\b(dedup\\w*|idempoten\\w*|event_id|retr(?:y|ies))\\b.*")) {
            return "idempotency";
        }
        if (question.matches(".*\\b(chang\\w*|revisit|recommend\\w*|posture|now)\\b.*")) {
            return "posture";
        }
        if (question.matches(".*\\b(poll\\w*|approv\\w*|adopt\\w*|adr|outbox|chose)\\b.*")) {
            return "adr";
        }
        if (question.matches(".*\\b(queue|region\\w*)\\b.*")) {
            return "queue";
        }
        return null;
    }

    record Turn(String role, String content) { }
    record Request(String revision, Boolean replayDrill, String focus, List<Turn> turns) { }
    record Reply(String text, List<String> nodeIds, String mode) { }
}

@RestController
class DecisionChatController {
    private final DecisionChat chat;

    DecisionChatController(DecisionChat chat) {
        this.chat = chat;
    }

    @GetMapping("/api/decision/chat")
    Mode mode() {
        return new Mode(chat.mode());
    }

    @PostMapping("/api/decision/chat")
    DecisionChat.Reply ask(@RequestBody DecisionChat.Request request) {
        return chat.ask(request);
    }

    record Mode(String mode) { }
}
