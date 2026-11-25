package example.architecture;

import com.embabel.agent.core.DataDictionary;
import com.embabel.dice.audit.AuditingPropositionRepository;
import com.embabel.dice.audit.InMemoryAuditLog;
import com.embabel.dice.common.Relations;
import com.embabel.dice.evidence.Evidence;
import com.embabel.dice.evidence.RuleDeclaration;
import com.embabel.dice.evidence.RuleGoal;
import com.embabel.dice.projection.graph.RelationBasedGraphProjector;
import com.embabel.dice.projection.prolog.DefaultPrologProjector;
import com.embabel.dice.projection.prolog.PrologEvidenceProvider;
import com.embabel.dice.projection.prolog.PrologSchema;
import com.embabel.dice.proposition.EntityMention;
import com.embabel.dice.proposition.MentionRole;
import com.embabel.dice.proposition.Proposition;
import com.embabel.dice.proposition.PropositionStatus;
import com.embabel.dice.proposition.store.InMemoryPropositionRepository;
import com.embabel.dice.provenance.ConnectorRef;
import com.embabel.dice.provenance.ProvenanceEntry;
import com.embabel.dice.store.ContextReadScope;
import com.embabel.dice.store.ReadBudget;
import com.embabel.dice.store.ReadCriteria;
import com.embabel.dice.store.ReadQuery;
import com.embabel.dice.store.ScopedReadSpec;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
final class DecisionNotebook {

    private static final String CONTEXT = "adr-024";
    private static final String DECISION = "outbound_webhooks";
    private static final Revision ADOPTED = new Revision("adopted", "At adoption", Instant.parse("2026-09-14T16:10:00Z"));
    private static final Revision REVIEW = new Revision("review", "Latest review", Instant.parse("2026-11-21T15:45:00Z"));
    private static final List<Revision> REVISIONS = List.of(ADOPTED, REVIEW);
    private static final Input NO_QUEUE = new Input("queue_gap", "queue", "lacks(Decision, regional_queue_absent)");
    private static final Input NO_DEDUP = new Input("idempotency_gap", "idempotency", "lacks(Decision, idempotency_absent)");
    private static final Input QUEUE = new Input("queue_verified", "queue", "verifies(Decision, regional_queue)");
    private static final Input DEDUP = new Input("idempotency_verified", "idempotency", "verifies(Decision, idempotency)");
    private static final Input REPLAY = new Input("replay_verified", "replay", "verifies(Decision, replay_drill)");
    private static final List<ProofRule> RULES = List.of(
            new ProofRule("retain_polling", List.of(NO_QUEUE, NO_DEDUP)),
            new ProofRule("revisit_polling", List.of(QUEUE, DEDUP)),
            new ProofRule("migration_ready", List.of(QUEUE, DEDUP, REPLAY)));
    private static final List<Input> INPUTS = List.of(NO_QUEUE, NO_DEDUP, QUEUE, DEDUP, REPLAY);
    private static final List<ProofRule> PROBES = INPUTS.stream()
            .map(input -> new ProofRule(input.key(), List.of(input))).toList();
    private static final String HYPOTHETICAL = "hypothetical-replay";
    private static final EvidenceSource SIMULATED_REPLAY = new EvidenceSource("Assumed passing replay drill",
            "Scenario input", 0, null, "Assume the same outbound webhook batch can be replayed without repeating side effects.");
    private static final List<Topic> TOPICS = List.of(
            new Topic("queue", "Queue availability", "The managed queue was unavailable in one dispatch region.", "queue-old", "queue-new"),
            new Topic("idempotency", "Idempotent delivery", "Consumers could not deduplicate retried deliveries.", "idempotency-old", "idempotency-new"),
            new Topic("replay", "Replay recovery", "Can a failed batch be replayed without repeating side effects?", null, "replay-drill"));

    private final DecisionProperties properties;
    private final ReplayClock clock = new ReplayClock(Instant.parse("2026-09-14T16:00:00Z"));
    private final InMemoryAuditLog audit = new InMemoryAuditLog(clock);
    private final InMemoryPropositionRepository repository = new InMemoryPropositionRepository();
    private final Map<String, EvidenceSource> sourcesByLocator = new LinkedHashMap<>();
    private final EvidenceSource decision = new EvidenceSource("ADR authored by Payments Platform",
            "docs/adr/0024-webhook-dispatch.md", 23, "2026-09-14",
            "Dispatch from a database outbox every 30 seconds. Revisit when queue support exists in both regions "
                    + "and consumers can deduplicate delivery attempts.");

    DecisionNotebook(DecisionProperties properties) {
        this.properties = properties;
        audit.initializeBaseline(CONTEXT, clock.instant(), List.of());
        var audited = AuditingPropositionRepository.wrapping(repository, audit);

        clock.advance(Instant.parse("2026-09-14T16:03:00Z"));
        var queueOld = claim("queue-old", "lacks", "regional_queue_absent", "regional queue availability", "Gap",
                new EvidenceSource("Regional service matrix", "infra/region-capabilities.md", 37, "2026-09-14",
                        "EU West does not have a managed durable queue in the approved platform catalogue."));
        var idempotencyOld = claim("idempotency-old", "lacks", "idempotency_absent", "idempotent delivery", "Gap",
                new EvidenceSource("Consumer delivery contract", "webhooks/consumer-contract.md", 62, "2026-09-14",
                        "Consumers cannot rely on a stable delivery identifier across retries."));
        audited.save(queueOld);
        audited.save(idempotencyOld);

        clock.advance(Instant.parse("2026-11-21T15:30:00Z"));
        audited.save(queueOld.withStatus(PropositionStatus.SUPERSEDED));
        audited.save(claim("queue-new", "verifies", "regional_queue", "regional queue availability", "Capability",
                new EvidenceSource("EU West queue deployment", "infra/eu-west-1-queue-pilot.md", 48, "2026-11-21",
                        "The durable webhook queue is deployed in EU West. It uses the same retry policy as US East.")));

        clock.advance(Instant.parse("2026-11-21T15:38:00Z"));
        audited.save(idempotencyOld.withStatus(PropositionStatus.SUPERSEDED));
        audited.save(claim("idempotency-new", "verifies", "idempotency", "idempotent delivery", "Capability",
                new EvidenceSource("Delivery contract v2", "webhooks/delivery-contract-v2.md", 81, "2026-11-21",
                        "Consumers deduplicate a ten-minute retry window by stable event_id. The integration suite passes.")));

        repository.save(Proposition.draft("different-adr", "Webhook dispatch verifies batch replay", 1.0)
                .withId("foreign-replay")
                .withMentions(List.of(new EntityMention("Webhook dispatch", "Decision", DECISION, MentionRole.SUBJECT),
                        new EntityMention("batch replay", "Capability", "replay_drill", MentionRole.OBJECT)))
                .toProposition());
        clock.advance(REVIEW.at());
    }

    DecisionView inspect(String revisionKey) {
        return inspect(revisionKey, false);
    }

    DecisionView inspect(String revisionKey, boolean replayDrill) {
        var revision = REVISIONS.stream().filter(candidate -> candidate.key().equals(revisionKey)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown revision"));
        var scope = ScopedReadSpec.of(ContextReadScope.ofValues(CONTEXT));
        var scoped = repository.scopedReads().query(scope, ReadQuery.of(ReadCriteria.empty()), ReadBudget.ofRows(25));
        var byId = new LinkedHashMap<String, Proposition>();
        scoped.forEach(claim -> byId.put(claim.getId(), claim));
        var statuses = new LinkedHashMap<String, PropositionStatus>();
        audit.beliefSetAsOf(CONTEXT, revision.at()).forEach(belief -> statuses.put(belief.getId(), belief.getStatus()));

        var active = new ArrayList<>(scoped.stream()
                .filter(claim -> statuses.get(claim.getId()) == PropositionStatus.ACTIVE)
                .map(claim -> claim.withStatus(PropositionStatus.ACTIVE))
                .toList());
        if (replayDrill) {
            var hypothesis = proposition(HYPOTHETICAL, "verifies", "replay_drill", "successful replay drill",
                    "Capability", new ConnectorRef("scenario", HYPOTHETICAL));
            active.add(hypothesis);
            byId.put(hypothesis.getId(), hypothesis);
        }
        var provider = provider(active);
        var retain = findings(provider, byId, "retain_polling", "Retain polling");
        var review = findings(provider, byId, "revisit_polling", "Revisit polling");
        var posture = review.rule() == null ? retain : review;
        var migration = findings(provider, byId, "migration_ready", "Ready for migration review");
        var checks = new LinkedHashMap<Input, Finding>();
        for (var input : INPUTS) {
            checks.put(input, findings(provider, byId, input.key(), input.condition()));
        }
        if (migration.rule() == null) {
            var label = checks.get(REPLAY).rule() == null ? "Replay safety unproven" : "Migration prerequisites incomplete";
            migration = new Finding(label, null, migration.logic(), migration.evidence());
        }

        var assumptions = TOPICS.stream().map(topic -> assumption(topic, statuses, byId)).toList();
        var graph = DecisionGraph.build(decision, assumptions, RULES, checks, retain, review, migration, replayDrill,
                SIMULATED_REPLAY);
        return new DecisionView(revision, REVISIONS, decision, assumptions, posture, migration, graph, List.of());
    }

    private Assumption assumption(Topic topic, Map<String, PropositionStatus> statuses, Map<String, Proposition> byId) {
        var original = topic.original() == null ? null : source(byId.get(topic.original()));
        if (statuses.get(topic.newer()) == PropositionStatus.ACTIVE) {
            return new Assumption(topic.id(), topic.title(), topic.check(), "superseded", original, source(byId.get(topic.newer())));
        }
        if (topic.original() != null && statuses.get(topic.original()) == PropositionStatus.ACTIVE) {
            return new Assumption(topic.id(), topic.title(), topic.check(), "holding", original, null);
        }
        return new Assumption(topic.id(), topic.title(), topic.check(), "unproven", null, null);
    }

    private Finding findings(PrologEvidenceProvider provider, Map<String, Proposition> byId, String ruleName, String label) {
        var rule = java.util.stream.Stream.concat(RULES.stream(), PROBES.stream()).filter(candidate -> candidate.name().equals(ruleName)).findFirst().orElseThrow();
        var evidence = provider.evidence(RuleGoal.of(rule.name() + "(" + DECISION + ")").withContext(CONTEXT));
        if (evidence instanceof Evidence.Derived result) {
            return new Finding(label, result.getRule().getRuleId(), rule.prolog(),
                    result.getInputIds().stream().map(byId::get).map(this::source).toList());
        }
        return new Finding("No source-backed conclusion", null, rule.prolog(), List.of());
    }

    private PrologEvidenceProvider provider(List<Proposition> active) {
        var relations = Relations.empty()
                .withSemanticBetween("Decision", "Gap", "lacks", "missing operating capability")
                .withSemanticBetween("Decision", "Capability", "verifies", "established operating capability");
        var edges = RelationBasedGraphProjector.from(relations).withDefaultPolicy(properties.minimumEvidenceConfidence())
                .projectAll(active, DataDictionary.fromClasses("architecture-decisions")).getProjected();
        var declared = java.util.stream.Stream.concat(RULES.stream(), PROBES.stream()).toList();
        var rules = declared.stream().map(ProofRule::prolog).reduce("", (one, two) -> one + "\n" + two);
        var schema = PrologSchema.Companion.withRules("lacks(_, _) :- fail.\nverifies(_, _) :- fail.\n" + rules, List.of());
        var projection = new DefaultPrologProjector(null, schema, true, true).projectAll(edges);
        var result = PrologEvidenceProvider.of(projection, schema);
        for (var rule : declared) {
            result = result.withDeclaration(rule.declaration());
        }
        return result;
    }

    private Proposition claim(String id, String predicate, String item, String label, String type, EvidenceSource evidence) {
        var locator = new ConnectorRef("synthetic-architecture-record", evidence.filename() + ":" + evidence.line());
        sourcesByLocator.put(locator.key(), evidence);
        return proposition(id, predicate, item, label, type, locator);
    }

    private static Proposition proposition(String id, String predicate, String item, String label, String type,
                                           ConnectorRef locator) {
        return Proposition.draft(CONTEXT, "Webhook dispatch " + predicate + " " + label, 0.98)
                .withId(id)
                .withMentions(List.of(new EntityMention("Webhook dispatch", "Decision", DECISION, MentionRole.SUBJECT),
                        new EntityMention(label, type, item, MentionRole.OBJECT)))
                .withGrounding(List.of(locator.getExternalId()))
                .withProvenance(List.of(new ProvenanceEntry(locator, locator.getExternalId())))
                .toProposition();
    }

    private EvidenceSource source(Proposition claim) {
        if (HYPOTHETICAL.equals(claim.getId())) {
            return SIMULATED_REPLAY;
        }
        return sourcesByLocator.get(claim.getProvenanceEntries().getFirst().getLocator().key());
    }

    record Revision(String key, String label, Instant at) { }
    record EvidenceSource(String title, String filename, int line, String date, String excerpt) { }
    record Assumption(String id, String title, String check, String status, EvidenceSource original, EvidenceSource update) { }
    record Finding(String label, String rule, String logic, List<EvidenceSource> evidence) { }
    record DecisionView(Revision revision, List<Revision> revisions, EvidenceSource decision, List<Assumption> assumptions,
                        Finding posture, Finding migration, DecisionGraph graph, List<DecisionRecord.Entry> history) { }
    private record Topic(String id, String title, String check, String original, String newer) { }

    record Input(String key, String condition, String goal) { }

    record ProofRule(String name, List<Input> inputs) {
        String prolog() {
            return name + "(Decision) :-\n    " + String.join(",\n    ", inputs.stream().map(Input::goal).toList()) + ".";
        }

        RuleDeclaration declaration() {
            var result = RuleDeclaration.of(name + "(Decision)");
            for (var input : inputs) {
                result = result.withInput(input.goal());
            }
            return result;
        }
    }

    private static final class ReplayClock extends Clock {
        private Instant instant;

        private ReplayClock(Instant start) {
            instant = start;
        }

        private void advance(Instant at) {
            instant = at;
        }

        @Override
        public Instant instant() {
            return instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(instant, zone);
        }
    }
}
