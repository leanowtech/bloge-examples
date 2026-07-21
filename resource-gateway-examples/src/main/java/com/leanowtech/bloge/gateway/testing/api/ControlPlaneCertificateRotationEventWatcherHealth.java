package com.leanowtech.bloge.gateway.testing.api;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Fixed-cardinality Actuator truth for certificate-rotation event delivery.
 *
 * <p>The projection is computed only from the watcher's cached descriptor and never performs
 * source or database I/O. It contains no deployment, replica, event, page, URI, fingerprint,
 * certificate, credential, or exception value. An enabled watcher remains unhealthy until one
 * authenticated poll establishes either an idle, applied, or intentionally serving-fenced
 * state.</p>
 */
public final class ControlPlaneCertificateRotationEventWatcherHealth
        implements HealthIndicator {

    /** Current health-detail protocol generation. */
    public static final String SCHEMA_VERSION =
            "bloge.controlPlaneCertificateRotationEventWatcherHealth.v1";

    private final Supplier<ControlPlaneCertificateRotationEventWatcher.Descriptor> descriptor;
    private final boolean required;

    /**
     * Creates health from one profile-owned watcher.
     *
     * @param watcher cached event watcher
     * @param properties strict deployment policy
     */
    public ControlPlaneCertificateRotationEventWatcherHealth(
            ControlPlaneCertificateRotationEventWatcher watcher,
            ControlPlaneCertificateRotationEventSourceProperties properties) {
        this(Objects.requireNonNull(watcher, "watcher")::descriptor,
                Objects.requireNonNull(properties, "properties").required());
    }

    /** Package-visible deterministic seam for health classification tests. */
    ControlPlaneCertificateRotationEventWatcherHealth(
            Supplier<ControlPlaneCertificateRotationEventWatcher.Descriptor> descriptor,
            boolean required) {
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
        this.required = required;
    }

    /**
     * Reports UP only for authenticated progress, idle, or intentional serving-fence states.
     *
     * @return bounded material-free health snapshot
     */
    @Override
    public Health health() {
        try {
            ControlPlaneCertificateRotationEventWatcher.Descriptor current =
                    Objects.requireNonNull(descriptor.get(), "descriptor");
            return (current.ready() ? Health.up() : Health.down())
                    .withDetails(details(current)).build();
        } catch (RuntimeException unavailable) {
            return Health.down().withDetails(unavailableDetails()).build();
        }
    }

    private Map<String, Object> details(
            ControlPlaneCertificateRotationEventWatcher.Descriptor current) {
        LinkedHashMap<String, Object> details = common();
        details.put("ready", current.ready());
        details.put("durableCursor", current.durableCursor());
        details.put("automaticPolling", current.automaticPolling());
        details.put("authenticatedProtocol", current.authenticatedProtocol());
        details.put("sourceMutualTls", current.sourceMutualTls());
        details.put("sourceCertificateIdentityBound",
                current.sourceCertificateIdentityBound());
        details.put("stagedPage", current.stagedPage());
        details.put("status", current.status());
        details.put("reasonCode", current.reasonCode());
        return Map.copyOf(details);
    }

    private Map<String, Object> unavailableDetails() {
        LinkedHashMap<String, Object> details = common();
        details.put("ready", false);
        details.put("durableCursor", false);
        details.put("automaticPolling", false);
        details.put("authenticatedProtocol", false);
        details.put("sourceMutualTls", false);
        details.put("sourceCertificateIdentityBound", false);
        details.put("stagedPage", false);
        details.put("status", "UNAVAILABLE");
        details.put("reasonCode", "WATCHER_DESCRIPTOR_UNAVAILABLE");
        return Map.copyOf(details);
    }

    private LinkedHashMap<String, Object> common() {
        LinkedHashMap<String, Object> details = new LinkedHashMap<>();
        details.put("schemaVersion", SCHEMA_VERSION);
        details.put("enabled", true);
        details.put("required", required);
        return details;
    }
}
