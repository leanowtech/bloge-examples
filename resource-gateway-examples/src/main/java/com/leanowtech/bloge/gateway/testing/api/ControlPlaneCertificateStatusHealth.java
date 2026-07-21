package com.leanowtech.bloge.gateway.testing.api;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Fixed-cardinality Actuator truth for certificate-status ingestion and request admission.
 *
 * <p>No endpoint, target id, authority identity, certificate fingerprint, reason detail, or
 * provider exception is projected. UP requires independent trust, strict pinned mTLS source,
 * durable watcher state, and a currently fresh request cache.</p>
 */
public final class ControlPlaneCertificateStatusHealth implements HealthIndicator {

    /** Current health-detail protocol version. */
    public static final String SCHEMA_VERSION =
            "bloge.controlPlaneCertificateStatusHealth.v1";

    private final ControlPlaneCertificateStatusMonitor monitor;
    private final ControlPlaneCertificateStatusSource source;
    private final ControlPlaneCertificateStatusTrustStore trustStore;
    private final ControlPlaneCertificateStatusAdmission admission;

    /**
     * Creates status health from live bounded descriptors.
     *
     * @param monitor durable/source refresh posture
     * @param source strict transport posture
     * @param trustStore independent signature trust posture
     * @param admission local hard-expiry request posture
     */
    public ControlPlaneCertificateStatusHealth(
            ControlPlaneCertificateStatusMonitor monitor,
            ControlPlaneCertificateStatusSource source,
            ControlPlaneCertificateStatusTrustStore trustStore,
            ControlPlaneCertificateStatusAdmission admission) {
        this.monitor = Objects.requireNonNull(monitor, "monitor");
        this.source = Objects.requireNonNull(source, "source");
        this.trustStore = Objects.requireNonNull(trustStore, "trustStore");
        this.admission = Objects.requireNonNull(admission, "admission");
    }

    /** {@inheritDoc} */
    @Override
    public Health health() {
        try {
            ControlPlaneCertificateStatusMonitor.Descriptor watcher = monitor.descriptor();
            ControlPlaneCertificateStatusSource.Descriptor transport = source.descriptor();
            ControlPlaneCertificateStatusTrustStore.Descriptor trust = trustStore.descriptor();
            ControlPlaneCertificateStatusAdmission.Descriptor cache = admission.descriptor();
            boolean ready = watcher.durable() && trust.available() && transport.available()
                    && transport.privateTrustStore() && transport.serverSpkiPinned()
                    && transport.mutualTls() && transport.certificateIdentityBound()
                    && transport.strictProtocol() && cache.fresh();
            Map<String, Object> details = details(watcher, transport, trust, cache, ready);
            return (ready ? Health.up() : Health.down()).withDetails(details).build();
        } catch (RuntimeException unavailable) {
            return Health.down()
                    .withDetail("schemaVersion", SCHEMA_VERSION)
                    .withDetail("runtimeStatus", "UNAVAILABLE")
                    .withDetail("trustAvailable", false)
                    .withDetail("strictSourceAvailable", false)
                    .withDetail("durableFloorIntegrated", false)
                    .withDetail("admissionFresh", false)
                    .withDetail("targetCount", 0)
                    .withDetail("goodTargetCount", 0)
                    .withDetail("revokedTargetCount", 0)
                    .withDetail("unknownTargetCount", 0)
                    .withDetail("productionReady", false)
                    .build();
        }
    }

    private static Map<String, Object> details(
            ControlPlaneCertificateStatusMonitor.Descriptor watcher,
            ControlPlaneCertificateStatusSource.Descriptor source,
            ControlPlaneCertificateStatusTrustStore.Descriptor trust,
            ControlPlaneCertificateStatusAdmission.Descriptor cache,
            boolean ready) {
        LinkedHashMap<String, Object> details = new LinkedHashMap<>();
        details.put("schemaVersion", SCHEMA_VERSION);
        details.put("runtimeStatus", ready ? "READY" : watcher.status().name());
        details.put("trustAvailable", trust.available());
        details.put("strictSourceAvailable", source.available() && source.strictProtocol());
        details.put("sourcePrivateTrust", source.privateTrustStore());
        details.put("sourceSpkiPinned", source.serverSpkiPinned());
        details.put("sourceMutualTls", source.mutualTls());
        details.put("sourceCertificateIdentityBound", source.certificateIdentityBound());
        details.put("durableFloorIntegrated", watcher.durable());
        details.put("admissionFresh", cache.fresh());
        details.put("targetCount", cache.targetCount());
        details.put("goodTargetCount", cache.goodTargetCount());
        details.put("revokedTargetCount", cache.revokedTargetCount());
        details.put("unknownTargetCount", cache.unknownTargetCount());
        details.put("productionReady", false);
        return Map.copyOf(details);
    }
}
