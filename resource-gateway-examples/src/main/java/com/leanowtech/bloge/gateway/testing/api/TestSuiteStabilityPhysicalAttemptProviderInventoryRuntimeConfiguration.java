package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.ToolStudioResourceGatewayProtocol;
import com.leanowtech.bloge.gateway.testing.api.ConfiguredTestSuiteStabilityPhysicalAttemptProviderInventoryAuthority.ExpectedBinding;
import com.leanowtech.bloge.gateway.testing.api.ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseTestSuiteStabilityPhysicalAttemptProviderInventoryCohortRepository;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseTestSuiteStabilityPhysicalAttemptProviderInventoryPublicationFloor;
import com.leanowtech.bloge.gateway.testing.persistence.TestRuntimeDatabase;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Strict Spring composition root for dynamic physical provider-inventory admission.
 *
 * <p>The runtime is physically absent in production and remains absent in test/staging unless
 * explicitly enabled. Enabling requires one installed-adapter catalog, an HTTPS signed ACTIVE
 * publication, independent deployment and witness quorums, a durable database anti-rollback
 * floor, and a database-clock exact replica cohort. Staging additionally requires externally
 * durable challenge-bound Byzantine ordering, managed receipt trust, complete-chain bootstrap
 * roots, and three independently authenticated control-plane transports. No unsigned inventory,
 * empty catalog, static fallback, local expected-replica list, or permissive resolver is created.</p>
 */
@Configuration(proxyBeanMethods = false)
@Profile("!production & (test | staging)")
@ConditionalOnProperty(prefix =
        TestSuiteStabilityPhysicalAttemptProviderInventoryRuntimeConfiguration.Properties.PREFIX,
        name = "enabled", havingValue = "true")
@EnableConfigurationProperties(
        TestSuiteStabilityPhysicalAttemptProviderInventoryRuntimeConfiguration.Properties.class)
public class TestSuiteStabilityPhysicalAttemptProviderInventoryRuntimeConfiguration {

    /** Creates the profile- and property-gated composition root. */
    public TestSuiteStabilityPhysicalAttemptProviderInventoryRuntimeConfiguration() {
    }

    /** Supplies a stateless environment resolver unless the embedder provides a secret authority. */
    @Bean
    @ConditionalOnMissingBean(ControlPlaneHttpTransport.SecretResolver.class)
    ControlPlaneHttpTransport.SecretResolver
            physicalProviderInventoryControlPlaneSecretResolver() {
        return new PinnedMutualTlsRecoveryFleetPublicationTransport.EnvironmentSecretResolver();
    }

    /** Validates profile-sensitive downgrade fences before physical floor or remote bootstrap. */
    @Bean
    ValidatedRuntimeConfiguration physicalProviderInventoryRuntimePreflight(
            Properties properties,
            Environment environment) {
        boolean staging = Objects.requireNonNull(environment, "environment")
                .acceptsProfiles(Profiles.of("staging"));
        var anchor = properties.externalAnchor();
        if (staging && (properties.allowInsecureLoopback()
                || !anchor.enabled() || !anchor.required()
                || anchor.maximumFaults() < 1 || anchor.minimumFaults() < 1
                || anchor.allowInsecureLoopback()
                || !anchor.transport().enabled() || !anchor.transport().required()
                || !anchor.transport().certificateIdentityBound()
                || !anchor.managedTrust().enabled() || !anchor.managedTrust().required()
                || anchor.managedTrust().allowInsecureLoopback()
                || !anchor.managedTrust().transport().enabled()
                || !anchor.managedTrust().transport().required()
                || !anchor.managedTrust().transport().certificateIdentityBound()
                || !anchor.managedTrust().bootstrapRoots().enabled()
                || !anchor.managedTrust().bootstrapRoots().required()
                || anchor.managedTrust().bootstrapRoots().allowInsecureLoopback()
                || !anchor.managedTrust().bootstrapRoots().transport().enabled()
                || !anchor.managedTrust().bootstrapRoots().transport().required()
                || !anchor.managedTrust().bootstrapRoots().transport()
                        .certificateIdentityBound())) {
            throw Properties.invalid();
        }
        return new ValidatedRuntimeConfiguration(staging);
    }

    /**
     * Creates the strict HTTP/quorum notary unless the embedder supplies one physical-domain bean.
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = Properties.EXTERNAL_ANCHOR_PREFIX,
            name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean(
            TestSuiteStabilityPhysicalAttemptProviderInventoryExternalSequenceAnchor.class)
    TestSuiteStabilityPhysicalAttemptProviderInventoryExternalSequenceAnchor
            physicalProviderInventoryExternalSequenceAnchor(
            ObjectMapper objectMapper,
            Environment environment,
            TestRuntimeDatabase database,
            Properties properties,
            ValidatedRuntimeConfiguration validated,
            ControlPlaneHttpTransport.SecretResolver secretResolver,
            ObjectProvider<ControlPlaneCertificateRotationRuntime> rotationRuntimes) {
        Objects.requireNonNull(validated, "validated");
        var anchor = properties.externalAnchor();
        if (anchor.maximumFaults() < Math.max(
                validated.staging() ? 1 : 0, anchor.minimumFaults())) {
            throw Properties.invalid();
        }
        ControlPlaneCertificateRotationRuntime rotationRuntime =
                rotationRuntimes.getIfAvailable();
        if (rotationRuntime == null && Boolean.TRUE.equals(environment.getProperty(
                ControlPlaneCertificateRotationRuntimeProperties.PREFIX + ".enabled",
                Boolean.class, false))) {
            throw Properties.invalid();
        }
        TestSuiteStabilityExternalSequenceAnchor shared =
                TestRuntimeConfiguration.buildExternalSequenceAnchor(
                        objectMapper, environment, database, Properties.EXTERNAL_ANCHOR_PREFIX,
                        "physical provider inventory", properties.scopeId(),
                        anchor.trustDomain(), anchor.anchorSetId(), anchor.signatureThreshold(),
                        anchor.maximumFaults(), anchor.authorityKeysJson(), anchor.endpointsJson(),
                        anchor.requestTimeoutMillis(), anchor.clockSkewSeconds(),
                        anchor.maximumReceiptLifetimeSeconds(), anchor.allowInsecureLoopback(),
                        secretResolver, rotationRuntime);
        return TestSuiteStabilityPhysicalAttemptProviderInventoryExternalSequenceAnchor.adapt(
                shared);
    }

    /** Exposes endpoint-, stream-, fingerprint-, authority-, and key-free notary health. */
    @Bean
    @ConditionalOnBean(
            TestSuiteStabilityPhysicalAttemptProviderInventoryExternalSequenceAnchor.class)
    @ConditionalOnMissingBean(
            TestSuiteStabilityPhysicalAttemptProviderInventoryExternalSequenceAnchorHealth.class)
    TestSuiteStabilityPhysicalAttemptProviderInventoryExternalSequenceAnchorHealth
            physicalProviderInventoryExternalSequenceAnchorHealth(
            TestSuiteStabilityPhysicalAttemptProviderInventoryExternalSequenceAnchor anchor) {
        return new
                TestSuiteStabilityPhysicalAttemptProviderInventoryExternalSequenceAnchorHealth(
                anchor);
    }

    /** Persists local heads and optionally commits their composite to an external quorum first. */
    @Bean
    TestSuiteStabilityPhysicalAttemptProviderInventoryPublicationFloor
            testSuiteStabilityPhysicalAttemptProviderInventoryPublicationFloor(
            TestRuntimeDatabase database,
            ObjectMapper objectMapper,
            Properties properties,
            Environment environment,
            ValidatedRuntimeConfiguration validated,
            ObjectProvider<
                    TestSuiteStabilityPhysicalAttemptProviderInventoryExternalSequenceAnchor>
                    externalAnchors) {
        Objects.requireNonNull(validated, "validated");
        var local = new DatabaseTestSuiteStabilityPhysicalAttemptProviderInventoryPublicationFloor(
                database.jdbc(), objectMapper, properties.scopeId(),
                database.transactionManager());
        local.init();
        TestSuiteStabilityPhysicalAttemptProviderInventoryExternalSequenceAnchor external =
                externalAnchor(externalAnchors, properties.externalAnchor(), environment);
        return external == null ? local : new
                ExternallyAnchoredTestSuiteStabilityPhysicalAttemptProviderInventoryPublicationFloor(
                objectMapper, local, external);
    }

    /** Bootstraps one dynamic signed publication from exactly one installed adapter catalog. */
    @Bean(destroyMethod = "close")
    DynamicTestSuiteStabilityPhysicalAttemptProviderInventoryAuthority
            dynamicTestSuiteStabilityPhysicalAttemptProviderInventoryAuthority(
            ObjectMapper objectMapper,
            ObjectProvider<TestSuiteStabilityPhysicalAttemptRuntimeAdapterCatalog> catalogs,
            TestSuiteStabilityPhysicalAttemptProviderInventoryPublicationFloor floor,
            Properties properties) {
        List<TestSuiteStabilityPhysicalAttemptRuntimeAdapterCatalog> installed =
                catalogs.orderedStream().toList();
        if (installed.size() != 1) {
            throw Properties.invalid();
        }
        Map<TestSuiteStabilityPhysicalAttemptProviderInventory.ProviderDeployment,
                TestSuiteStabilityPhysicalAttemptObservationAuthority> adapters =
                installed.getFirst().adapters();
        return new DynamicTestSuiteStabilityPhysicalAttemptProviderInventoryAuthority(
                objectMapper, properties.expectedBinding(), properties.signatureThreshold(),
                properties.deploymentKeys(objectMapper), adapters, floor,
                properties.witnessDomain(), properties.witnessSignatureThreshold(),
                properties.witnessKeys(objectMapper), properties.sourceSettings());
    }

    /** Creates the database-clock exact cohort whose expected set comes only from publication. */
    @Bean
    DatabaseTestSuiteStabilityPhysicalAttemptProviderInventoryCohortRepository
            testSuiteStabilityPhysicalAttemptProviderInventoryCohortRepository(
            TestRuntimeDatabase database,
            ObjectMapper objectMapper,
            DynamicTestSuiteStabilityPhysicalAttemptProviderInventoryAuthority authority,
            Properties properties) {
        return new DatabaseTestSuiteStabilityPhysicalAttemptProviderInventoryCohortRepository(
                database.jdbc(), objectMapper, authority, properties.localPolicy(),
                database.transactionManager());
    }

    /** Registers this process start immediately and renews its bounded database lease. */
    @Bean(destroyMethod = "close")
    TestSuiteStabilityPhysicalAttemptProviderInventoryCohortMonitor
            testSuiteStabilityPhysicalAttemptProviderInventoryCohortMonitor(
            DatabaseTestSuiteStabilityPhysicalAttemptProviderInventoryCohortRepository
                    repository,
            Properties properties) {
        return new TestSuiteStabilityPhysicalAttemptProviderInventoryCohortMonitor(
                repository, properties.heartbeatInterval());
    }

    /** Exposes only aggregate refresh and exact-cohort readiness through Actuator. */
    @Bean
    TestSuiteStabilityPhysicalAttemptProviderInventoryHealth
            testSuiteStabilityPhysicalAttemptProviderInventoryHealth(
            DynamicTestSuiteStabilityPhysicalAttemptProviderInventoryAuthority authority,
            DatabaseTestSuiteStabilityPhysicalAttemptProviderInventoryCohortRepository cohort) {
        return new TestSuiteStabilityPhysicalAttemptProviderInventoryHealth(authority, cohort);
    }

    /**
     * Strict dynamic provider-inventory runtime configuration.
     *
     * @param enabled explicit test/staging activation switch
     * @param trustDomain deployment publication trust domain
     * @param scopeId stable physical provider-fleet scope
     * @param cohortId exact Resource Gateway rollout cohort
     * @param providerProtocolVersion exact physical provider adapter protocol
     * @param acceptedPolicyFingerprints comma-separated accepted policy identities
     * @param signatureThreshold deployment-authority M-of-N threshold
     * @param authorityKeysJson deployment-authority public Ed25519 key array
     * @param publicationUri HTTPS current-publication endpoint
     * @param refreshIntervalSeconds fixed-delay refresh interval
     * @param requestTimeoutMillis bounded HTTPS request timeout
     * @param maximumSnapshotAgeSeconds hard local source freshness fence
     * @param allowInsecureLoopback local-test-only HTTP loopback escape hatch
     * @param witnessDomain independent witness trust domain
     * @param witnessSignatureThreshold independent witness M-of-N threshold
     * @param witnessAuthorityKeysJson witness public Ed25519 key array
     * @param replicaId stable local deployment slot identity
     * @param artifactFingerprint immutable Resource Gateway artifact identity
     * @param heartbeatIntervalMillis local database lease renewal interval
     * @param leaseDurationMillis database-clock live-member lease
     * @param recordRetentionSeconds expired-row retention before bounded purge
     * @param externalAnchor optional physical-domain external non-equivocation policy
     */
    @ConfigurationProperties(prefix = Properties.PREFIX, ignoreUnknownFields = false)
    public record Properties(
            Boolean enabled,
            String trustDomain,
            String scopeId,
            String cohortId,
            String providerProtocolVersion,
            String acceptedPolicyFingerprints,
            Integer signatureThreshold,
            String authorityKeysJson,
            String publicationUri,
            Long refreshIntervalSeconds,
            Long requestTimeoutMillis,
            Long maximumSnapshotAgeSeconds,
            Boolean allowInsecureLoopback,
            String witnessDomain,
            Integer witnessSignatureThreshold,
            String witnessAuthorityKeysJson,
            String replicaId,
            String artifactFingerprint,
            Long heartbeatIntervalMillis,
            Long leaseDurationMillis,
            Long recordRetentionSeconds,
            @NestedConfigurationProperty
            ExternalSequenceAnchorBootstrapRootRecoveryFleetExternalAnchorProperties
                    externalAnchor) {

        /** Prefix shared by Spring configuration, deployment examples, and startup tests. */
        public static final String PREFIX =
                "gateway.testing.stability-physical-attempt.provider-inventory";
        /** Nested prefix for physical provider-inventory external non-equivocation. */
        public static final String EXTERNAL_ANCHOR_PREFIX = PREFIX + ".external-anchor";

        /** Applies finite defaults and rejects incomplete or unsafe enabled policy. */
        public Properties {
            enabled = Boolean.TRUE.equals(enabled);
            trustDomain = normalized(trustDomain);
            scopeId = normalized(scopeId);
            cohortId = normalized(cohortId);
            providerProtocolVersion = defaulted(providerProtocolVersion,
                    "bloge.physical-attempt-provider.v1");
            acceptedPolicyFingerprints = normalized(acceptedPolicyFingerprints);
            signatureThreshold = defaulted(signatureThreshold, 0);
            authorityKeysJson = defaultedJson(authorityKeysJson);
            publicationUri = normalized(publicationUri);
            refreshIntervalSeconds = defaulted(refreshIntervalSeconds, 30L);
            requestTimeoutMillis = defaulted(requestTimeoutMillis, 3_000L);
            maximumSnapshotAgeSeconds = defaulted(maximumSnapshotAgeSeconds, 60L);
            allowInsecureLoopback = Boolean.TRUE.equals(allowInsecureLoopback);
            witnessDomain = normalized(witnessDomain);
            witnessSignatureThreshold = defaulted(witnessSignatureThreshold, 0);
            witnessAuthorityKeysJson = defaultedJson(witnessAuthorityKeysJson);
            replicaId = normalized(replicaId);
            artifactFingerprint = normalized(artifactFingerprint);
            heartbeatIntervalMillis = defaulted(heartbeatIntervalMillis, 5_000L);
            leaseDurationMillis = defaulted(leaseDurationMillis, 15_000L);
            recordRetentionSeconds = defaulted(recordRetentionSeconds, 86_400L);
            externalAnchor = externalAnchor == null
                    ? ExternalSequenceAnchorBootstrapRootRecoveryFleetExternalAnchorProperties
                    .disabled() : externalAnchor;
            try {
                ExpectedBinding binding = expectedBinding(trustDomain, scopeId, cohortId,
                        providerProtocolVersion, acceptedPolicyFingerprints);
                DynamicTestSuiteStabilityPhysicalAttemptProviderInventoryAuthority.Settings
                        source = sourceSettings(publicationUri, refreshIntervalSeconds,
                        requestTimeoutMillis, maximumSnapshotAgeSeconds,
                        allowInsecureLoopback);
                DatabaseTestSuiteStabilityPhysicalAttemptProviderInventoryCohortRepository
                        .LocalPolicy local = localPolicy(replicaId, artifactFingerprint,
                        leaseDurationMillis, recordRetentionSeconds);
                Duration heartbeat = Duration.ofMillis(heartbeatIntervalMillis);
                if (!enabled || signatureThreshold < 1 || signatureThreshold > 32
                        || witnessSignatureThreshold < 1
                        || witnessSignatureThreshold > 32
                        || binding.trustDomain().equals(witnessDomain)
                        || heartbeat.compareTo(Duration.ofMillis(250)) < 0
                        || heartbeat.multipliedBy(2).compareTo(
                        local.leaseDuration()) > 0
                        || source.maximumSnapshotAge().compareTo(
                        source.refreshInterval().plus(source.requestTimeout())) < 0) {
                    throw invalid();
                }
            } catch (Exception rejected) {
                throw invalid();
            }
        }

        private ExpectedBinding expectedBinding() {
            return expectedBinding(trustDomain, scopeId, cohortId,
                    providerProtocolVersion, acceptedPolicyFingerprints);
        }

        private DynamicTestSuiteStabilityPhysicalAttemptProviderInventoryAuthority.Settings
                sourceSettings() {
            return sourceSettings(publicationUri, refreshIntervalSeconds,
                    requestTimeoutMillis, maximumSnapshotAgeSeconds,
                    allowInsecureLoopback);
        }

        private DatabaseTestSuiteStabilityPhysicalAttemptProviderInventoryCohortRepository
                .LocalPolicy localPolicy() {
            return localPolicy(replicaId, artifactFingerprint, leaseDurationMillis,
                    recordRetentionSeconds);
        }

        private Duration heartbeatInterval() {
            return Duration.ofMillis(heartbeatIntervalMillis);
        }

        private List<AuthorityKey> deploymentKeys(ObjectMapper objectMapper) {
            try {
                return ConfiguredTestSuiteStabilityServingInventoryAuthority.parseKeys(
                        objectMapper, authorityKeysJson);
            } catch (Exception rejected) {
                throw invalid();
            }
        }

        private List<AuthorityKey> witnessKeys(ObjectMapper objectMapper) {
            try {
                return ConfiguredTestSuiteStabilityServingInventoryAuthority.parseKeys(
                        objectMapper, witnessAuthorityKeysJson);
            } catch (Exception rejected) {
                throw invalid();
            }
        }

        private static ExpectedBinding expectedBinding(
                String trustDomain,
                String scopeId,
                String cohortId,
                String providerProtocolVersion,
                String acceptedPolicyFingerprints) {
            Set<String> policies =
                    ConfiguredTestSuiteStabilityServingInventoryAuthority.parsePolicies(
                            acceptedPolicyFingerprints);
            return new ExpectedBinding(trustDomain, scopeId, cohortId,
                    providerProtocolVersion, policies);
        }

        private static DynamicTestSuiteStabilityPhysicalAttemptProviderInventoryAuthority.Settings
                sourceSettings(
                String publicationUri,
                long refreshIntervalSeconds,
                long requestTimeoutMillis,
                long maximumSnapshotAgeSeconds,
                boolean allowInsecureLoopback) {
            URI uri;
            try {
                uri = URI.create(publicationUri);
            } catch (RuntimeException invalid) {
                throw invalid();
            }
            return new DynamicTestSuiteStabilityPhysicalAttemptProviderInventoryAuthority.Settings(
                    uri, Duration.ofSeconds(refreshIntervalSeconds),
                    Duration.ofMillis(requestTimeoutMillis),
                    Duration.ofSeconds(maximumSnapshotAgeSeconds),
                    allowInsecureLoopback).validated();
        }

        private static DatabaseTestSuiteStabilityPhysicalAttemptProviderInventoryCohortRepository
                .LocalPolicy localPolicy(
                String replicaId,
                String artifactFingerprint,
                long leaseDurationMillis,
                long recordRetentionSeconds) {
            return new DatabaseTestSuiteStabilityPhysicalAttemptProviderInventoryCohortRepository
                    .LocalPolicy(replicaId, artifactFingerprint,
                    ToolStudioResourceGatewayProtocol.VERSION,
                    Duration.ofMillis(leaseDurationMillis),
                    Duration.ofSeconds(recordRetentionSeconds)).validated();
        }

        private static IllegalArgumentException invalid() {
            return new IllegalArgumentException(
                    "Physical provider-inventory runtime configuration is invalid");
        }

        private static String normalized(String value) {
            return value == null ? "" : value.trim();
        }

        private static String defaulted(String value, String defaultValue) {
            String normalized = normalized(value);
            return normalized.isEmpty() ? defaultValue : normalized;
        }

        private static String defaultedJson(String value) {
            String normalized = normalized(value);
            return normalized.isEmpty() ? "[]" : normalized;
        }

        private static int defaulted(Integer value, int defaultValue) {
            return value == null ? defaultValue : value;
        }

        private static long defaulted(Long value, long defaultValue) {
            return value == null ? defaultValue : value;
        }
    }

    /** Successful stateless profile preflight required by every stateful bean. */
    record ValidatedRuntimeConfiguration(boolean staging) {
    }

    private static TestSuiteStabilityPhysicalAttemptProviderInventoryExternalSequenceAnchor
            externalAnchor(
            ObjectProvider<
                    TestSuiteStabilityPhysicalAttemptProviderInventoryExternalSequenceAnchor>
                    anchors,
            ExternalSequenceAnchorBootstrapRootRecoveryFleetExternalAnchorProperties properties,
            Environment environment) {
        List<TestSuiteStabilityPhysicalAttemptProviderInventoryExternalSequenceAnchor> configured =
                anchors.orderedStream().toList();
        if ((properties.enabled() && configured.size() != 1)
                || (!properties.enabled() && !configured.isEmpty())) {
            throw new IllegalStateException(
                    "Physical provider-inventory non-equivocation requires exactly one anchor");
        }
        if (!properties.enabled()) {
            return null;
        }
        var result = configured.getFirst();
        TestSuiteStabilityExternalSequenceAnchor.Descriptor descriptor = result.descriptor();
        boolean byzantineRequired = properties.minimumFaults() > 0
                || environment.acceptsProfiles(Profiles.of("staging"));
        if (!descriptor.available() || !descriptor.externallyDurable()
                || !descriptor.challengeBound()
                || byzantineRequired && !descriptor.byzantineQuorum()) {
            throw new IllegalStateException(
                    "Physical provider-inventory external anchor is unavailable or unsafe");
        }
        return result;
    }
}
