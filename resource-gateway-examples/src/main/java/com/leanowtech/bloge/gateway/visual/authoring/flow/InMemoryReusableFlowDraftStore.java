package com.leanowtech.bloge.gateway.visual.authoring.flow;

import com.leanowtech.bloge.gateway.visual.authoring.resource.ExpectedRevision;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** Thread-safe reference implementation of the complete Flow draft save authority. */
public final class InMemoryReusableFlowDraftStore implements ReusableFlowDraftStore {
    private final Map<FlowKey, ReusableFlowSaveResult> heads = new HashMap<>();
    private final Map<RevisionKey, ReusableFlowStoredDraft> history = new HashMap<>();
    private final Map<CommandKey, Completion> commands = new HashMap<>();
    private final Supplier<String> identifierFactory;

    public InMemoryReusableFlowDraftStore() {
        this(() -> UUID.randomUUID().toString());
    }

    InMemoryReusableFlowDraftStore(Supplier<String> identifierFactory) {
        this.identifierFactory = java.util.Objects.requireNonNull(identifierFactory, "identifierFactory");
    }

    @Override public synchronized ReusableFlowSaveResult save(ReusableFlowSaveIntent intent) {
        FlowKey flowKey = new FlowKey(intent.scope(), intent.flowId());
        CommandKey commandKey = new CommandKey(intent.scope(), intent.actorId(),
                intent.flowId(), intent.idempotencyKey());
        Completion prior = commands.get(commandKey);
        if (prior != null) {
            if (!prior.requestFingerprint().equals(intent.requestFingerprint())
                    || !prior.expectedRevision().equals(intent.expectedRevision())) {
                throw new ReusableFlowFailure(ReusableFlowFailure.Code.CONFLICT);
            }
            ReusableFlowSaveResult result = prior.result();
            return new ReusableFlowSaveResult(result.draft(), result.receipt(), result.strongEtag(), true);
        }

        ReusableFlowSaveResult current = heads.get(flowKey);
        checkExpected(current, intent.expectedRevision());
        int revision = current == null ? 1 : current.draft().revision() + 1;
        String draftId = current == null ? "draft-" + nextIdentifier() : current.draft().draftId();
        ReusableFlowCommand.Flow flow = intent.command().flow();
        ReusableFlowDraft draft = new ReusableFlowDraft(ReusableFlowDraft.SCHEMA_VERSION,
                intent.flowId(), draftId, revision, intent.contentFingerprint(), flow.displayName(),
                flow.kind(), flow.description(), flow.contract(), flow.graph(), flow.layout(),
                ReusableFlowDraft.Status.DRAFT);
        ReusableFlowSaveReceipt receipt = new ReusableFlowSaveReceipt(
                ReusableFlowSaveReceipt.SCHEMA_VERSION, intent.flowId(), draft.subject(),
                ReusableFlowSaveReceipt.Validation.VALID);
        ReusableFlowSaveResult result = new ReusableFlowSaveResult(
                draft, receipt, "\"" + nextIdentifier() + "\"", false);
        heads.put(flowKey, result);
        history.put(new RevisionKey(intent.scope(), intent.flowId(), revision), stored(result));
        commands.put(commandKey, new Completion(intent.requestFingerprint(), intent.expectedRevision(), result));
        return result;
    }

    @Override public synchronized Optional<ReusableFlowStoredDraft> findHeadStored(
            AuthoringScope scope, String flowId) {
        ReusableFlowSaveResult result = heads.get(new FlowKey(scope, flowId));
        return result == null ? Optional.empty() : Optional.of(stored(result));
    }

    @Override public synchronized Optional<ReusableFlowStoredDraft> findRevisionStored(
            AuthoringScope scope, String flowId, int revision) {
        if (scope == null || flowId == null || flowId.isBlank() || revision < 1) return Optional.empty();
        return Optional.ofNullable(history.get(new RevisionKey(scope, flowId, revision)));
    }

    @Override public synchronized Optional<ReusableFlowStoredDraft> findRevisionByStrongEtag(
            AuthoringScope scope, String flowId, String strongEtag) {
        if (scope == null || flowId == null || flowId.isBlank() || strongEtag == null) return Optional.empty();
        return history.entrySet().stream()
                .filter(entry -> entry.getKey().scope().equals(scope)
                        && entry.getKey().flowId().equals(flowId)
                        && entry.getValue().strongEtag().equals(strongEtag))
                .map(Map.Entry::getValue).findFirst();
    }

    private static void checkExpected(ReusableFlowSaveResult current, ExpectedRevision expected) {
        boolean mismatch = expected instanceof ExpectedRevision.Create && current != null
                || expected instanceof ExpectedRevision.Match match
                && (current == null || current.draft().revision() != match.revision());
        if (mismatch) throw new ReusableFlowFailure(ReusableFlowFailure.Code.CAS_MISMATCH);
    }

    private String nextIdentifier() {
        String value = identifierFactory.get();
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw new ReusableFlowFailure(ReusableFlowFailure.Code.INTEGRITY);
        }
        return value;
    }

    private static ReusableFlowStoredDraft stored(ReusableFlowSaveResult result) {
        return new ReusableFlowStoredDraft(result.draft(), result.receipt(), result.strongEtag());
    }

    private record FlowKey(AuthoringScope scope, String flowId) { }
    private record RevisionKey(AuthoringScope scope, String flowId, int revision) { }
    private record CommandKey(AuthoringScope scope, String actorId, String flowId, String idempotencyKey) { }
    private record Completion(String requestFingerprint, ExpectedRevision expectedRevision,
                              ReusableFlowSaveResult result) { }
}
