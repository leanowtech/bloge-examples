package com.leanowtech.bloge.gateway.testing.api;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Product runtime that creates, durably reconstructs, and registers control-plane TLS transports.
 *
 * <p>Every domain asks this runtime to create a transport under one of twelve stable ids. Disabled
 * rotation preserves the historical static adapter. Enabled rotation fingerprints its out-of-band
 * baseline, verifies the durable generation floor, restores active and pending material from the
 * deployment catalog when needed, and registers a floor-bound signed controller. Unknown,
 * duplicate, un-inventoried, unbound, non-durable, or divergent targets fail before network
 * adapters are assembled.</p>
 */
public final class ControlPlaneCertificateRotationRuntime {

    /** Fixed-cardinality material-free readiness projection. */
    public record Descriptor(
            String schemaVersion,
            boolean enabled,
            boolean ready,
            boolean trustAvailable,
            boolean durableState,
            int inventoriedTargetCount,
            int registeredTargetCount,
            boolean synchronizedState) {

        /** Current runtime descriptor protocol version. */
        public static final String SCHEMA_VERSION =
                "bloge.controlPlaneCertificateRotationRuntimeDescriptor.v1";
    }

    private final ControlPlaneCertificateRotationRuntimeProperties properties;
    private final Map<String, ControlPlaneCertificateRotationRuntimeProperties.InitialTargetSpec>
            initialTargets;
    private final ControlPlaneCertificateRotationTrustStore trustStore;
    private final ControlPlaneCertificateRotationMaterialSource materialSource;
    private final ControlPlaneHttpTransport.SecretResolver secretResolver;
    private final ControlPlaneCertificateSettingsFingerprint fingerprinter;
    private final ControlPlaneCertificateRotationFloorFactory floorFactory;
    private final Clock clock;
    private final Map<String, ControlPlaneCertificateRotationController> controllers =
            new LinkedHashMap<>();
    private final Map<String, ControlPlaneCertificateRotationFloor> floors =
            new LinkedHashMap<>();

    /** Creates an immutable deployment policy with an initially empty target registry. */
    public ControlPlaneCertificateRotationRuntime(
            ControlPlaneCertificateRotationRuntimeProperties properties,
            Map<String, ControlPlaneCertificateRotationRuntimeProperties.InitialTargetSpec>
                    initialTargets,
            ControlPlaneCertificateRotationTrustStore trustStore,
            ControlPlaneCertificateRotationMaterialSource materialSource,
            ControlPlaneHttpTransport.SecretResolver secretResolver,
            ControlPlaneCertificateSettingsFingerprint fingerprinter,
            ControlPlaneCertificateRotationFloorFactory floorFactory,
            Clock clock) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.initialTargets = Map.copyOf(Objects.requireNonNull(initialTargets,
                "initialTargets"));
        this.trustStore = Objects.requireNonNull(trustStore, "trustStore");
        this.materialSource = Objects.requireNonNull(materialSource, "materialSource");
        this.secretResolver = Objects.requireNonNull(secretResolver, "secretResolver");
        this.fingerprinter = Objects.requireNonNull(fingerprinter, "fingerprinter");
        this.floorFactory = Objects.requireNonNull(floorFactory, "floorFactory");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (properties.enabled() != !this.initialTargets.isEmpty()) {
            throw invalid();
        }
    }

    /**
     * Creates one static or rotating transport and registers its exact product target.
     *
     * @param targetId one stable target from {@link ControlPlaneCertificateRotationTargets}
     * @param transport strict source transport policy
     * @return transport compatible with existing recovery-fleet consumers
     */
    public synchronized RecoveryFleetPublicationTransport transport(
            String targetId,
            RecoveryFleetPublicationTransportProperties transport) {
        RecoveryFleetPublicationTransportProperties required = Objects.requireNonNull(
                transport, "transport");
        if (!properties.enabled() || !required.enabled()) {
            return required.create(secretResolver);
        }
        String normalizedTarget = Objects.requireNonNullElse(targetId, "").trim();
        ControlPlaneCertificateRotationRuntimeProperties.InitialTargetSpec initial =
                initialTargets.get(normalizedTarget);
        if (!ControlPlaneCertificateRotationTargets.contains(normalizedTarget)
                || initial == null || !required.certificateIdentityBound()
                || controllers.containsKey(normalizedTarget)) {
            throw invalid();
        }
        var baselineSettings = required.pinnedSettings();
        String baselineFingerprint = fingerprinter.fingerprint(baselineSettings);
        ControlPlaneCertificateRotationFloor floor = floorFactory.create(
                properties.deploymentScopeId(), Map.of(normalizedTarget,
                        new ControlPlaneCertificateRotationFloor.InitialTarget(
                                initial.generation(), initial.materialId(),
                                baselineFingerprint)));
        if (floor == null || !floor.durable()) {
            throw invalid();
        }
        ControlPlaneCertificateRotationFloor.Snapshot snapshot =
                floor.snapshot(normalizedTarget);
        var activeSettings = snapshot.activeGeneration() == initial.generation()
                ? baselineSettings : resolvedSettings(normalizedTarget,
                snapshot.activeGeneration(), snapshot.activeMaterialId(),
                snapshot.activeSettingsFingerprint());
        var rotating = new RotatingControlPlaneHttpTransport(snapshot.activeGeneration(),
                activeSettings,
                secretResolver, clock, properties.minimumOverlap(),
                properties.maximumLeadTime());
        if (snapshot.hasPending()) {
            rotating.restorePending(snapshot.pendingGeneration(), snapshot.pendingActivateAt(),
                    resolvedSettings(normalizedTarget, snapshot.pendingGeneration(),
                            snapshot.pendingMaterialId(),
                            snapshot.pendingSettingsFingerprint()));
        }
        var controller = new ControlPlaneCertificateRotationController(
                trustStore, materialSource, clock, properties.deploymentScopeId(),
                Map.of(normalizedTarget,
                        new ControlPlaneCertificateRotationController.TargetRegistration(
                                rotating, snapshot.activeSettingsFingerprint())), floor);
        controllers.put(normalizedTarget, controller);
        floors.put(normalizedTarget, floor);
        return rotating;
    }

    /** Applies one signed event to its exact registered product target. */
    public ControlPlaneCertificateRotationController.ApplyResult apply(
            ControlPlaneCertificateRotationEvent event) {
        if (!properties.enabled()) {
            return rejected(ControlPlaneCertificateRotationController.ApplyStatus
                    .AUTHORIZATION_REJECTED, "CERTIFICATE_ROTATION_DISABLED");
        }
        if (event == null) {
            return rejected(ControlPlaneCertificateRotationController.ApplyStatus
                    .AUTHORIZATION_REJECTED, "CERTIFICATE_ROTATION_MATERIAL_INVALID");
        }
        ControlPlaneCertificateRotationController controller;
        synchronized (this) {
            controller = controllers.get(event.material().targetId());
        }
        return controller == null
                ? rejected(ControlPlaneCertificateRotationController.ApplyStatus.TARGET_UNKNOWN,
                "CERTIFICATE_ROTATION_TARGET_UNKNOWN")
                : controller.apply(event);
    }

    /** @return fixed-cardinality aggregate state without target ids or material identities */
    public synchronized Descriptor descriptor() {
        boolean synchronizedState = controllers.values().stream()
                .flatMap(controller -> controller.targetStates().values().stream())
                .allMatch(ControlPlaneCertificateRotationController.TargetStateDescriptor
                        ::synchronizedState);
        boolean trustAvailable = trustStore.descriptor().available();
        boolean durableState = !properties.enabled()
                || floors.size() == initialTargets.size() && floors.values().stream()
                .allMatch(ControlPlaneCertificateRotationFloor::durable);
        boolean ready = !properties.enabled() || trustAvailable
                && durableState && controllers.size() == initialTargets.size()
                && synchronizedState;
        return new Descriptor(Descriptor.SCHEMA_VERSION, properties.enabled(), ready,
                trustAvailable, durableState, initialTargets.size(), controllers.size(),
                synchronizedState);
    }

    private PinnedMutualTlsRecoveryFleetPublicationTransport.Settings resolvedSettings(
            String targetId,
            long generation,
            String materialId,
            String expectedFingerprint) {
        try {
            ControlPlaneCertificateRotationMaterialSource.ResolvedMaterial resolved =
                    materialSource.resolve(targetId, generation, materialId);
            if (resolved == null
                    || !expectedFingerprint.equals(resolved.settingsFingerprint())
                    || !expectedFingerprint.equals(fingerprinter.fingerprint(
                    resolved.settings()))) {
                throw invalid();
            }
            return resolved.settings();
        } catch (RuntimeException unavailable) {
            throw invalid();
        }
    }

    private static ControlPlaneCertificateRotationController.ApplyResult rejected(
            ControlPlaneCertificateRotationController.ApplyStatus status,
            String reason) {
        return new ControlPlaneCertificateRotationController.ApplyResult(
                ControlPlaneCertificateRotationController.ApplyResult.SCHEMA_VERSION,
                status, reason, "", "", 0, 0);
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException(
                "Control-plane certificate rotation runtime is invalid");
    }
}
