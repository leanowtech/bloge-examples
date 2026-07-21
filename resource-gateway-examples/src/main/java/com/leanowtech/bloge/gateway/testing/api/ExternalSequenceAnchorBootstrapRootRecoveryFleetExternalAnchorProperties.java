package com.leanowtech.bloge.gateway.testing.api;

import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.util.Objects;

/**
 * Strict public-only configuration for recovery-fleet external sequence non-equivocation.
 *
 * <p>The root record selects one challenge-bound notary quorum. Static receipt keys remain a test
 * migration mode. Managed mode rotates receipt keys from a signed publication, while its nested
 * bootstrap-root mode removes restart-bound bootstrap keys by replaying a complete signed root
 * chain. No level accepts private signer material, provider credentials, or business payloads.</p>
 *
 * @param enabled creates or requires one recovery-fleet domain-isolated external anchor
 * @param required rejects a local-database-only publication or trust-root floor
 * @param trustDomain receipt signer trust domain
 * @param anchorSetId stable independent notary-set identity
 * @param signatureThreshold accepted receipt quorum
 * @param maximumFaults declared Byzantine fault bound
 * @param minimumFaults deployment-required minimum Byzantine fault bound
 * @param authorityKeysJson strict static public Ed25519 receipt-key array
 * @param endpointsJson strict independent notary endpoint/failure-domain array
 * @param requestTimeoutMillis bounded notary request timeout
 * @param clockSkewSeconds accepted signed receipt clock skew
 * @param maximumReceiptLifetimeSeconds maximum signed receipt validity
 * @param allowInsecureLoopback test-profile-only HTTP loopback escape hatch
 * @param transport authenticated notary endpoint transport
 * @param managedTrust optional restart-free receipt trust source
 */
public record ExternalSequenceAnchorBootstrapRootRecoveryFleetExternalAnchorProperties(
        Boolean enabled,
        Boolean required,
        String trustDomain,
        String anchorSetId,
        Integer signatureThreshold,
        Integer maximumFaults,
        Integer minimumFaults,
        String authorityKeysJson,
        String endpointsJson,
        Long requestTimeoutMillis,
        Long clockSkewSeconds,
        Long maximumReceiptLifetimeSeconds,
        Boolean allowInsecureLoopback,
        @NestedConfigurationProperty RecoveryFleetPublicationTransportProperties transport,
        @NestedConfigurationProperty ManagedTrustProperties managedTrust) {

    /** Nested prefix shared by profile files, environment variables, and deployment docs. */
    public static final String PREFIX =
            ExternalSequenceAnchorBootstrapRootRecoveryFleetDynamicInventoryConfiguration
                    .DynamicInventoryProperties.PREFIX + ".external-anchor";

    /** Applies bounded defaults and rejects disabled, partial, or mixed notary configuration. */
    public ExternalSequenceAnchorBootstrapRootRecoveryFleetExternalAnchorProperties {
        enabled = Boolean.TRUE.equals(enabled);
        required = Boolean.TRUE.equals(required);
        trustDomain = normalized(trustDomain);
        anchorSetId = normalized(anchorSetId);
        signatureThreshold = defaulted(signatureThreshold, 0);
        maximumFaults = defaulted(maximumFaults, 0);
        minimumFaults = defaulted(minimumFaults, 0);
        authorityKeysJson = normalized(authorityKeysJson);
        endpointsJson = normalized(endpointsJson);
        requestTimeoutMillis = defaulted(requestTimeoutMillis, 3_000L);
        clockSkewSeconds = defaulted(clockSkewSeconds, 5L);
        maximumReceiptLifetimeSeconds = defaulted(maximumReceiptLifetimeSeconds, 15L);
        allowInsecureLoopback = Boolean.TRUE.equals(allowInsecureLoopback);
        transport = transport == null
                ? RecoveryFleetPublicationTransportProperties.disabled() : transport;
        managedTrust = managedTrust == null ? ManagedTrustProperties.disabled() : managedTrust;
        if (required && !enabled) {
            throw invalid();
        }
        if (!enabled && configured(trustDomain, anchorSetId, signatureThreshold,
                maximumFaults, minimumFaults, authorityKeysJson, endpointsJson,
                allowInsecureLoopback, transport.configured(), managedTrust.configured())) {
            throw invalid();
        }
        if (enabled && (trustDomain.isBlank() || anchorSetId.isBlank()
                || signatureThreshold < 1 || maximumFaults < 0 || maximumFaults > 10
                || minimumFaults < 0 || minimumFaults > maximumFaults
                || emptyArray(endpointsJson))) {
            throw invalid();
        }
        if (enabled && managedTrust.enabled() && !emptyArray(authorityKeysJson)) {
            throw invalid();
        }
        if (enabled && !managedTrust.enabled() && emptyArray(authorityKeysJson)) {
            throw invalid();
        }
        if (enabled && (transport.enabled() && allowInsecureLoopback
                || transport.sharesClientIdentityWith(managedTrust.transport())
                || transport.sharesClientIdentityWith(
                managedTrust.bootstrapRoots().transport())
                || managedTrust.transport().sharesClientIdentityWith(
                managedTrust.bootstrapRoots().transport()))) {
            throw invalid();
        }
    }

    /** @return disabled configuration with finite timing defaults */
    static ExternalSequenceAnchorBootstrapRootRecoveryFleetExternalAnchorProperties disabled() {
        return new ExternalSequenceAnchorBootstrapRootRecoveryFleetExternalAnchorProperties(
                false, false, "", "", 0, 0, 0, "[]", "[]", 3_000L, 5L, 15L,
                false, RecoveryFleetPublicationTransportProperties.disabled(),
                ManagedTrustProperties.disabled());
    }

    /** @return whether any non-default feature configuration is present */
    boolean configured() {
        return required || enabled || configured(trustDomain, anchorSetId, signatureThreshold,
                maximumFaults, minimumFaults, authorityKeysJson, endpointsJson,
                allowInsecureLoopback, transport.configured(), managedTrust.configured());
    }

    private static boolean configured(
            String trustDomain,
            String anchorSetId,
            int signatureThreshold,
            int maximumFaults,
            int minimumFaults,
            String keys,
            String endpoints,
            boolean insecure,
            boolean authenticatedTransport,
            boolean managed) {
        return !trustDomain.isBlank() || !anchorSetId.isBlank() || signatureThreshold != 0
                || maximumFaults != 0 || minimumFaults != 0 || !emptyArray(keys)
                || !emptyArray(endpoints) || insecure || authenticatedTransport || managed;
    }

    /**
     * Strict managed receipt-key publication configuration.
     *
     * @param enabled rotates notary receipt keys without Resource Gateway restart
     * @param required rejects static receipt-key fallback
     * @param publicationUri strict signed trust-publication source
     * @param trustRootSetId stable durable receipt trust stream identity
     * @param bootstrapTrustDomain bootstrap authority trust domain
     * @param acceptedPolicyFingerprints accepted receipt-key rotation policies
     * @param bootstrapSignatureThreshold static bootstrap M-of-N threshold
     * @param bootstrapAuthorityKeysJson static public bootstrap-key array
     * @param refreshIntervalSeconds fixed-delay trust refresh interval
     * @param requestTimeoutMillis bounded trust-source request timeout
     * @param unknownKeyRefreshIntervalSeconds unknown receipt-key refresh cooldown
     * @param maximumSnapshotAgeSeconds hard local trust-source freshness fence
     * @param maximumPublicationLifetimeSeconds maximum signed trust publication lifetime
     * @param clockSkewSeconds accepted trust-publication clock skew
     * @param minimumRemainingValiditySeconds required usable publication lifetime
     * @param allowInsecureLoopback test-profile-only HTTP loopback escape hatch
     * @param transport authenticated trust-publication source transport
     * @param bootstrapRoots optional restart-free bootstrap-root chain
     */
    public record ManagedTrustProperties(
            Boolean enabled,
            Boolean required,
            String publicationUri,
            String trustRootSetId,
            String bootstrapTrustDomain,
            String acceptedPolicyFingerprints,
            Integer bootstrapSignatureThreshold,
            String bootstrapAuthorityKeysJson,
            Long refreshIntervalSeconds,
            Long requestTimeoutMillis,
            Long unknownKeyRefreshIntervalSeconds,
            Long maximumSnapshotAgeSeconds,
            Long maximumPublicationLifetimeSeconds,
            Long clockSkewSeconds,
            Long minimumRemainingValiditySeconds,
            Boolean allowInsecureLoopback,
            @NestedConfigurationProperty RecoveryFleetPublicationTransportProperties transport,
            @NestedConfigurationProperty BootstrapRootProperties bootstrapRoots) {

        /** Nested managed receipt-trust prefix. */
        public static final String PREFIX =
                ExternalSequenceAnchorBootstrapRootRecoveryFleetExternalAnchorProperties.PREFIX
                        + ".managed-trust";

        /** Applies finite defaults and rejects partial or mixed bootstrap trust. */
        public ManagedTrustProperties {
            enabled = Boolean.TRUE.equals(enabled);
            required = Boolean.TRUE.equals(required);
            publicationUri = normalized(publicationUri);
            trustRootSetId = normalized(trustRootSetId);
            bootstrapTrustDomain = normalized(bootstrapTrustDomain);
            acceptedPolicyFingerprints = normalized(acceptedPolicyFingerprints);
            bootstrapSignatureThreshold = defaulted(bootstrapSignatureThreshold, 0);
            bootstrapAuthorityKeysJson = normalized(bootstrapAuthorityKeysJson);
            refreshIntervalSeconds = defaulted(refreshIntervalSeconds, 30L);
            requestTimeoutMillis = defaulted(requestTimeoutMillis, 3_000L);
            unknownKeyRefreshIntervalSeconds = defaulted(
                    unknownKeyRefreshIntervalSeconds, 5L);
            maximumSnapshotAgeSeconds = defaulted(maximumSnapshotAgeSeconds, 60L);
            maximumPublicationLifetimeSeconds = defaulted(
                    maximumPublicationLifetimeSeconds, 86_400L);
            clockSkewSeconds = defaulted(clockSkewSeconds, 5L);
            minimumRemainingValiditySeconds = defaulted(
                    minimumRemainingValiditySeconds, 30L);
            allowInsecureLoopback = Boolean.TRUE.equals(allowInsecureLoopback);
            transport = transport == null
                    ? RecoveryFleetPublicationTransportProperties.disabled() : transport;
            bootstrapRoots = bootstrapRoots == null
                    ? BootstrapRootProperties.disabled() : bootstrapRoots;
            if (required && !enabled) {
                throw invalid();
            }
            if (!enabled && configured(publicationUri, trustRootSetId,
                    bootstrapTrustDomain, acceptedPolicyFingerprints,
                    bootstrapSignatureThreshold, bootstrapAuthorityKeysJson,
                    allowInsecureLoopback, transport.configured(),
                    bootstrapRoots.configured())) {
                throw invalid();
            }
            if (enabled && (publicationUri.isBlank() || trustRootSetId.isBlank()
                    || bootstrapTrustDomain.isBlank()
                    || acceptedPolicyFingerprints.isBlank())) {
                throw invalid();
            }
            if (enabled && bootstrapRoots.enabled()
                    && (bootstrapSignatureThreshold != 0
                    || !emptyArray(bootstrapAuthorityKeysJson))) {
                throw invalid();
            }
            if (enabled && !bootstrapRoots.enabled()
                    && (bootstrapSignatureThreshold < 1
                    || emptyArray(bootstrapAuthorityKeysJson))) {
                throw invalid();
            }
            if (enabled && transport.enabled() && allowInsecureLoopback) {
                throw invalid();
            }
        }

        private static ManagedTrustProperties disabled() {
            return new ManagedTrustProperties(false, false, "", "", "", "", 0,
                    "[]", 30L, 3_000L, 5L, 60L, 86_400L, 5L, 30L, false,
                    RecoveryFleetPublicationTransportProperties.disabled(),
                    BootstrapRootProperties.disabled());
        }

        private boolean configured() {
            return required || enabled || configured(publicationUri, trustRootSetId,
                    bootstrapTrustDomain, acceptedPolicyFingerprints,
                    bootstrapSignatureThreshold, bootstrapAuthorityKeysJson,
                    allowInsecureLoopback, transport.configured(),
                    bootstrapRoots.configured());
        }

        private static boolean configured(
                String uri,
                String rootSetId,
                String bootstrapDomain,
                String policies,
                int threshold,
                String keys,
                boolean insecure,
                boolean authenticatedTransport,
                boolean roots) {
            return !uri.isBlank() || !rootSetId.isBlank() || !bootstrapDomain.isBlank()
                    || !policies.isBlank() || threshold != 0 || !emptyArray(keys)
                    || insecure || authenticatedTransport || roots;
        }
    }

    /**
     * Strict complete-chain bootstrap-root source for managed notary receipt trust.
     *
     * @param enabled rotates bootstrap roots without Resource Gateway restart
     * @param required rejects static bootstrap-key fallback
     * @param genesisJson pinned strict public bootstrap-root genesis document
     * @param acceptedPolicyFingerprints accepted root transition policies
     * @param bundleUri strict complete signed root-chain source
     * @param refreshIntervalSeconds fixed-delay root refresh interval
     * @param requestTimeoutMillis bounded root-source request timeout
     * @param unknownKeyRefreshIntervalSeconds unknown bootstrap-key refresh cooldown
     * @param maximumSnapshotAgeSeconds hard local root-source freshness fence
     * @param maximumRootLifetimeSeconds maximum signed root lifetime
     * @param clockSkewSeconds accepted root-chain clock skew
     * @param minimumRemainingValiditySeconds required usable head lifetime
     * @param maximumTransitions maximum complete-chain transition count
     * @param allowInsecureLoopback test-profile-only HTTP loopback escape hatch
     * @param transport authenticated complete-chain bundle source transport
     */
    public record BootstrapRootProperties(
            Boolean enabled,
            Boolean required,
            String genesisJson,
            String acceptedPolicyFingerprints,
            String bundleUri,
            Long refreshIntervalSeconds,
            Long requestTimeoutMillis,
            Long unknownKeyRefreshIntervalSeconds,
            Long maximumSnapshotAgeSeconds,
            Long maximumRootLifetimeSeconds,
            Long clockSkewSeconds,
            Long minimumRemainingValiditySeconds,
            Integer maximumTransitions,
            Boolean allowInsecureLoopback,
            @NestedConfigurationProperty RecoveryFleetPublicationTransportProperties transport) {

        /** Nested managed bootstrap-root prefix. */
        public static final String PREFIX = ManagedTrustProperties.PREFIX + ".bootstrap-roots";

        /** Applies finite defaults and rejects disabled or partial root-chain configuration. */
        public BootstrapRootProperties {
            enabled = Boolean.TRUE.equals(enabled);
            required = Boolean.TRUE.equals(required);
            genesisJson = normalized(genesisJson);
            acceptedPolicyFingerprints = normalized(acceptedPolicyFingerprints);
            bundleUri = normalized(bundleUri);
            refreshIntervalSeconds = defaulted(refreshIntervalSeconds, 30L);
            requestTimeoutMillis = defaulted(requestTimeoutMillis, 3_000L);
            unknownKeyRefreshIntervalSeconds = defaulted(
                    unknownKeyRefreshIntervalSeconds, 5L);
            maximumSnapshotAgeSeconds = defaulted(maximumSnapshotAgeSeconds, 60L);
            maximumRootLifetimeSeconds = defaulted(maximumRootLifetimeSeconds, 2_592_000L);
            clockSkewSeconds = defaulted(clockSkewSeconds, 5L);
            minimumRemainingValiditySeconds = defaulted(
                    minimumRemainingValiditySeconds, 30L);
            maximumTransitions = defaulted(maximumTransitions, 128);
            allowInsecureLoopback = Boolean.TRUE.equals(allowInsecureLoopback);
            transport = transport == null
                    ? RecoveryFleetPublicationTransportProperties.disabled() : transport;
            if (required && !enabled) {
                throw invalid();
            }
            if (!enabled && configured(genesisJson, acceptedPolicyFingerprints, bundleUri,
                    allowInsecureLoopback, transport.configured())) {
                throw invalid();
            }
            if (enabled && (genesisJson.isBlank() || acceptedPolicyFingerprints.isBlank()
                    || bundleUri.isBlank())) {
                throw invalid();
            }
            if (enabled && transport.enabled() && allowInsecureLoopback) {
                throw invalid();
            }
        }

        private static BootstrapRootProperties disabled() {
            return new BootstrapRootProperties(false, false, "", "", "", 30L, 3_000L,
                    5L, 60L, 2_592_000L, 5L, 30L, 128, false,
                    RecoveryFleetPublicationTransportProperties.disabled());
        }

        private boolean configured() {
            return required || enabled || configured(
                    genesisJson, acceptedPolicyFingerprints, bundleUri,
                    allowInsecureLoopback, transport.configured());
        }

        private static boolean configured(
                String genesis,
                String policies,
                String uri,
                boolean insecure,
                boolean authenticatedTransport) {
            return !genesis.isBlank() || !policies.isBlank() || !uri.isBlank() || insecure
                    || authenticatedTransport;
        }
    }

    private static boolean emptyArray(String value) {
        return value.isBlank() || "[]".equals(value);
    }

    private static String normalized(String value) {
        return Objects.requireNonNullElse(value, "").trim();
    }

    private static int defaulted(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    private static long defaulted(Long value, long fallback) {
        return value == null ? fallback : value;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException(
                "Recovery-fleet external anchor configuration is invalid");
    }
}
