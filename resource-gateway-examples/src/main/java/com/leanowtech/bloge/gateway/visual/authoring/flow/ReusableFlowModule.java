package com.leanowtech.bloge.gateway.visual.authoring.flow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ExpectedRevision;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringFingerprints;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;

import java.util.Objects;
import java.util.Optional;

/**
 * Deep application module for compiling and saving reusable Tool/Solution drafts.
 *
 * <p>The compiler runs before the store sees an idempotency coordinate. Invalid DAGs therefore
 * cannot occupy a key. The store is the sole authority for atomic replay, CAS, revision/head and
 * historical reads; the module does not reconstruct those rules in HTTP adapters.</p>
 */
public final class ReusableFlowModule {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ReusableFlowCompiler compiler;
    private final ReusableFlowDraftStore store;

    public ReusableFlowModule(ReusableFlowCompiler compiler, ReusableFlowDraftStore store) {
        this.compiler = Objects.requireNonNull(compiler, "compiler");
        this.store = Objects.requireNonNull(store, "store");
    }

    /** Compiles then atomically saves one exact Flow intent. */
    public ReusableFlowSaveResult save(AuthoringScope scope, String actorId, String flowId,
                                       ExpectedRevision expectedRevision, String idempotencyKey,
                                       ReusableFlowCommand command) {
        compiler.compile(scope, command);
        JsonNode commandJson = JSON.valueToTree(command);
        ObjectNode content = JSON.valueToTree(command.flow());
        content.remove("layout");
        ReusableFlowSaveIntent intent = new ReusableFlowSaveIntent(scope, actorId, flowId,
                expectedRevision, idempotencyKey, AuthoringFingerprints.of(commandJson),
                AuthoringFingerprints.of(content), command);
        return store.save(intent);
    }

    /** Resolves a strong-validator precondition then compiles and saves one exact intent. */
    public ReusableFlowSaveResult save(AuthoringScope scope, String actorId, String flowId,
                                       ReusableFlowPrecondition precondition, String idempotencyKey,
                                       ReusableFlowCommand command) {
        Objects.requireNonNull(precondition, "precondition");
        ExpectedRevision expected;
        if (precondition instanceof ReusableFlowPrecondition.Create) {
            expected = ExpectedRevision.create();
        } else {
            String strongEtag = ((ReusableFlowPrecondition.MatchStrongEtag) precondition).strongEtag();
            Optional<ReusableFlowStoredDraft> prior = store.findRevisionByStrongEtag(scope, flowId, strongEtag);
            if (prior.isEmpty()) {
                ReusableFlowFailure.Code code = store.findHeadStored(scope, flowId).isEmpty()
                        ? ReusableFlowFailure.Code.NOT_FOUND : ReusableFlowFailure.Code.CAS_MISMATCH;
                throw new ReusableFlowFailure(code);
            }
            expected = ExpectedRevision.match(prior.get().draft().revision());
        }
        return save(scope, actorId, flowId, expected, idempotencyKey, command);
    }

    /** Reads the current committed draft authority in one trusted scope. */
    public Optional<ReusableFlowStoredDraft> findHeadStored(AuthoringScope scope, String flowId) {
        return store.findHeadStored(scope, flowId);
    }

    /** Reads one exact committed draft authority in one trusted scope. */
    public Optional<ReusableFlowStoredDraft> findRevisionStored(
            AuthoringScope scope, String flowId, int revision) {
        return store.findRevisionStored(scope, flowId, revision);
    }

    /** Reads the current committed draft in one trusted scope. */
    public Optional<ReusableFlowDraft> findHead(AuthoringScope scope, String flowId) {
        return store.findHead(scope, flowId);
    }

    /** Reads one exact committed historical draft revision in one trusted scope. */
    public Optional<ReusableFlowDraft> findRevision(AuthoringScope scope, String flowId, int revision) {
        return store.findRevision(scope, flowId, revision);
    }
}
