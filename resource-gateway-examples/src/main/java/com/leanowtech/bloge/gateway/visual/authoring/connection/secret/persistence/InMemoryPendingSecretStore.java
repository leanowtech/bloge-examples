package com.leanowtech.bloge.gateway.visual.authoring.connection.secret.persistence;

import com.leanowtech.bloge.gateway.visual.authoring.connection.secret.ActiveSecretBinding;
import com.leanowtech.bloge.gateway.visual.authoring.connection.secret.ActivatedExternalSecret;
import com.leanowtech.bloge.gateway.visual.authoring.connection.secret.PreparedExternalSecret;
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
    private enum State { PENDING, ACTIVATED, ABORT_REQUIRED, ABORTED }
    private final Clock clock;
    private final Map<AttemptKey, Entry> entries = new HashMap<>();
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
        if (expired(batch.lease())) throw failure(PendingSecretStoreException.Code.LEASE_EXPIRED);
        entries.put(key, new Entry(batch, List.of(), State.PENDING));
    }

    @Override public synchronized Optional<PendingSecretBatch> findExact(CommandLease lease,
                                                                           ConnectionRevisionCoordinate coordinate) {
        if (lease == null || coordinate == null) return Optional.empty();
        if (!coordinate.scope().equals(lease.key().scope())
                || !coordinate.connectionId().equals(lease.key().targetId())) return Optional.empty();
        Entry entry = entries.get(key(new PendingSecretLease(lease, coordinate)));
        return entry == null || (entry.state != State.PENDING && entry.state != State.ABORT_REQUIRED)
                ? Optional.empty() : Optional.of(entry.batch);
    }

    @Override public synchronized void commitBindings(PendingSecretBatch batch,
                                                       List<ActivatedSecretSlot> activated) {
        requireBatch(batch);
        List<ActivatedSecretSlot> outputs = List.copyOf(activated == null ? List.of() : activated);
        Entry entry = requireEntry(batch.lease());
        if (!entry.batch.equals(batch)) throw failure(PendingSecretStoreException.Code.INTEGRITY);
        if (entry.state == State.ACTIVATED) {
            if (entry.activated.equals(outputs)) return;
            throw failure(PendingSecretStoreException.Code.INTEGRITY);
        }
        if (entry.state != State.PENDING) throw failure(PendingSecretStoreException.Code.RECOVERY_STATE);
        validateActivation(entry.batch, outputs);
        Map<BindingKey, ActiveSecretBinding> writes = new HashMap<>();
        for (PendingSecretOperation operation : entry.batch.operations()) {
            ActiveSecretBinding binding;
            if (operation instanceof PendingSecretOperation.Retained retained) {
                ActiveSecretBinding old = retained.oldBinding();
                binding = new ActiveSecretBinding(old.providerId(), old.activeLocator(), entry.batch.lease().commandLease().commandId());
            } else {
                ActivatedExternalSecret result = findOutput(outputs, operation.slot());
                binding = new ActiveSecretBinding(result.providerId(), result.activeLocator(),
                        entry.batch.lease().commandLease().commandId());
            }
            writes.put(new BindingKey(entry.batch.lease().coordinate(), operation.slot()), binding);
        }
        active.putAll(writes);
        AttemptKey owner = key(entry.batch.lease());
        for (BindingKey bindingKey : writes.keySet()) activeOwners.put(bindingKey, owner);
        entries.put(key(entry.batch.lease()), new Entry(entry.batch, outputs, State.ACTIVATED));
    }

    @Override public synchronized void markAbortRequired(PendingSecretLease lease) {
        if (lease == null) throw failure(PendingSecretStoreException.Code.LEASE_FENCED);
        Entry entry = requireEntry(lease);
        if (entry.state == State.ABORTED) return;
        if (entry.state == State.ABORT_REQUIRED) return;
        if (entry.state != State.PENDING && entry.state != State.ACTIVATED) {
            throw failure(PendingSecretStoreException.Code.RECOVERY_STATE);
        }
        removeBindings(entry);
        entries.put(key(lease), new Entry(entry.batch, entry.activated, State.ABORT_REQUIRED));
    }

    @Override public synchronized List<SecretAbortCandidate> findRecoveryDue(int commandLimit) {
        if (commandLimit < 1) throw failure(PendingSecretStoreException.Code.INTEGRITY);
        List<Entry> due = entries.values().stream()
                .filter(entry -> entry.state == State.ABORT_REQUIRED ||
                        (entry.state == State.PENDING && expired(entry.batch.lease())))
                .sorted(Comparator.comparing((Entry e) -> e.batch.lease().commandLease().commandId())
                        .thenComparingInt(e -> e.batch.lease().commandLease().attemptNo()))
                .toList();
        List<SecretAbortCandidate> result = new ArrayList<>();
        Set<String> commands = new HashSet<>();
        for (Entry entry : due) {
            String command = entry.batch.lease().commandLease().commandId();
            if (!commands.contains(command) && commands.size() == commandLimit) break;
            commands.add(command);
            result.add(new SecretAbortCandidate(entry.batch, entry.activated));
        }
        return List.copyOf(result);
    }

    @Override public synchronized void completeAbort(SecretAbortCandidate candidate) {
        if (candidate == null) throw failure(PendingSecretStoreException.Code.INTEGRITY);
        PendingSecretLease lease = candidate.batch().lease();
        Entry entry = entries.get(key(lease));
        if (entry == null) throw failure(PendingSecretStoreException.Code.STAGE_MISSING);
        if (entry.state == State.ABORTED) {
            if (entry.batch.equals(candidate.batch()) && entry.activated.equals(candidate.activated())) return;
            throw failure(PendingSecretStoreException.Code.INTEGRITY);
        }
        if (entry.state != State.ABORT_REQUIRED || !entry.batch.equals(candidate.batch())
                || !entry.activated.equals(candidate.activated())) {
            throw failure(PendingSecretStoreException.Code.RECOVERY_STATE);
        }
        removeBindings(entry);
        entries.put(key(lease), new Entry(entry.batch, entry.activated, State.ABORTED));
    }

    @Override public synchronized Optional<ActiveSecretBinding> findActive(ConnectionRevisionCoordinate coordinate,
                                                                             String slot) {
        if (coordinate == null || slot == null) return Optional.empty();
        PendingSecretOperation.SlotRules.require(slot);
        return Optional.ofNullable(active.get(new BindingKey(coordinate, slot)));
    }

    private void requireBatch(PendingSecretBatch batch) {
        if (batch == null) throw failure(PendingSecretStoreException.Code.INTEGRITY);
        PendingSecretLease lease = batch.lease();
        CommandLease command = lease.commandLease();
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
                        || !command.key().actorId().equals(context.actorId())
                        || !value.leaseUntil().equals(command.leaseUntil())) {
                    throw failure(PendingSecretStoreException.Code.INTEGRITY);
                }
            } else {
                ActiveSecretBinding old = ((PendingSecretOperation.Retained) operation).oldBinding();
                if (old.commandId().equals(command.commandId())) throw failure(PendingSecretStoreException.Code.INTEGRITY);
            }
        }
    }

    private void validateActivation(PendingSecretBatch batch, List<ActivatedSecretSlot> outputs) {
        Set<String> expected = new HashSet<>();
        for (PendingSecretOperation operation : batch.operations()) {
            if (operation instanceof PendingSecretOperation.Prepared) expected.add(operation.slot());
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

    private ActivatedExternalSecret findOutput(List<ActivatedSecretSlot> outputs, String slot) {
        ActivatedSecretSlot output = outputs.stream().filter(candidate -> candidate.slot().equals(slot)).findFirst().orElseThrow();
        return output.activated();
    }

    private Entry requireEntry(PendingSecretLease lease) {
        Entry entry = entries.get(key(lease));
        if (entry == null) {
            boolean fenced = entries.values().stream().anyMatch(candidate -> {
                PendingSecretLease stored = candidate.batch.lease();
                return stored.coordinate().equals(lease.coordinate())
                        && stored.commandLease().commandId().equals(lease.commandLease().commandId());
            });
            throw failure(fenced ? PendingSecretStoreException.Code.LEASE_FENCED
                    : PendingSecretStoreException.Code.STAGE_MISSING);
        }
        if (!entry.batch.lease().equals(lease)) throw failure(PendingSecretStoreException.Code.LEASE_FENCED);
        return entry;
    }

    private boolean expired(PendingSecretLease lease) { return !lease.leaseUntil().isAfter(Instant.now(clock)); }

    private void removeBindings(Entry entry) {
        for (PendingSecretOperation operation : entry.batch.operations()) {
            BindingKey key = new BindingKey(entry.batch.lease().coordinate(), operation.slot());
            ActiveSecretBinding binding = active.get(key);
            if (binding != null && key(entry.batch.lease()).equals(activeOwners.get(key))) {
                active.remove(key);
                activeOwners.remove(key);
            }
        }
    }

    private static AttemptKey key(PendingSecretLease lease) {
        CommandLease command = lease.commandLease();
        return new AttemptKey(lease.coordinate(), command.commandId(), command.attemptNo(), command.attemptToken());
    }

    private static PendingSecretStoreException failure(PendingSecretStoreException.Code code) {
        return new PendingSecretStoreException(code);
    }

    private record AttemptKey(ConnectionRevisionCoordinate coordinate, String commandId, int attemptNo,
                              String attemptToken) { }
    private record BindingKey(ConnectionRevisionCoordinate coordinate, String slot) { }
    private record Entry(PendingSecretBatch batch, List<ActivatedSecretSlot> activated, State state) { }
}
