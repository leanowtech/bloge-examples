package com.leanowtech.bloge.gateway.visual.authoring.connection.persistence;

import com.leanowtech.bloge.gateway.visual.authoring.connection.ApiConnectionAuthoringException;
import com.leanowtech.bloge.gateway.visual.authoring.connection.ApiConnectionCommand;
import com.leanowtech.bloge.gateway.visual.authoring.connection.ApiConnectionDecisions;
import com.leanowtech.bloge.gateway.visual.authoring.connection.ApiConnectionSpec;
import com.leanowtech.bloge.gateway.visual.authoring.connection.PreparedSecretBinding;
import com.leanowtech.bloge.gateway.visual.authoring.connection.persistence.ApiConnectionCommitStoreException.Code;
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
import java.util.UUID;

/**
 * Deterministic short-lived test/reference adapter, never a production store.
 * Durable implementations retain fencing and cleanup in the JDBC command journal.
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
    /** Exact failed-lease tombstones; a durable adapter owns journal cleanup policy. */
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
        if (lease.key().endpoint() == AuthoringEndpoint.API_CONNECTION_SAVE) {
            if (!connectionId.equals(lease.key().targetId())
                    || !lease.expectedRevision().equals(connectionExpected)) fail(Code.INTEGRITY);
        } else if (lease.key().endpoint() == AuthoringEndpoint.API_RESOURCE_SAVE) {
            if (!(connectionExpected instanceof ExpectedRevision.Create)) fail(Code.INTEGRITY);
        } else {
            fail(Code.INTEGRITY);
        }
        CommandKey commandKey = lease.key();
        CommandLease currentLease = active.get(commandKey);
        if (currentLease != null) {
            if (currentLease.equals(lease)) {
                requireStoredLeaseLive(currentLease);
            } else if (currentLease.leaseUntil().isAfter(clock.instant())) {
                fail(Code.LEASE_FENCED);
            } else {
                if (!currentLease.commandId().equals(lease.commandId())
                        || !currentLease.requestFingerprint().equals(lease.requestFingerprint())
                        || lease.attemptNo() <= currentLease.attemptNo()) fail(Code.LEASE_FENCED);
                removeStage(currentLease);
                active.remove(commandKey);
            }
        }
        CommandLease failedLease = failed.get(commandKey);
        if (failedLease != null) {
            if (!lease.commandId().equals(failedLease.commandId())
                    || lease.attemptNo() <= failedLease.attemptNo()) fail(Code.LEASE_FENCED);
            failed.remove(commandKey);
        }
        if (currentLease == null || !currentLease.equals(lease)) requireIncomingLeaseLive(lease);

        StageKey stageKey = new StageKey(lease.commandId(), lease.attemptToken());
        StagedApiConnection prior = stages.get(stageKey);
        if (prior != null) {
            if (!prior.lease().equals(lease)) fail(Code.LEASE_FENCED);
            if (!prior.connectionExpected().equals(connectionExpected)) fail(Code.INTEGRITY);
            ApiConnectionSpec candidate = decisions.next(lease.key().scope(),
                    Optional.ofNullable(stageBases.get(stageKey)), connectionId, command,
                    connectionExpected, prepared);
            if (!candidate.fingerprint().equals(prior.metadataFingerprint())) fail(Code.INTEGRITY);
            return prior;
        }

        ConnectionKey key = new ConnectionKey(lease.key().scope(), connectionId);
        StoredApiConnection head = heads.get(key);
        ApiConnectionSpec current = head == null ? null
                : committedSpecs.get(new RevisionKey(key, head.view().revision()));
        ApiConnectionSpec next;
        try {
            next = decisions.next(lease.key().scope(), Optional.ofNullable(current), connectionId, command,
                    connectionExpected, prepared);
        } catch (ApiConnectionAuthoringException ex) {
            if (ex.code() == ApiConnectionAuthoringException.Code.ALREADY_EXISTS
                    || ex.code() == ApiConnectionAuthoringException.Code.NOT_FOUND
                    || ex.code() == ApiConnectionAuthoringException.Code.CAS_MISMATCH) fail(Code.CAS_MISMATCH);
            throw ex;
        }
        StagedApiConnection staged = new StagedApiConnection(lease, next, connectionExpected, opaqueEtag());
        CommandLease previous = active.put(commandKey, lease);
        if (previous != null) removeStage(previous);
        stages.put(stageKey, staged);
        stageBases.put(stageKey, current);
        return staged;
    }

    /** {@inheritDoc} */
    @Override
    public synchronized StoredApiConnection commit(CommandLease lease) {
        if (lease != null && lease.key() != null && lease.key().endpoint() == AuthoringEndpoint.API_RESOURCE_SAVE) {
            fail(Code.INTEGRITY);
        }
        return commitInternal(lease);
    }

    /**
     * Commits only the Connection child of a composite resource command. The
     * reference adapter has no outer journal; the child therefore publishes
     * its local revision/head while the caller owns the composite receipt.
     */
    @Override
    public synchronized StoredApiConnection commitChild(CommandLease lease) {
        requireLease(lease);
        if (lease.key().endpoint() != AuthoringEndpoint.API_RESOURCE_SAVE) fail(Code.INTEGRITY);
        return commitInternal(lease);
    }

    private StoredApiConnection commitInternal(CommandLease lease) {
        requireLease(lease);
        CommandLease currentLease = active.get(lease.key());
        if (currentLease == null) {
            CommandLease failedLease = failed.get(lease.key());
            if (failedLease != null && failedLease.equals(lease)) fail(Code.STAGE_MISSING);
            fail(Code.LEASE_FENCED);
        }
        if (!currentLease.equals(lease)) fail(Code.LEASE_FENCED);
        requireStoredLeaseLive(currentLease);
        StagedApiConnection staged = stages.get(new StageKey(lease.commandId(), lease.attemptToken()));
        if (staged == null) fail(Code.STAGE_MISSING);
        ConnectionKey key = new ConnectionKey(lease.key().scope(), staged.spec().connectionId());
        StoredApiConnection current = heads.get(key);
        checkExpected(current, staged.connectionExpected());
        if (!staged.spec().secretBindings().isEmpty()) {
            removeStage(lease);
            active.remove(lease.key());
            failed.put(lease.key(), lease);
            fail(Code.INTEGRITY);
        }
        StoredApiConnection stored = new StoredApiConnection(key.scope, staged.view(), staged.metadataFingerprint(),
                staged.strongEtag(), lease.commandId());
        heads.put(key, stored);
        RevisionKey revisionKey = new RevisionKey(key, staged.view().revision());
        history.put(revisionKey, stored);
        committedSpecs.put(revisionKey, staged.spec());
        stages.remove(new StageKey(lease.commandId(), lease.attemptToken()));
        active.remove(lease.key());
        return stored;
    }

    /** {@inheritDoc} */
    @Override
    public synchronized void fail(CommandLease lease) {
        if (lease == null) return;
        CommandLease current = active.get(lease.key());
        if (current == null || !current.equals(lease)) return;
        if (!current.leaseUntil().isAfter(clock.instant())) return;
        removeStage(current);
        active.remove(lease.key());
        failed.put(lease.key(), current);
    }

    /** {@inheritDoc} */
    @Override
    public synchronized Optional<StoredApiConnection> findHead(AuthoringScope scope, String connectionId) {
        return Optional.ofNullable(heads.get(new ConnectionKey(scope, connectionId)));
    }

    /** {@inheritDoc} */
    @Override
    public synchronized Optional<StoredApiConnection> findRevision(AuthoringScope scope, String connectionId,
                                                                    long revision) {
        return Optional.ofNullable(history.get(new RevisionKey(new ConnectionKey(scope, connectionId), revision)));
    }

    private void requireLease(CommandLease lease) {
        if (lease == null || lease.key() == null) fail(Code.LEASE_FENCED);
    }

    private void requireIncomingLeaseLive(CommandLease lease) {
        if (!lease.leaseUntil().isAfter(clock.instant())) fail(Code.LEASE_EXPIRED);
    }

    private void requireStoredLeaseLive(CommandLease lease) {
        if (!lease.leaseUntil().isAfter(clock.instant())) fail(Code.LEASE_EXPIRED);
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
