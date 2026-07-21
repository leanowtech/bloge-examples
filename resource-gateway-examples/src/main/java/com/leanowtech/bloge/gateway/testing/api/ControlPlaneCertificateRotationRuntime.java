package com.leanowtech.bloge.gateway.testing.api;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Product runtime that creates and registers all restart-free control-plane TLS transports.
 *
 * <p>Every domain asks this runtime to create a transport under one of twelve stable ids. Disabled
 * rotation preserves the historical static adapter. Enabled rotation computes the current settings
 * fingerprint, restores its explicit generation, builds one immutable rotating transport, and
 * registers a signed controller before returning it. Unknown, duplicate, un-inventoried, unbound,
 * or disabled targets fail before network adapters are assembled.</p>
 */
public final class ControlPlaneCertificateRotationRuntime {

    /** Fixed-cardinality material-free readiness projection. */
    public record Descriptor(
            String schemaVersion,
            boolean enabled,
            boolean ready,
            boolean trustAvailable,
            int inventoriedTargetCount,
            int registeredTargetCount,
            boolean synchronizedState) {

        /** Current runtime descriptor protocol version. */
        public static final String SCHEMA_VERSION =
                "bloge.controlPlaneCertificateRotationRuntimeDescriptor.v1";
    }

    private final ControlPlaneCertificateRotationRuntimeProperties properties;
    private final Map<String, Long> initialGenerations;
    private final ControlPlaneCertificateRotationTrustStore trustStore;
    private final ControlPlaneCertificateRotationMaterialSource materialSource;
    private final ControlPlaneHttpTransport.SecretResolver secretResolver;
    private final ControlPlaneCertificateSettingsFingerprint fingerprinter;
    private final Clock clock;
    private final Map<String, ControlPlaneCertificateRotationController> controllers =
            new LinkedHashMap<>();

    /** Creates an immutable deployment policy with an initially empty target registry. */
    public ControlPlaneCertificateRotationRuntime(
            ControlPlaneCertificateRotationRuntimeProperties properties,
            Map<String, Long> initialGenerations,
            ControlPlaneCertificateRotationTrustStore trustStore,
            ControlPlaneCertificateRotationMaterialSource materialSource,
            ControlPlaneHttpTransport.SecretResolver secretResolver,
            ControlPlaneCertificateSettingsFingerprint fingerprinter,
            Clock clock) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.initialGenerations = Map.copyOf(Objects.requireNonNull(
                initialGenerations, "initialGenerations"));
        this.trustStore = Objects.requireNonNull(trustStore, "trustStore");
        this.materialSource = Objects.requireNonNull(materialSource, "materialSource");
        this.secretResolver = Objects.requireNonNull(secretResolver, "secretResolver");
        this.fingerprinter = Objects.requireNonNull(fingerprinter, "fingerprinter");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (properties.enabled() != !this.initialGenerations.isEmpty()) {
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
        Long generation = initialGenerations.get(normalizedTarget);
        if (!ControlPlaneCertificateRotationTargets.contains(normalizedTarget)
                || generation == null || !required.certificateIdentityBound()
                || controllers.containsKey(normalizedTarget)) {
            throw invalid();
        }
        var settings = required.pinnedSettings();
        String activeFingerprint = fingerprinter.fingerprint(settings);
        var rotating = new RotatingControlPlaneHttpTransport(generation, settings,
                secretResolver, clock, properties.minimumOverlap(),
                properties.maximumLeadTime());
        var controller = new ControlPlaneCertificateRotationController(
                trustStore, materialSource, clock, properties.deploymentScopeId(),
                Map.of(normalizedTarget,
                        new ControlPlaneCertificateRotationController.TargetRegistration(
                                rotating, activeFingerprint)));
        controllers.put(normalizedTarget, controller);
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
        boolean ready = !properties.enabled() || trustAvailable
                && controllers.size() == initialGenerations.size() && synchronizedState;
        return new Descriptor(Descriptor.SCHEMA_VERSION, properties.enabled(), ready,
                trustAvailable, initialGenerations.size(), controllers.size(),
                synchronizedState);
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
