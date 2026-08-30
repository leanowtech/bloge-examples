package com.leanowtech.bloge.gateway.visual.authoring.connection.persistence;

import com.leanowtech.bloge.gateway.visual.authoring.connection.ApiConnectionAuthoringException;
import com.leanowtech.bloge.gateway.visual.authoring.connection.ApiConnectionCommand;
import com.leanowtech.bloge.gateway.visual.authoring.connection.ApiConnectionDecisions;
import com.leanowtech.bloge.gateway.visual.authoring.connection.ApiConnectionSpec;
import com.leanowtech.bloge.gateway.visual.authoring.connection.PreparedSecretBinding;
import com.leanowtech.bloge.gateway.visual.authoring.connection.secret.persistence.FinalizedSecretSlots;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ExpectedRevision;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringEndpoint;
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
    private final Map<StageKey, ApiConnectionSpec> stageBases = new HashMap<>();
    private final Map<CommandKey, CommandLease> failed = new HashMap<>();

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

        StageKey stageKey = new StageKey(lease.commandId(), lease.attemptToken());
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
        StagedApiConnection staged = stages.get(stageKey);
        if (staged == null) fail(Code.STAGE_MISSING);
        validateFinalizedSlots(staged, finalized);
        ConnectionKey key = new ConnectionKey(lease.key().scope(), staged.view().connectionId());
        StoredApiConnection current = heads.get(key);
        checkExpected(current, staged.connectionExpected());
        ApiConnectionSpec spec = staged.spec();
        StoredApiConnection stored = new StoredApiConnection(key.scope, spec.view(), decisions.fingerprint(spec),
                staged.strongEtag(), lease.commandId());
        heads.put(key, stored);
        RevisionKey revisionKey = new RevisionKey(key, staged.view().revision());
        history.put(revisionKey, stored);
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
        if (finalized != null && (!finalized.coordinate().scope().equals(staged.lease().key().scope())
                || !finalized.coordinate().connectionId().equals(staged.view().connectionId())
                || finalized.coordinate().revision() != staged.view().revision()
                || !finalized.slots().equals(Set.of(slotFor(staged.view().auth().kind()))))) fail(Code.INTEGRITY);
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
