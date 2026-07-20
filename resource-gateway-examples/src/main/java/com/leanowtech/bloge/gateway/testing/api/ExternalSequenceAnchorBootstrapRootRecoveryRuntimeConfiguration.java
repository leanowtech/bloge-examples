package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.ConfiguredExternalSequenceAnchorBootstrapRootTrustStore.ExpectedBinding;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootPublicationRuntimeConfiguration.Properties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Explicit Spring composition root for one bootstrap-root ceremony recovery lane.
 *
 * <p>The runtime is physically absent whenever a {@code production} profile is active and remains
 * inert in {@code test}/{@code staging} until explicitly enabled. It deliberately reuses the
 * publication runtime's exact durable journal and root-set binding: a recovered public bundle must
 * enter the same transactional publication outbox rather than stop at a process-local success.</p>
 *
 * <p>Configuration contains only public genesis and bounded local policy. An embedder must provide
 * an {@link ExternalSequenceAnchorBootstrapRootAuthorityResolver} that resolves approved signer
 * cohorts to opaque authority ports; this composition never binds private keys, HSM credentials,
 * provider endpoints, or authority inventories. It is one root-set lane, not a cross-root worker
 * platform, enterprise IAM boundary, HSM custody service, or provider cancellation mechanism.</p>
 */
@Configuration(proxyBeanMethods = false)
@Profile("!production & (test | staging)")
@ConditionalOnProperty(prefix = ExternalSequenceAnchorBootstrapRootRecoveryRuntimeConfiguration
        .RecoveryProperties.PREFIX, name = "enabled", havingValue = "true")
@EnableConfigurationProperties(
        ExternalSequenceAnchorBootstrapRootRecoveryRuntimeConfiguration
                .RecoveryProperties.class)
public class ExternalSequenceAnchorBootstrapRootRecoveryRuntimeConfiguration {

    /** Creates the profile-gated recovery composition root. */
    public ExternalSequenceAnchorBootstrapRootRecoveryRuntimeConfiguration() {
    }

    @Bean
    ValidatedRecoveryBinding externalSequenceAnchorBootstrapRootRecoveryBinding(
            ObjectMapper objectMapper,
            Environment environment,
            Properties publication,
            RecoveryProperties recovery) {
        try {
            requirePublication(publication);
            ExternalSequenceAnchorBootstrapRootGenesis genesis =
                    ExternalSequenceAnchorBootstrapRootGenesis.fromJson(
                            objectMapper, recovery.genesisJson());
            if (!publication.scopeId().equals(genesis.scopeId())
                    || !publication.rootSetId().equals(genesis.rootSetId())
                    || environment.acceptsProfiles(Profiles.of("staging"))
                    && genesis.maximumFaults() < 1) {
                throw RecoveryProperties.invalid();
            }
            var binding = new ExpectedBinding(publication.scopeId(), publication.rootSetId(),
                    genesis.trustDomain(), genesis.signatureThreshold(),
                    genesis.maximumFaults(), recovery.maximumRootLifetime(),
                    recovery.clockSkew(), recovery.minimumRemainingValidity(),
                    recovery.maximumTransitionCount());
            return new ValidatedRecoveryBinding(genesis, binding);
        } catch (RuntimeException invalid) {
            throw RecoveryProperties.invalid();
        }
    }

    /** Creates the public-only producer kernel unless an embedder supplies an equivalent bean. */
    @Bean
    @ConditionalOnMissingBean(ExternalSequenceAnchorBootstrapRootCeremonyProducer.class)
    ExternalSequenceAnchorBootstrapRootCeremonyProducer
            externalSequenceAnchorBootstrapRootCeremonyProducer(
            ObjectMapper objectMapper,
            ValidatedRecoveryBinding validated,
            RecoveryProperties recovery) {
        return new ExternalSequenceAnchorBootstrapRootCeremonyProducer(
                objectMapper, Clock.systemUTC(), validated.binding(),
                recovery.acceptedPolicies(), validated.genesis(),
                recovery.maximumExecutionDelay());
    }

    /** Creates the durable ceremony coordinator over the publication journal. */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(ExternalSequenceAnchorBootstrapRootCeremonyService.class)
    ExternalSequenceAnchorBootstrapRootCeremonyService
            externalSequenceAnchorBootstrapRootCeremonyService(
            ExternalSequenceAnchorBootstrapRootCeremonyProducer producer,
            ExternalSequenceAnchorBootstrapRootCeremonyJournal journal,
            RecoveryProperties properties) {
        return new ExternalSequenceAnchorBootstrapRootCeremonyService(
                producer, journal, properties.signerCallPolicy());
    }

    /** Starts one bounded recovery lane after an authority resolver is supplied. */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(
            ExternalSequenceAnchorBootstrapRootCeremonyRecoveryScheduler.class)
    ExternalSequenceAnchorBootstrapRootCeremonyRecoveryScheduler
            externalSequenceAnchorBootstrapRootCeremonyRecoveryScheduler(
            ExternalSequenceAnchorBootstrapRootCeremonyService service,
            ExternalSequenceAnchorBootstrapRootAuthorityResolver authorityResolver,
            RecoveryProperties properties) {
        return new ExternalSequenceAnchorBootstrapRootCeremonyRecoveryScheduler(
                service, properties.workerId(), properties.leaseDurationSeconds(),
                authorityResolver, properties.schedulePolicy());
    }

    /** Exposes aggregate-only readiness for the configured recovery lane. */
    @Bean
    @ConditionalOnMissingBean(
            ExternalSequenceAnchorBootstrapRootCeremonyRecoveryHealth.class)
    ExternalSequenceAnchorBootstrapRootCeremonyRecoveryHealth
            externalSequenceAnchorBootstrapRootCeremonyRecoveryHealth(
            ExternalSequenceAnchorBootstrapRootCeremonyService service,
            ExternalSequenceAnchorBootstrapRootCeremonyRecoveryScheduler scheduler) {
        return new ExternalSequenceAnchorBootstrapRootCeremonyRecoveryHealth(
                service, scheduler);
    }

    private static void requirePublication(Properties publication) {
        Properties safe = Objects.requireNonNull(publication, "publication");
        if (!safe.enabled()) {
            throw RecoveryProperties.invalid();
        }
    }

    private record ValidatedRecoveryBinding(
            ExternalSequenceAnchorBootstrapRootGenesis genesis,
            ExpectedBinding binding) {

        private ValidatedRecoveryBinding {
            genesis = Objects.requireNonNull(genesis, "genesis");
            binding = Objects.requireNonNull(binding, "binding");
        }
    }

    /**
     * Strict single-root ceremony recovery configuration.
     *
     * @param enabled explicit test/staging activation switch
     * @param workerId stable pre-authenticated recovery worker identity
     * @param genesisJson strict public-only root genesis document
     * @param acceptedPolicyFingerprints comma-separated accepted ceremony policy fingerprints
     * @param maximumRootLifetimeSeconds maximum lifecycle of one generated root
     * @param clockSkewSeconds accepted signer clock skew
     * @param minimumRemainingValiditySeconds required successor validity after verification
     * @param maximumTransitionCount maximum complete-chain transition count
     * @param maximumExecutionDelaySeconds maximum age of approved material before signing
     * @param resolverTimeoutMillis wall-clock authority-resolution deadline
     * @param descriptorTimeoutMillis wall-clock public descriptor deadline
     * @param signatureTimeoutMillis wall-clock detached-signature deadline
     * @param maximumConcurrentSignerCalls fixed no-queue signer call capacity
     * @param leaseDurationSeconds auto-renewed database execution lease
     * @param initialDelayMillis delay before the first local recovery poll
     * @param pollIntervalMillis fixed delay after each completed poll
     * @param drainTimeoutMillis bounded scheduler shutdown wait
     */
    @ConfigurationProperties(prefix = RecoveryProperties.PREFIX,
            ignoreUnknownFields = false)
    public record RecoveryProperties(
            Boolean enabled,
            String workerId,
            String genesisJson,
            String acceptedPolicyFingerprints,
            Long maximumRootLifetimeSeconds,
            Long clockSkewSeconds,
            Long minimumRemainingValiditySeconds,
            Integer maximumTransitionCount,
            Long maximumExecutionDelaySeconds,
            Long resolverTimeoutMillis,
            Long descriptorTimeoutMillis,
            Long signatureTimeoutMillis,
            Integer maximumConcurrentSignerCalls,
            Long leaseDurationSeconds,
            Long initialDelayMillis,
            Long pollIntervalMillis,
            Long drainTimeoutMillis) {

        /** Prefix shared by Spring configuration, profile examples, and startup tests. */
        public static final String PREFIX =
                "gateway.testing.external-sequence-anchor.bootstrap-root-recovery";

        /** Applies finite defaults and rejects every enabled partial or unsafe policy. */
        public RecoveryProperties {
            enabled = Boolean.TRUE.equals(enabled);
            workerId = normalized(workerId);
            genesisJson = normalized(genesisJson);
            acceptedPolicyFingerprints = normalized(acceptedPolicyFingerprints);
            maximumRootLifetimeSeconds = defaulted(
                    maximumRootLifetimeSeconds, 2_592_000L);
            clockSkewSeconds = defaulted(clockSkewSeconds, 5L);
            minimumRemainingValiditySeconds = defaulted(
                    minimumRemainingValiditySeconds, 30L);
            maximumTransitionCount = maximumTransitionCount == null
                    ? ExternalSequenceAnchorBootstrapRootBundle.MAXIMUM_TRANSITIONS
                    : maximumTransitionCount;
            maximumExecutionDelaySeconds = defaulted(
                    maximumExecutionDelaySeconds, 300L);
            resolverTimeoutMillis = defaulted(resolverTimeoutMillis, 5_000L);
            descriptorTimeoutMillis = defaulted(descriptorTimeoutMillis, 5_000L);
            signatureTimeoutMillis = defaulted(signatureTimeoutMillis, 30_000L);
            maximumConcurrentSignerCalls = maximumConcurrentSignerCalls == null
                    ? 8 : maximumConcurrentSignerCalls;
            leaseDurationSeconds = defaulted(leaseDurationSeconds, 30L);
            initialDelayMillis = defaulted(initialDelayMillis, 5_000L);
            pollIntervalMillis = defaulted(pollIntervalMillis, 5_000L);
            drainTimeoutMillis = defaulted(drainTimeoutMillis, 5_000L);

            try {
                var bindingShape = new ExpectedBinding("validation-scope", "validation-root",
                        "validation-trust", 1, 0,
                        Duration.ofSeconds(maximumRootLifetimeSeconds),
                        Duration.ofSeconds(clockSkewSeconds),
                        Duration.ofSeconds(minimumRemainingValiditySeconds),
                        maximumTransitionCount);
                new ExternalSequenceAnchorBootstrapRootSignerCallSupervisor.Policy(
                        Duration.ofMillis(resolverTimeoutMillis),
                        Duration.ofMillis(descriptorTimeoutMillis),
                        Duration.ofMillis(signatureTimeoutMillis),
                        maximumConcurrentSignerCalls);
                new ExternalSequenceAnchorBootstrapRootCeremonyRecoveryScheduler
                        .SchedulePolicy(Duration.ofMillis(initialDelayMillis),
                        Duration.ofMillis(pollIntervalMillis),
                        Duration.ofMillis(drainTimeoutMillis));
                if (maximumExecutionDelaySeconds < bindingShape.clockSkew().toSeconds()
                        || maximumExecutionDelaySeconds
                        > bindingShape.maximumRootLifetime().toSeconds()) {
                    throw invalid();
                }
                if (enabled) {
                    if (workerId.isEmpty() || genesisJson.isEmpty()
                            || acceptedPolicyFingerprints.isEmpty()) {
                        throw invalid();
                    }
                    new ExternalSequenceAnchorBootstrapRootCeremonyJournal
                            .RecoveryAcquisitionCommand(
                            ExternalSequenceAnchorBootstrapRootCeremonyJournal
                                    .RecoveryAcquisitionCommand.SCHEMA_VERSION,
                            workerId, leaseDurationSeconds);
                    acceptedPolicies(acceptedPolicyFingerprints);
                }
            } catch (RuntimeException validationFailure) {
                throw invalid();
            }
        }

        private Set<String> acceptedPolicies() {
            return acceptedPolicies(acceptedPolicyFingerprints);
        }

        private static Set<String> acceptedPolicies(String configured) {
            List<String> configuredPolicies = Arrays.stream(configured.split(",", -1))
                    .map(String::trim)
                    .toList();
            Set<String> policies = Set.copyOf(configuredPolicies);
            if (policies.contains("") || policies.size() > 32
                    || policies.size() != configuredPolicies.size()) {
                throw invalid();
            }
            return policies;
        }

        private Duration maximumRootLifetime() {
            return Duration.ofSeconds(maximumRootLifetimeSeconds);
        }

        private Duration clockSkew() {
            return Duration.ofSeconds(clockSkewSeconds);
        }

        private Duration minimumRemainingValidity() {
            return Duration.ofSeconds(minimumRemainingValiditySeconds);
        }

        private Duration maximumExecutionDelay() {
            return Duration.ofSeconds(maximumExecutionDelaySeconds);
        }

        private ExternalSequenceAnchorBootstrapRootSignerCallSupervisor.Policy
                signerCallPolicy() {
            return new ExternalSequenceAnchorBootstrapRootSignerCallSupervisor.Policy(
                    Duration.ofMillis(resolverTimeoutMillis),
                    Duration.ofMillis(descriptorTimeoutMillis),
                    Duration.ofMillis(signatureTimeoutMillis),
                    maximumConcurrentSignerCalls);
        }

        private ExternalSequenceAnchorBootstrapRootCeremonyRecoveryScheduler.SchedulePolicy
                schedulePolicy() {
            return new ExternalSequenceAnchorBootstrapRootCeremonyRecoveryScheduler
                    .SchedulePolicy(Duration.ofMillis(initialDelayMillis),
                    Duration.ofMillis(pollIntervalMillis),
                    Duration.ofMillis(drainTimeoutMillis));
        }

        private static long defaulted(Long value, long fallback) {
            return value == null ? fallback : value;
        }

        private static String normalized(String value) {
            return Objects.requireNonNullElse(value, "").trim();
        }

        private static IllegalArgumentException invalid() {
            return new IllegalArgumentException(
                    "Bootstrap-root ceremony recovery runtime configuration is invalid");
        }
    }
}
