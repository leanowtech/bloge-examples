package com.leanowtech.bloge.gateway.visual.authoring.connection.secret.persistence;

import com.leanowtech.bloge.gateway.visual.authoring.connection.secret.ActiveSecretBinding;
import com.leanowtech.bloge.gateway.visual.authoring.connection.secret.ActivatedExternalSecret;
import com.leanowtech.bloge.gateway.visual.authoring.connection.secret.PreparedExternalSecret;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ExpectedRevision;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.CommandLease;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Small reference implementation of the pending-secret protocol.
 * It is useful for contract tests and local reasoning, not as a durable store.
 */
public final class InMemoryPendingSecretStore implements PendingSecretStore {
    private enum State { PENDING, ABORT_REQUIRED }
    private enum TerminalOutcome { COMMITTED, ABORTED }
    private final Clock clock;
    private final Map<AttemptKey, Entry> entries = new HashMap<>();
    private final Map<AttemptKey, Completion> completed = new HashMap<>();
    private final Map<CommandAttemptKey, AttemptKey> attemptOwners = new HashMap<>();
    private final Map<String, CommandAuthority> commandOwners = new HashMap<>();
    private final Map<BindingKey, ActiveSecretBinding> active = new HashMap<>();
    private final Map<BindingKey, AttemptKey> activeOwners = new HashMap<>();

    /** Creates a store using UTC wall-clock time. */
    public InMemoryPendingSecretStore() { this(Clock.systemUTC()); }

    /** Creates a store with an injected clock so recovery boundaries are deterministic. */
    public InMemoryPendingSecretStore(Clock clock) {
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    @Override public synchronized void stage(PendingSecretBatch batch) {
        requireBatch(batch);
        AttemptKey key = key(batch.lease());
        Entry existing = entries.get(key);
        if (existing != null) {
            if (existing.batch.equals(batch)) return;
            throw failure(PendingSecretStoreException.Code.INTEGRITY);
        }
        Completion done = completed.get(key);
        if (done != null) {
            if (done.batch.equals(batch)) return;
            throw failure(PendingSecretStoreException.Code.INTEGRITY);
        }
        CommandAttemptKey attempt = attemptKey(batch.lease());
        AttemptKey owner = attemptOwners.get(attempt);
        if (owner != null && !owner.equals(key)) throw failure(PendingSecretStoreException.Code.INTEGRITY);
        CommandAuthority authority = authority(batch.lease());
        CommandAuthority commandOwner = commandOwners.get(batch.lease().commandLease().commandId());
        if (commandOwner != null && !commandOwner.equals(authority)) {
            throw failure(PendingSecretStoreException.Code.INTEGRITY);
        }
        Map<String, ActiveSecretBinding> retained = snapshotRetained(batch);
        Instant now = clock.instant();
        Instant deadline = effectiveDeadline(batch);
        if (!deadline.isAfter(now)) throw failure(PendingSecretStoreException.Code.LEASE_EXPIRED);
        entries.put(key, new Entry(batch, retained, State.PENDING, deadline, now));
        attemptOwners.put(attempt, key);
        commandOwners.putIfAbsent(batch.lease().commandLease().commandId(), authority);
    }

    @Override public synchronized Optional<PendingSecretBatch> findExact(PendingSecretLease lease) {
        if (lease == null) return Optional.empty();
        Entry entry = entries.get(key(lease));
        return entry == null || !entry.batch.lease().equals(lease) ? Optional.empty() : Optional.of(entry.batch);
    }

    @Override public synchronized FinalizedSecretSlots prepareFinalization(PendingSecretBatch batch,
                                                                            List<ActivatedSecretSlot> activated) {
        requireBatch(batch);
        List<ActivatedSecretSlot> outputs = canonical(activated);
        Completion done = completed.get(key(batch.lease()));
        if (done != null) {
            if (done.outcome == TerminalOutcome.COMMITTED && done.batch.equals(batch)
                    && done.outputs.equals(outputs)) return done.proof;
            if (done.outcome == TerminalOutcome.ABORTED) throw failure(PendingSecretStoreException.Code.RECOVERY_STATE);
            throw failure(PendingSecretStoreException.Code.INTEGRITY);
        }
        Entry entry = requireEntry(batch.lease());
        if (!entry.batch.equals(batch)) throw failure(PendingSecretStoreException.Code.INTEGRITY);
        if (entry.state != State.PENDING) throw failure(PendingSecretStoreException.Code.RECOVERY_STATE);
        if (!entry.effectiveDeadline.isAfter(clock.instant())) {
            throw failure(PendingSecretStoreException.Code.LEASE_EXPIRED);
        }
        validateActivation(entry.batch, entry.retained, outputs);
        return FinalizedSecretSlots.from(batch.lease(), entry.batch.operations().stream()
                .map(PendingSecretOperation::slot)
                .collect(java.util.stream.Collectors.toUnmodifiableSet()));
    }

    @Override public synchronized FinalizedSecretSlots commitBindings(PendingSecretBatch batch,
                                                                       List<ActivatedSecretSlot> activated) {
        requireBatch(batch);
        List<ActivatedSecretSlot> outputs = canonical(activated);
        AttemptKey key = key(batch.lease());
        Completion done = completed.get(key);
        if (done != null) {
            if (done.outcome == TerminalOutcome.COMMITTED && done.batch.equals(batch)
                    && done.outputs.equals(outputs)) return done.proof;
            if (done.outcome == TerminalOutcome.ABORTED) throw failure(PendingSecretStoreException.Code.RECOVERY_STATE);
            throw failure(PendingSecretStoreException.Code.INTEGRITY);
        }
        FinalizedSecretSlots proof = prepareFinalization(batch, outputs);
        Entry entry = requireEntry(batch.lease());
        Map<BindingKey, ActiveSecretBinding> writes = new HashMap<>();
        for (PendingSecretOperation operation : entry.batch.operations()) {
            ActiveSecretBinding binding;
            if (operation instanceof PendingSecretOperation.Retained) {
                ActiveSecretBinding old = entry.retained.get(operation.slot());
                if (old == null) throw failure(PendingSecretStoreException.Code.STAGE_MISSING);
                binding = new ActiveSecretBinding(old.providerId(), old.activeLocator(),
                        batch.lease().commandLease().commandId());
            } else {
                ActivatedExternalSecret result = findOutput(outputs, operation.slot());
                binding = new ActiveSecretBinding(result.providerId(), result.activeLocator(),
                        batch.lease().commandLease().commandId());
            }
            writes.put(new BindingKey(batch.lease().coordinate(), operation.slot()), binding);
        }
        active.putAll(writes);
        for (BindingKey bindingKey : writes.keySet()) activeOwners.put(bindingKey, key);
        entries.remove(key);
        completed.put(key, new Completion(batch, outputs, proof, TerminalOutcome.COMMITTED));
        return proof;
    }

    @Override public synchronized void markAbortRequired(PendingSecretLease lease) {
        if (lease == null) throw failure(PendingSecretStoreException.Code.LEASE_FENCED);
        AttemptKey key = key(lease);
        Completion done = completed.get(key);
        if (done != null) {
            if (done.outcome == TerminalOutcome.ABORTED) return;
            throw failure(PendingSecretStoreException.Code.RECOVERY_STATE);
        }
        Entry entry = requireEntry(lease);
        if (entry.state == State.ABORT_REQUIRED) return;
        entries.put(key, new Entry(entry.batch, entry.retained, State.ABORT_REQUIRED,
                entry.effectiveDeadline, clock.instant()));
    }

    @Override public synchronized List<SecretAbortCandidate> claimRecoveryDue(int attemptLimit) {
        if (attemptLimit < 1) throw failure(PendingSecretStoreException.Code.INTEGRITY);
        Instant now = clock.instant();
        List<Entry> due = entries.values().stream()
                .filter(entry -> entry.state == State.ABORT_REQUIRED || !entry.effectiveDeadline.isAfter(now))
                .sorted(Comparator.comparing((Entry e) -> e.state == State.ABORT_REQUIRED ? 0 : 1)
                        .thenComparing(e -> e.effectiveDeadline)
                        .thenComparing(e -> e.updatedAt)
                        .thenComparing(e -> e.batch.lease().commandLease().commandId())
                        .thenComparingInt(e -> e.batch.lease().commandLease().attemptNo())
                        .thenComparing(e -> e.batch.lease().commandLease().attemptToken()))
                .limit(attemptLimit)
                .toList();
        List<SecretAbortCandidate> result = new ArrayList<>();
        for (Entry entry : due) {
            AttemptKey key = key(entry.batch.lease());
            Entry claimed = entry.state == State.ABORT_REQUIRED ? entry
                    : new Entry(entry.batch, entry.retained, State.ABORT_REQUIRED,
                    entry.effectiveDeadline, now);
            entries.put(key, claimed);
            result.add(new SecretAbortCandidate(claimed.batch));
        }
        return List.copyOf(result);
    }

    @Override public synchronized void completeAbort(SecretAbortCandidate candidate) {
        if (candidate == null) throw failure(PendingSecretStoreException.Code.INTEGRITY);
        PendingSecretLease lease = candidate.batch().lease();
        AttemptKey key = key(lease);
        Completion done = completed.get(key);
        if (done != null) {
            if (done.outcome == TerminalOutcome.ABORTED && done.batch.equals(candidate.batch())) return;
            if (done.outcome == TerminalOutcome.COMMITTED) throw failure(PendingSecretStoreException.Code.RECOVERY_STATE);
            throw failure(PendingSecretStoreException.Code.INTEGRITY);
        }
        Entry entry = requireEntry(lease);
        if (entry.state != State.ABORT_REQUIRED || !entry.batch.equals(candidate.batch())) {
            throw failure(PendingSecretStoreException.Code.RECOVERY_STATE);
        }
        removeBindings(entry, key);
        entries.remove(key);
        completed.put(key, new Completion(entry.batch, List.of(), null, TerminalOutcome.ABORTED));
    }

    @Override public synchronized Optional<ActiveSecretBinding> findActive(ConnectionRevisionCoordinate coordinate,
                                                                             String slot) {
        if (coordinate == null || slot == null) return Optional.empty();
        PendingSecretOperation.SlotRules.require(slot);
        return Optional.ofNullable(active.get(new BindingKey(coordinate, slot)));
    }

    private Map<String, ActiveSecretBinding> snapshotRetained(PendingSecretBatch batch) {
        Map<String, ActiveSecretBinding> result = new HashMap<>();
        for (PendingSecretOperation operation : batch.operations()) {
            if (operation instanceof PendingSecretOperation.Retained retained) {
                ConnectionRevisionCoordinate source = retained.source();
                ConnectionRevisionCoordinate target = batch.lease().coordinate();
                if (!source.scope().equals(target.scope()) || !source.connectionId().equals(target.connectionId())
                        || source.revision() != target.revision() - 1) {
                    throw failure(PendingSecretStoreException.Code.INTEGRITY);
                }
                ActiveSecretBinding binding = active.get(new BindingKey(source, operation.slot()));
                if (binding == null) throw failure(PendingSecretStoreException.Code.STAGE_MISSING);
                result.put(operation.slot(), binding);
            }
        }
        return Map.copyOf(result);
    }

    private void requireBatch(PendingSecretBatch batch) {
        if (batch == null) throw failure(PendingSecretStoreException.Code.INTEGRITY);
        PendingSecretLease lease = batch.lease();
        CommandLease command = lease.commandLease();
        ExpectedRevision connectionExpected = lease.connectionExpected();
        if (command.key().endpoint() == com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringEndpoint.API_CONNECTION_SAVE
                && !command.key().targetId().equals(lease.coordinate().connectionId())) {
            throw failure(PendingSecretStoreException.Code.INTEGRITY);
        }
        if (command.key().endpoint() == com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringEndpoint.API_RESOURCE_SAVE) {
            if (!(connectionExpected instanceof ExpectedRevision.Create)
                    || lease.coordinate().revision() != 1
                    || batch.operations().stream().anyMatch(operation -> operation instanceof PendingSecretOperation.Retained)) {
                throw failure(PendingSecretStoreException.Code.INTEGRITY);
            }
        }
        if (command.key().endpoint() != com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringEndpoint.API_CONNECTION_SAVE
                && command.key().endpoint() != com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringEndpoint.API_RESOURCE_SAVE) {
            throw failure(PendingSecretStoreException.Code.INTEGRITY);
        }
        if (!connectionExpected.equals(command.expectedRevision())
                && command.key().endpoint() != com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringEndpoint.API_RESOURCE_SAVE) {
            throw failure(PendingSecretStoreException.Code.INTEGRITY);
        }
        if (connectionExpected instanceof ExpectedRevision.Create) {
            if (lease.coordinate().revision() != 1 || batch.operations().stream()
                    .anyMatch(operation -> operation instanceof PendingSecretOperation.Retained)) {
                throw failure(PendingSecretStoreException.Code.INTEGRITY);
            }
        } else if (connectionExpected instanceof ExpectedRevision.Match match) {
            if (lease.coordinate().revision() != match.revision() + 1) {
                throw failure(PendingSecretStoreException.Code.INTEGRITY);
            }
        } else {
            throw failure(PendingSecretStoreException.Code.INTEGRITY);
        }
        for (PendingSecretOperation operation : batch.operations()) {
            if (operation instanceof PendingSecretOperation.Prepared prepared) {
                PreparedExternalSecret value = prepared.prepared();
                var context = value.context();
                if (!lease.coordinate().scope().equals(context.scope())
                        || !lease.coordinate().connectionId().equals(context.connectionId())
                        || lease.coordinate().revision() != context.revision()
                        || !command.commandId().equals(context.commandId())
                        || command.attemptNo() != context.attemptNo()
                        || !command.attemptToken().equals(context.attemptToken())
                        || !command.key().actorId().equals(context.actorId())) {
                    throw failure(PendingSecretStoreException.Code.INTEGRITY);
                }
            }
        }
    }

    private Instant effectiveDeadline(PendingSecretBatch batch) {
        Instant deadline = batch.lease().commandLease().leaseUntil();
        for (PendingSecretOperation operation : batch.operations()) {
            if (operation instanceof PendingSecretOperation.Prepared prepared) {
                deadline = deadline.compareTo(prepared.prepared().leaseUntil()) <= 0
                        ? deadline : prepared.prepared().leaseUntil();
            }
        }
        return deadline;
    }

    private void validateActivation(PendingSecretBatch batch, Map<String, ActiveSecretBinding> retained,
                                    List<ActivatedSecretSlot> outputs) {
        Set<String> expected = new HashSet<>();
        for (PendingSecretOperation operation : batch.operations()) {
            if (operation instanceof PendingSecretOperation.Prepared) expected.add(operation.slot());
            else if (!retained.containsKey(operation.slot())) {
                throw failure(PendingSecretStoreException.Code.STAGE_MISSING);
            }
        }
        Set<String> actual = new HashSet<>();
        for (ActivatedSecretSlot output : outputs) {
            if (!actual.add(output.slot()) || !expected.contains(output.slot())) {
                throw failure(PendingSecretStoreException.Code.ACTIVATION_MISMATCH);
            }
            PendingSecretOperation.Prepared prepared = (PendingSecretOperation.Prepared) batch.operation(output.slot());
            if (!prepared.prepared().providerId().equals(output.activated().providerId())
                    || !prepared.prepared().leaseId().equals(output.activated().leaseId())) {
                throw failure(PendingSecretStoreException.Code.ACTIVATION_MISMATCH);
            }
        }
        if (!actual.equals(expected)) throw failure(PendingSecretStoreException.Code.ACTIVATION_MISMATCH);
    }

    private static List<ActivatedSecretSlot> canonical(List<ActivatedSecretSlot> outputs) {
        if (outputs == null) return List.of();
        return outputs.stream().sorted(Comparator.comparing(ActivatedSecretSlot::slot)).toList();
    }

    private static ActivatedExternalSecret findOutput(List<ActivatedSecretSlot> outputs, String slot) {
        return outputs.stream().filter(candidate -> candidate.slot().equals(slot))
                .map(ActivatedSecretSlot::activated).findFirst().orElseThrow();
    }

    private Entry requireEntry(PendingSecretLease lease) {
        Entry entry = entries.get(key(lease));
        if (entry == null) {
            boolean fenced = entries.keySet().stream().anyMatch(candidate -> candidate.coordinate.equals(lease.coordinate())
                    && candidate.commandId.equals(lease.commandLease().commandId()));
            throw failure(fenced ? PendingSecretStoreException.Code.LEASE_FENCED
                    : PendingSecretStoreException.Code.STAGE_MISSING);
        }
        if (!entry.batch.lease().equals(lease)) throw failure(PendingSecretStoreException.Code.LEASE_FENCED);
        return entry;
    }

    private void removeBindings(Entry entry, AttemptKey owner) {
        for (PendingSecretOperation operation : entry.batch.operations()) {
            BindingKey bindingKey = new BindingKey(entry.batch.lease().coordinate(), operation.slot());
            if (owner.equals(activeOwners.get(bindingKey))) {
                active.remove(bindingKey);
                activeOwners.remove(bindingKey);
            }
        }
    }

    private static AttemptKey key(PendingSecretLease lease) {
        CommandLease command = lease.commandLease();
        return new AttemptKey(lease.coordinate(), command.commandId(), command.attemptNo(), command.attemptToken());
    }

    private static CommandAttemptKey attemptKey(PendingSecretLease lease) {
        CommandLease command = lease.commandLease();
        return new CommandAttemptKey(command.commandId(), command.attemptNo(), command.attemptToken());
    }

    private static CommandAuthority authority(PendingSecretLease lease) {
        CommandLease command = lease.commandLease();
        return new CommandAuthority(command.key(), command.requestFingerprint(), lease.coordinate(),
                lease.connectionExpected());
    }

    private static PendingSecretStoreException failure(PendingSecretStoreException.Code code) {
        return new PendingSecretStoreException(code);
    }

    private record AttemptKey(ConnectionRevisionCoordinate coordinate, String commandId, int attemptNo,
                              String attemptToken) { }
    private record CommandAttemptKey(String commandId, int attemptNo, String attemptToken) { }
    private record CommandAuthority(com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.CommandKey key,
                                    String requestFingerprint, ConnectionRevisionCoordinate coordinate,
                                    ExpectedRevision connectionExpected) { }
    private record BindingKey(ConnectionRevisionCoordinate coordinate, String slot) { }
    private record Entry(PendingSecretBatch batch, Map<String, ActiveSecretBinding> retained, State state,
                         Instant effectiveDeadline, Instant updatedAt) { }
    private record Completion(PendingSecretBatch batch, List<ActivatedSecretSlot> outputs,
                              FinalizedSecretSlots proof,
                              TerminalOutcome outcome) { }
}
