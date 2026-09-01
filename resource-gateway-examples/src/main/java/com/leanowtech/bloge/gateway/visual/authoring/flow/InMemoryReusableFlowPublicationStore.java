package com.leanowtech.bloge.gateway.visual.authoring.flow;

import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSubjectRef;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** In-memory reference state machine for immutable Flow publication. */
public final class InMemoryReusableFlowPublicationStore implements ReusableFlowPublicationStore {
    private final Supplier<String> publicationIds;
    private final Clock clock;
    private final Map<FlowKey, String> identities = new LinkedHashMap<>();
    private final Map<VersionKey, ReusableFlowVersion> versions = new LinkedHashMap<>();
    private final Map<CommandKey, Command> commands = new LinkedHashMap<>();

    public InMemoryReusableFlowPublicationStore() {
        this(() -> "publication-" + UUID.randomUUID(), Clock.systemUTC());
    }

    public InMemoryReusableFlowPublicationStore(Supplier<String> publicationIds, Clock clock) {
        this.publicationIds = Objects.requireNonNull(publicationIds, "publicationIds");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override public synchronized ReusableFlowPublishResult publish(ReusableFlowPublishIntent intent) {
        requireIntent(intent);
        CommandKey commandKey = new CommandKey(intent.scope(), intent.actorId(),
                intent.flowId(), intent.idempotencyKey());
        Command prior = commands.get(commandKey);
        if (prior != null) {
            if (!prior.requestFingerprint().equals(intent.requestFingerprint())) {
                throw new ReusableFlowFailure(ReusableFlowFailure.Code.CONFLICT);
            }
            ReusableFlowVersion version = versions.get(prior.versionKey());
            if (version == null || !prior.receipt().version().equals(version.subject())) {
                throw new ReusableFlowFailure(ReusableFlowFailure.Code.INTEGRITY);
            }
            return new ReusableFlowPublishResult(version, prior.receipt(), true);
        }

        FlowKey flowKey = new FlowKey(intent.scope(), intent.flowId());
        String publicationId = identities.computeIfAbsent(flowKey, ignored -> nextIdentifier());
        int revision = versions.keySet().stream()
                .filter(key -> key.scope().equals(intent.scope()) && key.publicationId().equals(publicationId))
                .mapToInt(VersionKey::revision).max().orElse(0) + 1;
        ReusableFlowDraft draft = intent.draft();
        ReusableFlowVersion version = new ReusableFlowVersion(ReusableFlowVersion.SCHEMA_VERSION,
                publicationId, revision, intent.versionFingerprint(),
                new ReusableFlowVersion.Source(draft.draftId(), draft.revision(), draft.fingerprint()),
                draft.flowId(), draft.displayName(), draft.kind(), draft.description(),
                draft.contract(), draft.graph(), clock.instant(), intent.actorId(),
                ReusableFlowVersion.Status.PUBLISHED);
        FixtureSubjectRef.FlowDraft source = draft.subject();
        ReusableFlowPublishReceipt receipt = new ReusableFlowPublishReceipt(
                ReusableFlowPublishReceipt.SCHEMA_VERSION, source, version.subject(),
                ReusableFlowPublishReceipt.Catalog.AVAILABLE);
        VersionKey versionKey = new VersionKey(intent.scope(), publicationId, revision);
        versions.put(versionKey, version);
        commands.put(commandKey, new Command(intent.requestFingerprint(), versionKey, receipt));
        return new ReusableFlowPublishResult(version, receipt, false);
    }

    @Override public synchronized Optional<ReusableFlowVersion> findVersion(
            AuthoringScope scope, String publicationId, int revision) {
        if (scope == null || publicationId == null || revision < 1) return Optional.empty();
        return Optional.ofNullable(versions.get(new VersionKey(scope, publicationId, revision)));
    }

    @Override public synchronized Optional<ReusableFlowVersion> findLatestVersion(
            AuthoringScope scope, String flowId) {
        if (scope == null || flowId == null) return Optional.empty();
        String publicationId = identities.get(new FlowKey(scope, flowId));
        if (publicationId == null) return Optional.empty();
        return versions.entrySet().stream()
                .filter(entry -> entry.getKey().scope().equals(scope)
                        && entry.getKey().publicationId().equals(publicationId))
                .max(java.util.Comparator.comparingInt(entry -> entry.getKey().revision()))
                .map(Map.Entry::getValue);
    }

    private static void requireIntent(ReusableFlowPublishIntent intent) {
        if (intent == null || !intent.flowId().equals(intent.draft().flowId())
                || intent.actorId().isBlank() || intent.idempotencyKey().isBlank()
                || intent.idempotencyKey().length() > 160
                || !intent.requestFingerprint().matches("sha256:[0-9a-f]{64}")
                || !intent.versionFingerprint().matches("sha256:[0-9a-f]{64}")) {
            throw new ReusableFlowFailure(ReusableFlowFailure.Code.VALIDATION);
        }
    }

    private String nextIdentifier() {
        String value = publicationIds.get();
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw new ReusableFlowFailure(ReusableFlowFailure.Code.INTEGRITY);
        }
        return value;
    }

    private record FlowKey(AuthoringScope scope, String flowId) { }
    private record VersionKey(AuthoringScope scope, String publicationId, int revision) { }
    private record CommandKey(AuthoringScope scope, String actorId, String flowId, String idempotencyKey) { }
    private record Command(String requestFingerprint, VersionKey versionKey,
                           ReusableFlowPublishReceipt receipt) { }
}
