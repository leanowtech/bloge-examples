package com.leanowtech.bloge.gateway.testing.api;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Fixed-cardinality Actuator truth for the local signed certificate-rotation runtime.
 *
 * <p>The projection contains no target ids, authority identities, fingerprints, material ids,
 * paths, credential references, or exception text. Local readiness is intentionally distinct
 * from replica convergence and production readiness. Durable-floor integration is projected only
 * when every registered target is backed by the database authority.</p>
 */
public final class ControlPlaneCertificateRotationHealth implements HealthIndicator {

    /** Current health-detail protocol generation. */
    public static final String SCHEMA_VERSION =
            "bloge.controlPlaneCertificateRotationHealth.v1";

    private final Supplier<ControlPlaneCertificateRotationRuntime.Descriptor> descriptor;

    /** @param runtime local product rotation runtime */
    public ControlPlaneCertificateRotationHealth(
            ControlPlaneCertificateRotationRuntime runtime) {
        this(Objects.requireNonNull(runtime, "runtime")::descriptor);
    }

    /** Package-visible deterministic seam for health classification tests. */
    ControlPlaneCertificateRotationHealth(
            Supplier<ControlPlaneCertificateRotationRuntime.Descriptor> descriptor) {
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
    }

    /**
     * Reports UP for an explicitly disabled or locally ready runtime and DOWN for partial state.
     *
     * @return bounded material-free health snapshot
     */
    @Override
    public Health health() {
        try {
            ControlPlaneCertificateRotationRuntime.Descriptor current =
                    Objects.requireNonNull(descriptor.get(), "descriptor");
            RuntimeStatus status = classify(current);
            Map<String, Object> details = details(current, status);
            return (status == RuntimeStatus.DISABLED || status == RuntimeStatus.LOCAL_READY
                    ? Health.up() : Health.down()).withDetails(details).build();
        } catch (RuntimeException unavailable) {
            return Health.down()
                    .withDetail("schemaVersion", SCHEMA_VERSION)
                    .withDetail("runtimeStatus", RuntimeStatus.UNAVAILABLE.name())
                    .withDetail("enabled", false)
                    .withDetail("localReady", false)
                    .withDetail("trustAvailable", false)
                    .withDetail("inventoriedTargetCount", 0)
                    .withDetail("registeredTargetCount", 0)
                    .withDetail("synchronizedState", false)
                    .withDetail("durableGenerationFloorIntegrated", false)
                    .withDetail("replicaConvergenceIntegrated", false)
                    .withDetail("replicaConvergenceAvailable", false)
                    .withDetail("replicaConvergenceProven", false)
                    .withDetail("servingReady", false)
                    .withDetail("convergenceStatus", "UNAVAILABLE")
                    .withDetail("productionReady", false)
                    .build();
        }
    }

    private static RuntimeStatus classify(
            ControlPlaneCertificateRotationRuntime.Descriptor current) {
        if (!current.enabled()) {
            return RuntimeStatus.DISABLED;
        }
        if (!current.trustAvailable()) {
            return RuntimeStatus.TRUST_UNAVAILABLE;
        }
        if (current.registeredTargetCount() != current.inventoriedTargetCount()) {
            return RuntimeStatus.INCOMPLETE_REGISTRATION;
        }
        if (!current.durableState()) {
            return RuntimeStatus.DURABILITY_UNAVAILABLE;
        }
        if (!current.synchronizedState()) {
            return RuntimeStatus.STATE_OUT_OF_SYNC;
        }
        if (current.convergenceIntegrated() && !current.convergenceAvailable()) {
            return RuntimeStatus.CONVERGENCE_UNAVAILABLE;
        }
        if (current.convergenceIntegrated() && !current.servingReady()) {
            return RuntimeStatus.SERVING_FENCED;
        }
        return current.ready() ? RuntimeStatus.LOCAL_READY
                : RuntimeStatus.INCOMPLETE_REGISTRATION;
    }

    private static Map<String, Object> details(
            ControlPlaneCertificateRotationRuntime.Descriptor current,
            RuntimeStatus status) {
        LinkedHashMap<String, Object> details = new LinkedHashMap<>();
        details.put("schemaVersion", SCHEMA_VERSION);
        details.put("runtimeStatus", status.name());
        details.put("enabled", current.enabled());
        details.put("localReady", status == RuntimeStatus.LOCAL_READY);
        details.put("trustAvailable", current.trustAvailable());
        details.put("inventoriedTargetCount", current.inventoriedTargetCount());
        details.put("registeredTargetCount", current.registeredTargetCount());
        details.put("synchronizedState", current.synchronizedState());
        details.put("durableGenerationFloorIntegrated",
                current.enabled() && current.durableState());
        details.put("replicaConvergenceIntegrated", current.convergenceIntegrated());
        details.put("replicaConvergenceAvailable", current.convergenceAvailable());
        details.put("replicaConvergenceProven", current.replicaConvergenceProven());
        details.put("servingReady", current.servingReady());
        details.put("convergenceStatus", current.convergenceStatus());
        details.put("productionReady", current.productionReady());
        return Map.copyOf(details);
    }

    /** Closed local readiness states safe for metrics labels and unauthenticated health. */
    public enum RuntimeStatus {
        /** Rotation is explicitly disabled and static transports remain in use. */
        DISABLED,
        /** Every inventoried local target is registered and synchronized. */
        LOCAL_READY,
        /** External signature authorization has no currently usable quorum. */
        TRUST_UNAVAILABLE,
        /** One or more configured targets lack a readable durable generation floor. */
        DURABILITY_UNAVAILABLE,
        /** Product transport registration does not match the configured inventory. */
        INCOMPLETE_REGISTRATION,
        /** A live transport diverged from controller-observed generation state. */
        STATE_OUT_OF_SYNC,
        /** The configured replica authority has no current bounded decision lease. */
        CONVERGENCE_UNAVAILABLE,
        /** Certificate generation serving is fenced pending exact replica proof. */
        SERVING_FENCED,
        /** The bounded descriptor could not be read. */
        UNAVAILABLE
    }
}
