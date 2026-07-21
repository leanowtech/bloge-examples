package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootCeremonyJournal.RecoveryPolicy;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootPublicationOutbox.PublicationPolicy;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetDynamicInventoryConfiguration.DynamicInventoryProperties;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetDynamicInventoryConfiguration.ManagedTrustRootProperties;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseExternalSequenceAnchorBootstrapRootCeremonyJournal;
import com.leanowtech.bloge.gateway.testing.persistence.TestRuntimeDatabase;
import org.springframework.beans.factory.ObjectProvider;
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
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Explicit Spring composition root for one bootstrap-root publication lane.
 *
 * <p>The runtime is physically absent whenever a {@code production} profile is active and remains
 * inert in {@code test}/{@code staging} until its explicit switch is true. Enabling it binds one
 * root set to one durable database journal, one strict signed-response publisher, one fixed call
 * supervisor, and one fixed-delay scheduler. Spring dependency destruction then closes scheduler,
 * service, publisher, and database in that order.</p>
 *
 * <p>This is deliberately a single-root-set composition. Staging requires PKIX, hostname
 * verification, SPKI pinning, and a dedicated mutual-TLS client identity before journal creation.
 * It does not claim cross-root discovery, sharding, fleet fairness, response-key hot rotation, or
 * publisher anti-equivocation.</p>
 */
@Configuration(proxyBeanMethods = false)
@Profile("!production & (test | staging)")
@EnableConfigurationProperties(
        ExternalSequenceAnchorBootstrapRootPublicationRuntimeConfiguration.Properties.class)
public class ExternalSequenceAnchorBootstrapRootPublicationRuntimeConfiguration {

    /** Creates the profile-gated publication composition root. */
    public ExternalSequenceAnchorBootstrapRootPublicationRuntimeConfiguration() {
    }

    /**
     * Freezes transport credentials and staging downgrade policy before any publication journal,
     * worker, or network protocol adapter can be created.
     */
    @Bean
    @ConditionalOnProperty(prefix = Properties.PREFIX, name = "enabled",
            havingValue = "true")
    ValidatedPublicationTransport externalSequenceAnchorBootstrapRootPublicationTransport(
            Properties properties,
            Environment environment,
            ObjectProvider<ControlPlaneHttpTransport.SecretResolver> secretResolvers) {
        boolean staging = Objects.requireNonNull(environment, "environment")
                .acceptsProfiles(Profiles.of("staging"));
        if (staging && (!properties.transport().enabled()
                || !properties.transport().required()
                || properties.allowInsecureLoopback())) {
            throw Properties.invalid();
        }
        requireIndependentClientIdentity(environment, properties.transport());
        ControlPlaneHttpTransport.SecretResolver secretResolver =
                secretResolver(secretResolvers, properties.transport().enabled());
        return new ValidatedPublicationTransport(properties.transport().create(secretResolver));
    }

    /** Creates the default database authority unless an embedder supplies a durable outbox. */
    @Bean
    @ConditionalOnProperty(prefix = Properties.PREFIX, name = "enabled",
            havingValue = "true")
    @ConditionalOnMissingBean(ExternalSequenceAnchorBootstrapRootPublicationOutbox.class)
    DatabaseExternalSequenceAnchorBootstrapRootCeremonyJournal
            externalSequenceAnchorBootstrapRootPublicationJournal(
            TestRuntimeDatabase database,
            ObjectMapper objectMapper,
            Properties properties,
            ValidatedPublicationTransport validatedTransport) {
        Objects.requireNonNull(validatedTransport, "validatedTransport");
        return new DatabaseExternalSequenceAnchorBootstrapRootCeremonyJournal(
                database.jdbc(), objectMapper, properties.scopeId(), properties.rootSetId(),
                database.transactionManager(), properties.recoveryPolicy(),
                properties.publicationPolicy());
    }

    /** Creates the strict HTTPS adapter unless an embedder supplies an equivalent publisher. */
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = Properties.PREFIX, name = "enabled",
            havingValue = "true")
    @ConditionalOnMissingBean(ExternalSequenceAnchorBootstrapRootPublisher.class)
    HttpExternalSequenceAnchorBootstrapRootPublisher
            externalSequenceAnchorBootstrapRootPublisher(
            ObjectMapper objectMapper,
            Properties properties,
            ValidatedPublicationTransport validatedTransport) {
        return HttpExternalSequenceAnchorBootstrapRootPublisher.fromBase64(
                objectMapper, properties.trustDomain(), properties.publisherId(),
                properties.responseKeyId(), properties.responsePublicKeyBase64(),
                properties.responseKeyNotBeforeInstant(),
                properties.responseKeyExpiresAtInstant(), properties.endpointUri(),
                properties.publisherSettings(), validatedTransport.transport());
    }

    /** Creates the database-fenced consumer and its fixed-capacity call boundary. */
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = Properties.PREFIX, name = "enabled",
            havingValue = "true")
    @ConditionalOnMissingBean(ExternalSequenceAnchorBootstrapRootPublicationService.class)
    ExternalSequenceAnchorBootstrapRootPublicationService
            externalSequenceAnchorBootstrapRootPublicationService(
            ExternalSequenceAnchorBootstrapRootPublicationOutbox outbox,
            ExternalSequenceAnchorBootstrapRootPublisher publisher,
            Properties properties) {
        return new ExternalSequenceAnchorBootstrapRootPublicationService(
                outbox, publisher, properties.publisherCallPolicy());
    }

    /** Starts one bounded fixed-delay lane after all publication dependencies are assembled. */
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = Properties.PREFIX, name = "enabled",
            havingValue = "true")
    @ConditionalOnMissingBean(ExternalSequenceAnchorBootstrapRootPublicationScheduler.class)
    ExternalSequenceAnchorBootstrapRootPublicationScheduler
            externalSequenceAnchorBootstrapRootPublicationScheduler(
            ExternalSequenceAnchorBootstrapRootPublicationService service,
            Properties properties) {
        return new ExternalSequenceAnchorBootstrapRootPublicationScheduler(
                service, properties.workerId(), properties.leaseDurationSeconds(),
                properties.schedulePolicy());
    }

    /** Exposes aggregate-only readiness for the configured single-root publication lane. */
    @Bean
    @ConditionalOnProperty(prefix = Properties.PREFIX, name = "enabled",
            havingValue = "true")
    @ConditionalOnMissingBean(ExternalSequenceAnchorBootstrapRootPublicationHealth.class)
    ExternalSequenceAnchorBootstrapRootPublicationHealth
            externalSequenceAnchorBootstrapRootPublicationHealth(
            ExternalSequenceAnchorBootstrapRootPublicationService service,
            ExternalSequenceAnchorBootstrapRootPublicationScheduler scheduler) {
        return new ExternalSequenceAnchorBootstrapRootPublicationHealth(service, scheduler);
    }

    /**
     * Strict single-root publication configuration.
     *
     * @param enabled explicit test/staging activation switch
     * @param scopeId stable Resource Gateway fleet scope
     * @param rootSetId exact bootstrap-root chain identity
     * @param workerId stable pre-authenticated local worker identity
     * @param endpoint exact remote publication endpoint
     * @param trustDomain expected response-signing trust domain
     * @param publisherId expected logical publisher identity
     * @param responseKeyId expected static response-signing key identity
     * @param responsePublicKeyBase64 X.509-encoded Ed25519 verification key
     * @param responseKeyNotBefore inclusive key activation instant
     * @param responseKeyExpiresAt exclusive key expiry instant
     * @param transport endpoint authentication and publisher client-identity policy
     * @param requestTimeoutMillis complete HTTP request timeout
     * @param clockSkewSeconds accepted publisher clock lead
     * @param maximumResponseLifetimeSeconds maximum signed-response lifetime
     * @param allowInsecureLoopback explicit local HTTP test escape hatch
     * @param publisherCallTimeoutMillis outer publisher-call deadline
     * @param maximumConcurrentCalls fixed no-queue call capacity
     * @param leaseDurationSeconds database claim lease including terminal-commit margin
     * @param initialDelayMillis delay before the first local poll
     * @param pollIntervalMillis fixed delay after each completed poll
     * @param drainTimeoutMillis bounded scheduler shutdown wait
     * @param initialRetryDelaySeconds first database-time publication retry delay
     * @param maximumRetryDelaySeconds capped publication retry delay
     * @param maximumAutomaticAttempts durable automatic publication attempt budget
     * @param recoveryInitialRetryDelaySeconds future ceremony recovery first retry delay
     * @param recoveryMaximumRetryDelaySeconds future ceremony recovery capped retry delay
     * @param recoveryMaximumAutomaticAttempts future ceremony recovery attempt budget
     */
    @ConfigurationProperties(prefix = Properties.PREFIX, ignoreUnknownFields = false)
    public record Properties(
            Boolean enabled,
            String scopeId,
            String rootSetId,
            String workerId,
            String endpoint,
            String trustDomain,
            String publisherId,
            String responseKeyId,
            String responsePublicKeyBase64,
            String responseKeyNotBefore,
            String responseKeyExpiresAt,
            @NestedConfigurationProperty
            RecoveryFleetPublicationTransportProperties transport,
            Long requestTimeoutMillis,
            Long clockSkewSeconds,
            Long maximumResponseLifetimeSeconds,
            Boolean allowInsecureLoopback,
            Long publisherCallTimeoutMillis,
            Integer maximumConcurrentCalls,
            Long leaseDurationSeconds,
            Long initialDelayMillis,
            Long pollIntervalMillis,
            Long drainTimeoutMillis,
            Long initialRetryDelaySeconds,
            Long maximumRetryDelaySeconds,
            Long maximumAutomaticAttempts,
            Long recoveryInitialRetryDelaySeconds,
            Long recoveryMaximumRetryDelaySeconds,
            Long recoveryMaximumAutomaticAttempts) {

        /** Prefix shared by Spring configuration, profile examples, and startup tests. */
        public static final String PREFIX =
                "gateway.testing.external-sequence-anchor.bootstrap-root-publication";

        /** Applies finite defaults and fails startup on any enabled partial or unsafe policy. */
        public Properties {
            enabled = Boolean.TRUE.equals(enabled);
            scopeId = normalized(scopeId);
            rootSetId = normalized(rootSetId);
            workerId = normalized(workerId);
            endpoint = normalized(endpoint);
            trustDomain = normalized(trustDomain);
            publisherId = normalized(publisherId);
            responseKeyId = normalized(responseKeyId);
            responsePublicKeyBase64 = normalized(responsePublicKeyBase64);
            responseKeyNotBefore = normalized(responseKeyNotBefore);
            responseKeyExpiresAt = normalized(responseKeyExpiresAt);
            transport = transport == null
                    ? RecoveryFleetPublicationTransportProperties.disabled() : transport;
            requestTimeoutMillis = defaulted(requestTimeoutMillis, 3_000L);
            clockSkewSeconds = defaulted(clockSkewSeconds, 5L);
            maximumResponseLifetimeSeconds = defaulted(
                    maximumResponseLifetimeSeconds, 10L);
            allowInsecureLoopback = Boolean.TRUE.equals(allowInsecureLoopback);
            publisherCallTimeoutMillis = defaulted(publisherCallTimeoutMillis, 4_000L);
            maximumConcurrentCalls = maximumConcurrentCalls == null
                    ? 2 : maximumConcurrentCalls;
            leaseDurationSeconds = defaulted(leaseDurationSeconds, 6L);
            initialDelayMillis = defaulted(initialDelayMillis, 5_000L);
            pollIntervalMillis = defaulted(pollIntervalMillis, 5_000L);
            drainTimeoutMillis = defaulted(drainTimeoutMillis, 5_000L);
            initialRetryDelaySeconds = defaulted(initialRetryDelaySeconds, 5L);
            maximumRetryDelaySeconds = defaulted(maximumRetryDelaySeconds, 300L);
            maximumAutomaticAttempts = defaulted(maximumAutomaticAttempts, 20L);
            recoveryInitialRetryDelaySeconds = defaulted(
                    recoveryInitialRetryDelaySeconds, 5L);
            recoveryMaximumRetryDelaySeconds = defaulted(
                    recoveryMaximumRetryDelaySeconds, 300L);
            recoveryMaximumAutomaticAttempts = defaulted(
                    recoveryMaximumAutomaticAttempts, 20L);

            var publisherSettings = new HttpExternalSequenceAnchorBootstrapRootPublisher.Settings(
                    Duration.ofMillis(requestTimeoutMillis), Duration.ofSeconds(clockSkewSeconds),
                    Duration.ofSeconds(maximumResponseLifetimeSeconds), allowInsecureLoopback);
            var callPolicy = new ExternalSequenceAnchorBootstrapRootPublisherCallSupervisor.Policy(
                    Duration.ofMillis(publisherCallTimeoutMillis), maximumConcurrentCalls);
            var schedulePolicy = new ExternalSequenceAnchorBootstrapRootPublicationScheduler
                    .SchedulePolicy(Duration.ofMillis(initialDelayMillis),
                    Duration.ofMillis(pollIntervalMillis),
                    Duration.ofMillis(drainTimeoutMillis));
            new PublicationPolicy(PublicationPolicy.SCHEMA_VERSION,
                    initialRetryDelaySeconds, maximumRetryDelaySeconds,
                    maximumAutomaticAttempts);
            new RecoveryPolicy(RecoveryPolicy.SCHEMA_VERSION,
                    recoveryInitialRetryDelaySeconds, recoveryMaximumRetryDelaySeconds,
                    recoveryMaximumAutomaticAttempts);
            if (publisherCallTimeoutMillis <= publisherSettings.requestTimeout().toMillis()
                    || leaseDurationSeconds < callPolicyTimeoutSeconds(callPolicy) + 2L) {
                throw invalid();
            }
            Objects.requireNonNull(schedulePolicy, "schedulePolicy");
            if (enabled) {
                requireConfigured(scopeId, rootSetId, workerId, endpoint, trustDomain,
                        publisherId, responseKeyId, responsePublicKeyBase64,
                        responseKeyNotBefore, responseKeyExpiresAt);
                parseUri(endpoint);
                parseInstant(responseKeyNotBefore);
                parseInstant(responseKeyExpiresAt);
                if (transport.enabled() && allowInsecureLoopback) {
                    throw invalid();
                }
            } else if (transport.configured()) {
                throw invalid();
            }
        }

        private HttpExternalSequenceAnchorBootstrapRootPublisher.Settings publisherSettings() {
            return new HttpExternalSequenceAnchorBootstrapRootPublisher.Settings(
                    Duration.ofMillis(requestTimeoutMillis), Duration.ofSeconds(clockSkewSeconds),
                    Duration.ofSeconds(maximumResponseLifetimeSeconds), allowInsecureLoopback);
        }

        private ExternalSequenceAnchorBootstrapRootPublisherCallSupervisor.Policy
                publisherCallPolicy() {
            return new ExternalSequenceAnchorBootstrapRootPublisherCallSupervisor.Policy(
                    Duration.ofMillis(publisherCallTimeoutMillis), maximumConcurrentCalls);
        }

        private ExternalSequenceAnchorBootstrapRootPublicationScheduler.SchedulePolicy
                schedulePolicy() {
            return new ExternalSequenceAnchorBootstrapRootPublicationScheduler.SchedulePolicy(
                    Duration.ofMillis(initialDelayMillis),
                    Duration.ofMillis(pollIntervalMillis),
                    Duration.ofMillis(drainTimeoutMillis));
        }

        private PublicationPolicy publicationPolicy() {
            return new PublicationPolicy(PublicationPolicy.SCHEMA_VERSION,
                    initialRetryDelaySeconds, maximumRetryDelaySeconds,
                    maximumAutomaticAttempts);
        }

        private RecoveryPolicy recoveryPolicy() {
            return new RecoveryPolicy(RecoveryPolicy.SCHEMA_VERSION,
                    recoveryInitialRetryDelaySeconds, recoveryMaximumRetryDelaySeconds,
                    recoveryMaximumAutomaticAttempts);
        }

        private URI endpointUri() {
            return parseUri(endpoint);
        }

        private Instant responseKeyNotBeforeInstant() {
            return parseInstant(responseKeyNotBefore);
        }

        private Instant responseKeyExpiresAtInstant() {
            return parseInstant(responseKeyExpiresAt);
        }

        private static long callPolicyTimeoutSeconds(
                ExternalSequenceAnchorBootstrapRootPublisherCallSupervisor.Policy policy) {
            return Math.floorDiv(policy.publisherTimeout().toMillis() + 999L, 1_000L);
        }

        private static URI parseUri(String value) {
            try {
                return URI.create(value);
            } catch (RuntimeException ignored) {
                throw invalid();
            }
        }

        private static Instant parseInstant(String value) {
            try {
                Instant result = Instant.parse(value);
                if (result.getNano() != 0) {
                    throw invalid();
                }
                return result;
            } catch (RuntimeException ignored) {
                throw invalid();
            }
        }

        private static void requireConfigured(String... values) {
            for (String value : values) {
                if (value.isEmpty()) {
                    throw invalid();
                }
            }
        }

        private static long defaulted(Long value, long fallback) {
            return value == null ? fallback : value;
        }

        private static String normalized(String value) {
            return Objects.requireNonNullElse(value, "").trim();
        }

        private static IllegalArgumentException invalid() {
            return new IllegalArgumentException(
                    "Bootstrap-root publication runtime configuration is invalid");
        }
    }

    private static void requireIndependentClientIdentity(
            Environment environment,
            RecoveryFleetPublicationTransportProperties publisher) {
        List<String> independentSources = List.of(
                DynamicInventoryProperties.PREFIX + ".transport",
                ManagedTrustRootProperties.PREFIX + ".transport");
        for (String prefix : independentSources) {
            if (publisher.sharesClientIdentityWith(transport(environment, prefix))) {
                throw Properties.invalid();
            }
        }
    }

    private static RecoveryFleetPublicationTransportProperties transport(
            Environment environment,
            String prefix) {
        String ownerPrefix = prefix.endsWith(".transport")
                ? prefix.substring(0, prefix.length() - ".transport".length()) : prefix;
        if (!environment.getProperty(ownerPrefix + ".enabled", Boolean.class, false)) {
            return RecoveryFleetPublicationTransportProperties.disabled();
        }
        return new RecoveryFleetPublicationTransportProperties(
                environment.getProperty(prefix + ".enabled", Boolean.class, false),
                environment.getProperty(prefix + ".required", Boolean.class, false),
                environment.getProperty(prefix + ".trust-store-path", ""),
                environment.getProperty(prefix + ".trust-store-password-ref", ""),
                environment.getProperty(prefix + ".client-key-store-path", ""),
                environment.getProperty(prefix + ".client-key-store-password-ref", ""),
                environment.getProperty(prefix + ".server-spki-pins", ""));
    }

    private static ControlPlaneHttpTransport.SecretResolver secretResolver(
            ObjectProvider<ControlPlaneHttpTransport.SecretResolver> providers,
            boolean required) {
        var configured = Objects.requireNonNull(providers, "providers")
                .orderedStream().toList();
        if (configured.size() > 1) {
            throw Properties.invalid();
        }
        if (configured.size() == 1) {
            return configured.getFirst();
        }
        if (!required) {
            return reference -> {
                throw new IllegalStateException(
                        "Bootstrap-root publisher transport credential is unavailable");
            };
        }
        return new PinnedMutualTlsRecoveryFleetPublicationTransport
                .EnvironmentSecretResolver();
    }

    /** Validated immutable transport token shared by journal and publisher construction. */
    record ValidatedPublicationTransport(ControlPlaneHttpTransport transport) {

        /** Rejects an absent transport after credential loading or profile preflight. */
        ValidatedPublicationTransport {
            transport = Objects.requireNonNull(transport, "transport");
        }
    }
}
