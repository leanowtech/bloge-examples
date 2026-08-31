package com.leanowtech.bloge.gateway.visual.authoring.resource.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceCommand;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceDecisions;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceSpec;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ExpectedRevision;
import com.leanowtech.bloge.gateway.visual.authoring.connection.persistence.StrongEtag;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Contract-reference implementation; intentionally not a production bean. */
public final class InMemoryApiResourceCommitStore implements ApiResourceCommitStore {
    private final Clock clock;
    private final Duration leaseDuration;
    private final ApiResourceDecisions decisions;
    private final ApiResourceProjectionCompiler compiler;
    private final State state;

    /** Durable state holder whose monitor serializes all stores sharing it. */
    public static final class State {
        private final Object monitor = new Object();
        private final Map<CommandKey, Journal> journals = new ConcurrentHashMap<>();
        private final Map<StageKey, StagedApiResource> stages = new ConcurrentHashMap<>();
        private final Map<ResourceKey, StoredApiResource> heads = new ConcurrentHashMap<>();
        private final Map<ResourceRevisionKey, StoredApiResource> revisions = new ConcurrentHashMap<>();
    }

    /** Creates a store with the default decision engine and a fresh state. */
    public InMemoryApiResourceCommitStore(Clock clock, Duration leaseDuration, ApiResourceProjectionCompiler compiler) {
        this(clock, leaseDuration, new ApiResourceDecisions(), compiler, new State());
    }

    /** Creates a store with injectable decisions and fresh state. */
    public InMemoryApiResourceCommitStore(Clock clock, Duration leaseDuration, ApiResourceDecisions decisions,
                                          ApiResourceProjectionCompiler compiler) {
        this(clock, leaseDuration, decisions, compiler, new State());
    }

    /** Reopens a state holder after restart. */
    public InMemoryApiResourceCommitStore(Clock clock, Duration leaseDuration, ApiResourceDecisions decisions,
                                          ApiResourceProjectionCompiler compiler, State state) {
        this.clock = Objects.requireNonNull(clock, "clock");
        if (leaseDuration == null || leaseDuration.isZero() || leaseDuration.isNegative()) throw new IllegalArgumentException("leaseDuration must be positive");
        this.leaseDuration = leaseDuration;
        this.decisions = Objects.requireNonNull(decisions, "decisions");
        this.compiler = Objects.requireNonNull(compiler, "compiler");
        this.state = Objects.requireNonNull(state, "state");
    }

    /** Reopens state using the default decision engine. */
    public InMemoryApiResourceCommitStore(Clock clock, Duration leaseDuration, ApiResourceProjectionCompiler compiler, State state) {
        this(clock, leaseDuration, new ApiResourceDecisions(), compiler, state);
    }

    @Override
    public ClaimResult claim(CommandKey key, String requestFingerprint, ExpectedRevision expectedRevision) {
        Objects.requireNonNull(key, "key");
        requireFingerprint(requestFingerprint);
        Objects.requireNonNull(expectedRevision, "expectedRevision");
        synchronized (state.monitor) {
            Journal prior = state.journals.get(key);
            Instant now = clock.instant();
            if (prior != null && !prior.fingerprint.equals(requestFingerprint)) {
                return new ClaimResult.Conflict("idempotency fingerprint conflict");
            }
            if (prior != null && !prior.expectedRevision.equals(expectedRevision)) {
                return new ClaimResult.Conflict("expected revision conflict");
            }
            if (prior != null && prior.status == Status.COMMITTED) return new ClaimResult.Replay(prior.receipt);
            if (prior != null && prior.status == Status.PREPARING && prior.lease.leaseUntil().isAfter(now)) return new ClaimResult.Busy(prior.lease.leaseUntil());
            boolean resumed = prior != null;
            int attempt = prior == null ? 1 : prior.lease.attemptNo() + 1;
            if (prior != null) state.stages.remove(new StageKey(prior.lease.commandId(), prior.lease.attemptToken()));
            String commandId = prior == null ? UUID.randomUUID().toString() : prior.lease.commandId();
            CommandLease lease = new CommandLease(commandId, attempt, UUID.randomUUID().toString(), key,
                    requestFingerprint, now.plus(leaseDuration), expectedRevision);
            state.journals.put(key, new Journal(Status.PREPARING, requestFingerprint, expectedRevision,
                    lease, null, null));
            return new ClaimResult.Acquired(lease, resumed);
        }
    }

    @Override
    public StagedApiResource stage(CommandLease lease, String connectionId, ApiResourceCommand command) {
        synchronized (state.monitor) {
            requireActive(lease);
            StageKey stageKey = new StageKey(lease.commandId(), lease.attemptToken());
            StagedApiResource existing = state.stages.get(stageKey);
            ResourceKey resourceKey = new ResourceKey(lease.key().scope(), lease.key().targetId());
            StoredApiResource head = state.heads.get(resourceKey);
            ApiResourceSpec next;
            try {
                next = decisions.next(Optional.ofNullable(head == null ? null : head.resource()),
                        lease.key().targetId(), connectionId, command, lease.expectedRevision());
            } catch (com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceAuthoringException ex) {
                if (ex.code() == com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceAuthoringException.Code.ALREADY_EXISTS
                        || ex.code() == com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceAuthoringException.Code.NOT_FOUND
                        || ex.code() == com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceAuthoringException.Code.CAS_MISMATCH) {
                    throw error(ApiResourceCommitStoreException.Code.CAS_MISMATCH, "head revision changed");
                }
                throw ex;
            }
            if (existing != null) {
                if (!existing.resource().fingerprint().equals(next.fingerprint())) {
                    throw error(ApiResourceCommitStoreException.Code.INTEGRITY, "staged content changed");
                }
                return existing;
            }
            ReadyApiResourceProjections projections;
            try {
                projections = compiler.compile(lease.key().scope(), next);
            } catch (RuntimeException ex) {
                throw error(ApiResourceCommitStoreException.Code.PROJECTION_INVALID, "projection compilation failed");
            }
            verifyProjections(next, projections);
            StagedApiResource staged = new StagedApiResource(lease, next, projections, opaqueEtag());
            state.stages.put(stageKey, staged);
            return staged;
        }
    }

    /** Explicit-target stage overload retained for adapter ergonomics. */
    public StagedApiResource stage(CommandLease lease, String resourceId, String connectionId, ApiResourceCommand command) {
        if (!lease.key().targetId().equals(resourceId)) throw error(ApiResourceCommitStoreException.Code.INTEGRITY, "resource target differs from lease");
        return stage(lease, connectionId, command);
    }

    @Override
    public CommandReceipt commit(CommandLease lease, CommandReceipt finalReceipt) {
        synchronized (state.monitor) {
            requireActive(lease);
            StagedApiResource staged = state.stages.get(new StageKey(lease.commandId(), lease.attemptToken()));
            if (staged == null) throw error(ApiResourceCommitStoreException.Code.STAGE_MISSING, "staged resource is missing");
            try {
                validateReceipt(finalReceipt, staged.strongEtag());
            } catch (ApiResourceCommitStoreException ex) {
                throw ex;
            } catch (RuntimeException ex) {
                throw error(ApiResourceCommitStoreException.Code.RECEIPT_INVALID, "receipt is invalid");
            }
            ResourceKey key = new ResourceKey(lease.key().scope(), lease.key().targetId());
            StoredApiResource current = state.heads.get(key);
            checkExpected(current, lease.expectedRevision());
            verifyProjections(staged.resource(), staged.projections());
            StoredApiResource stored = new StoredApiResource(lease.key().scope(), staged.resource(), staged.projections(), finalReceipt);
            state.heads.put(key, stored);
            state.revisions.put(new ResourceRevisionKey(key, staged.resource().revision()), stored);
            state.stages.remove(new StageKey(lease.commandId(), lease.attemptToken()));
            state.journals.put(lease.key(), new Journal(Status.COMMITTED, lease.requestFingerprint(),
                    lease.expectedRevision(), lease, finalReceipt, null));
            return finalReceipt;
        }
    }

    @Override public void fail(CommandLease lease, CommandFailureCode failureCode) {
        synchronized (state.monitor) {
            if (lease == null) throw error(ApiResourceCommitStoreException.Code.LEASE_FENCED, "lease is fenced");
            if (failureCode == null) throw error(ApiResourceCommitStoreException.Code.INTEGRITY, "failure code is required");
            Journal journal = state.journals.get(lease.key());
            if (journal == null || !sameLease(journal.lease, lease)) return;
            if (journal.status == Status.COMMITTED) throw error(ApiResourceCommitStoreException.Code.INTEGRITY, "committed command cannot fail");
            requireActive(lease);
            state.stages.remove(new StageKey(lease.commandId(), lease.attemptToken()));
            state.journals.put(lease.key(), new Journal(Status.FAILED, journal.fingerprint,
                    journal.expectedRevision, lease, null, failureCode));
        }
    }

    @Override
    public Optional<StoredApiResource> findHead(AuthoringScope scope, String resourceId) {
        synchronized (state.monitor) {
            return Optional.ofNullable(state.heads.get(new ResourceKey(scope, resourceId)));
        }
    }

    @Override
    public Optional<StoredApiResource> findRevision(AuthoringScope scope, String resourceId, long revision) {
        synchronized (state.monitor) {
            return Optional.ofNullable(state.revisions.get(new ResourceRevisionKey(new ResourceKey(scope, resourceId), revision)));
        }
    }

    @Override
    public Optional<StoredApiResource> findRevisionByStrongEtag(AuthoringScope scope, String resourceId,
                                                               String strongEtag) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(resourceId, "resourceId");
        if (!StrongEtag.isValid(strongEtag)) {
            throw error(ApiResourceCommitStoreException.Code.INTEGRITY, "strong ETag is invalid");
        }
        synchronized (state.monitor) {
            return state.revisions.entrySet().stream()
                    .filter(entry -> entry.getKey().resource().equals(new ResourceKey(scope, resourceId)))
                    .map(Map.Entry::getValue)
                    .filter(stored -> strongEtag.equals(stored.receipt().strongEtag()))
                    .reduce((left, right) -> {
                        throw error(ApiResourceCommitStoreException.Code.INTEGRITY,
                                "committed resource ETag provenance is ambiguous");
                    });
        }
    }

    private void requireActive(CommandLease lease) {
        if (lease == null) throw error(ApiResourceCommitStoreException.Code.LEASE_FENCED, "lease is fenced");
        Journal journal = state.journals.get(lease.key());
        if (journal == null || journal.status != Status.PREPARING || !sameLease(journal.lease, lease)) {
            throw error(ApiResourceCommitStoreException.Code.LEASE_FENCED, "lease is fenced");
        }
        if (!lease.leaseUntil().isAfter(clock.instant())) throw error(ApiResourceCommitStoreException.Code.LEASE_EXPIRED, "lease expired");
    }
    private static boolean sameLease(CommandLease a, CommandLease b) {
        return a != null && b != null && a.commandId().equals(b.commandId())
                && a.attemptNo() == b.attemptNo() && a.attemptToken().equals(b.attemptToken());
    }

    private static void checkExpected(StoredApiResource current, ExpectedRevision expected) {
        boolean mismatch = expected instanceof ExpectedRevision.Create && current != null
                || expected instanceof ExpectedRevision.Match m
                && (current == null || current.resource().revision() != m.revision());
        if (mismatch) throw error(ApiResourceCommitStoreException.Code.CAS_MISMATCH, "head revision changed");
    }

    private static void verifyProjections(ApiResourceSpec resource, ReadyApiResourceProjections projections) {
        if (projections == null || !resource.ref().equals(projections.subject())) {
            throw error(ApiResourceCommitStoreException.Code.PROJECTION_INVALID, "projection subject drift");
        }
        for (ProjectionDocument document : new ProjectionDocument[]{projections.descriptor(), projections.designContract(), projections.operator()}) {
            if (document.state() != ProjectionDocument.State.READY
                    || !AuthoringFingerprints.of(document.body()).equals(document.fingerprint())) {
                throw error(ApiResourceCommitStoreException.Code.PROJECTION_INVALID, "projection integrity drift");
            }
        }
    }

    private static void validateReceipt(CommandReceipt receipt, String expectedEtag) {
        if (receipt == null || !expectedEtag.equals(receipt.strongEtag())
                || !AuthoringFingerprints.of(receipt.body()).equals(receipt.bodyFingerprint())) {
            throw error(ApiResourceCommitStoreException.Code.RECEIPT_INVALID, "receipt integrity drift");
        }
    }

    private static String opaqueEtag() { return "\"" + UUID.randomUUID() + "\""; }

    private static void requireFingerprint(String value) {
        if (value == null || !value.matches("sha256:[0-9a-f]{64}")) {
            throw error(ApiResourceCommitStoreException.Code.INTEGRITY, "fingerprint is invalid");
        }
    }

    private static ApiResourceCommitStoreException error(ApiResourceCommitStoreException.Code code, String message) {
        return new ApiResourceCommitStoreException(code, message);
    }
    private enum Status { PREPARING, FAILED, COMMITTED }
    private record Journal(Status status, String fingerprint, ExpectedRevision expectedRevision,
                           CommandLease lease, CommandReceipt receipt, CommandFailureCode failureCode) { }
    private record ResourceKey(AuthoringScope scope, String resourceId) { }
    private record ResourceRevisionKey(ResourceKey resource, long revision) { }
    private record StageKey(String commandId, String attemptToken) { }
}
