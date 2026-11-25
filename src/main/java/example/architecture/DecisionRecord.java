package example.architecture;

import com.embabel.dice.proposition.Proposition;
import com.embabel.dice.proposition.PropositionRepository;
import com.embabel.dice.proposition.store.JsonFilePropositionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
final class DecisionRecord {

    private static final String CONTEXT = "adr-024-decision-log";
    private final DecisionNotebook notebook;
    private final PropositionRepository versions;

    @Autowired
    DecisionRecord(DecisionNotebook notebook, @Value("${decisions.history-file}") Path path) {
        this(notebook, new JsonFilePropositionRepository(path));
    }

    DecisionRecord(DecisionNotebook notebook, PropositionRepository versions) {
        this.notebook = notebook;
        this.versions = versions;
    }

    synchronized DecisionNotebook.DecisionView inspect(String revision, boolean replayDrill) {
        var baseline = notebook.inspect(revision, replayDrill);
        if (!"review".equals(baseline.revision().key())) {
            return baseline;
        }
        var entries = history();
        var nodes = new ArrayList<>(baseline.graph().nodes());
        var edges = new ArrayList<>(baseline.graph().edges());
        var currentVersions = new HashMap<String, String>();
        entries.forEach(entry -> currentVersions.put(entry.lineage(), entry.id()));

        for (var entry : entries) {
            var status = currentVersions.get(entry.lineage()).equals(entry.id()) ? "Current" : "Superseded";
            var evidence = new DecisionNotebook.EvidenceSource("Recorded decision · v" + entry.version(),
                    "Decision log", 0, entry.recordedAt().toString(), entry.rationale());
            nodes.add(new DecisionGraph.Node(entry.id(), "decision", entry.title(), status,
                    entry.rationale(), null, List.of(evidence)));
            if (entry.related() != null) {
                edges.add(new DecisionGraph.Edge(entry.related(), entry.id(), "recorded", "Linked evidence"));
            }
            if (entry.previous() != null) {
                edges.add(new DecisionGraph.Edge(entry.previous(), entry.id(), "recorded", "Supersedes"));
            }
            if (entry.restored() != null && !entry.restored().equals(entry.previous())) {
                edges.add(new DecisionGraph.Edge(entry.restored(), entry.id(), "restored", "Restored from"));
            }
        }
        return new DecisionNotebook.DecisionView(baseline.revision(), baseline.revisions(), baseline.decision(),
                baseline.assumptions(), baseline.posture(), baseline.migration(),
                new DecisionGraph(List.copyOf(nodes), List.copyOf(edges), replayDrill), entries);
    }

    synchronized Entry write(Change change) {
        if (change == null || !"review".equals(change.revision()) || !Boolean.FALSE.equals(change.replayDrill())) {
            throw failure(HttpStatus.BAD_REQUEST, "Record decisions from the latest review with the what-if scenario off.");
        }
        var entries = history();
        var head = change.expectedHead() == null ? null : find(entries, change.expectedHead());
        if (head != null && entries.stream().anyMatch(entry -> head.id().equals(entry.previous()))) {
            throw failure(HttpStatus.CONFLICT, "This decision changed in another tab. Reopen its current version and try again.");
        }
        var restored = change.restoreFrom() == null ? null : find(entries, change.restoreFrom());
        if (restored != null && (head == null || !head.lineage().equals(restored.lineage()) || head.id().equals(restored.id()))) {
            throw failure(HttpStatus.BAD_REQUEST, "Choose an earlier version of this decision to restore.");
        }

        var title = restored == null ? required(change.title(), 100, "Enter a decision title of up to 100 characters.") : restored.title();
        var rationale = restored == null ? required(change.rationale(), 2000, "Enter a rationale of up to 2000 characters.") : restored.rationale();
        var related = restored == null ? change.related() : restored.related();
        if (related != null && inspect("review", false).graph().nodes().stream().noneMatch(node -> node.id().equals(related))) {
            throw failure(HttpStatus.BAD_REQUEST, "Choose a link from the decision graph.");
        }

        var id = "decision-" + UUID.randomUUID();
        var entry = new Entry(id, head == null ? id : head.lineage(), head == null ? 1 : head.version() + 1, title,
                rationale, Instant.now(), related, head == null ? null : head.id(), restored == null ? null : restored.id());
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("lineage", entry.lineage());
        metadata.put("version", entry.version());
        metadata.put("title", entry.title());
        if (entry.previous() != null) metadata.put("previous", entry.previous());
        if (entry.restored() != null) metadata.put("restored", entry.restored());
        if (entry.related() != null) metadata.put("related", entry.related());
        versions.save(Proposition.draft(CONTEXT, rationale, 1.0).withId(id)
                .withCreated(entry.recordedAt()).withMetadata(metadata).withPinned(true).toProposition());
        return entry;
    }

    private List<Entry> history() {
        return versions.findByContextIdValue(CONTEXT).stream().map(DecisionRecord::entry)
                .sorted(Comparator.comparing(Entry::lineage).thenComparingInt(Entry::version)).toList();
    }

    private static Entry entry(Proposition proposition) {
        Map<String, Object> metadata = proposition.getMetadata();
        return new Entry(proposition.getId(), (String) metadata.get("lineage"), ((Number) metadata.get("version")).intValue(),
                (String) metadata.get("title"), proposition.getText(), proposition.getCreated(),
                (String) metadata.get("related"), (String) metadata.get("previous"), (String) metadata.get("restored"));
    }

    private static Entry find(List<Entry> entries, String id) {
        return entries.stream().filter(entry -> entry.id().equals(id)).findFirst()
                .orElseThrow(() -> failure(HttpStatus.NOT_FOUND, "That decision version no longer exists."));
    }

    private static String required(String value, int limit, String message) {
        if (value == null || value.isBlank() || value.length() > limit) {
            throw failure(HttpStatus.BAD_REQUEST, message);
        }
        return value.strip();
    }

    private static ResponseStatusException failure(HttpStatus code, String detail) {
        return new ResponseStatusException(code, detail);
    }

    record Entry(String id, String lineage, int version, String title, String rationale, Instant recordedAt,
                 String related, String previous, String restored) { }

    record Change(String revision, Boolean replayDrill, String title, String rationale, String related,
                  String expectedHead, String restoreFrom) { }
}
