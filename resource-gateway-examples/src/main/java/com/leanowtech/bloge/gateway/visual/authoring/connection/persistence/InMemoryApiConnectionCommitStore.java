package com.leanowtech.bloge.gateway.visual.authoring.connection.persistence;

import com.leanowtech.bloge.gateway.visual.authoring.connection.ApiConnectionCommand;
import com.leanowtech.bloge.gateway.visual.authoring.connection.ApiConnectionDecisions;
import com.leanowtech.bloge.gateway.visual.authoring.connection.ApiConnectionSpec;
import com.leanowtech.bloge.gateway.visual.authoring.connection.PreparedSecretBinding;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ExpectedRevision;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.CommandKey;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.CommandLease;
import com.leanowtech.bloge.gateway.visual.authoring.connection.persistence.ApiConnectionCommitStoreException.Code;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Small deterministic contract adapter; it is not a production persistence implementation. */
public final class InMemoryApiConnectionCommitStore implements ApiConnectionCommitStore {
    private final Clock clock;
    private final ApiConnectionDecisions decisions;
    private final Map<ConnectionKey, StoredApiConnection> heads = new HashMap<>();
    private final Map<RevisionKey, StoredApiConnection> history = new HashMap<>();
    private final Map<RevisionKey, ApiConnectionSpec> committedSpecs = new HashMap<>();
    private final Map<CommandKey, Active> active = new HashMap<>();
    private final Map<StageKey, StagedApiConnection> stages = new HashMap<>();
    private final java.util.Set<StageKey> failed = new java.util.HashSet<>();

    public InMemoryApiConnectionCommitStore() { this(Clock.systemUTC(), new ApiConnectionDecisions()); }
    public InMemoryApiConnectionCommitStore(Clock clock, ApiConnectionDecisions decisions) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.decisions = Objects.requireNonNull(decisions, "decisions");
    }

    @Override
    public synchronized StagedApiConnection stage(CommandLease lease, String connectionId,
                                                   ApiConnectionCommand command, PreparedSecretBinding... prepared) {
        requireTarget(lease, connectionId);
        requireLiveForStage(lease);
        Active currentAttempt = active.get(lease.key());
        if (currentAttempt != null && !sameLease(currentAttempt.lease, lease)
                && currentAttempt.lease.attemptNo() >= lease.attemptNo()) {
            fail(Code.LEASE_FENCED, "lease is fenced");
        }
        StageKey stageKey = new StageKey(lease.commandId(), lease.attemptToken());
        StagedApiConnection prior = stages.get(stageKey);
        if (prior != null) {
            ApiConnectionSpec candidate = decisions.next(lease.key().scope(), Optional.of(prior.spec()),
                    connectionId, command, ExpectedRevision.match(prior.spec().revision()), prepared);
            if (!candidate.fingerprint().equals(prior.metadataFingerprint())) fail(Code.INTEGRITY, "staged content changed");
            return prior;
        }
        ConnectionKey key = new ConnectionKey(lease.key().scope(), connectionId);
        StoredApiConnection head = heads.get(key);
        ApiConnectionSpec next;
        try {
            next = decisions.next(lease.key().scope(), Optional.ofNullable(head == null ? null
                            : committedSpecs.get(new RevisionKey(key, head.view().revision()))),
                    connectionId, command, lease.expectedRevision(), prepared);
        } catch (RuntimeException ex) {
            if (ex instanceof com.leanowtech.bloge.gateway.visual.authoring.connection.ApiConnectionAuthoringException a
                    && (a.code() == com.leanowtech.bloge.gateway.visual.authoring.connection.ApiConnectionAuthoringException.Code.ALREADY_EXISTS
                    || a.code() == com.leanowtech.bloge.gateway.visual.authoring.connection.ApiConnectionAuthoringException.Code.NOT_FOUND
                    || a.code() == com.leanowtech.bloge.gateway.visual.authoring.connection.ApiConnectionAuthoringException.Code.CAS_MISMATCH)) {
                fail(Code.CAS_MISMATCH, "head revision changed");
            }
            throw ex;
        }
        StagedApiConnection staged = new StagedApiConnection(lease, next, opaqueEtag());
        Active previous = active.put(lease.key(), new Active(lease));
        if (previous != null) stages.remove(new StageKey(previous.lease.commandId(), previous.lease.attemptToken()));
        stages.put(stageKey, staged);
        return staged;
    }

    @Override public synchronized StoredApiConnection commit(CommandLease lease) {
        if (lease != null && failed.contains(new StageKey(lease.commandId(), lease.attemptToken()))) {
            fail(Code.STAGE_MISSING, "staged connection is missing");
        }
        requireActive(lease);
        StagedApiConnection staged = stages.get(new StageKey(lease.commandId(), lease.attemptToken()));
        if (staged == null) fail(Code.STAGE_MISSING, "staged connection is missing");
        ConnectionKey key = new ConnectionKey(lease.key().scope(), staged.spec().connectionId());
        StoredApiConnection current = heads.get(key);
        checkExpected(current, lease.expectedRevision());
        StoredApiConnection stored = new StoredApiConnection(key.scope, staged.view(), staged.metadataFingerprint(),
                staged.strongEtag(), lease.commandId());
        heads.put(key, stored);
        history.put(new RevisionKey(key, staged.view().revision()), stored);
        committedSpecs.put(new RevisionKey(key, staged.view().revision()), staged.spec());
        stages.remove(new StageKey(lease.commandId(), lease.attemptToken()));
        active.remove(lease.key());
        return stored;
    }

    /** Convenience overload for callers holding the stage receipt itself. */
    public StoredApiConnection commit(StagedApiConnection staged) {
        return commit(staged == null ? null : staged.lease());
    }

    @Override public synchronized void fail(CommandLease lease) {
        if (lease == null) return;
        Active current = active.get(lease.key());
        if (current == null || !sameLease(current.lease, lease)) return;
        if (!lease.leaseUntil().isAfter(clock.instant())) return;
        stages.remove(new StageKey(lease.commandId(), lease.attemptToken()));
        failed.add(new StageKey(lease.commandId(), lease.attemptToken()));
        active.remove(lease.key());
    }

    @Override public synchronized Optional<StoredApiConnection> findHead(AuthoringScope scope, String connectionId) {
        return Optional.ofNullable(heads.get(new ConnectionKey(scope, connectionId)));
    }
    @Override public synchronized Optional<StoredApiConnection> findRevision(AuthoringScope scope, String connectionId, long revision) {
        return Optional.ofNullable(history.get(new RevisionKey(new ConnectionKey(scope, connectionId), revision)));
    }

    private void requireLiveForStage(CommandLease lease) {
        if (lease == null) fail(Code.LEASE_FENCED, "lease is fenced");
        if (!lease.leaseUntil().isAfter(clock.instant())) fail(Code.LEASE_EXPIRED, "lease expired");
    }
    private void requireActive(CommandLease lease) {
        requireLiveForStage(lease);
        Active current = active.get(lease.key());
        if (current == null || !sameLease(current.lease, lease)) fail(Code.LEASE_FENCED, "lease is fenced");
    }
    private static void requireTarget(CommandLease lease, String connectionId) {
        if (lease == null || connectionId == null || !connectionId.equals(lease.key().targetId())) fail(Code.INTEGRITY, "connection target differs from lease");
    }
    private static boolean sameLease(CommandLease a, CommandLease b) { return a.commandId().equals(b.commandId()) && a.attemptNo() == b.attemptNo() && a.attemptToken().equals(b.attemptToken()); }
    private static void checkExpected(StoredApiConnection current, ExpectedRevision expected) {
        if (expected instanceof ExpectedRevision.Create && current != null || expected instanceof ExpectedRevision.Match m && (current == null || current.view().revision() != m.revision())) fail(Code.CAS_MISMATCH, "head revision changed");
    }
    private static String opaqueEtag() { return "\"" + UUID.randomUUID() + "\""; }
    private static void fail(Code code, String message) { throw new ApiConnectionCommitStoreException(code, message); }
    private record Active(CommandLease lease) { }
    private record StageKey(String commandId, String attemptToken) { }
    private record ConnectionKey(AuthoringScope scope, String connectionId) { }
    private record RevisionKey(ConnectionKey key, long revision) { }
}
