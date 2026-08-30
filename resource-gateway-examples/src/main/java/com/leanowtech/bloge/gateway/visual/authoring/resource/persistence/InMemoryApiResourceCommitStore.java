package com.leanowtech.bloge.gateway.visual.authoring.resource.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceCommand;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceDecisions;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceSpec;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ExpectedRevision;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Contract-reference implementation of the commit protocol. It is deliberately
 * not a Spring bean and uses one monitor to keep the state machine easy to audit.
 */
public final class InMemoryApiResourceCommitStore implements ApiResourceCommitStore {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Clock clock;
    private final Duration leaseDuration;
    private final ApiResourceProjectionCompiler compiler;
    private final State state;

    /** Mutable durable state holder useful for restart/recovery contract tests. */
    public static final class State {
        private final Map<CommandKey, Journal> journals = new ConcurrentHashMap<>();
        private final Map<StageKey, StagedApiResource> stages = new ConcurrentHashMap<>();
        private final Map<ResourceKey, StoredApiResource> heads = new ConcurrentHashMap<>();
        private final Map<ResourceRevisionKey, StoredApiResource> revisions = new ConcurrentHashMap<>();
    }

    /** Creates a reference store with a supplied clock, lease and compiler. */
    public InMemoryApiResourceCommitStore(Clock clock, Duration leaseDuration,
                                          ApiResourceProjectionCompiler compiler) {
        this(clock, leaseDuration, compiler, new State());
    }

    /** Reopens the same state holder, modelling process restart. */
    public InMemoryApiResourceCommitStore(Clock clock, Duration leaseDuration,
                                          ApiResourceProjectionCompiler compiler, State state) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.leaseDuration = requireDuration(leaseDuration);
        this.compiler = Objects.requireNonNull(compiler, "compiler");
        this.state = Objects.requireNonNull(state, "state");
    }

    /** Compatibility constructor retaining an explicit decision dependency in the seam. */
    public InMemoryApiResourceCommitStore(Clock clock, Duration leaseDuration,
                                          ApiResourceDecisions decisions,
                                          ApiResourceProjectionCompiler compiler) {
        this(clock, leaseDuration, compiler);
    }

    /** Compatibility ordering for adapters that list the compiler before the decision dependency. */
    public InMemoryApiResourceCommitStore(Clock clock, Duration leaseDuration,
                                          ApiResourceProjectionCompiler compiler,
                                          ApiResourceDecisions decisions) {
        this(clock, leaseDuration, compiler);
    }

    @Override
    public synchronized ClaimResult claim(CommandKey key, String requestFingerprint, ExpectedRevision expectedRevision) {
        Objects.requireNonNull(key, "key");
        requireText(requestFingerprint, "requestFingerprint");
        Objects.requireNonNull(expectedRevision, "expectedRevision");
        Journal prior = state.journals.get(key);
        Instant now = clock.instant();
        if (prior != null && prior.status == Status.COMMITTED) {
            return prior.fingerprint.equals(requestFingerprint)
                    ? new ClaimResult.Replay(prior.receipt)
                    : new ClaimResult.Conflict("idempotency key has a different request fingerprint");
        }
        if (prior != null && prior.status == Status.PREPARING && prior.lease.leaseUntil().isAfter(now)) {
            return prior.fingerprint.equals(requestFingerprint)
                    ? new ClaimResult.Busy(prior.lease)
                    : new ClaimResult.Conflict("idempotency key has a different request fingerprint");
        }
        if (prior != null && prior.status == Status.PREPARING) {
            if (!prior.fingerprint.equals(requestFingerprint)) {
                return new ClaimResult.Conflict("idempotency key has a different request fingerprint");
            }
            state.stages.remove(new StageKey(prior.lease.commandId(), prior.lease.attemptToken()));
        }
        if (prior != null && prior.status == Status.FAILED && !prior.fingerprint.equals(requestFingerprint)) {
            return new ClaimResult.Conflict("idempotency key has a different request fingerprint");
        }
        boolean resumed = prior != null;
        CommandLease lease = new CommandLease(UUID.randomUUID().toString(), UUID.randomUUID().toString(), key,
                requestFingerprint, now.plus(leaseDuration), expectedRevision);
        state.journals.put(key, new Journal(Status.PREPARING, requestFingerprint, lease, null));
        return new ClaimResult.Acquired(lease, resumed);
    }

    @Override
    public synchronized StagedApiResource stage(CommandLease lease, String connectionId, ApiResourceCommand command) {
        requireActive(lease);
        StageKey stageKey = new StageKey(lease.commandId(), lease.attemptToken());
        StagedApiResource existing = state.stages.get(stageKey);
        ResourceKey resourceKey = new ResourceKey(lease.key().scope(), lease.key().targetId());
        StoredApiResource head = state.heads.get(resourceKey);
        ApiResourceSpec next = ApiResourceDecisions.next(Optional.ofNullable(head == null ? null : head.resource()),
                lease.key().targetId(), connectionId, command, lease.expectedRevision());
        if (existing != null) {
            if (!existing.resource().fingerprint().equals(next.fingerprint())) {
                throw new IllegalStateException("same attempt has different staged content");
            }
            return existing;
        }
        ReadyApiResourceProjections projections = compiler.compile(next);
        verifyProjections(next, projections);
        StagedApiResource staged = new StagedApiResource(lease, next, projections);
        state.stages.put(stageKey, staged);
        return staged;
    }

    /** Alias allowing callers to make the target explicit while retaining the key as authority. */
    public StagedApiResource stage(CommandLease lease, String resourceId, String connectionId, ApiResourceCommand command) {
        if (!lease.key().targetId().equals(resourceId)) throw new IllegalArgumentException("resourceId differs from command key");
        return stage(lease, connectionId, command);
    }

    @Override
    public synchronized CommandReceipt commit(CommandLease lease) {
        requireActive(lease);
        StageKey stageKey = new StageKey(lease.commandId(), lease.attemptToken());
        StagedApiResource staged = state.stages.get(stageKey);
        if (staged == null) throw new IllegalStateException("staged resource is missing");
        ResourceKey key = new ResourceKey(lease.key().scope(), lease.key().targetId());
        StoredApiResource current = state.heads.get(key);
        checkExpected(current, lease.expectedRevision(), lease.key().targetId());
        verifyProjections(staged.resource(), staged.projections());
        CommandReceipt receipt = newReceipt(staged.resource(), lease.key());
        StoredApiResource stored = new StoredApiResource(lease.key().scope(), staged.resource(), staged.projections(), receipt);
        state.heads.put(key, stored);
        state.revisions.put(new ResourceRevisionKey(key, staged.resource().revision()), stored);
        state.stages.remove(stageKey);
        state.journals.put(lease.key(), new Journal(Status.COMMITTED, lease.requestFingerprint(), lease, receipt));
        return receipt;
    }

    @Override
    public synchronized void fail(CommandLease lease) {
        Journal journal = state.journals.get(lease.key());
        if (journal == null || !sameLease(journal.lease, lease)) return;
        if (journal.status == Status.COMMITTED) throw new IllegalStateException("committed command cannot fail");
        if (journal.status == Status.PREPARING) {
            state.stages.remove(new StageKey(lease.commandId(), lease.attemptToken()));
            state.journals.put(lease.key(), new Journal(Status.FAILED, journal.fingerprint, lease, null));
        }
    }

    @Override
    public synchronized Optional<StoredApiResource> findHead(AuthoringScope scope, String resourceId) {
        return Optional.ofNullable(state.heads.get(new ResourceKey(scope, resourceId)));
    }

    @Override
    public synchronized Optional<StoredApiResource> findRevision(AuthoringScope scope, String resourceId, long revision) {
        return Optional.ofNullable(state.revisions.get(new ResourceRevisionKey(new ResourceKey(scope, resourceId), revision)));
    }

    private void requireActive(CommandLease lease) {
        Objects.requireNonNull(lease, "lease");
        Journal journal = state.journals.get(lease.key());
        if (journal == null || journal.status != Status.PREPARING || !sameLease(journal.lease, lease)) {
            throw new IllegalStateException("command lease is fenced");
        }
        if (!lease.leaseUntil().isAfter(clock.instant())) throw new IllegalStateException("command lease expired");
    }

    private static boolean sameLease(CommandLease left, CommandLease right) {
        return left.commandId().equals(right.commandId()) && left.attemptToken().equals(right.attemptToken());
    }

    private static void checkExpected(StoredApiResource current, ExpectedRevision expected, String resourceId) {
        if (expected instanceof ExpectedRevision.Create) {
            if (current != null) throw new IllegalStateException("commit CAS mismatch for " + resourceId);
        } else if (expected instanceof ExpectedRevision.Match match) {
            if (current == null || current.resource().revision() != match.revision()) {
                throw new IllegalStateException("commit CAS mismatch for " + resourceId);
            }
        } else throw new IllegalArgumentException("unsupported expected revision");
    }

    private static void verifyProjections(ApiResourceSpec resource, ReadyApiResourceProjections projections) {
        if (projections == null || !resource.ref().equals(projections.subject())) throw new IllegalStateException("projection subject drift");
        List<ProjectionDocument> docs = List.of(projections.descriptor(), projections.designContract(), projections.operator());
        for (ProjectionDocument doc : docs) {
            if (doc.state() != ProjectionDocument.State.READY || !resource.fingerprint().equals(doc.fingerprint())) {
                throw new IllegalStateException("projection fingerprint or state drift");
            }
        }
    }

    private static CommandReceipt newReceipt(ApiResourceSpec resource, CommandKey key) {
        ObjectNode body = JSON.createObjectNode();
        body.put("schemaVersion", CommandReceipt.SCHEMA_VERSION);
        body.put("resourceId", resource.resourceId());
        body.put("revision", resource.revision());
        body.put("fingerprint", resource.fingerprint());
        body.put("scope", key.scope().tenantId() + "/" + key.scope().projectId() + "/" + key.scope().environmentId());
        return new CommandReceipt(CommandReceipt.SCHEMA_VERSION, body, fingerprint(body), "\"" + UUID.randomUUID() + "\"");
    }

    private static String fingerprint(JsonNode node) {
        try {
            byte[] bytes = node.toString().getBytes(StandardCharsets.UTF_8);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder out = new StringBuilder("sha256:");
            for (byte b : digest) out.append(String.format("%02x", b));
            return out.toString();
        } catch (Exception e) { throw new IllegalStateException("unable to fingerprint receipt", e); }
    }

    private static Duration requireDuration(Duration duration) {
        if (duration == null || duration.isZero() || duration.isNegative()) throw new IllegalArgumentException("leaseDuration must be positive");
        return duration;
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
    }

    private enum Status { PREPARING, FAILED, COMMITTED }
    private record Journal(Status status, String fingerprint, CommandLease lease, CommandReceipt receipt) { }
    private record ResourceKey(AuthoringScope scope, String resourceId) { }
    private record ResourceRevisionKey(ResourceKey resource, long revision) { }
    private record StageKey(String commandId, String attemptToken) { }
}
