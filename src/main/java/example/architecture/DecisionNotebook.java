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
    private static final List<ProofRule> RULES = List.of(
            new ProofRule("retain_polling", List.of("lacks(Decision, regional_queue_absent)",
                    "lacks(Decision, idempotency_absent)")),
            new ProofRule("revisit_polling", List.of("verifies(Decision, regional_queue)",
                    "verifies(Decision, idempotency)")),
            new ProofRule("migration_ready", List.of("verifies(Decision, regional_queue)",
                    "verifies(Decision, idempotency)", "verifies(Decision, replay_drill)")));
    private static final List<Topic> TOPICS = List.of(
            new Topic("Queue availability", "The managed queue was unavailable in one dispatch region.", "queue-old", "queue-new"),
            new Topic("Idempotent delivery", "Consumers could not deduplicate retried deliveries.", "idempotency-old", "idempotency-new"),
            new Topic("Replay recovery", "Can a failed batch be replayed without repeating side effects?", null, "replay-drill"));

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
        var revision = REVISIONS.stream().filter(candidate -> candidate.key().equals(revisionKey)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown revision"));
        var scope = ScopedReadSpec.of(ContextReadScope.ofValues(CONTEXT));
        var scoped = repository.scopedReads().query(scope, ReadQuery.of(ReadCriteria.empty()), ReadBudget.ofRows(25));
        var byId = new LinkedHashMap<String, Proposition>();
        scoped.forEach(claim -> byId.put(claim.getId(), claim));
        var statuses = new LinkedHashMap<String, PropositionStatus>();
        audit.beliefSetAsOf(CONTEXT, revision.at()).forEach(belief -> statuses.put(belief.getId(), belief.getStatus()));

        var active = scoped.stream()
                .filter(claim -> statuses.get(claim.getId()) == PropositionStatus.ACTIVE)
                .map(claim -> claim.withStatus(PropositionStatus.ACTIVE))
                .toList();
        var provider = provider(active);
        var review = findings(provider, byId, "revisit_polling", "Revisit polling");
        var posture = review.rule() == null ? findings(provider, byId, "retain_polling", "Retain polling") : review;
        var migration = findings(provider, byId, "migration_ready", "Ready for migration review");
        if (migration.rule() == null) {
            migration = new Finding("Replay safety unproven", null, migration.logic(), migration.evidence());
        }

        var assumptions = TOPICS.stream().map(topic -> assumption(topic, statuses, byId)).toList();
        return new DecisionView(revision, REVISIONS, decision, assumptions, posture, migration);
    }

    private Assumption assumption(Topic topic, Map<String, PropositionStatus> statuses, Map<String, Proposition> byId) {
        var original = topic.original() == null ? null : source(byId.get(topic.original()));
        if (statuses.get(topic.newer()) == PropositionStatus.ACTIVE) {
            return new Assumption(topic.title(), topic.check(), "superseded", original, source(byId.get(topic.newer())));
        }
        if (topic.original() != null && statuses.get(topic.original()) == PropositionStatus.ACTIVE) {
            return new Assumption(topic.title(), topic.check(), "holding", original, null);
        }
        return new Assumption(topic.title(), topic.check(), "unproven", null, null);
    }

    private Finding findings(PrologEvidenceProvider provider, Map<String, Proposition> byId, String ruleName, String label) {
        var rule = RULES.stream().filter(candidate -> candidate.name().equals(ruleName)).findFirst().orElseThrow();
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
        var rules = RULES.stream().map(ProofRule::prolog).reduce("", (one, two) -> one + "\n" + two);
        var schema = PrologSchema.Companion.withRules(rules, List.of());
        var projection = new DefaultPrologProjector(null, schema, true, true).projectAll(edges);
        var result = PrologEvidenceProvider.of(projection, schema);
        for (var rule : RULES) {
            result = result.withDeclaration(rule.declaration());
        }
        return result;
    }

    private Proposition claim(String id, String predicate, String item, String label, String type, EvidenceSource evidence) {
        var locator = new ConnectorRef("synthetic-architecture-record", evidence.filename() + ":" + evidence.line());
        sourcesByLocator.put(locator.key(), evidence);
        return Proposition.draft(CONTEXT, "Webhook dispatch " + predicate + " " + label, 0.98)
                .withId(id)
                .withMentions(List.of(new EntityMention("Webhook dispatch", "Decision", DECISION, MentionRole.SUBJECT),
                        new EntityMention(label, type, item, MentionRole.OBJECT)))
                .withGrounding(List.of(locator.getExternalId()))
                .withProvenance(List.of(new ProvenanceEntry(locator, locator.getExternalId())))
                .toProposition();
    }

    private EvidenceSource source(Proposition claim) {
        return sourcesByLocator.get(claim.getProvenanceEntries().getFirst().getLocator().key());
    }

    record Revision(String key, String label, Instant at) { }
    record EvidenceSource(String title, String filename, int line, String date, String excerpt) { }
    record Assumption(String title, String check, String status, EvidenceSource original, EvidenceSource update) { }
    record Finding(String label, String rule, String logic, List<EvidenceSource> evidence) { }
    record DecisionView(Revision revision, List<Revision> revisions, EvidenceSource decision, List<Assumption> assumptions,
                        Finding posture, Finding migration) { }
    private record Topic(String title, String check, String original, String newer) { }

    private record ProofRule(String name, List<String> inputs) {
        String prolog() {
            return name + "(Decision) :-\n    " + String.join(",\n    ", inputs) + ".";
        }

        RuleDeclaration declaration() {
            var result = RuleDeclaration.of(name + "(Decision)");
            for (var input : inputs) {
                result = result.withInput(input);
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
