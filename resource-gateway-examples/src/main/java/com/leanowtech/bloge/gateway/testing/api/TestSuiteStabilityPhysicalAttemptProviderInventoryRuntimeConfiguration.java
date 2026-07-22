package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.ToolStudioResourceGatewayProtocol;
import com.leanowtech.bloge.gateway.testing.api.ConfiguredTestSuiteStabilityPhysicalAttemptProviderInventoryAuthority.ExpectedBinding;
import com.leanowtech.bloge.gateway.testing.api.ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseTestSuiteStabilityPhysicalAttemptProviderInventoryCohortRepository;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseTestSuiteStabilityPhysicalAttemptProviderInventoryPublicationFloor;
import com.leanowtech.bloge.gateway.testing.persistence.TestRuntimeDatabase;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Strict Spring composition root for dynamic physical provider-inventory admission.
 *
 * <p>The runtime is physically absent in production and remains absent in test/staging unless
 * explicitly enabled. Enabling requires one installed-adapter catalog, an HTTPS signed ACTIVE
 * publication, independent deployment and witness quorums, a durable database anti-rollback
 * floor, and a database-clock exact replica cohort. No unsigned inventory, empty catalog, static
 * fallback, local expected-replica list, or permissive resolver is created.</p>
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

    /** Persists publication and independent witness chain heads before local publication. */
    @Bean
    TestSuiteStabilityPhysicalAttemptProviderInventoryPublicationFloor
            testSuiteStabilityPhysicalAttemptProviderInventoryPublicationFloor(
            TestRuntimeDatabase database,
            ObjectMapper objectMapper,
            Properties properties) {
        return new DatabaseTestSuiteStabilityPhysicalAttemptProviderInventoryPublicationFloor(
                database.jdbc(), objectMapper, properties.scopeId(),
                database.transactionManager());
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
            Long recordRetentionSeconds) {

        /** Prefix shared by Spring configuration, deployment examples, and startup tests. */
        public static final String PREFIX =
                "gateway.testing.stability-physical-attempt.provider-inventory";

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
}
