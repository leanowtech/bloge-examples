package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.ConfiguredExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority.AuthorityKey;
import com.leanowtech.bloge.gateway.testing.api.ConfiguredExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority.LaneResolver;
import com.leanowtech.bloge.gateway.testing.api.ConfiguredExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootAuthority.ExpectedBinding;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority.VerifiedBinding;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetRuntimeConfiguration.FleetProperties;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetRuntimeConfiguration.ValidatedFleetConfiguration;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublicationFloor;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootFloor;
import com.leanowtech.bloge.gateway.testing.persistence.TestRuntimeDatabase;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Strict test/staging composition for the dynamic witnessed recovery-fleet inventory authority.
 *
 * <p>The first bean performs only local validation: strict public-key JSON parsing, independent
 * deployment/witness trust checks, exact deployment/fleet binding, static-versus-managed key-mode
 * exclusion, and bounded transport policy. It must complete before either default durable floor
 * creates tables or either authority performs a remote bootstrap. In managed mode the root source
 * is constructed before the inventory consumer and Spring therefore closes the consumer first.</p>
 *
 * <p>This configuration is physically absent in production, disabled by default, and accepts no
 * signer private key or provider credential. A deployment may replace the default floor with one
 * custom durable implementation, but the lane resolver remains a caller-owned reviewed local
 * catalog and must be unique.</p>
 */
@Configuration(proxyBeanMethods = false)
@Profile("!production & (test | staging)")
@ConditionalOnProperty(prefix = FleetProperties.PREFIX, name = "enabled",
        havingValue = "true")
public class ExternalSequenceAnchorBootstrapRootRecoveryFleetDynamicInventoryConfiguration {

    /** Creates the profile-gated dynamic inventory composition. */
    public ExternalSequenceAnchorBootstrapRootRecoveryFleetDynamicInventoryConfiguration() {
    }

    /**
     * Supplies the default no-cache environment resolver unless the embedder provides a vault or
     * workload-identity backed resolver.
     *
     * @return stateless resolver accepting only {@code env:VARIABLE_NAME} references
     */
    @Bean
    @ConditionalOnMissingBean(RecoveryFleetPublicationTransport.SecretResolver.class)
    RecoveryFleetPublicationTransport.SecretResolver
            recoveryFleetPublicationTransportSecretResolver() {
        return new PinnedMutualTlsRecoveryFleetPublicationTransport.EnvironmentSecretResolver();
    }

    /**
     * Parses and freezes all public-only configuration before creating stateful resources.
     *
     * @param objectMapper application protocol mapper
     * @param fleet exact durable fleet topology
     * @param properties strict dynamic inventory policy
     * @param laneResolvers reviewed local runtime catalogs; exactly one is required
     * @param configured successful stateless fleet preflight token
     * @param environment active profile used to prohibit test-only transport in staging
     * @param secretResolvers deployment credential resolvers; exactly one is used when mTLS is on
     * @return validated public trust, binding, and refresh settings
     */
    @Bean
    @ConditionalOnProperty(prefix = DynamicInventoryProperties.PREFIX, name = "enabled",
            havingValue = "true")
    ValidatedDynamicInventoryConfiguration
            externalSequenceAnchorBootstrapRootRecoveryFleetDynamicInventoryPreflight(
            ObjectMapper objectMapper,
            FleetProperties fleet,
            DynamicInventoryProperties properties,
            ObjectProvider<LaneResolver> laneResolvers,
            ValidatedFleetConfiguration configured,
            Environment environment,
            ObjectProvider<ControlPlaneCertificateRotationRuntime> rotationRuntimes,
            ObjectProvider<RecoveryFleetPublicationTransport.SecretResolver> secretResolvers) {
        try {
            Objects.requireNonNull(configured, "configured");
            boolean staging = Objects.requireNonNull(environment, "environment")
                    .acceptsProfiles(Profiles.of("staging"));
            if (staging) {
                var anchor = properties.externalAnchor();
                if (properties.allowInsecureLoopback()
                        || properties.trustRoots().allowInsecureLoopback()
                        || !properties.transport().enabled()
                        || !properties.transport().required()
                        || !properties.trustRoots().transport().enabled()
                        || !properties.trustRoots().transport().required()
                        || anchor.allowInsecureLoopback()
                        || anchor.managedTrust().allowInsecureLoopback()
                        || anchor.managedTrust().bootstrapRoots().allowInsecureLoopback()
                        || !anchor.enabled() || !anchor.required()
                        || anchor.maximumFaults() < 1
                        || !anchor.managedTrust().enabled()
                        || !anchor.managedTrust().required()
                        || !anchor.managedTrust().bootstrapRoots().enabled()
                        || !anchor.managedTrust().bootstrapRoots().required()) {
                    throw DynamicInventoryProperties.invalid();
                }
            }
            if (properties.trustRoots().enabled()
                    && properties.transport().sharesClientIdentityWith(
                    properties.trustRoots().transport())) {
                throw DynamicInventoryProperties.invalid();
            }
            RecoveryFleetPublicationTransport.SecretResolver secretResolver =
                    secretResolver(secretResolvers, properties.transport().enabled()
                            || properties.trustRoots().transport().enabled());
            ControlPlaneCertificateRotationRuntime rotationRuntime =
                    rotationRuntimes.getIfAvailable();
            if (rotationRuntime == null && Boolean.TRUE.equals(environment.getProperty(
                    ControlPlaneCertificateRotationRuntimeProperties.PREFIX + ".enabled",
                    Boolean.class, false))) {
                throw DynamicInventoryProperties.invalid();
            }
            ObjectMapper strict = Objects.requireNonNull(objectMapper, "objectMapper").copy()
                    .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
            Set<String> policies = ConfiguredTestSuiteStabilityServingInventoryAuthority
                    .acceptedPolicies(ConfiguredTestSuiteStabilityServingInventoryAuthority
                            .parsePolicies(properties.acceptedPolicyFingerprints()));
            List<LaneResolver> resolvers = laneResolvers.orderedStream().toList();
            if (resolvers.size() != 1) {
                throw DynamicInventoryProperties.invalid();
            }
            ValidatedManagedTrustRoots managedTrustRoots = properties.trustRoots().enabled()
                    ? validateManagedTrustRoots(strict, fleet, properties, secretResolver,
                    rotationRuntime) : null;
            List<AuthorityKey> deploymentKeys = List.of();
            List<AuthorityKey> witnessKeys = List.of();
            if (managedTrustRoots == null) {
                deploymentKeys = parseKeys(strict, properties.authorityKeysJson());
                witnessKeys = parseKeys(strict, properties.witnessAuthorityKeysJson());
                ConfiguredTestSuiteStabilityServingInventoryAuthority.indexedKeys(
                        deploymentKeys.stream().map(AuthorityKey::delegate).toList(),
                        properties.signatureThreshold());
                ConfiguredTestSuiteStabilityServingInventoryAuthority.indexedKeys(
                        witnessKeys.stream().map(AuthorityKey::delegate).toList(),
                        properties.witnessSignatureThreshold());
                if (properties.trustDomain().equals(properties.witnessDomain())
                        || !DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority
                        .independentAuthorities(deploymentKeys, witnessKeys)) {
                    throw DynamicInventoryProperties.invalid();
                }
            }
            VerifiedBinding binding = new VerifiedBinding(
                    properties.deploymentScopeId(), fleet.fleetId(),
                    properties.artifactFingerprint(), fleet.partitionCount());
            var settings = new
                    DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority
                    .Settings(URI.create(properties.publicationUri()),
                    Duration.ofSeconds(properties.refreshIntervalSeconds()),
                    Duration.ofMillis(properties.requestTimeoutMillis()),
                    Duration.ofSeconds(properties.maximumSnapshotAgeSeconds()),
                    properties.allowInsecureLoopback()).validated();
            RecoveryFleetPublicationTransport transport = rotationRuntime == null
                    ? properties.transport().create(secretResolver)
                    : rotationRuntime.transport(
                    ControlPlaneCertificateRotationTargets.RECOVERY_FLEET_INVENTORY,
                    properties.transport());
            return new ValidatedDynamicInventoryConfiguration(
                    properties.trustDomain(), policies, properties.signatureThreshold(),
                    deploymentKeys, binding, properties.witnessDomain(),
                    properties.witnessSignatureThreshold(), witnessKeys, resolvers.getFirst(),
                    settings, transport, managedTrustRoots);
        } catch (Exception invalid) {
            throw DynamicInventoryProperties.invalid();
        }
    }

    private static ValidatedManagedTrustRoots validateManagedTrustRoots(
            ObjectMapper strict,
            FleetProperties fleet,
            DynamicInventoryProperties properties,
            RecoveryFleetPublicationTransport.SecretResolver secretResolver,
            ControlPlaneCertificateRotationRuntime rotationRuntime) throws Exception {
        ManagedTrustRootProperties roots = properties.trustRoots();
        List<AuthorityKey> deploymentRootKeys = parseKeys(
                strict, roots.deploymentRootAuthorityKeysJson());
        List<AuthorityKey> witnessRootKeys = parseKeys(
                strict, roots.witnessRootAuthorityKeysJson());
        ConfiguredTestSuiteStabilityServingInventoryAuthority.indexedKeys(
                deploymentRootKeys.stream().map(AuthorityKey::delegate).toList(),
                roots.deploymentRootSignatureThreshold());
        ConfiguredTestSuiteStabilityServingInventoryAuthority.indexedKeys(
                witnessRootKeys.stream().map(AuthorityKey::delegate).toList(),
                roots.witnessRootSignatureThreshold());
        if (!DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority
                .independentAuthorities(deploymentRootKeys, witnessRootKeys)) {
            throw DynamicInventoryProperties.invalid();
        }
        URI inventoryUri = URI.create(properties.publicationUri());
        URI rootUri = URI.create(roots.publicationUri());
        if (inventoryUri.equals(rootUri)) {
            throw DynamicInventoryProperties.invalid();
        }
        ExpectedBinding binding = new ExpectedBinding(
                properties.deploymentScopeId(), fleet.fleetId(), roots.trustRootSetId(),
                ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublication
                        .SCHEMA_VERSION,
                roots.deploymentRootDomain(), roots.witnessRootDomain());
        Set<String> policies = ConfiguredTestSuiteStabilityServingInventoryAuthority
                .acceptedPolicies(ConfiguredTestSuiteStabilityServingInventoryAuthority
                        .parsePolicies(roots.acceptedPolicyFingerprints()));
        var settings = new
                DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootAuthority
                .Settings(rootUri, Duration.ofSeconds(roots.refreshIntervalSeconds()),
                Duration.ofMillis(roots.requestTimeoutMillis()),
                Duration.ofSeconds(roots.unknownKeyRefreshIntervalSeconds()),
                Duration.ofSeconds(roots.maximumSnapshotAgeSeconds()),
                roots.allowInsecureLoopback()).validated();
        RecoveryFleetPublicationTransport transport = rotationRuntime == null
                ? roots.transport().create(secretResolver)
                : rotationRuntime.transport(ControlPlaneCertificateRotationTargets
                .RECOVERY_FLEET_INVENTORY_TRUST_ROOTS, roots.transport());
        return new ValidatedManagedTrustRoots(binding, policies,
                roots.deploymentRootSignatureThreshold(), deploymentRootKeys,
                roots.witnessRootSignatureThreshold(), witnessRootKeys, settings,
                transport);
    }

    /**
     * Creates the built-in challenge-bound notary quorum unless the embedder supplies one
     * recovery-fleet-domain adapter.
     *
     * @param objectMapper canonical protocol mapper
     * @param environment active profile and managed trust property source
     * @param database isolated durable testing database
     * @param properties strict dynamic inventory and external anchor policy
     * @param validated successful public-only preflight
     * @param secretResolver deployment control-plane credential resolver
     * @return domain-isolated external sequence authority
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(
            prefix = ExternalSequenceAnchorBootstrapRootRecoveryFleetExternalAnchorProperties
                    .PREFIX,
            name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean(
            ExternalSequenceAnchorBootstrapRootRecoveryFleetExternalSequenceAnchor.class)
    ExternalSequenceAnchorBootstrapRootRecoveryFleetExternalSequenceAnchor
            externalSequenceAnchorBootstrapRootRecoveryFleetExternalSequenceAnchor(
            ObjectMapper objectMapper,
            Environment environment,
            TestRuntimeDatabase database,
            DynamicInventoryProperties properties,
            ValidatedDynamicInventoryConfiguration validated,
            ControlPlaneHttpTransport.SecretResolver secretResolver,
            ControlPlaneCertificateRotationRuntime rotationRuntime) {
        Objects.requireNonNull(validated, "validated");
        var anchor = properties.externalAnchor();
        int profileMinimumFaults = environment.acceptsProfiles(Profiles.of("staging")) ? 1 : 0;
        if (anchor.maximumFaults() < Math.max(
                profileMinimumFaults, anchor.minimumFaults())) {
            throw DynamicInventoryProperties.invalid();
        }
        TestSuiteStabilityExternalSequenceAnchor shared =
                TestRuntimeConfiguration.buildExternalSequenceAnchor(
                        objectMapper, environment, database,
                        ExternalSequenceAnchorBootstrapRootRecoveryFleetExternalAnchorProperties
                                .PREFIX,
                        "recovery-fleet inventory", properties.deploymentScopeId(),
                        anchor.trustDomain(), anchor.anchorSetId(), anchor.signatureThreshold(),
                        anchor.maximumFaults(), anchor.authorityKeysJson(), anchor.endpointsJson(),
                        anchor.requestTimeoutMillis(), anchor.clockSkewSeconds(),
                        anchor.maximumReceiptLifetimeSeconds(),
                        anchor.allowInsecureLoopback(), secretResolver, rotationRuntime);
        return ExternalSequenceAnchorBootstrapRootRecoveryFleetExternalSequenceAnchor.adapt(
                shared);
    }

    /** Exposes endpoint-, stream-, fingerprint-, authority-, and key-free notary health. */
    @Bean
    @ConditionalOnMissingBean(
            ExternalSequenceAnchorBootstrapRootRecoveryFleetExternalSequenceAnchorHealth.class)
    @ConditionalOnBean(
            ExternalSequenceAnchorBootstrapRootRecoveryFleetExternalSequenceAnchor.class)
    ExternalSequenceAnchorBootstrapRootRecoveryFleetExternalSequenceAnchorHealth
            externalSequenceAnchorBootstrapRootRecoveryFleetExternalSequenceAnchorHealth(
            ExternalSequenceAnchorBootstrapRootRecoveryFleetExternalSequenceAnchor anchor) {
        return new ExternalSequenceAnchorBootstrapRootRecoveryFleetExternalSequenceAnchorHealth(
                anchor);
    }

    /** Creates the local floor and optionally wraps it with external-first non-equivocation. */
    @Bean
    @ConditionalOnProperty(prefix = DynamicInventoryProperties.PREFIX, name = "enabled",
            havingValue = "true")
    @ConditionalOnMissingBean(
            ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublicationFloor.class)
    ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublicationFloor
            externalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublicationFloor(
            TestRuntimeDatabase database,
            ObjectMapper objectMapper,
            ValidatedDynamicInventoryConfiguration validated,
            DynamicInventoryProperties properties,
            Environment environment,
            ObjectProvider<
                    ExternalSequenceAnchorBootstrapRootRecoveryFleetExternalSequenceAnchor>
                    externalAnchors) {
        ExternalSequenceAnchorBootstrapRootRecoveryFleetExternalSequenceAnchor external =
                externalAnchor(externalAnchors, properties.externalAnchor(), environment);
        DatabaseExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublicationFloor local =
                new DatabaseExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublicationFloor(
                database.jdbc(), objectMapper, validated.binding().deploymentScopeId(),
                validated.binding().fleetId(), database.transactionManager());
        local.init();
        return external == null ? local : new
                ExternallyAnchoredExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublicationFloor(
                objectMapper, local, external);
    }

    /** Creates the managed root floor and optionally adds the same external-first authority. */
    @Bean
    @ConditionalOnProperty(prefix = ManagedTrustRootProperties.PREFIX, name = "enabled",
            havingValue = "true")
    @ConditionalOnMissingBean(
            ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootFloor.class)
    ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootFloor
            externalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootFloor(
            TestRuntimeDatabase database,
            ObjectMapper objectMapper,
            ValidatedDynamicInventoryConfiguration validated,
            DynamicInventoryProperties properties,
            Environment environment,
            ObjectProvider<
                    ExternalSequenceAnchorBootstrapRootRecoveryFleetExternalSequenceAnchor>
                    externalAnchors) {
        ValidatedManagedTrustRoots roots = validated.requiredManagedTrustRoots();
        ExternalSequenceAnchorBootstrapRootRecoveryFleetExternalSequenceAnchor external =
                externalAnchor(externalAnchors, properties.externalAnchor(), environment);
        DatabaseExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootFloor local = new
                DatabaseExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootFloor(
                database.jdbc(), objectMapper, validated.binding().deploymentScopeId(),
                validated.binding().fleetId(), roots.binding().trustRootSetId(),
                database.transactionManager());
        local.init();
        return external == null ? local : new
                ExternallyAnchoredExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootFloor(
                local, external);
    }

    /** Bootstraps and owns the atomic dual runtime-key source used by managed inventory mode. */
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = ManagedTrustRootProperties.PREFIX, name = "enabled",
            havingValue = "true")
    DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootAuthority
            externalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootAuthority(
            ObjectMapper objectMapper,
            ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootFloor floor,
            ValidatedDynamicInventoryConfiguration validated) {
        ValidatedManagedTrustRoots roots = validated.requiredManagedTrustRoots();
        return new
                DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootAuthority(
                objectMapper, roots.binding(), roots.acceptedPolicies(),
                roots.deploymentRootSignatureThreshold(), roots.deploymentRootKeys(),
                roots.witnessRootSignatureThreshold(), roots.witnessRootKeys(), floor,
                roots.settings(), roots.transport());
    }

    /**
     * Bootstraps and owns the single dynamic authority used by the fleet runtime.
     *
     * @param objectMapper application protocol mapper
     * @param floor unique durable publication and witness floor
     * @param validated successful public-only dynamic configuration preflight
     * @return bootstrapped dynamic authority and fleet inventory
     */
    @Bean(name = "externalSequenceAnchorBootstrapRootRecoveryFleetDynamicInventoryAuthority",
            destroyMethod = "close")
    @ConditionalOnProperty(prefix = DynamicInventoryProperties.PREFIX, name = "enabled",
            havingValue = "true")
    @ConditionalOnProperty(prefix = ManagedTrustRootProperties.PREFIX, name = "enabled",
            havingValue = "false", matchIfMissing = true)
    DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority
            externalSequenceAnchorBootstrapRootRecoveryFleetStaticKeyDynamicInventoryAuthority(
            ObjectMapper objectMapper,
            ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublicationFloor floor,
            ValidatedDynamicInventoryConfiguration validated) {
        return new DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority(
                objectMapper, validated.trustDomain(), validated.acceptedPolicies(),
                validated.signatureThreshold(), validated.authorityKeys(), validated.binding(),
                validated.laneResolver(), floor, validated.witnessDomain(),
                validated.witnessSignatureThreshold(), validated.witnessKeys(),
                validated.settings(), validated.transport());
    }

    /** Bootstraps the inventory consumer from one exact managed dual-key generation. */
    @Bean(name = "externalSequenceAnchorBootstrapRootRecoveryFleetDynamicInventoryAuthority",
            destroyMethod = "close")
    @ConditionalOnProperty(prefix = DynamicInventoryProperties.PREFIX, name = "enabled",
            havingValue = "true")
    @ConditionalOnProperty(prefix = ManagedTrustRootProperties.PREFIX, name = "enabled",
            havingValue = "true")
    DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority
            externalSequenceAnchorBootstrapRootRecoveryFleetManagedKeyDynamicInventoryAuthority(
            ObjectMapper objectMapper,
            ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublicationFloor floor,
            DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootAuthority
                    trustRoots,
            ValidatedDynamicInventoryConfiguration validated) {
        return new DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority(
                objectMapper, validated.acceptedPolicies(), validated.binding(),
                validated.laneResolver(), floor, trustRoots, validated.settings(),
                validated.transport());
    }

    /** Exposes aggregate-only managed-root readiness without source or key identities. */
    @Bean
    @ConditionalOnProperty(prefix = ManagedTrustRootProperties.PREFIX, name = "enabled",
            havingValue = "true")
    @ConditionalOnMissingBean(
            ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootHealth.class)
    ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootHealth
            externalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootHealth(
            DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootAuthority
                    authority) {
        return new ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootHealth(
                authority);
    }

    /**
     * Exposes aggregate-only dynamic inventory readiness independent of configuration order.
     *
     * @param authority bootstrapped dynamic inventory authority
     * @param worker successfully preflighted fleet runtime lifecycle gate
     * @return aggregate signed-inventory health without trust or topology identifiers
     */
    @Bean
    @ConditionalOnProperty(prefix = DynamicInventoryProperties.PREFIX, name = "enabled",
            havingValue = "true")
    @ConditionalOnMissingBean(
            ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryHealth.class)
    ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryHealth
            externalSequenceAnchorBootstrapRootRecoveryFleetDynamicInventoryHealth(
            DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority authority,
            ExternalSequenceAnchorBootstrapRootRecoveryFleetWorker worker) {
        Objects.requireNonNull(worker, "worker");
        return new ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryHealth(authority);
    }

    private static List<AuthorityKey> parseKeys(ObjectMapper mapper, String json)
            throws Exception {
        return ConfiguredTestSuiteStabilityServingInventoryAuthority.parseKeys(mapper, json)
                .stream().map(key -> new AuthorityKey(key.authorityId(), key.keyId(),
                        key.publicKey(), key.notBefore(), key.expiresAt(), key.enabled(),
                        key.revoked())).toList();
    }

    private static RecoveryFleetPublicationTransport.SecretResolver secretResolver(
            ObjectProvider<RecoveryFleetPublicationTransport.SecretResolver> providers,
            boolean required) {
        List<RecoveryFleetPublicationTransport.SecretResolver> configured =
                providers.orderedStream().toList();
        if (required && configured.size() != 1) {
            throw DynamicInventoryProperties.invalid();
        }
        return configured.size() == 1 ? configured.getFirst() : null;
    }

    private static ExternalSequenceAnchorBootstrapRootRecoveryFleetExternalSequenceAnchor
            externalAnchor(
            ObjectProvider<
                    ExternalSequenceAnchorBootstrapRootRecoveryFleetExternalSequenceAnchor>
                    anchors,
            ExternalSequenceAnchorBootstrapRootRecoveryFleetExternalAnchorProperties properties,
            Environment environment) {
        List<ExternalSequenceAnchorBootstrapRootRecoveryFleetExternalSequenceAnchor> configured =
                anchors.orderedStream().toList();
        if ((properties.enabled() && configured.size() != 1)
                || (!properties.enabled() && !configured.isEmpty())) {
            throw new IllegalStateException(
                    "Recovery-fleet external non-equivocation requires exactly one anchor");
        }
        if (!properties.enabled()) {
            return null;
        }
        ExternalSequenceAnchorBootstrapRootRecoveryFleetExternalSequenceAnchor result =
                configured.getFirst();
        TestSuiteStabilityExternalSequenceAnchor.Descriptor descriptor = result.descriptor();
        boolean byzantineRequired = properties.minimumFaults() > 0
                || environment.acceptsProfiles(Profiles.of("staging"));
        if (!descriptor.available() || !descriptor.externallyDurable()
                || !descriptor.challengeBound()
                || byzantineRequired && !descriptor.byzantineQuorum()) {
            throw new IllegalStateException(
                    "Recovery-fleet external non-equivocation anchor is unavailable or unsafe");
        }
        return result;
    }

    record ValidatedDynamicInventoryConfiguration(
            String trustDomain,
            Set<String> acceptedPolicies,
            int signatureThreshold,
            List<AuthorityKey> authorityKeys,
            VerifiedBinding binding,
            String witnessDomain,
            int witnessSignatureThreshold,
            List<AuthorityKey> witnessKeys,
            LaneResolver laneResolver,
            DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority.Settings
                    settings,
            RecoveryFleetPublicationTransport transport,
            ValidatedManagedTrustRoots managedTrustRoots) {

        ValidatedDynamicInventoryConfiguration {
            trustDomain = Objects.requireNonNull(trustDomain, "trustDomain");
            acceptedPolicies = Set.copyOf(acceptedPolicies);
            authorityKeys = List.copyOf(authorityKeys);
            binding = Objects.requireNonNull(binding, "binding");
            witnessDomain = Objects.requireNonNull(witnessDomain, "witnessDomain");
            witnessKeys = List.copyOf(witnessKeys);
            laneResolver = Objects.requireNonNull(laneResolver, "laneResolver");
            settings = Objects.requireNonNull(settings, "settings");
            transport = Objects.requireNonNull(transport, "transport");
        }

        private ValidatedManagedTrustRoots requiredManagedTrustRoots() {
            return Objects.requireNonNull(managedTrustRoots,
                    "validated managed recovery-fleet inventory trust roots");
        }
    }

    record ValidatedManagedTrustRoots(
            ExpectedBinding binding,
            Set<String> acceptedPolicies,
            int deploymentRootSignatureThreshold,
            List<AuthorityKey> deploymentRootKeys,
            int witnessRootSignatureThreshold,
            List<AuthorityKey> witnessRootKeys,
            DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootAuthority
                    .Settings settings,
            RecoveryFleetPublicationTransport transport) {

        ValidatedManagedTrustRoots {
            binding = Objects.requireNonNull(binding, "binding");
            acceptedPolicies = Set.copyOf(acceptedPolicies);
            deploymentRootKeys = List.copyOf(deploymentRootKeys);
            witnessRootKeys = List.copyOf(witnessRootKeys);
            settings = Objects.requireNonNull(settings, "settings");
            transport = Objects.requireNonNull(transport, "transport");
        }
    }

    /**
     * Public-only dynamic recovery-fleet inventory configuration.
     *
     * @param enabled explicitly creates the built-in dynamic authority
     * @param required rejects static or non-witnessed inventory in the final fleet preflight
     * @param deploymentScopeId stable tenant/environment deployment scope
     * @param artifactFingerprint exact local executable artifact SHA-256
     * @param trustDomain deployment inventory/publication trust domain
     * @param acceptedPolicyFingerprints comma-separated accepted policy SHA-256 values
     * @param signatureThreshold required distinct deployment-authority signatures
     * @param authorityKeysJson strict public Ed25519 deployment-key array
     * @param publicationUri HTTPS witnessed publication endpoint
     * @param refreshIntervalSeconds fixed-delay refresh interval
     * @param requestTimeoutMillis bounded connect/request timeout
     * @param maximumSnapshotAgeSeconds hard local source freshness fence
     * @param allowInsecureLoopback local-test-only HTTP loopback escape hatch
     * @param witnessDomain independent witness trust domain
     * @param witnessSignatureThreshold required distinct witness signatures
     * @param witnessAuthorityKeysJson strict public Ed25519 witness-key array
     * @param transport inventory-source server/client transport trust
     * @param externalAnchor optional externally witnessed dual-stream ordering authority
     * @param trustRoots optional managed atomic runtime-key source
     */
    @ConfigurationProperties(prefix = DynamicInventoryProperties.PREFIX,
            ignoreUnknownFields = false)
    public record DynamicInventoryProperties(
            Boolean enabled,
            Boolean required,
            String deploymentScopeId,
            String artifactFingerprint,
            String trustDomain,
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
            @NestedConfigurationProperty
            RecoveryFleetPublicationTransportProperties transport,
            @NestedConfigurationProperty
            ExternalSequenceAnchorBootstrapRootRecoveryFleetExternalAnchorProperties
                    externalAnchor,
            ManagedTrustRootProperties trustRoots) {

        /** Prefix shared by profile files, environment variables, and deployment documentation. */
        public static final String PREFIX =
                "gateway.testing.external-sequence-anchor."
                        + "bootstrap-root-recovery-fleet-dynamic-inventory";

        /** Applies bounded defaults and rejects disabled half-configuration. */
        public DynamicInventoryProperties {
            enabled = Boolean.TRUE.equals(enabled);
            required = Boolean.TRUE.equals(required);
            deploymentScopeId = normalized(deploymentScopeId);
            artifactFingerprint = normalized(artifactFingerprint);
            trustDomain = normalized(trustDomain);
            acceptedPolicyFingerprints = normalized(acceptedPolicyFingerprints);
            signatureThreshold = signatureThreshold == null ? 0 : signatureThreshold;
            authorityKeysJson = normalized(authorityKeysJson);
            publicationUri = normalized(publicationUri);
            refreshIntervalSeconds = defaulted(refreshIntervalSeconds, 30L);
            requestTimeoutMillis = defaulted(requestTimeoutMillis, 3_000L);
            maximumSnapshotAgeSeconds = defaulted(maximumSnapshotAgeSeconds, 60L);
            allowInsecureLoopback = Boolean.TRUE.equals(allowInsecureLoopback);
            witnessDomain = normalized(witnessDomain);
            witnessSignatureThreshold = witnessSignatureThreshold == null
                    ? 0 : witnessSignatureThreshold;
            witnessAuthorityKeysJson = normalized(witnessAuthorityKeysJson);
            transport = transport == null
                    ? RecoveryFleetPublicationTransportProperties.disabled() : transport;
            externalAnchor = externalAnchor == null
                    ? ExternalSequenceAnchorBootstrapRootRecoveryFleetExternalAnchorProperties
                    .disabled() : externalAnchor;
            trustRoots = trustRoots == null ? ManagedTrustRootProperties.disabled() : trustRoots;
            if (!enabled && hasSourceConfiguration(deploymentScopeId, artifactFingerprint,
                    trustDomain, acceptedPolicyFingerprints, signatureThreshold,
                    authorityKeysJson, publicationUri, allowInsecureLoopback, witnessDomain,
                    witnessSignatureThreshold, witnessAuthorityKeysJson,
                    transport.configured(),
                    externalAnchor.configured(),
                    trustRoots.configured())) {
                throw invalid();
            }
            if (enabled && (deploymentScopeId.isBlank() || artifactFingerprint.isBlank()
                    || acceptedPolicyFingerprints.isBlank() || publicationUri.isBlank())) {
                throw invalid();
            }
            boolean staticKeysPresent = !trustDomain.isBlank() || signatureThreshold != 0
                    || !emptyKeys(authorityKeysJson) || !witnessDomain.isBlank()
                    || witnessSignatureThreshold != 0 || !emptyKeys(witnessAuthorityKeysJson);
            if (enabled && trustRoots.enabled() && staticKeysPresent) {
                throw invalid();
            }
            if (enabled && !trustRoots.enabled()
                    && (trustDomain.isBlank() || signatureThreshold < 1
                    || emptyKeys(authorityKeysJson) || witnessDomain.isBlank()
                    || witnessSignatureThreshold < 1 || emptyKeys(witnessAuthorityKeysJson))) {
                throw invalid();
            }
        }

        private static boolean hasSourceConfiguration(
                String deploymentScopeId,
                String artifactFingerprint,
                String trustDomain,
                String acceptedPolicies,
                int threshold,
                String keys,
                String uri,
                boolean insecure,
                String witnessDomain,
                int witnessThreshold,
                String witnessKeys,
                boolean transportConfigured,
                boolean externalAnchorConfigured,
                boolean managedRootsConfigured) {
            return !deploymentScopeId.isBlank() || !artifactFingerprint.isBlank()
                    || !trustDomain.isBlank() || !acceptedPolicies.isBlank() || threshold != 0
                    || (!keys.isBlank() && !"[]".equals(keys)) || !uri.isBlank() || insecure
                    || !witnessDomain.isBlank() || witnessThreshold != 0
                    || (!witnessKeys.isBlank() && !"[]".equals(witnessKeys))
                    || transportConfigured
                    || externalAnchorConfigured
                    || managedRootsConfigured;
        }

        private static boolean emptyKeys(String value) {
            return value.isBlank() || "[]".equals(value);
        }

        private static long defaulted(Long value, long fallback) {
            return value == null ? fallback : value;
        }

        private static String normalized(String value) {
            return Objects.requireNonNullElse(value, "").trim();
        }

        static IllegalArgumentException invalid() {
            return new IllegalArgumentException(
                    "Dynamic recovery-fleet inventory configuration is invalid");
        }
    }

    /**
     * Public-only managed runtime-key source nested below dynamic inventory configuration.
     *
     * @param enabled selects managed atomic dual-key generations instead of static runtime keys
     * @param required rejects static-key mode; mandatory for staging fleets
     * @param trustRootSetId stable managed dual-key set identity
     * @param acceptedPolicyFingerprints comma-separated accepted root-rotation policies
     * @param deploymentRootDomain deployment bootstrap-root trust domain
     * @param deploymentRootSignatureThreshold deployment bootstrap-root M-of-N threshold
     * @param deploymentRootAuthorityKeysJson strict public Ed25519 bootstrap-key array
     * @param witnessRootDomain independent witness bootstrap-root trust domain
     * @param witnessRootSignatureThreshold witness bootstrap-root M-of-N threshold
     * @param witnessRootAuthorityKeysJson strict public Ed25519 bootstrap-key array
     * @param publicationUri HTTPS atomic dual-key publication endpoint
     * @param refreshIntervalSeconds fixed-delay root refresh interval
     * @param requestTimeoutMillis bounded connect/request timeout
     * @param unknownKeyRefreshIntervalSeconds cooldown for synchronous unknown-key refresh
     * @param maximumSnapshotAgeSeconds hard local root-source freshness fence
     * @param allowInsecureLoopback local-test-only HTTP loopback escape hatch
     * @param transport managed-root-source server/client transport trust
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
            Boolean allowInsecureLoopback,
            @NestedConfigurationProperty
            RecoveryFleetPublicationTransportProperties transport) {

        /** Nested prefix shared by profile files and deployment documentation. */
        public static final String PREFIX = DynamicInventoryProperties.PREFIX + ".trust-roots";

        /** Applies finite defaults and rejects disabled, partial, or mixed root configuration. */
        public ManagedTrustRootProperties {
            enabled = Boolean.TRUE.equals(enabled);
            required = Boolean.TRUE.equals(required);
            trustRootSetId = normalized(trustRootSetId);
            acceptedPolicyFingerprints = normalized(acceptedPolicyFingerprints);
            deploymentRootDomain = normalized(deploymentRootDomain);
            deploymentRootSignatureThreshold = deploymentRootSignatureThreshold == null
                    ? 0 : deploymentRootSignatureThreshold;
            deploymentRootAuthorityKeysJson = normalized(deploymentRootAuthorityKeysJson);
            witnessRootDomain = normalized(witnessRootDomain);
            witnessRootSignatureThreshold = witnessRootSignatureThreshold == null
                    ? 0 : witnessRootSignatureThreshold;
            witnessRootAuthorityKeysJson = normalized(witnessRootAuthorityKeysJson);
            publicationUri = normalized(publicationUri);
            refreshIntervalSeconds = defaulted(refreshIntervalSeconds, 30L);
            requestTimeoutMillis = defaulted(requestTimeoutMillis, 3_000L);
            unknownKeyRefreshIntervalSeconds = defaulted(
                    unknownKeyRefreshIntervalSeconds, 5L);
            maximumSnapshotAgeSeconds = defaulted(maximumSnapshotAgeSeconds, 60L);
            allowInsecureLoopback = Boolean.TRUE.equals(allowInsecureLoopback);
            transport = transport == null
                    ? RecoveryFleetPublicationTransportProperties.disabled() : transport;
            if (required && !enabled) {
                throw DynamicInventoryProperties.invalid();
            }
            if (!enabled && configured(trustRootSetId, acceptedPolicyFingerprints,
                    deploymentRootDomain, deploymentRootSignatureThreshold,
                    deploymentRootAuthorityKeysJson, witnessRootDomain,
                    witnessRootSignatureThreshold, witnessRootAuthorityKeysJson,
                    publicationUri, allowInsecureLoopback, transport.configured())) {
                throw DynamicInventoryProperties.invalid();
            }
            if (enabled && (trustRootSetId.isBlank()
                    || acceptedPolicyFingerprints.isBlank() || deploymentRootDomain.isBlank()
                    || deploymentRootSignatureThreshold < 1
                    || emptyKeys(deploymentRootAuthorityKeysJson)
                    || witnessRootDomain.isBlank() || witnessRootSignatureThreshold < 1
                    || emptyKeys(witnessRootAuthorityKeysJson)
                    || publicationUri.isBlank())) {
                throw DynamicInventoryProperties.invalid();
            }
        }

        private static ManagedTrustRootProperties disabled() {
            return new ManagedTrustRootProperties(false, false, "", "", "", 0, "[]",
                    "", 0, "[]", "", 30L, 3_000L, 5L, 60L, false,
                    RecoveryFleetPublicationTransportProperties.disabled());
        }

        private boolean configured() {
            return required || enabled || configured(trustRootSetId,
                    acceptedPolicyFingerprints, deploymentRootDomain,
                    deploymentRootSignatureThreshold, deploymentRootAuthorityKeysJson,
                    witnessRootDomain, witnessRootSignatureThreshold,
                    witnessRootAuthorityKeysJson, publicationUri, allowInsecureLoopback,
                    transport.configured());
        }

        private static boolean configured(
                String rootSetId,
                String policies,
                String deploymentDomain,
                int deploymentThreshold,
                String deploymentKeys,
                String witnessDomain,
                int witnessThreshold,
                String witnessKeys,
                String uri,
                boolean insecure,
                boolean transportConfigured) {
            return !rootSetId.isBlank() || !policies.isBlank()
                    || !deploymentDomain.isBlank() || deploymentThreshold != 0
                    || !emptyKeys(deploymentKeys) || !witnessDomain.isBlank()
                    || witnessThreshold != 0 || !emptyKeys(witnessKeys) || !uri.isBlank()
                    || insecure || transportConfigured;
        }

        private static boolean emptyKeys(String value) {
            return value.isBlank() || "[]".equals(value);
        }

        private static long defaulted(Long value, long fallback) {
            return value == null ? fallback : value;
        }

        private static String normalized(String value) {
            return Objects.requireNonNullElse(value, "").trim();
        }
    }
}
