package com.leanowtech.bloge.gateway.testing.api;

import java.time.Clock;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * Authorizes, resolves, and atomically stages signed control-plane certificate rotations.
 *
 * <p>The controller is the single policy boundary between untrusted rotation events and live
 * transports. It requires independent M-of-N authorization, exact target binding, a contiguous
 * generation, and a resolver-computed candidate fingerprint. Exact concurrent replay shares one
 * resolution attempt; a different command for the same target is rejected. Resolver and staging
 * failures never mutate accepted state and expose no exception text, path, or secret reference.
 * When a durable floor is supplied, verified candidate identity is committed before local staging;
 * an exact replay can therefore repair a replica that failed after the durable commit.</p>
 *
 * <p>The compatibility constructor retains process-local accepted state for embedders. Product
 * composition supplies a durable floor, but event distribution and fleet convergence remain
 * separate responsibilities.</p>
 */
public final class ControlPlaneCertificateRotationController {

    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");
    private static final int MAXIMUM_TARGETS = 64;

    /** Stable bounded outcomes safe for health, metrics, and API problem mapping. */
    public enum ApplyStatus {
        /** A fully authorized successor was staged. */
        APPLIED,
        /** The exact previously accepted event was submitted again. */
        REPLAYED,
        /** Independent authorization did not verify. */
        AUTHORIZATION_REJECTED,
        /** No configured local target matches the signed identity. */
        TARGET_UNKNOWN,
        /** Active, predecessor, successor, or pending generation does not match. */
        GENERATION_CONFLICT,
        /** The live target changed outside this controller's observed state. */
        STATE_OUT_OF_SYNC,
        /** Candidate material could not be obtained. */
        MATERIAL_UNAVAILABLE,
        /** Resolved material differs from the signed material fingerprint. */
        MATERIAL_MISMATCH,
        /** The atomic TLS target rejected the otherwise verified candidate. */
        STAGING_REJECTED,
        /** Durable generation state rejected the otherwise verified event. */
        DURABILITY_REJECTED,
        /** Durable generation state was unavailable or corrupt. */
        DURABILITY_UNAVAILABLE
    }

    /**
     * Immutable local target bootstrap state.
     *
     * @param target atomic live transport
     * @param activeMaterialFingerprint exact fingerprint of its initial active settings
     */
    public record TargetRegistration(
            ControlPlaneCertificateRotationTarget target,
            String activeMaterialFingerprint) {

        /** Requires a live target without pending work and a canonical active fingerprint. */
        public TargetRegistration {
            target = Objects.requireNonNull(target, "target");
            activeMaterialFingerprint = normalized(activeMaterialFingerprint);
            if (!FINGERPRINT.matcher(activeMaterialFingerprint).matches()
                    || target.activeGeneration() < 1 || target.pendingGeneration().isPresent()) {
                throw invalid();
            }
        }
    }

    /**
     * Bounded command result without material lookup identities or exception details.
     *
     * @param schemaVersion result protocol version
     * @param status closed application outcome
     * @param reasonCode stable machine-readable reason
     * @param eventId signed external change identity only for accepted/replayed commands
     * @param eventFingerprint signed event fingerprint only for accepted/replayed commands
     * @param activeGeneration observed active generation, or zero for an unknown target
     * @param pendingGeneration observed pending generation, or zero when absent
     */
    public record ApplyResult(
            String schemaVersion,
            ApplyStatus status,
            String reasonCode,
            String eventId,
            String eventFingerprint,
            long activeGeneration,
            long pendingGeneration) {

        /** Current rotation application-result protocol version. */
        public static final String SCHEMA_VERSION =
                "bloge.controlPlaneCertificateRotationApplyResult.v1";
        private static final Pattern REASON = Pattern.compile("[A-Z][A-Z0-9_.-]{0,127}");

        /** Prevents failed results from disclosing signed command identities. */
        public ApplyResult {
            schemaVersion = normalized(schemaVersion).isBlank()
                    ? SCHEMA_VERSION : normalized(schemaVersion);
            status = Objects.requireNonNull(status, "status");
            reasonCode = normalized(reasonCode);
            eventId = normalized(eventId);
            eventFingerprint = normalized(eventFingerprint);
            boolean accepted = status == ApplyStatus.APPLIED || status == ApplyStatus.REPLAYED;
            if (!SCHEMA_VERSION.equals(schemaVersion) || !REASON.matcher(reasonCode).matches()
                    || activeGeneration < 0 || pendingGeneration < 0
                    || pendingGeneration > 0 && pendingGeneration <= activeGeneration
                    || accepted && (!IDENTIFIER.matcher(eventId).matches()
                    || !FINGERPRINT.matcher(eventFingerprint).matches())
                    || !accepted && (!eventId.isBlank() || !eventFingerprint.isBlank())) {
                throw invalid();
            }
        }

        /** @return true only when this exact command is accepted */
        public boolean accepted() {
            return status == ApplyStatus.APPLIED || status == ApplyStatus.REPLAYED;
        }
    }

    /**
     * Key- and material-free target state.
     *
     * @param activeGeneration observed active generation
     * @param pendingGeneration observed pending generation, or zero
     * @param synchronizedState whether target and controller state agree
     */
    public record TargetStateDescriptor(
            long activeGeneration,
            long pendingGeneration,
            boolean synchronizedState) {
    }

    private final ControlPlaneCertificateRotationTrustStore trustStore;
    private final ControlPlaneCertificateRotationMaterialSource materialSource;
    private final Clock clock;
    private final String deploymentScopeId;
    private final Map<String, MutableTargetState> targets;
    private final ControlPlaneCertificateRotationFloor floor;
    private final ControlPlaneCertificateRotationLifecycle lifecycle;
    private final Map<String, Attempt> attempts = new HashMap<>();
    private final Object monitor = new Object();

    /**
     * Creates one bounded signed rotation controller.
     *
     * @param trustStore independent public-key authorization trust
     * @param materialSource deployment-owned material resolver
     * @param clock authoritative acceptance clock
     * @param deploymentScopeId exact local deployment scope
     * @param registrations one through 64 independently governed targets
     */
    public ControlPlaneCertificateRotationController(
            ControlPlaneCertificateRotationTrustStore trustStore,
            ControlPlaneCertificateRotationMaterialSource materialSource,
            Clock clock,
            String deploymentScopeId,
            Map<String, TargetRegistration> registrations) {
        this(trustStore, materialSource, clock, deploymentScopeId, registrations, null,
                ControlPlaneCertificateRotationLifecycle.noop());
    }

    /**
     * Creates one bounded signed rotation controller with a durable post-authorization floor.
     *
     * @param trustStore independent public-key authorization trust
     * @param materialSource deployment-owned material resolver
     * @param clock authoritative local staging clock
     * @param deploymentScopeId exact local deployment scope
     * @param registrations one through 64 independently governed targets
     * @param floor durable monotonic generation authority for the same target inventory
     */
    public ControlPlaneCertificateRotationController(
            ControlPlaneCertificateRotationTrustStore trustStore,
            ControlPlaneCertificateRotationMaterialSource materialSource,
            Clock clock,
            String deploymentScopeId,
            Map<String, TargetRegistration> registrations,
            ControlPlaneCertificateRotationFloor floor) {
        this(trustStore, materialSource, clock, deploymentScopeId, registrations, floor,
                ControlPlaneCertificateRotationLifecycle.noop());
    }

    /**
     * Creates one durable controller with an exact post-verification staging lifecycle.
     *
     * @param trustStore independent public-key authorization trust
     * @param materialSource deployment-owned material resolver
     * @param clock authoritative local staging clock
     * @param deploymentScopeId exact local deployment scope
     * @param registrations one through 64 independently governed targets
     * @param floor durable monotonic generation authority for the same target inventory
     * @param lifecycle verified local stage and failure observer
     */
    public ControlPlaneCertificateRotationController(
            ControlPlaneCertificateRotationTrustStore trustStore,
            ControlPlaneCertificateRotationMaterialSource materialSource,
            Clock clock,
            String deploymentScopeId,
            Map<String, TargetRegistration> registrations,
            ControlPlaneCertificateRotationFloor floor,
            ControlPlaneCertificateRotationLifecycle lifecycle) {
        this.trustStore = Objects.requireNonNull(trustStore, "trustStore");
        this.materialSource = Objects.requireNonNull(materialSource, "materialSource");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.deploymentScopeId = normalized(deploymentScopeId);
        this.floor = floor;
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        if (!IDENTIFIER.matcher(this.deploymentScopeId).matches()
                || registrations == null || registrations.isEmpty()
                || registrations.size() > MAXIMUM_TARGETS) {
            throw invalid();
        }
        LinkedHashMap<String, MutableTargetState> indexed = new LinkedHashMap<>();
        registrations.forEach((targetId, registration) -> {
            String normalizedId = normalized(targetId);
            TargetRegistration required = Objects.requireNonNull(
                    registration, "registration");
            if (!IDENTIFIER.matcher(normalizedId).matches()
                    || indexed.putIfAbsent(normalizedId,
                    new MutableTargetState(normalizedId, required.target(),
                            required.activeMaterialFingerprint())) != null) {
                throw invalid();
            }
        });
        targets = indexed;
    }

    /**
     * Applies one untrusted signed event through every authorization and material gate.
     *
     * @param event untrusted externally supplied event
     * @return bounded deterministic outcome
     */
    public ApplyResult apply(ControlPlaneCertificateRotationEvent event) {
        if (event == null) {
            return rejected(ApplyStatus.AUTHORIZATION_REJECTED,
                    "CERTIFICATE_ROTATION_MATERIAL_INVALID", null);
        }
        ControlPlaneCertificateRotationEvent.Material material = event.material();
        MutableTargetState state = targets.get(material.targetId());
        if (state == null) {
            return rejected(ApplyStatus.TARGET_UNKNOWN,
                    "CERTIFICATE_ROTATION_TARGET_UNKNOWN", null);
        }

        ControlPlaneCertificateRotationTrustStore.Verification verification;
        try {
            verification = trustStore.verify(event,
                    new ControlPlaneCertificateRotationTrustStore.ExpectedBinding(
                            deploymentScopeId, material.targetId()), clock.instant());
        } catch (RuntimeException unavailable) {
            return rejected(ApplyStatus.AUTHORIZATION_REJECTED,
                    "CERTIFICATE_ROTATION_AUTHORIZATION_UNAVAILABLE", state);
        }
        if (verification == null || !verification.verified()) {
            String reason = verification == null
                    ? "CERTIFICATE_ROTATION_AUTHORIZATION_UNAVAILABLE"
                    : verification.reasonCode();
            return rejected(ApplyStatus.AUTHORIZATION_REJECTED, reason, state);
        }
        if (!verification.eventId().equals(material.eventId())
                || !verification.eventFingerprint().equals(event.materialFingerprint())
                || !verification.materialFingerprint().equals(
                material.settingsFingerprint())) {
            return rejected(ApplyStatus.AUTHORIZATION_REJECTED,
                    "CERTIFICATE_ROTATION_AUTHORIZATION_BINDING_MISMATCH", state);
        }

        Attempt attempt;
        boolean owner;
        synchronized (monitor) {
            Attempt existing = attempts.get(material.targetId());
            if (existing != null) {
                if (!existing.eventFingerprint().equals(event.materialFingerprint())) {
                    return rejectedLocked(ApplyStatus.GENERATION_CONFLICT,
                            "CERTIFICATE_ROTATION_GENERATION_CONFLICT", state);
                }
                attempt = existing;
                owner = false;
            } else {
                refresh(state);
                if (state.outOfSync) {
                    return rejectedLocked(ApplyStatus.STATE_OUT_OF_SYNC,
                            "CERTIFICATE_ROTATION_STATE_OUT_OF_SYNC", state);
                }
                if (state.acceptedEventFingerprint.equals(event.materialFingerprint())) {
                    return acceptedLocked(ApplyStatus.REPLAYED, "REPLAYED", event, state);
                }
                if (state.acceptedEventId.equals(material.eventId())) {
                    return rejectedLocked(ApplyStatus.GENERATION_CONFLICT,
                            "CERTIFICATE_ROTATION_EVENT_ID_CONFLICT", state);
                }
                if (material.generation() != state.activeGeneration + 1
                        || !material.previousMaterialFingerprint().equals(
                        state.activeMaterialFingerprint)
                        || state.pendingGeneration > 0
                        || state.target.pendingGeneration().isPresent()) {
                    return rejectedLocked(ApplyStatus.GENERATION_CONFLICT,
                            "CERTIFICATE_ROTATION_GENERATION_CONFLICT", state);
                }
                attempt = new Attempt(event.materialFingerprint(), new CompletableFuture<>());
                attempts.put(material.targetId(), attempt);
                owner = true;
            }
        }
        if (!owner) {
            ApplyResult completed = attempt.completion().join();
            return completed.status() == ApplyStatus.APPLIED
                    ? replayOf(completed) : completed;
        }

        ApplyResult result;
        try {
            result = resolveAndStage(event, state);
        } catch (RuntimeException unexpected) {
            synchronized (monitor) {
                state.outOfSync = true;
                result = rejectedCached(ApplyStatus.STATE_OUT_OF_SYNC,
                        "CERTIFICATE_ROTATION_STATE_OUT_OF_SYNC", state);
            }
        }
        synchronized (monitor) {
            attempts.remove(material.targetId(), attempt);
            if (result.status() == ApplyStatus.APPLIED) {
                state.acceptedEventId = material.eventId();
                state.acceptedEventFingerprint = event.materialFingerprint();
                if (result.activeGeneration() == material.generation()) {
                    state.activeGeneration = material.generation();
                    state.activeMaterialFingerprint = material.settingsFingerprint();
                    state.pendingGeneration = 0;
                    state.pendingMaterialFingerprint = "";
                } else {
                    state.pendingGeneration = material.generation();
                    state.pendingMaterialFingerprint = material.settingsFingerprint();
                }
            }
        }
        attempt.completion().complete(result);
        return result;
    }

    /** @return immutable key- and material-free state for all configured targets */
    public Map<String, TargetStateDescriptor> targetStates() {
        synchronized (monitor) {
            LinkedHashMap<String, TargetStateDescriptor> result = new LinkedHashMap<>();
            targets.forEach((targetId, state) -> {
                refresh(state);
                long pending = state.target.pendingGeneration().orElse(0);
                result.put(targetId, new TargetStateDescriptor(state.activeGeneration,
                        pending, !state.outOfSync && durableStateMatches(state, pending)));
            });
            return Map.copyOf(result);
        }
    }

    private ApplyResult resolveAndStage(
            ControlPlaneCertificateRotationEvent event,
            MutableTargetState state) {
        ControlPlaneCertificateRotationEvent.Material material = event.material();
        ControlPlaneCertificateRotationMaterialSource.ResolvedMaterial resolved;
        try {
            resolved = materialSource.resolve(material.targetId(), material.generation(),
                    material.materialId());
        } catch (RuntimeException unavailable) {
            return rejected(ApplyStatus.MATERIAL_UNAVAILABLE,
                    "ROTATION_MATERIAL_UNAVAILABLE", state);
        }
        if (resolved == null || !material.settingsFingerprint().equals(
                resolved.settingsFingerprint())) {
            return rejected(ApplyStatus.MATERIAL_MISMATCH,
                    "CERTIFICATE_ROTATION_MATERIAL_MISMATCH", state);
        }
        ControlPlaneCertificateRotationFloor.Acceptance durableAcceptance = null;
        if (floor != null) {
            try {
                durableAcceptance = floor.accept(event);
            } catch (IllegalArgumentException rejected) {
                return rejected(ApplyStatus.DURABILITY_REJECTED,
                        "CERTIFICATE_ROTATION_DURABILITY_REJECTED", state);
            } catch (RuntimeException unavailable) {
                return rejected(ApplyStatus.DURABILITY_UNAVAILABLE,
                        "CERTIFICATE_ROTATION_DURABILITY_UNAVAILABLE", state);
            }
        }
        try {
            lifecycle.prepare(event);
        } catch (RuntimeException unavailable) {
            return rejected(ApplyStatus.STAGING_REJECTED,
                    "CERTIFICATE_ROTATION_CONVERGENCE_UNAVAILABLE", state);
        }
        try {
            if (durableAcceptance != null
                    && durableAcceptance.snapshot().activeGeneration()
                    == material.generation()) {
                state.target.reconcileActive(material.generation(),
                        durableAcceptance.snapshot().activatedAt(), resolved.settings());
            } else if (durableAcceptance != null
                    && durableAcceptance.snapshot().pendingGeneration()
                    == material.generation()
                    && !clock.instant().isBefore(material.activateAt())) {
                state.target.restorePending(material.generation(), material.activateAt(),
                        resolved.settings());
            } else {
                state.target.stage(material.generation(), material.activateAt(),
                        resolved.settings());
            }
        } catch (RuntimeException rejected) {
            lifecycleFailed(event, "LOCAL_STAGING_REJECTED");
            return rejected(ApplyStatus.STAGING_REJECTED,
                    "CERTIFICATE_ROTATION_STAGING_REJECTED", state);
        }
        long active = state.target.activeGeneration();
        OptionalLong pending = state.target.pendingGeneration();
        if (active == material.generation() && pending.isEmpty()) {
            lifecycleApplied(event);
            return accepted(ApplyStatus.APPLIED, "APPLIED", event, state,
                    material.generation(), 0);
        }
        if (active != state.activeGeneration || pending.isEmpty()
                || pending.getAsLong() != material.generation()) {
            lifecycleFailed(event, "LOCAL_STATE_DIVERGED");
            return rejected(ApplyStatus.STATE_OUT_OF_SYNC,
                    "CERTIFICATE_ROTATION_STATE_OUT_OF_SYNC", state);
        }
        lifecycleApplied(event);
        return accepted(ApplyStatus.APPLIED, "APPLIED", event, state,
                state.activeGeneration, material.generation());
    }

    private void lifecycleApplied(ControlPlaneCertificateRotationEvent event) {
        try {
            lifecycle.applied(event);
        } catch (RuntimeException ignored) {
            // The transport gate remains fail-closed until a later heartbeat succeeds.
        }
    }

    private void lifecycleFailed(
            ControlPlaneCertificateRotationEvent event,
            String failureCode) {
        try {
            lifecycle.failed(event, failureCode);
        } catch (RuntimeException ignored) {
            // Failure evidence never grants permission to weaken local staging policy.
        }
    }

    private void refresh(MutableTargetState state) {
        try {
            long actualActive = state.target.activeGeneration();
            if (actualActive == state.activeGeneration) {
                OptionalLong actualPending = state.target.pendingGeneration();
                if (state.pendingGeneration == 0 && actualPending.isPresent()
                        || state.pendingGeneration > 0 && (actualPending.isEmpty()
                        || actualPending.getAsLong() != state.pendingGeneration)) {
                    state.outOfSync = true;
                }
                return;
            }
            if (state.pendingGeneration > 0 && actualActive == state.pendingGeneration) {
                state.activeGeneration = actualActive;
                state.activeMaterialFingerprint = state.pendingMaterialFingerprint;
                state.pendingGeneration = 0;
                state.pendingMaterialFingerprint = "";
                if (state.target.pendingGeneration().isPresent()) {
                    state.outOfSync = true;
                }
                return;
            }
            state.outOfSync = true;
        } catch (RuntimeException unavailable) {
            state.outOfSync = true;
        }
    }

    private static ApplyResult replayOf(ApplyResult applied) {
        return new ApplyResult(ApplyResult.SCHEMA_VERSION, ApplyStatus.REPLAYED, "REPLAYED",
                applied.eventId(), applied.eventFingerprint(), applied.activeGeneration(),
                applied.pendingGeneration());
    }

    private static ApplyResult acceptedLocked(
            ApplyStatus status,
            String reason,
            ControlPlaneCertificateRotationEvent event,
            MutableTargetState state) {
        return accepted(status, reason, event, state, state.activeGeneration,
                state.target.pendingGeneration().orElse(0));
    }

    private static ApplyResult accepted(
            ApplyStatus status,
            String reason,
            ControlPlaneCertificateRotationEvent event,
            MutableTargetState state,
            long activeGeneration,
            long pendingGeneration) {
        return new ApplyResult(ApplyResult.SCHEMA_VERSION, status, reason,
                event.material().eventId(), event.materialFingerprint(),
                activeGeneration, pendingGeneration);
    }

    private boolean durableStateMatches(MutableTargetState state, long pendingGeneration) {
        if (floor == null) {
            return true;
        }
        try {
            ControlPlaneCertificateRotationFloor.Snapshot snapshot =
                    floor.snapshot(state.targetId);
            return snapshot.activeGeneration() == state.activeGeneration
                    && snapshot.pendingGeneration() == pendingGeneration;
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private ApplyResult rejected(
            ApplyStatus status, String reason, MutableTargetState state) {
        synchronized (monitor) {
            if (state != null) {
                refresh(state);
            }
            return rejectedLocked(status, reason, state);
        }
    }

    private static ApplyResult rejectedLocked(
            ApplyStatus status, String reason, MutableTargetState state) {
        long active = state == null ? 0 : state.activeGeneration;
        long pending = state == null ? 0 : state.pendingGeneration;
        if (state != null && !state.outOfSync) {
            try {
                pending = state.target.pendingGeneration().orElse(0);
            } catch (RuntimeException unavailable) {
                state.outOfSync = true;
            }
        }
        return new ApplyResult(ApplyResult.SCHEMA_VERSION, status, reason,
                "", "", active, pending);
    }

    private static ApplyResult rejectedCached(
            ApplyStatus status, String reason, MutableTargetState state) {
        return new ApplyResult(ApplyResult.SCHEMA_VERSION, status, reason,
                "", "", state.activeGeneration, state.pendingGeneration);
    }

    private static String normalized(String value) {
        return Objects.requireNonNullElse(value, "").trim();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException(
                "Control-plane certificate rotation controller is invalid");
    }

    private static final class MutableTargetState {
        private final String targetId;
        private final ControlPlaneCertificateRotationTarget target;
        private long activeGeneration;
        private String activeMaterialFingerprint;
        private long pendingGeneration;
        private String pendingMaterialFingerprint = "";
        private String acceptedEventId = "";
        private String acceptedEventFingerprint = "";
        private boolean outOfSync;

        private MutableTargetState(
                String targetId,
                ControlPlaneCertificateRotationTarget target,
                String activeMaterialFingerprint) {
            this.targetId = targetId;
            this.target = target;
            this.activeGeneration = target.activeGeneration();
            this.activeMaterialFingerprint = activeMaterialFingerprint;
        }
    }

    private record Attempt(
            String eventFingerprint,
            CompletableFuture<ApplyResult> completion) {
    }
}
