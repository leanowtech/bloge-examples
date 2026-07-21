package com.leanowtech.bloge.gateway.testing.api;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.time.Duration;
import java.util.Objects;

/**
 * Strict test/staging configuration for authenticated certificate-rotation event delivery.
 *
 * <p>The configuration binds an immutable page-chain baseline to a separately authenticated
 * source transport and a bounded fixed-delay consumer. Password values, private keys, raw
 * certificates, event bodies, and authority signatures are absent. Disabled residual values and
 * every partial private-PKIX/SPKI/mTLS/workload-identity policy fail during property binding.</p>
 *
 * @param enabled enables event-page ingestion for the local stable serving slot
 * @param required prevents deployment downgrade to manually delivered rotation events
 * @param endpointUri strict HTTPS event-page endpoint
 * @param baselineSequence deployment-pinned page-chain baseline sequence
 * @param baselinePageFingerprint deployment-pinned page-chain baseline fingerprint
 * @param pollIntervalSeconds fixed delay between bounded polling cycles
 * @param maximumPagesPerPoll maximum contiguous pages consumed by one cycle
 * @param requestTimeoutMillis source connect and request deadline
 * @param maximumPageBytes hard response-body bound applied before JSON parsing
 * @param clockSkewSeconds accepted future publication skew
 * @param maximumPageLifetimeSeconds maximum source page validity duration
 * @param allowInsecureLoopback permits HTTP only for explicit local protocol tests
 * @param transport independent private PKIX/SPKI/mTLS/workload-identity source transport
 */
@ConfigurationProperties(
        prefix = ControlPlaneCertificateRotationEventSourceProperties.PREFIX,
        ignoreUnknownFields = false)
public record ControlPlaneCertificateRotationEventSourceProperties(
        Boolean enabled,
        Boolean required,
        String endpointUri,
        Long baselineSequence,
        String baselinePageFingerprint,
        Long pollIntervalSeconds,
        Integer maximumPagesPerPoll,
        Long requestTimeoutMillis,
        Integer maximumPageBytes,
        Long clockSkewSeconds,
        Long maximumPageLifetimeSeconds,
        Boolean allowInsecureLoopback,
        @NestedConfigurationProperty RecoveryFleetPublicationTransportProperties transport) {

    /** Configuration prefix shared by YAML, environment, health, and capability metadata. */
    public static final String PREFIX =
            "gateway.testing.control-plane-certificate-rotation-event-source";

    /** Applies finite defaults and rejects partial, residual, or downgrade-prone policies. */
    public ControlPlaneCertificateRotationEventSourceProperties {
        enabled = Boolean.TRUE.equals(enabled);
        required = Boolean.TRUE.equals(required);
        endpointUri = normalized(endpointUri);
        baselineSequence = baselineSequence == null ? 0L : baselineSequence;
        baselinePageFingerprint = normalized(baselinePageFingerprint);
        pollIntervalSeconds = pollIntervalSeconds == null ? 5L : pollIntervalSeconds;
        maximumPagesPerPoll = maximumPagesPerPoll == null ? 4 : maximumPagesPerPoll;
        requestTimeoutMillis = requestTimeoutMillis == null ? 3_000L : requestTimeoutMillis;
        maximumPageBytes = maximumPageBytes == null ? 256 * 1024 : maximumPageBytes;
        clockSkewSeconds = clockSkewSeconds == null ? 30L : clockSkewSeconds;
        maximumPageLifetimeSeconds = maximumPageLifetimeSeconds == null
                ? 300L : maximumPageLifetimeSeconds;
        allowInsecureLoopback = Boolean.TRUE.equals(allowInsecureLoopback);
        transport = Objects.requireNonNullElseGet(
                transport, RecoveryFleetPublicationTransportProperties::disabled);
        boolean residual = !endpointUri.isBlank() || baselineSequence != 0
                || !baselinePageFingerprint.isBlank() || pollIntervalSeconds != 5
                || maximumPagesPerPoll != 4 || requestTimeoutMillis != 3_000
                || maximumPageBytes != 256 * 1024 || clockSkewSeconds != 30
                || maximumPageLifetimeSeconds != 300 || allowInsecureLoopback
                || transport.configured();
        if (required && !enabled || !enabled && residual
                || enabled && (baselineSequence < 0
                || !baselinePageFingerprint.matches("sha256:[a-f0-9]{64}")
                || endpointUri.length() > 2_048
                || pollIntervalSeconds < 1 || pollIntervalSeconds > 3_600
                || maximumPagesPerPoll < 1 || maximumPagesPerPoll > 32
                || !transport.enabled() || !transport.required()
                || transport.trustStorePath().isBlank()
                || transport.trustStorePasswordRef().isBlank()
                || !transport.certificateIdentityRequired()
                || !transport.certificateIdentityBound()
                || oversized(transport))) {
            throw invalid();
        }
        if (enabled) {
            try {
                new HttpControlPlaneCertificateRotationEventSource.Settings(
                        endpointUri, requestTimeoutMillis, maximumPageBytes,
                        clockSkewSeconds, maximumPageLifetimeSeconds,
                        allowInsecureLoopback);
            } catch (RuntimeException invalid) {
                throw invalid();
            }
        }
    }

    /** @return strict bounded HTTP source settings; enabled configuration only */
    public HttpControlPlaneCertificateRotationEventSource.Settings sourceSettings() {
        if (!enabled) {
            throw invalid();
        }
        return new HttpControlPlaneCertificateRotationEventSource.Settings(
                endpointUri, requestTimeoutMillis, maximumPageBytes,
                clockSkewSeconds, maximumPageLifetimeSeconds, allowInsecureLoopback);
    }

    /** @return finite fixed delay between poll cycles */
    public Duration pollInterval() {
        return Duration.ofSeconds(pollIntervalSeconds);
    }

    /** Returns the canonical disabled event-source policy. */
    public static ControlPlaneCertificateRotationEventSourceProperties disabled() {
        return new ControlPlaneCertificateRotationEventSourceProperties(
                false, false, "", 0L, "", 5L, 4,
                3_000L, 256 * 1024, 30L, 300L, false,
                RecoveryFleetPublicationTransportProperties.disabled());
    }

    private static String normalized(String value) {
        return Objects.requireNonNullElse(value, "").trim();
    }

    private static boolean oversized(RecoveryFleetPublicationTransportProperties transport) {
        return transport.trustStorePath().length() > 4_096
                || transport.trustStorePasswordRef().length() > 255
                || transport.clientKeyStorePath().length() > 4_096
                || transport.clientKeyStorePasswordRef().length() > 255
                || transport.serverSpkiPins().length() > 1_151
                || transport.expectedClientSubjectDn().length() > 1_024
                || transport.expectedClientUriSan().length() > 2_048
                || transport.clientIssuerSpkiPins().length() > 1_151
                || transport.expectedServerUriSan().length() > 2_048
                || transport.serverIssuerSpkiPins().length() > 1_151;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException(
                "Control-plane certificate rotation event source configuration is invalid");
    }
}
