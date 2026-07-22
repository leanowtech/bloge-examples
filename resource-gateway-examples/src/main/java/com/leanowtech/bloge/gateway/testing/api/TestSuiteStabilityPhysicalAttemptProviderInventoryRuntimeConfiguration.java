package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.ToolStudioResourceGatewayProtocol;
import com.leanowtech.bloge.gateway.testing.api.ConfiguredTestSuiteStabilityPhysicalAttemptProviderInventoryAuthority.ExpectedBinding;
import com.leanowtech.bloge.gateway.testing.api.ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseTestSuiteStabilityPhysicalAttemptProviderInventoryCohortRepository;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseTestSuiteStabilityPhysicalAttemptProviderInventoryPublicationFloor;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseTestSuiteStabilityPhysicalAttemptProviderInventoryTrustRootFloor;
import com.leanowtech.bloge.gateway.testing.persistence.TestRuntimeDatabase;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
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
        var roots = properties.trustRoots();
        if (staging && (properties.allowInsecureLoopback()
                || !roots.enabled() || !roots.required()
                || roots.allowInsecureLoopback()
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
     * Strictly parses and freezes managed bootstrap-root trust before any table or remote fetch.
     */
    @Bean
    @ConditionalOnProperty(prefix = ManagedTrustRootProperties.PREFIX,
            name = "enabled", havingValue = "true")
    ValidatedManagedTrustRoots physicalProviderInventoryManagedTrustRootPreflight(
            ObjectMapper objectMapper,
            Properties properties,
            ValidatedRuntimeConfiguration validated) {
        Objects.requireNonNull(validated, "validated");
        try {
            ManagedTrustRootProperties roots = properties.trustRoots();
            ObjectMapper strict = Objects.requireNonNull(objectMapper, "objectMapper").copy()
                    .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
            List<AuthorityKey> deploymentRoots =
                    ConfiguredTestSuiteStabilityServingInventoryAuthority.parseKeys(
                            strict, roots.deploymentRootAuthorityKeysJson());
            List<AuthorityKey> witnessRoots =
                    ConfiguredTestSuiteStabilityServingInventoryAuthority.parseKeys(
                            strict, roots.witnessRootAuthorityKeysJson());
            ConfiguredTestSuiteStabilityServingInventoryAuthority.indexedKeys(
                    deploymentRoots, roots.deploymentRootSignatureThreshold());
            ConfiguredTestSuiteStabilityServingInventoryAuthority.indexedKeys(
                    witnessRoots, roots.witnessRootSignatureThreshold());
            if (!ConfiguredTestSuiteStabilityPhysicalAttemptProviderInventoryTrustRootAuthority
                    .independentAuthorities(deploymentRoots, witnessRoots)
                    || properties.sourceSettings().publicationUri().equals(
                    roots.settings().publicationUri())) {
                throw Properties.invalid();
            }
            var binding = new
                    ConfiguredTestSuiteStabilityPhysicalAttemptProviderInventoryTrustRootAuthority
                    .ExpectedBinding(properties.scopeId(), roots.trustRootSetId(),
                    ToolStudioResourceGatewayProtocol.VERSION,
                    roots.deploymentRootDomain(), roots.witnessRootDomain());
            Set<String> policies =
                    ConfiguredTestSuiteStabilityServingInventoryAuthority.parsePolicies(
                            roots.acceptedPolicyFingerprints());
            return new ValidatedManagedTrustRoots(binding, policies,
                    roots.deploymentRootSignatureThreshold(), deploymentRoots,
                    roots.witnessRootSignatureThreshold(), witnessRoots, roots.settings());
        } catch (RuntimeException | java.security.GeneralSecurityException
                 | java.io.IOException rejected) {
            throw Properties.invalid();
        }
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

    /** Creates the local root floor and externally anchors it before local advancement. */
    @Bean
    @ConditionalOnProperty(prefix = ManagedTrustRootProperties.PREFIX,
            name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean(
            TestSuiteStabilityPhysicalAttemptProviderInventoryTrustRootFloor.class)
    TestSuiteStabilityPhysicalAttemptProviderInventoryTrustRootFloor
            testSuiteStabilityPhysicalAttemptProviderInventoryTrustRootFloor(
            TestRuntimeDatabase database,
            ObjectMapper objectMapper,
            Properties properties,
            Environment environment,
            ValidatedManagedTrustRoots roots,
            ObjectProvider<
                    TestSuiteStabilityPhysicalAttemptProviderInventoryExternalSequenceAnchor>
                    externalAnchors) {
        var local = new
                DatabaseTestSuiteStabilityPhysicalAttemptProviderInventoryTrustRootFloor(
                database.jdbc(), objectMapper, properties.scopeId(),
                roots.binding().trustRootSetId(), database.transactionManager());
        local.init();
        TestSuiteStabilityPhysicalAttemptProviderInventoryExternalSequenceAnchor external =
                externalAnchor(externalAnchors, properties.externalAnchor(), environment);
        return external == null ? local : new
                ExternallyAnchoredTestSuiteStabilityPhysicalAttemptProviderInventoryTrustRootFloor(
                local, external);
    }

    /** Bootstraps and owns the restart-free atomic deployment/witness runtime-key source. */
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = ManagedTrustRootProperties.PREFIX,
            name = "enabled", havingValue = "true")
    DynamicTestSuiteStabilityPhysicalAttemptProviderInventoryTrustRootAuthority
            dynamicTestSuiteStabilityPhysicalAttemptProviderInventoryTrustRootAuthority(
            ObjectMapper objectMapper,
            TestSuiteStabilityPhysicalAttemptProviderInventoryTrustRootFloor floor,
            ValidatedManagedTrustRoots roots) {
        return new DynamicTestSuiteStabilityPhysicalAttemptProviderInventoryTrustRootAuthority(
                objectMapper, roots.binding(), roots.acceptedPolicies(),
                roots.deploymentRootSignatureThreshold(), roots.deploymentRootKeys(),
                roots.witnessRootSignatureThreshold(), roots.witnessRootKeys(), floor,
                roots.settings());
    }

    /** Bootstraps the inventory consumer with explicit static migration keys. */
    @Bean(name = "dynamicTestSuiteStabilityPhysicalAttemptProviderInventoryAuthority",
            destroyMethod = "close")
    @ConditionalOnProperty(prefix = ManagedTrustRootProperties.PREFIX,
            name = "enabled", havingValue = "false", matchIfMissing = true)
    DynamicTestSuiteStabilityPhysicalAttemptProviderInventoryAuthority
            staticKeyDynamicTestSuiteStabilityPhysicalAttemptProviderInventoryAuthority(
            ObjectMapper objectMapper,
            ObjectProvider<TestSuiteStabilityPhysicalAttemptRuntimeAdapterCatalog> catalogs,
            TestSuiteStabilityPhysicalAttemptProviderInventoryPublicationFloor floor,
            Properties properties) {
        Map<TestSuiteStabilityPhysicalAttemptProviderInventory.ProviderDeployment,
                TestSuiteStabilityPhysicalAttemptObservationAuthority> adapters =
                installedAdapters(catalogs);
        return new DynamicTestSuiteStabilityPhysicalAttemptProviderInventoryAuthority(
                objectMapper, properties.expectedBinding(), properties.signatureThreshold(),
                properties.deploymentKeys(objectMapper), adapters, floor,
                properties.witnessDomain(), properties.witnessSignatureThreshold(),
                properties.witnessKeys(objectMapper), properties.sourceSettings());
    }

    /** Bootstraps the inventory consumer from the exact current managed-root generation. */
    @Bean(name = "dynamicTestSuiteStabilityPhysicalAttemptProviderInventoryAuthority",
            destroyMethod = "close")
    @ConditionalOnProperty(prefix = ManagedTrustRootProperties.PREFIX,
            name = "enabled", havingValue = "true")
    DynamicTestSuiteStabilityPhysicalAttemptProviderInventoryAuthority
            managedKeyDynamicTestSuiteStabilityPhysicalAttemptProviderInventoryAuthority(
            ObjectMapper objectMapper,
            ObjectProvider<TestSuiteStabilityPhysicalAttemptRuntimeAdapterCatalog> catalogs,
            TestSuiteStabilityPhysicalAttemptProviderInventoryPublicationFloor floor,
            DynamicTestSuiteStabilityPhysicalAttemptProviderInventoryTrustRootAuthority roots,
            Properties properties) {
        return new DynamicTestSuiteStabilityPhysicalAttemptProviderInventoryAuthority(
                objectMapper, properties.expectedBinding(), installedAdapters(catalogs), floor,
                roots, properties.sourceSettings());
    }

    /** Exposes aggregate-only managed-root lifecycle and floor strength through Actuator. */
    @Bean
    @ConditionalOnBean(
            DynamicTestSuiteStabilityPhysicalAttemptProviderInventoryTrustRootAuthority.class)
    @ConditionalOnMissingBean(
            TestSuiteStabilityPhysicalAttemptProviderInventoryTrustRootHealth.class)
    TestSuiteStabilityPhysicalAttemptProviderInventoryTrustRootHealth
            testSuiteStabilityPhysicalAttemptProviderInventoryTrustRootHealth(
            DynamicTestSuiteStabilityPhysicalAttemptProviderInventoryTrustRootAuthority roots) {
        return new TestSuiteStabilityPhysicalAttemptProviderInventoryTrustRootHealth(roots);
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
     * @param trustRoots optional managed atomic runtime-key source
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
                    externalAnchor,
            @NestedConfigurationProperty
            ManagedTrustRootProperties trustRoots) {

        /** Prefix shared by Spring configuration, deployment examples, and startup tests. */
        public static final String PREFIX =
                "gateway.testing.stability-physical-attempt.provider-inventory";
        /** Nested prefix for physical provider-inventory external non-equivocation. */
        public static final String EXTERNAL_ANCHOR_PREFIX = PREFIX + ".external-anchor";

        /**
         * Backward-compatible constructor for explicit static-key migration configurations.
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
        public Properties(
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
                ExternalSequenceAnchorBootstrapRootRecoveryFleetExternalAnchorProperties
                        externalAnchor) {
            this(enabled, trustDomain, scopeId, cohortId, providerProtocolVersion,
                    acceptedPolicyFingerprints, signatureThreshold, authorityKeysJson,
                    publicationUri, refreshIntervalSeconds, requestTimeoutMillis,
                    maximumSnapshotAgeSeconds, allowInsecureLoopback, witnessDomain,
                    witnessSignatureThreshold, witnessAuthorityKeysJson, replicaId,
                    artifactFingerprint, heartbeatIntervalMillis, leaseDurationMillis,
                    recordRetentionSeconds, externalAnchor,
                    ManagedTrustRootProperties.disabled());
        }

        /** Applies finite defaults and rejects incomplete or unsafe enabled policy. */
        @ConstructorBinding
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
            trustRoots = trustRoots == null
                    ? ManagedTrustRootProperties.disabled() : trustRoots;
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
                boolean staticKeyMaterial = signatureThreshold != 0
                        || !"[]".equals(authorityKeysJson)
                        || witnessSignatureThreshold != 0
                        || !"[]".equals(witnessAuthorityKeysJson);
                boolean invalidStaticMode = !trustRoots.enabled()
                        && (signatureThreshold < 1 || signatureThreshold > 32
                        || witnessSignatureThreshold < 1
                        || witnessSignatureThreshold > 32
                        || "[]".equals(authorityKeysJson)
                        || "[]".equals(witnessAuthorityKeysJson));
                if (!enabled || trustRoots.enabled() && staticKeyMaterial
                        || invalidStaticMode
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

    /**
     * Public-only managed runtime-key source nested below physical provider inventory.
     *
     * @param enabled selects restart-free atomic dual-key generations instead of static keys
     * @param required rejects static-key migration mode; mandatory in staging
     * @param trustRootSetId stable managed deployment/witness key-set identity
     * @param acceptedPolicyFingerprints accepted signed root-rotation policy fingerprints
     * @param deploymentRootDomain deployment bootstrap-root trust domain
     * @param deploymentRootSignatureThreshold deployment bootstrap-root M-of-N threshold
     * @param deploymentRootAuthorityKeysJson public deployment bootstrap Ed25519 keys
     * @param witnessRootDomain independent witness bootstrap-root trust domain
     * @param witnessRootSignatureThreshold witness bootstrap-root M-of-N threshold
     * @param witnessRootAuthorityKeysJson public witness bootstrap Ed25519 keys
     * @param publicationUri strict HTTPS atomic dual-key publication endpoint
     * @param refreshIntervalSeconds fixed-delay refresh interval
     * @param requestTimeoutMillis bounded connect and request timeout
     * @param unknownKeyRefreshIntervalSeconds synchronous unknown-key refresh cooldown
     * @param maximumSnapshotAgeSeconds hard local root-source freshness fence
     * @param allowInsecureLoopback local-test-only HTTP loopback escape hatch
     */
    public record ManagedTrustRootProperties(
            Boolean enabled,
            Boolean required,
            String trustRootSetId,
            String acceptedPolicyFingerprints,
            String deploymentRootDomain,
            Integer deploymentRootSignatureThreshold,
            String deploymentRootAuthorityKeysJson,
            String witnessRootDomain,
            Integer witnessRootSignatureThreshold,
            String witnessRootAuthorityKeysJson,
            String publicationUri,
            Long refreshIntervalSeconds,
            Long requestTimeoutMillis,
            Long unknownKeyRefreshIntervalSeconds,
            Long maximumSnapshotAgeSeconds,
            Boolean allowInsecureLoopback) {

        /** Prefix shared by Spring profiles, deployment metadata, and conditional beans. */
        public static final String PREFIX = Properties.PREFIX + ".trust-roots";

        /** Applies finite defaults and rejects disabled, partial, or mixed root policy. */
        public ManagedTrustRootProperties {
            enabled = Boolean.TRUE.equals(enabled);
            required = Boolean.TRUE.equals(required);
            trustRootSetId = normalized(trustRootSetId);
            acceptedPolicyFingerprints = normalized(acceptedPolicyFingerprints);
            deploymentRootDomain = normalized(deploymentRootDomain);
            deploymentRootSignatureThreshold = defaulted(
                    deploymentRootSignatureThreshold, 0);
            deploymentRootAuthorityKeysJson = defaultedJson(
                    deploymentRootAuthorityKeysJson);
            witnessRootDomain = normalized(witnessRootDomain);
            witnessRootSignatureThreshold = defaulted(witnessRootSignatureThreshold, 0);
            witnessRootAuthorityKeysJson = defaultedJson(witnessRootAuthorityKeysJson);
            publicationUri = normalized(publicationUri);
            refreshIntervalSeconds = defaulted(refreshIntervalSeconds, 30L);
            requestTimeoutMillis = defaulted(requestTimeoutMillis, 3_000L);
            unknownKeyRefreshIntervalSeconds = defaulted(
                    unknownKeyRefreshIntervalSeconds, 5L);
            maximumSnapshotAgeSeconds = defaulted(maximumSnapshotAgeSeconds, 60L);
            allowInsecureLoopback = Boolean.TRUE.equals(allowInsecureLoopback);
            if (required && !enabled) {
                throw Properties.invalid();
            }
            boolean configured = !trustRootSetId.isBlank()
                    || !acceptedPolicyFingerprints.isBlank()
                    || !deploymentRootDomain.isBlank()
                    || deploymentRootSignatureThreshold != 0
                    || !"[]".equals(deploymentRootAuthorityKeysJson)
                    || !witnessRootDomain.isBlank()
                    || witnessRootSignatureThreshold != 0
                    || !"[]".equals(witnessRootAuthorityKeysJson)
                    || !publicationUri.isBlank() || allowInsecureLoopback;
            if (!enabled && configured) {
                throw Properties.invalid();
            }
            if (enabled && (trustRootSetId.isBlank()
                    || acceptedPolicyFingerprints.isBlank()
                    || deploymentRootDomain.isBlank()
                    || deploymentRootSignatureThreshold < 1
                    || deploymentRootSignatureThreshold > 32
                    || "[]".equals(deploymentRootAuthorityKeysJson)
                    || witnessRootDomain.isBlank()
                    || witnessRootSignatureThreshold < 1
                    || witnessRootSignatureThreshold > 32
                    || "[]".equals(witnessRootAuthorityKeysJson)
                    || deploymentRootDomain.equals(witnessRootDomain)
                    || publicationUri.isBlank())) {
                throw Properties.invalid();
            }
            if (enabled) {
                settings(publicationUri, refreshIntervalSeconds, requestTimeoutMillis,
                        unknownKeyRefreshIntervalSeconds, maximumSnapshotAgeSeconds,
                        allowInsecureLoopback);
            }
        }

        /**
         * Returns the canonical disabled managed-root policy.
         *
         * @return fully normalized disabled policy
         */
        public static ManagedTrustRootProperties disabled() {
            return new ManagedTrustRootProperties(false, false, "", "", "", 0,
                    "[]", "", 0, "[]", "", 30L, 3_000L, 5L, 60L, false);
        }

        private DynamicTestSuiteStabilityPhysicalAttemptProviderInventoryTrustRootAuthority
                .Settings settings() {
            return settings(publicationUri, refreshIntervalSeconds, requestTimeoutMillis,
                    unknownKeyRefreshIntervalSeconds, maximumSnapshotAgeSeconds,
                    allowInsecureLoopback);
        }

        private static DynamicTestSuiteStabilityPhysicalAttemptProviderInventoryTrustRootAuthority
                .Settings settings(
                String publicationUri,
                long refreshIntervalSeconds,
                long requestTimeoutMillis,
                long unknownKeyRefreshIntervalSeconds,
                long maximumSnapshotAgeSeconds,
                boolean allowInsecureLoopback) {
            URI uri;
            try {
                uri = URI.create(publicationUri);
            } catch (RuntimeException rejected) {
                throw Properties.invalid();
            }
            return new
                    DynamicTestSuiteStabilityPhysicalAttemptProviderInventoryTrustRootAuthority
                    .Settings(uri, Duration.ofSeconds(refreshIntervalSeconds),
                    Duration.ofMillis(requestTimeoutMillis),
                    Duration.ofSeconds(unknownKeyRefreshIntervalSeconds),
                    Duration.ofSeconds(maximumSnapshotAgeSeconds),
                    allowInsecureLoopback).validated();
        }

        private static String normalized(String value) {
            return Objects.requireNonNullElse(value, "").trim();
        }

        private static String defaultedJson(String value) {
            String normalized = normalized(value);
            return normalized.isEmpty() ? "[]" : normalized;
        }

        private static int defaulted(Integer value, int fallback) {
            return value == null ? fallback : value;
        }

        private static long defaulted(Long value, long fallback) {
            return value == null ? fallback : value;
        }
    }

    /** Successful stateless profile preflight required by every stateful bean. */
    record ValidatedRuntimeConfiguration(boolean staging) {
    }

    /** Immutable strictly parsed bootstrap-root policy consumed by all managed-root beans. */
    record ValidatedManagedTrustRoots(
            ConfiguredTestSuiteStabilityPhysicalAttemptProviderInventoryTrustRootAuthority
                    .ExpectedBinding binding,
            Set<String> acceptedPolicies,
            int deploymentRootSignatureThreshold,
            List<AuthorityKey> deploymentRootKeys,
            int witnessRootSignatureThreshold,
            List<AuthorityKey> witnessRootKeys,
            DynamicTestSuiteStabilityPhysicalAttemptProviderInventoryTrustRootAuthority.Settings
                    settings) {

        ValidatedManagedTrustRoots {
            binding = Objects.requireNonNull(binding, "binding");
            acceptedPolicies = Set.copyOf(acceptedPolicies);
            deploymentRootKeys = List.copyOf(deploymentRootKeys);
            witnessRootKeys = List.copyOf(witnessRootKeys);
            settings = Objects.requireNonNull(settings, "settings");
        }
    }

    private static Map<TestSuiteStabilityPhysicalAttemptProviderInventory.ProviderDeployment,
            TestSuiteStabilityPhysicalAttemptObservationAuthority> installedAdapters(
            ObjectProvider<TestSuiteStabilityPhysicalAttemptRuntimeAdapterCatalog> catalogs) {
        List<TestSuiteStabilityPhysicalAttemptRuntimeAdapterCatalog> installed =
                catalogs.orderedStream().toList();
        if (installed.size() != 1) {
            throw Properties.invalid();
        }
        return installed.getFirst().adapters();
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
