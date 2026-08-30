package com.leanowtech.bloge.gateway.visual.authoring.connection.persistence;

import com.leanowtech.bloge.gateway.visual.authoring.connection.ApiConnectionAuthoringException;
import com.leanowtech.bloge.gateway.visual.authoring.connection.ApiConnectionCommand;
import com.leanowtech.bloge.gateway.visual.authoring.connection.ApiConnectionDecisions;
import com.leanowtech.bloge.gateway.visual.authoring.connection.ApiConnectionSpec;
import com.leanowtech.bloge.gateway.visual.authoring.connection.PreparedSecretBinding;
import com.leanowtech.bloge.gateway.visual.authoring.connection.secret.persistence.FinalizedSecretSlots;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ExpectedRevision;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringEndpoint;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.ApiResourceSaveReceiptClosure;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.CommandReceipt;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.CommandKey;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.CommandLease;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.leanowtech.bloge.gateway.visual.authoring.connection.persistence.ApiConnectionCommitStoreException.Code;

/**
 * Deterministic short-lived reference adapter for the Connection metadata
 * authority. Secret preparation is accepted only while staging so that the
 * pure decision engine can validate it; this store never retains or activates
 * a provider reference.
 */
public final class InMemoryApiConnectionCommitStore implements ApiConnectionCommitStore {
    private final Clock clock;
    private final ApiConnectionDecisions decisions;
    private final Map<ConnectionKey, StoredApiConnection> heads = new HashMap<>();
    private final Map<RevisionKey, StoredApiConnection> history = new HashMap<>();
    private final Map<RevisionKey, ApiConnectionSpec> committedSpecs = new HashMap<>();
    private final Map<CommandKey, CommandLease> active = new HashMap<>();
    private final Map<StageKey, StagedApiConnection> stages = new HashMap<>();
    /** Children are committed atomically with the outer command but hidden until its receipt publishes. */
    private final Map<StageKey, StoredApiConnection> unpublishedChildren = new HashMap<>();
    /** Pending child heads reserve the exact connection coordinate without making it readable. */
    private final Map<ConnectionKey, StoredApiConnection> pendingHeads = new HashMap<>();
    private final Map<StageKey, CommandLease> unpublishedChildLeases = new HashMap<>();
    /** Published children keyed by the exact attempt, so replay never guesses a resource coordinate. */
    private final Map<StageKey, StoredApiConnection> publishedChildren = new HashMap<>();
    private final Map<StageKey, CommandLease> publishedChildLeases = new HashMap<>();
    private final Map<StageKey, CommandReceipt> publishedChildReceipts = new HashMap<>();
    private final Map<StageKey, ApiConnectionSpec> stageBases = new HashMap<>();
    private final Map<CommandKey, CommandLease> failed = new HashMap<>();
    private final Map<StageKey, CommandLease> failedAttempts = new HashMap<>();

    /** Creates a store using UTC wall-clock time and default decisions. */
    public InMemoryApiConnectionCommitStore() { this(Clock.systemUTC(), new ApiConnectionDecisions()); }

    /** Creates a store with injectable time and pure authority decisions. */
    public InMemoryApiConnectionCommitStore(Clock clock, ApiConnectionDecisions decisions) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.decisions = Objects.requireNonNull(decisions, "decisions");
    }

    /** {@inheritDoc} */
    @Override
    public synchronized StagedApiConnection stage(CommandLease lease, String connectionId,
                                                   ExpectedRevision connectionExpected,
                                                   ApiConnectionCommand command,
                                                   PreparedSecretBinding... prepared) {
        requireLease(lease);
        if (connectionId == null || connectionId.isBlank() || connectionExpected == null) fail(Code.INTEGRITY);
        requireLeaseShape(lease, connectionId, connectionExpected);
        CommandKey commandKey = lease.key();
        StageKey stageKey = new StageKey(lease.commandId(), lease.attemptToken());
        if (failedAttempts.containsKey(stageKey)) fail(Code.LEASE_FENCED);
        CommandLease currentLease = active.get(commandKey);
        if (currentLease != null) {
            if (currentLease.equals(lease)) requireStoredLeaseLive(currentLease);
            else if (currentLease.leaseUntil().isAfter(clock.instant())) fail(Code.LEASE_FENCED);
            else {
                if (!currentLease.commandId().equals(lease.commandId())
                        || !currentLease.requestFingerprint().equals(lease.requestFingerprint())
                        || lease.attemptNo() <= currentLease.attemptNo()) fail(Code.LEASE_FENCED);
                removeStage(currentLease);
                active.remove(commandKey);
            }
        }
        CommandLease failedLease = failed.get(commandKey);
        if (failedLease != null) {
            if (!lease.commandId().equals(failedLease.commandId()) || lease.attemptNo() <= failedLease.attemptNo()) {
                fail(Code.LEASE_FENCED);
            }
            failed.remove(commandKey);
        }
        if (currentLease == null || !currentLease.equals(lease)) requireIncomingLeaseLive(lease);

        StagedApiConnection prior = stages.get(stageKey);
        ApiConnectionSpec base = prior == null ? currentSpec(lease.key().scope(), connectionId)
                : stageBases.get(stageKey);
        ApiConnectionSpec next;
        try {
            next = decisions.next(lease.key().scope(), Optional.ofNullable(base), connectionId, command,
                    connectionExpected, prepared);
        } catch (ApiConnectionAuthoringException ex) {
            if (ex.code() == ApiConnectionAuthoringException.Code.ALREADY_EXISTS
                    || ex.code() == ApiConnectionAuthoringException.Code.NOT_FOUND
                    || ex.code() == ApiConnectionAuthoringException.Code.CAS_MISMATCH) fail(Code.CAS_MISMATCH);
            throw ex;
        }
        if (prior != null) {
            if (!prior.lease().equals(lease) || !prior.connectionExpected().equals(connectionExpected)
                    || !prior.metadataFingerprint().equals(next.fingerprint())) fail(Code.INTEGRITY);
            return prior;
        }
        StagedApiConnection staged = new StagedApiConnection(lease, next, connectionExpected, opaqueEtag());
        active.put(commandKey, lease);
        stages.put(stageKey, staged);
        stageBases.put(stageKey, base);
        return staged;
    }

    /** {@inheritDoc} */
    @Override public synchronized StoredApiConnection commit(CommandLease lease) {
        return commitInternal(lease, null, false);
    }

    /** {@inheritDoc} */
    @Override public synchronized StoredApiConnection commit(CommandLease lease, FinalizedSecretSlots finalized) {
        return commitInternal(lease, finalized, false);
    }

    /** {@inheritDoc} */
    @Override public synchronized StoredApiConnection commitChild(CommandLease lease) {
        return commitInternal(lease, null, true);
    }

    /** {@inheritDoc} */
    @Override public synchronized StoredApiConnection commitChild(CommandLease lease, FinalizedSecretSlots finalized) {
        return commitInternal(lease, finalized, true);
    }

    /** {@inheritDoc} */
    @Override
    public synchronized StoredApiConnection publishChild(CommandLease lease, CommandReceipt outerReceipt) {
        requireLease(lease);
        if (lease.key().endpoint() != AuthoringEndpoint.API_RESOURCE_SAVE) fail(Code.INTEGRITY);
        StageKey stageKey = new StageKey(lease.commandId(), lease.attemptToken());
        StoredApiConnection child = unpublishedChildren.get(stageKey);
        if (child == null) {
            child = publishedChildren.get(stageKey);
            if (child == null) fail(Code.STAGE_MISSING);
            if (!lease.equals(publishedChildLeases.get(stageKey))) fail(Code.LEASE_FENCED);
            validateOuterReceipt(lease, outerReceipt, child);
            if (!outerReceipt.equals(publishedChildReceipts.get(stageKey))) fail(Code.INTEGRITY);
            return child;
        }
        if (!lease.equals(unpublishedChildLeases.get(stageKey))) fail(Code.LEASE_FENCED);
        validateOuterReceipt(lease, outerReceipt, child);
        ConnectionKey key = new ConnectionKey(child.scope(), child.view().connectionId());
        StoredApiConnection current = heads.get(key);
        if (current != null && !current.equals(child)) fail(Code.CAS_MISMATCH);
        heads.put(key, child);
        history.put(new RevisionKey(key, child.view().revision()), child);
        unpublishedChildren.remove(stageKey);
        unpublishedChildLeases.remove(stageKey);
        pendingHeads.remove(key);
        publishedChildren.put(stageKey, child);
        publishedChildLeases.put(stageKey, lease);
        publishedChildReceipts.put(stageKey, outerReceipt);
        return child;
    }

    /** {@inheritDoc} */
    @Override
    public synchronized void failChild(CommandLease lease) {
        if (lease == null) return;
        if (lease.key().endpoint() != AuthoringEndpoint.API_RESOURCE_SAVE) fail(Code.INTEGRITY);
        StageKey key = new StageKey(lease.commandId(), lease.attemptToken());
        CommandLease stagedLease = unpublishedChildLeases.get(key);
        if (stagedLease == null) {
            stagedLease = stages.get(key) == null ? null : stages.get(key).lease();
        }
        if (stagedLease != null && !stagedLease.equals(lease)) fail(Code.LEASE_FENCED);
        CommandLease publishedLease = publishedChildLeases.get(key);
        if (publishedLease != null && !publishedLease.equals(lease)) fail(Code.LEASE_FENCED);
        if (publishedLease != null) return;
        if (stagedLease == null && publishedLease == null && !unpublishedChildren.containsKey(key)) return;
        StoredApiConnection child = unpublishedChildren.remove(key);
        if (child != null) {
            pendingHeads.remove(new ConnectionKey(child.scope(), child.view().connectionId()));
            committedSpecs.remove(new RevisionKey(new ConnectionKey(child.scope(), child.view().connectionId()),
                    child.view().revision()));
        }
        unpublishedChildLeases.remove(key);
        removeStage(lease);
        if (active.get(lease.key()) != null && active.get(lease.key()).equals(lease)) active.remove(lease.key());
        failed.put(lease.key(), lease);
        failedAttempts.put(key, lease);
    }

    private StoredApiConnection commitInternal(CommandLease lease, FinalizedSecretSlots finalized, boolean child) {
        requireLease(lease);
        if (child != (lease.key().endpoint() == AuthoringEndpoint.API_RESOURCE_SAVE)) fail(Code.INTEGRITY);
        CommandLease currentLease = active.get(lease.key());
        if (currentLease == null) {
            if (lease.equals(failed.get(lease.key()))) fail(Code.STAGE_MISSING);
            fail(Code.LEASE_FENCED);
        }
        if (!currentLease.equals(lease)) fail(Code.LEASE_FENCED);
        requireStoredLeaseLive(currentLease);
        StageKey stageKey = new StageKey(lease.commandId(), lease.attemptToken());
        if (failedAttempts.containsKey(stageKey)) fail(Code.LEASE_FENCED);
        StagedApiConnection staged = stages.get(stageKey);
        if (staged == null) fail(Code.STAGE_MISSING);
        validateFinalizedSlots(staged, finalized);
        if (!staged.metadataFingerprint().equals(decisions.fingerprint(staged.spec()))) fail(Code.INTEGRITY);
        ConnectionKey key = new ConnectionKey(lease.key().scope(), staged.view().connectionId());
        StoredApiConnection current = heads.get(key);
        checkExpected(current, staged.connectionExpected());
        if (pendingHeads.containsKey(key)) fail(Code.CAS_MISMATCH);
        ApiConnectionSpec spec = staged.spec();
        StoredApiConnection stored = new StoredApiConnection(key.scope, spec.view(), decisions.fingerprint(spec),
                staged.strongEtag(), lease.commandId());
        RevisionKey revisionKey = new RevisionKey(key, staged.view().revision());
        if (child) {
            unpublishedChildren.put(stageKey, stored);
            pendingHeads.put(key, stored);
            unpublishedChildLeases.put(stageKey, lease);
        } else {
            heads.put(key, stored);
            history.put(revisionKey, stored);
        }
        committedSpecs.put(revisionKey, spec);
        stages.remove(stageKey);
        stageBases.remove(stageKey);
        active.remove(lease.key());
        return stored;
    }

    private ApiConnectionSpec currentSpec(AuthoringScope scope, String connectionId) {
        StoredApiConnection head = heads.get(new ConnectionKey(scope, connectionId));
        return head == null ? null : committedSpecs.get(new RevisionKey(
                new ConnectionKey(scope, connectionId), head.view().revision()));
    }

    private static void validateFinalizedSlots(StagedApiConnection staged, FinalizedSecretSlots finalized) {
        boolean secretStage = !staged.view().auth().kind().equals("NONE");
        if (secretStage && finalized == null) fail(Code.INTEGRITY);
        if (!secretStage && finalized != null) fail(Code.INTEGRITY);
        if (finalized != null && (!finalized.lease().commandLease().equals(staged.lease())
                || !finalized.coordinate().scope().equals(staged.lease().key().scope())
                || !finalized.coordinate().connectionId().equals(staged.view().connectionId())
                || finalized.coordinate().revision() != staged.view().revision()
                || !finalized.slots().equals(Set.of(slotFor(staged.view().auth().kind()))))) fail(Code.INTEGRITY);
    }

    private static void validateOuterReceipt(CommandLease lease, CommandReceipt receipt, StoredApiConnection child) {
        try {
            ApiResourceSaveReceiptClosure.require(receipt, lease.key().targetId(), child.view().connectionId(),
                    child.view().revision());
        } catch (RuntimeException ex) {
            fail(Code.INTEGRITY);
        }
    }

    /** {@inheritDoc} */
    @Override public synchronized void fail(CommandLease lease) {
        if (lease == null) return;
        CommandLease current = active.get(lease.key());
        if (current == null || !current.equals(lease)) return;
        if (!current.leaseUntil().isAfter(clock.instant())) return;
        removeStage(current);
        active.remove(lease.key());
        failed.put(lease.key(), current);
        failedAttempts.put(new StageKey(lease.commandId(), lease.attemptToken()), current);
    }

    /** {@inheritDoc} */
    @Override public synchronized Optional<StoredApiConnection> findHead(AuthoringScope scope, String connectionId) {
        return Optional.ofNullable(heads.get(new ConnectionKey(scope, connectionId)));
    }

    /** {@inheritDoc} */
    @Override public synchronized Optional<StoredApiConnection> findRevision(AuthoringScope scope, String connectionId,
                                                                              long revision) {
        return Optional.ofNullable(history.get(new RevisionKey(new ConnectionKey(scope, connectionId), revision)));
    }

    private static String slotFor(String kind) {
        return switch (kind) {
            case "BEARER" -> "token";
            case "BASIC" -> "password";
            case "API_KEY" -> "value";
            default -> "";
        };
    }

    private void requireLease(CommandLease lease) { if (lease == null || lease.key() == null) fail(Code.LEASE_FENCED); }
    private void requireIncomingLeaseLive(CommandLease lease) { if (!lease.leaseUntil().isAfter(clock.instant())) fail(Code.LEASE_EXPIRED); }
    private void requireStoredLeaseLive(CommandLease lease) { if (!lease.leaseUntil().isAfter(clock.instant())) fail(Code.LEASE_EXPIRED); }

    private static void requireLeaseShape(CommandLease lease, String connectionId, ExpectedRevision expected) {
        if (lease.key().endpoint() == AuthoringEndpoint.API_CONNECTION_SAVE) {
            if (!connectionId.equals(lease.key().targetId()) || !lease.expectedRevision().equals(expected)) fail(Code.INTEGRITY);
        } else if (lease.key().endpoint() == AuthoringEndpoint.API_RESOURCE_SAVE) {
            if (!(expected instanceof ExpectedRevision.Create)) fail(Code.INTEGRITY);
        } else fail(Code.INTEGRITY);
    }

    private void removeStage(CommandLease lease) {
        StageKey key = new StageKey(lease.commandId(), lease.attemptToken());
        stages.remove(key);
        stageBases.remove(key);
    }

    private static void checkExpected(StoredApiConnection current, ExpectedRevision expected) {
        if (expected instanceof ExpectedRevision.Create && current != null
                || expected instanceof ExpectedRevision.Match match
                && (current == null || current.view().revision() != match.revision())) fail(Code.CAS_MISMATCH);
    }

    private static String opaqueEtag() { return "\"" + UUID.randomUUID() + "\""; }
    private static void fail(Code code) { throw new ApiConnectionCommitStoreException(code); }
    private record StageKey(String commandId, String attemptToken) { }
    private record ConnectionKey(AuthoringScope scope, String connectionId) { }
    private record RevisionKey(ConnectionKey key, long revision) { }
}
