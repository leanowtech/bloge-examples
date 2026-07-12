package com.leanowtech.bloge.gateway.example;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Process-local repository used by isolated tests and non-Spring embedders. */
final class InMemoryDynamicRunControlRepository implements DynamicRunControlRepository {
    private final ConcurrentHashMap<String, State> states = new ConcurrentHashMap<>();

    @Override
    public synchronized Claim claim(DynamicRunIntent intent, String ownerId, Instant leaseExpiresAt) {
        expire(intent.requestId(), Instant.now());
        State existing = states.get(intent.requestId());
        if (existing != null) {
            return new Claim(false, "RG.RUN_CONTROL.DUPLICATE_REQUEST", "Run request id is already registered.", existing);
        }
        State created = new State(new DynamicRunControlView("", intent.requestId(), "", "QUEUED", "ACCEPTED",
                1, intent.deadlineAt(), null, null, null, false, false), digest(intent.fencingToken()),
                new Owner(ownerId, 1), leaseExpiresAt, intent.cancellationGraceMs(), "NONE");
        states.put(intent.requestId(), created);
        return new Claim(true, "RG.RUN_CONTROL.CLAIMED", "", created);
    }

    @Override
    public synchronized Optional<State> find(String requestId, Instant now) {
        expire(requestId, now);
        return Optional.ofNullable(states.get(requestId));
    }

    @Override
    public synchronized Optional<State> start(String requestId, Owner owner, Instant now, Instant leaseExpiresAt) {
        return owned(requestId, owner).map(state -> {
            if (!"QUEUED".equals(state.view().status())) {
                return state;
            }
            return replace(state, view(state, "RUNNING", "EXECUTION_STARTED",
                    state.view().engineExecutionId(), now, state.view().cancelRequestedAt(), null, false, false),
                    leaseExpiresAt, "NONE");
        });
    }

    @Override
    public synchronized Optional<State> observeExecutionId(String requestId, Owner owner, String executionId,
                                                           Instant leaseExpiresAt) {
        return owned(requestId, owner).map(state -> {
            if (executionId == null || executionId.isBlank() || !state.view().engineExecutionId().isBlank()) {
                return renewState(state, leaseExpiresAt);
            }
            return replace(state, view(state, state.view().status(), state.view().reasonCode(), executionId,
                    state.view().startedAt(), state.view().cancelRequestedAt(), state.view().terminalAt(),
                    state.view().terminationConfirmed(), state.view().sideEffectsMayBeInFlight()),
                    leaseExpiresAt, state.recoveryDisposition());
        });
    }

    @Override
    public synchronized Optional<State> requestOwnerStop(String requestId, Owner owner, String status,
                                                         String reasonCode, Instant now, Instant leaseExpiresAt) {
        return owned(requestId, owner).map(state -> {
            if (terminal(state.view()) || stopRequested(state.view())) {
                return renewState(state, leaseExpiresAt);
            }
            return replace(state, view(state, status, reasonCode, state.view().engineExecutionId(),
                    state.view().startedAt(), now, null, false, true), leaseExpiresAt, "NONE");
        });
    }

    @Override
    public synchronized CommandResult requestCallerCancel(DynamicRunControlCommand command, Instant now) {
        expire(command.requestId(), now);
        State state = states.get(command.requestId());
        if (state == null) {
            return new CommandResult(false, "RG.RUN_CONTROL.NOT_FOUND", "Controlled run was not found.", null);
        }
        if (!MessageDigest.isEqual(state.fenceDigest().getBytes(StandardCharsets.UTF_8),
                digest(command.fencingToken()).getBytes(StandardCharsets.UTF_8))) {
            return new CommandResult(false, "RG.RUN_CONTROL.FENCE_MISMATCH",
                    "Control command fencing token does not match the run intent.", state);
        }
        if (command.expectedRevision() > 0 && command.expectedRevision() != state.view().revision()) {
            return new CommandResult(false, "RG.RUN_CONTROL.REVISION_CONFLICT",
                    "Control command expectedRevision is stale.", state);
        }
        if (terminal(state.view()) || stopRequested(state.view())) {
            return new CommandResult(false, "RG.RUN_CONTROL.ALREADY_TERMINAL",
                    "Controlled run has already stopped accepting cancellation commands.", state);
        }
        State changed = replace(state, view(state, "CANCEL_REQUESTED", "USER_CANCEL_REQUESTED",
                state.view().engineExecutionId(), state.view().startedAt(), now, null, false, true),
                state.leaseExpiresAt(), state.recoveryDisposition());
        return new CommandResult(true, "RG.RUN_CONTROL.CANCEL_ACCEPTED", "", changed);
    }

    @Override
    public synchronized Optional<State> markUnconfirmed(String requestId, Owner owner, String reasonCode, Instant now) {
        return owned(requestId, owner).map(state -> replace(state,
                view(state, "TERMINATION_UNCONFIRMED", reasonCode, state.view().engineExecutionId(),
                        state.view().startedAt(), state.view().cancelRequestedAt(), now, false, true),
                state.leaseExpiresAt(), "QUARANTINE"));
    }

    @Override
    public synchronized Optional<State> finish(String requestId, Owner owner, String status, String reasonCode,
                                               Instant now) {
        return owned(requestId, owner).map(state -> replace(state,
                view(state, status, reasonCode, state.view().engineExecutionId(), state.view().startedAt(),
                        state.view().cancelRequestedAt(), now, true, false), now, "COMPLETED"));
    }

    @Override
    public synchronized Optional<State> renew(String requestId, Owner owner, Instant leaseExpiresAt) {
        return owned(requestId, owner).map(state -> renewState(state, leaseExpiresAt));
    }

    @Override
    public synchronized void purgeTerminalBefore(Instant cutoff) {
        states.entrySet().removeIf(entry -> entry.getValue().view().terminationConfirmed()
                && entry.getValue().view().terminalAt() != null
                && entry.getValue().view().terminalAt().isBefore(cutoff));
    }

    private Optional<State> owned(String requestId, Owner owner) {
        expire(requestId, Instant.now());
        State state = states.get(requestId);
        return state != null && state.owner().equals(owner) && !"ABANDONED".equals(state.recoveryDisposition())
                ? Optional.of(state)
                : Optional.empty();
    }

    private State renewState(State state, Instant leaseExpiresAt) {
        State renewed = new State(state.view(), state.fenceDigest(), state.owner(), leaseExpiresAt,
                state.cancellationGraceMs(), state.recoveryDisposition());
        states.put(state.view().requestId(), renewed);
        return renewed;
    }

    private State replace(State state, DynamicRunControlView view, Instant lease, String recovery) {
        State changed = new State(view, state.fenceDigest(), state.owner(), lease,
                state.cancellationGraceMs(), recovery);
        states.put(view.requestId(), changed);
        return changed;
    }

    private static DynamicRunControlView view(State state, String status, String reason, String executionId,
                                              Instant startedAt, Instant cancelAt, Instant terminalAt,
                                              boolean confirmed, boolean sideEffects) {
        DynamicRunControlView previous = state.view();
        return new DynamicRunControlView("", previous.requestId(), executionId, status, reason,
                previous.revision() + 1, previous.deadlineAt(), startedAt, cancelAt, terminalAt,
                confirmed, sideEffects);
    }

    private void expire(String requestId, Instant now) {
        State state = states.get(requestId);
        if (state == null || terminal(state.view()) || state.leaseExpiresAt() == null
                || now == null || now.isBefore(state.leaseExpiresAt())) {
            return;
        }
        replace(state, view(state, "TERMINATION_UNCONFIRMED", "OWNER_LEASE_EXPIRED",
                state.view().engineExecutionId(), state.view().startedAt(), state.view().cancelRequestedAt(),
                now, false, true), now, "ABANDONED");
    }

    static boolean terminal(DynamicRunControlView view) {
        return view.terminationConfirmed() || "REJECTED".equals(view.status())
                || "TERMINATION_UNCONFIRMED".equals(view.status());
    }

    static boolean stopRequested(DynamicRunControlView view) {
        return "CANCEL_REQUESTED".equals(view.status()) || "TIMING_OUT".equals(view.status())
                || "TERMINATION_UNCONFIRMED".equals(view.status());
    }

    static String digest(String token) {
        try {
            byte[] value = MessageDigest.getInstance("SHA-256")
                    .digest((token == null ? "" : token).getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(value);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
