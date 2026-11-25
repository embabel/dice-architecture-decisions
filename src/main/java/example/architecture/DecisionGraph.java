package example.architecture;

import example.architecture.DecisionNotebook.Assumption;
import example.architecture.DecisionNotebook.EvidenceSource;
import example.architecture.DecisionNotebook.Finding;
import example.architecture.DecisionNotebook.Input;
import example.architecture.DecisionNotebook.ProofRule;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** An inspectable view of the same clauses and evidence that went into the notebook's proof. */
record DecisionGraph(List<Node> nodes, List<Edge> edges, boolean hypothetical) {

    record Node(String id, String kind, String title, String status, String description, String logic,
                List<EvidenceSource> sources) { }

    record Edge(String from, String to, String status, String label) { }

    static DecisionGraph build(EvidenceSource approved, List<Assumption> assumptions, List<ProofRule> rules,
                               Map<Input, Finding> checks, Finding retain, Finding revisit, Finding migrate,
                               boolean hypothetical, EvidenceSource simulation) {
        var nodes = new ArrayList<Node>();
        var edges = new ArrayList<Edge>();
        nodes.add(new Node("adr", "decision", "Poll for outbound webhooks", "Approved", approved.excerpt(),
                null, List.of(approved)));
        for (var assumption : assumptions) {
            var condition = assumption.id();
            var proofs = checks.entrySet().stream().filter(e -> condition.equals(e.getKey().condition()))
                    .filter(e -> e.getValue().rule() != null).toList();
            var simulated = hypothetical && condition.equals("replay");
            var sources = simulated ? List.of(simulation)
                    : Stream.of(assumption.original(), assumption.update()).filter(java.util.Objects::nonNull).toList();
            var status = simulated ? "Assumed" : switch (assumption.status()) {
                case "holding" -> "Original evidence";
                case "superseded" -> "Updated evidence";
                default -> "No recorded proof";
            };
            nodes.add(new Node(condition + "-source", "source", assumption.title(),
                    status, assumption.check(), null, sources));
            var verified = proofs.stream().anyMatch(e -> e.getKey().goal().startsWith("verifies"));
            var conditionStatus = proofs.isEmpty() ? "Unproven" : verified ? (simulated ? "Assumed" : "Verified") : "Gap";
            var detail = proofs.isEmpty() ? "The selected evidence did not prove this condition."
                    : verified ? "This capability meets the rule's evidence requirement."
                    : "At this point in time the evidence established a gap.";
            nodes.add(new Node(condition, "condition", assumption.title(), conditionStatus, detail, null,
                    proofs.stream().flatMap(e -> e.getValue().evidence().stream()).distinct().toList()));
            edges.add(new Edge(condition + "-source", condition,
                    proofs.isEmpty() ? "blocked" : simulated ? "hypothetical" : "proved", "Supports check"));
        }
        var outcomes = List.of(retain, revisit, migrate);
        var ids = List.of("retain", "revisit", "migrate");
        var titles = List.of("Retain polling", "Revisit polling", "Migration review");
        var descriptions = List.of("Both original capability gaps support keeping the poller.",
                "Verifying a durable queue and deduplication supports reopening the ADR.",
                "Queue availability, deduplication and a successful replay drill are required for migration review. Approval remains a separate decision.");
        for (int i = 0; i < rules.size(); i++) {
            var rule = rules.get(i);
            var finding = outcomes.get(i);
            var id = ids.get(i);
            var status = finding.rule() == null ? "Blocked" : hypothetical && "migrate".equals(id)
                    ? "Ready in scenario" : "Derived";
            nodes.add(new Node(id, "outcome", titles.get(i), status, descriptions.get(i), finding.logic(),
                    finding.evidence()));
            for (var input : rule.inputs()) {
                var edgeStatus = checks.get(input).rule() == null ? "blocked"
                        : hypothetical && "replay".equals(input.condition()) ? "hypothetical" : "proved";
                edges.add(new Edge(input.condition(), id, edgeStatus, input.goal().startsWith("lacks")
                        ? "Requires gap" : "Requires capability"));
            }
        }
        return new DecisionGraph(List.copyOf(nodes), List.copyOf(edges), hypothetical);
    }
}
