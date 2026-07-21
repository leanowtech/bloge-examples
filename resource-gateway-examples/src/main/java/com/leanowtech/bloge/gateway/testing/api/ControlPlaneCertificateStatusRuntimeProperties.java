package com.leanowtech.bloge.gateway.testing.api;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.util.Objects;

/**
 * Strict test/staging product configuration for certificate-status ingestion and admission.
 *
 * <p>Private keys, passwords, raw certificates, OCSP responses, and CRLs are absent. Trust JSON
 * contains public Ed25519 keys only; source credentials remain opaque references in the nested
 * pinned-mTLS transport. Disabled residual configuration and every partial security binding fail
 * at startup.</p>
 *
 * @param enabled enables signed status ingestion and per-request admission
 * @param required prevents deployment downgrade to status-unmanaged rotation
 * @param deploymentScopeId exact Resource Gateway deployment scope
 * @param trustDomain independent certificate-status trust domain
 * @param acceptedPolicyFingerprints comma-separated normalization-policy fingerprints
 * @param signatureThreshold required distinct external authority signatures
 * @param authorityKeysJson strict public Ed25519 authority-key array
 * @param baselineSequence deployment-pinned source cursor baseline
 * @param baselinePublicationFingerprint deployment-pinned predecessor fingerprint
 * @param endpointUri strict HTTPS normalized-publication endpoint
 * @param requestTimeoutMillis source request deadline
 * @param maximumPublicationBytes hard response-body bound
 * @param clockSkewSeconds accepted source/local clock skew
 * @param maximumPublicationLifetimeSeconds maximum signed publication lifetime
 * @param refreshDelayMillis fixed delay between refresh cycles
 * @param initialDelayMillis startup delay before the first cycle
 * @param maximumBatch maximum contiguous publications per cycle
 * @param slo finite fixed-cardinality local service-level policy
 * @param transport independent private PKIX/SPKI/mTLS/workload-identity source transport
 */
@ConfigurationProperties(
        prefix = ControlPlaneCertificateStatusRuntimeProperties.PREFIX,
        ignoreUnknownFields = false)
public record ControlPlaneCertificateStatusRuntimeProperties(
        Boolean enabled,
        Boolean required,
        String deploymentScopeId,
        String trustDomain,
        String acceptedPolicyFingerprints,
        Integer signatureThreshold,
        String authorityKeysJson,
        Long baselineSequence,
        String baselinePublicationFingerprint,
        String endpointUri,
        Long requestTimeoutMillis,
        Integer maximumPublicationBytes,
        Long clockSkewSeconds,
        Long maximumPublicationLifetimeSeconds,
        Long refreshDelayMillis,
        Long initialDelayMillis,
        Integer maximumBatch,
        @NestedConfigurationProperty ControlPlaneCertificateStatusSloProperties slo,
        @NestedConfigurationProperty RecoveryFleetPublicationTransportProperties transport) {

    /** Configuration prefix shared by YAML, environment, scheduler, and capability metadata. */
    public static final String PREFIX = "gateway.testing.control-plane-certificate-status";

    /** Applies finite defaults and rejects partial or downgrade-prone status policy. */
    public ControlPlaneCertificateStatusRuntimeProperties {
        enabled = Boolean.TRUE.equals(enabled);
        required = Boolean.TRUE.equals(required);
        deploymentScopeId = normalized(deploymentScopeId);
        trustDomain = normalized(trustDomain);
        acceptedPolicyFingerprints = normalized(acceptedPolicyFingerprints);
        signatureThreshold = signatureThreshold == null ? 0 : signatureThreshold;
        authorityKeysJson = normalized(authorityKeysJson);
        baselineSequence = baselineSequence == null ? 0L : baselineSequence;
        baselinePublicationFingerprint = normalized(baselinePublicationFingerprint);
        endpointUri = normalized(endpointUri);
        requestTimeoutMillis = requestTimeoutMillis == null ? 5_000L : requestTimeoutMillis;
        maximumPublicationBytes = maximumPublicationBytes == null
                ? 512 * 1024 : maximumPublicationBytes;
        clockSkewSeconds = clockSkewSeconds == null ? 60L : clockSkewSeconds;
        maximumPublicationLifetimeSeconds = maximumPublicationLifetimeSeconds == null
                ? 3_600L : maximumPublicationLifetimeSeconds;
        refreshDelayMillis = refreshDelayMillis == null ? 30_000L : refreshDelayMillis;
        initialDelayMillis = initialDelayMillis == null ? 1_000L : initialDelayMillis;
        maximumBatch = maximumBatch == null ? 8 : maximumBatch;
        slo = Objects.requireNonNullElseGet(
                slo, ControlPlaneCertificateStatusSloProperties::defaults);
        transport = Objects.requireNonNullElseGet(
                transport, RecoveryFleetPublicationTransportProperties::disabled);
        boolean residual = !deploymentScopeId.isBlank() || !trustDomain.isBlank()
                || !acceptedPolicyFingerprints.isBlank() || signatureThreshold != 0
                || !emptyArray(authorityKeysJson) || baselineSequence != 0
                || !baselinePublicationFingerprint.isBlank() || !endpointUri.isBlank()
                || requestTimeoutMillis != 5_000L || maximumPublicationBytes != 512 * 1024
                || clockSkewSeconds != 60L || maximumPublicationLifetimeSeconds != 3_600L
                || refreshDelayMillis != 30_000L || initialDelayMillis != 1_000L
                || maximumBatch != 8 || slo.configured() || transport.configured();
        if (required && !enabled || !enabled && residual
                || enabled && (!identifier(deploymentScopeId) || !identifier(trustDomain)
                || !validPolicies(acceptedPolicyFingerprints)
                || signatureThreshold < 1 || signatureThreshold > 32
                || emptyArray(authorityKeysJson) || authorityKeysJson.length() > 512 * 1024
                || baselineSequence < 0
                || !baselinePublicationFingerprint.matches("sha256:[a-f0-9]{64}")
                || endpointUri.isBlank() || endpointUri.length() > 2_048
                || requestTimeoutMillis < 100
                || requestTimeoutMillis > 30_000 || maximumPublicationBytes < 1_024
                || maximumPublicationBytes > 2 * 1024 * 1024
                || clockSkewSeconds < 0 || clockSkewSeconds > 300
                || maximumPublicationLifetimeSeconds < 1
                || maximumPublicationLifetimeSeconds > 86_400
                || refreshDelayMillis < 100 || refreshDelayMillis > 300_000
                || initialDelayMillis < 0 || initialDelayMillis > 300_000
                || maximumBatch < 1 || maximumBatch > 32
                || secondsToMillis(slo.startupGraceSeconds())
                < saturatedAdd(initialDelayMillis, requestTimeoutMillis)
                || secondsToMillis(slo.maximumRefreshSuccessAgeSeconds())
                < saturatedAdd(refreshDelayMillis, requestTimeoutMillis)
                || slo.minimumExpiryHeadroomSeconds()
                >= maximumPublicationLifetimeSeconds
                || !transport.enabled() || !transport.required()
                || transport.trustStorePath().isBlank()
                || transport.trustStorePasswordRef().isBlank()
                || !transport.certificateIdentityRequired()
                || !transport.certificateIdentityBound()
                || oversized(transport))) {
            throw invalid();
        }
    }

    /** @return strict bounded HTTP source settings; enabled configuration only */
    public HttpControlPlaneCertificateStatusSource.Settings sourceSettings() {
        if (!enabled) {
            throw invalid();
        }
        return new HttpControlPlaneCertificateStatusSource.Settings(deploymentScopeId,
                endpointUri, requestTimeoutMillis, maximumPublicationBytes, clockSkewSeconds,
                maximumPublicationLifetimeSeconds, false);
    }

    /** Returns the canonical disabled policy. */
    public static ControlPlaneCertificateStatusRuntimeProperties disabled() {
        return new ControlPlaneCertificateStatusRuntimeProperties(false, false,
                "", "", "", 0, "[]", 0L, "", "",
                5_000L, 512 * 1024, 60L, 3_600L, 30_000L, 1_000L, 8,
                ControlPlaneCertificateStatusSloProperties.defaults(),
                RecoveryFleetPublicationTransportProperties.disabled());
    }

    private static boolean emptyArray(String value) {
        return value.isBlank() || "[]".equals(value);
    }

    private static boolean identifier(String value) {
        return value.matches("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");
    }

    private static boolean validPolicies(String value) {
        String[] policies = value.split(",", -1);
        if (policies.length < 1 || policies.length > 32) {
            return false;
        }
        java.util.HashSet<String> distinct = new java.util.HashSet<>();
        for (String policy : policies) {
            String normalized = normalized(policy);
            if (!normalized.matches("sha256:[a-f0-9]{64}")
                    || !distinct.add(normalized)) {
                return false;
            }
        }
        return true;
    }

    private static String normalized(String value) {
        return Objects.requireNonNullElse(value, "").trim();
    }

    private static boolean oversized(RecoveryFleetPublicationTransportProperties transport) {
        return transport.trustStorePath().length() > 2_048
                || transport.trustStorePasswordRef().length() > 2_048
                || transport.clientKeyStorePath().length() > 2_048
                || transport.clientKeyStorePasswordRef().length() > 2_048
                || transport.serverSpkiPins().length() > 1_151
                || transport.expectedClientSubjectDn().length() > 2_048
                || transport.expectedClientUriSan().length() > 2_048
                || transport.clientIssuerSpkiPins().length() > 1_151
                || transport.expectedServerUriSan().length() > 2_048
                || transport.serverIssuerSpkiPins().length() > 1_151;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException(
                "Control-plane certificate status runtime configuration is invalid");
    }

    private static long secondsToMillis(long seconds) {
        try {
            return Math.multiplyExact(seconds, 1_000L);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    private static long saturatedAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }
}
