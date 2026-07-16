package com.leanowtech.bloge.gateway.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.NamedType;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.operator.OperatorMeta;
import com.leanowtech.bloge.core.operator.StreamingOperator;
import com.leanowtech.bloge.core.operator.SuspendableOperator;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.core.spi.OperatorRegistry;
import com.leanowtech.bloge.gateway.carrier.TenantMdcCarrier;
import com.leanowtech.bloge.gateway.expression.BlgeExpressionEvaluator;
import com.leanowtech.bloge.gateway.interceptor.QuotaConfigProvider;
import com.leanowtech.bloge.gateway.integration.DatabaseGovernanceGateResultRepository;
import com.leanowtech.bloge.gateway.integration.DatabaseEvidenceKeySetTrustPublicationRepository;
import com.leanowtech.bloge.gateway.integration.DatabaseIntegrationAccessAuditRepository;
import com.leanowtech.bloge.gateway.integration.DatabaseIntegrationChangeEventOutbox;
import com.leanowtech.bloge.gateway.integration.DatabaseSideEffectReconciliationRepository;
import com.leanowtech.bloge.gateway.integration.ConfiguredIntegrationJwtTrustStore;
import com.leanowtech.bloge.gateway.integration.ConfiguredEvidenceKeySetTrustStore;
import com.leanowtech.bloge.gateway.integration.DynamicJwksIntegrationJwtTrustStore;
import com.leanowtech.bloge.gateway.integration.GovernanceGateResultRepository;
import com.leanowtech.bloge.gateway.integration.EvidenceKeySetTrustPublicationRepository;
import com.leanowtech.bloge.gateway.integration.EvidenceKeySetTrustStore;
import com.leanowtech.bloge.gateway.integration.IntegrationAccessAuditRepository;
import com.leanowtech.bloge.gateway.integration.IntegrationChangeEventOutbox;
import com.leanowtech.bloge.gateway.integration.IntegrationIdentityResolver;
import com.leanowtech.bloge.gateway.integration.IntegrationJwtTrustStore;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationWorkloadIdentity;
import com.leanowtech.bloge.gateway.integration.StaticBearerIntegrationIdentityResolver;
import com.leanowtech.bloge.gateway.integration.SignedJwtIntegrationIdentityResolver;
import com.leanowtech.bloge.gateway.integration.SideEffectReconciler;
import com.leanowtech.bloge.gateway.integration.SideEffectReconcilerRegistry;
import com.leanowtech.bloge.gateway.integration.SideEffectReconciliationRepository;
import com.leanowtech.bloge.gateway.operator.HttpResourceOperator;
import com.leanowtech.bloge.gateway.operator.PayloadExtractor;
import com.leanowtech.bloge.gateway.operator.ResponseValidator;
import com.leanowtech.bloge.gateway.operator.UrlTemplateRenderer;
import com.leanowtech.bloge.gateway.testing.security.ExecutionControlBoundaryGuardFilter;
import com.leanowtech.bloge.gateway.resource.DatabaseResourceRegistry;
import com.leanowtech.bloge.gateway.resource.WritableResourceRegistry;
import com.leanowtech.bloge.gateway.visual.asset.DatabaseVisualRuntimeBindingImplementationRepository;
import com.leanowtech.bloge.gateway.visual.asset.VisualRuntimeBindingImplementationRepository;
import com.leanowtech.bloge.gateway.visual.catalog.DatabaseOperatorLibraryRegistry;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibraryRegistry;
import com.leanowtech.bloge.gateway.visual.draft.DatabaseGraphDraftRepository;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftRepository;
import com.leanowtech.bloge.gateway.visual.golden.DatabaseVisualGraphGoldenCertificationRepository;
import com.leanowtech.bloge.gateway.visual.golden.DatabaseVisualGraphGoldenCaseRepository;
import com.leanowtech.bloge.gateway.visual.golden.VisualGraphGoldenCertificationRepository;
import com.leanowtech.bloge.gateway.visual.golden.VisualGraphGoldenCaseRepository;
import com.leanowtech.bloge.gateway.visual.publication.DatabaseVisualGraphPublicationRepository;
import com.leanowtech.bloge.gateway.visual.publication.VisualGraphPublicationRepository;
import com.leanowtech.bloge.gateway.visual.resource.DatabaseResourceDesignContractRegistry;
import com.leanowtech.bloge.gateway.visual.resource.ResourceDesignContractRegistry;
import com.leanowtech.bloge.gateway.visual.runtime.DatabaseVisualExecutableLoweringIntegrationRepository;
import com.leanowtech.bloge.gateway.visual.runtime.DatabaseVisualGraphRunRepository;
import com.leanowtech.bloge.gateway.visual.runtime.DatabaseVisualRunPayloadRepository;
import com.leanowtech.bloge.gateway.visual.runtime.DatabaseVisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.ConfiguredVisualPayloadGovernancePolicy;
import com.leanowtech.bloge.gateway.visual.runtime.DatabaseVisualRuntimeAdapterActivationRepository;
import com.leanowtech.bloge.gateway.visual.runtime.DatabaseVisualRuntimeRolloutObservationRepository;
import com.leanowtech.bloge.gateway.visual.runtime.HttpManagedEvidenceSigningProvider;
import com.leanowtech.bloge.gateway.visual.runtime.ManagedEvidenceSigningProvider;
import com.leanowtech.bloge.gateway.visual.runtime.ManagedVisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualExecutableLoweringIntegrationRepository;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphPublicationOperator;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphRunRepository;
import com.leanowtech.bloge.gateway.visual.runtime.VisualPayloadGovernancePolicy;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunPayloadRepository;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphRunService;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRuntimeAdapterActivationRepository;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRuntimeRolloutObservationRepository;
import com.leanowtech.bloge.gateway.example.DatabaseDynamicRunControlRepository;
import com.leanowtech.bloge.gateway.example.DynamicRunControlRepository;
import com.leanowtech.bloge.gateway.visual.testing.DatabaseVisualOperatorContractTestSuiteRepository;
import com.leanowtech.bloge.gateway.visual.testing.VisualOperatorContractTestSuiteRepository;
import com.leanowtech.bloge.operators.http.HttpRequestInput;
import com.leanowtech.bloge.operators.http.HttpRequestOperator;
import com.leanowtech.bloge.spring.annotation.BlogeOperator;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Central Spring configuration for the resource gateway example.
 *
 * <p>Defines infrastructure beans — serialization, URL rendering, payload extraction,
 * response validation, HTTP operators, interceptors, and context carriers — and wires
 * them together in a Spring-friendly way.
 *
 * <p>Beans that are already provided by component scanning (e.g. {@link BlgeExpressionEvaluator},
 * {@link QuotaConfigProvider}, {@link TenantMdcCarrier}) are not redefined here.
 * This class only creates beans that either require explicit constructor wiring or that
 * should be centrally configured rather than scattered across component annotations.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class GatewayConfiguration {

    // ── Serialization ───────────────────────────────────────────────────

    /**
     * Shared Jackson {@link ObjectMapper} configured for the gateway's serialization
     * needs (Java 8 time types, no timestamp arrays).
     *
     * @return a pre-configured object mapper
     */
    @Bean
    @ConditionalOnMissingBean
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.addMixIn(HttpRequestInput.HttpAuth.class, HttpAuthMixin.class);
        mapper.registerSubtypes(
                new NamedType(HttpRequestInput.BearerAuth.class, "bearer"),
                new NamedType(HttpRequestInput.BasicAuth.class, "basic"),
                new NamedType(HttpRequestInput.ApiKeyAuth.class, "apiKey")
        );
        return mapper;
    }

    /** Durable authority for run cancellation, owner leases and restart recovery. */
    @Bean
    @ConditionalOnMissingBean
    public DynamicRunControlRepository dynamicRunControlRepository(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager) {
        return new DatabaseDynamicRunControlRepository(jdbc, transactionManager);
    }

    // ── Operator support ────────────────────────────────────────────────

    /**
     * URL template renderer for resolving {@code {placeholder}} variables.
     *
     * @return a new renderer instance
     */
    @Bean
    @ConditionalOnMissingBean
    public UrlTemplateRenderer urlTemplateRenderer() {
        return new UrlTemplateRenderer();
    }

    /**
     * JSON payload extractor for navigating dot-notation paths in response bodies.
     *
     * @return a new extractor instance
     */
    @Bean
    @ConditionalOnMissingBean
    public PayloadExtractor payloadExtractor() {
        return new PayloadExtractor();
    }

    /**
     * Response validator that interprets HTTP responses against {@code ResponseProtocol}
     * definitions.
     *
     * @param evaluator the bloge expression evaluator (for {@code BlgeExpression} protocols)
     * @return a new validator instance
     */
    @Bean
    @ConditionalOnMissingBean
    public ResponseValidator responseValidator(BlgeExpressionEvaluator evaluator) {
        return new ResponseValidator(evaluator);
    }

    /**
     * Low-level HTTP request operator using the JDK {@link java.net.http.HttpClient}.
     *
     * @return a new HTTP request operator
     */
    @Bean
    @ConditionalOnMissingBean
    public HttpRequestOperator httpRequestOperator() {
        return new HttpRequestOperator();
    }

    /**
     * High-level HTTP resource operator that resolves descriptors from the registry
     * and orchestrates the full call lifecycle.
     *
     * @param httpRequestOperator the underlying HTTP client
     * @param registry            the resource descriptor registry
     * @param evaluator           bloge expression evaluator
     * @param renderer            URL template renderer
     * @param extractor           payload extractor
     * @param validator           response validator
     * @return the configured resource operator
     */
    @Bean
    @ConditionalOnMissingBean
    public HttpResourceOperator httpResourceOperator(HttpRequestOperator httpRequestOperator,
                                                     WritableResourceRegistry registry,
                                                     BlgeExpressionEvaluator evaluator,
                                                     UrlTemplateRenderer renderer,
                                                     PayloadExtractor extractor,
                                                     ResponseValidator validator) {
        return new HttpResourceOperator(httpRequestOperator, registry, evaluator, renderer, extractor, validator);
    }

    /**
     * Runtime operator used by publication-backed virtual operators.
     *
     * @param repository immutable publication repository
     * @param runnerProvider lazy runner provider to avoid eager registry cycles
     * @return visual publication invocation operator
     */
    @Bean
    @ConditionalOnMissingBean
    public VisualGraphPublicationOperator visualGraphPublicationOperator(
            VisualGraphPublicationRepository repository,
            ObjectProvider<VisualGraphRunService> runnerProvider) {
        return new VisualGraphPublicationOperator(repository, runnerProvider);
    }

    /**
     * Runtime operator registry shared by DSL execution and the visual Java operator inventory.
     *
     * @param operators synchronous Java operators
     * @param streamingOperators streaming Java operators
     * @param suspendableOperators suspendable Java operators
     * @return registry populated from Spring operator beans
     */
    @Bean
    @ConditionalOnMissingBean
    public OperatorRegistry operatorRegistry(List<Operator<?, ?>> operators,
                                             List<StreamingOperator<?, ?>> streamingOperators,
                                             List<SuspendableOperator<?, ?>> suspendableOperators) {
        DefaultOperatorRegistry registry = new DefaultOperatorRegistry();
        Set<Object> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        Set<String> names = new LinkedHashSet<>();
        List<Object> candidates = new ArrayList<>();
        candidates.addAll(operators == null ? List.of() : operators);
        candidates.addAll(streamingOperators == null ? List.of() : streamingOperators);
        candidates.addAll(suspendableOperators == null ? List.of() : suspendableOperators);
        for (Object operator : candidates) {
            if (operator == null || !seen.add(operator)) {
                continue;
            }
            String name = operatorName(operator);
            if (name.isBlank() || !names.add(name)) {
                continue;
            }
            registry.registerRaw(name, operator);
        }
        return registry;
    }

    // ── Persistence ─────────────────────────────────────────────────────

    /** Credential-free append-only security audit for integration authentication decisions. */
    @Bean
    @ConditionalOnMissingBean
    public IntegrationAccessAuditRepository integrationAccessAuditRepository(JdbcTemplate jdbc) {
        return new DatabaseIntegrationAccessAuditRepository(jdbc);
    }

    /**
     * Signed JWT workload identity when configured, otherwise the explicit demo identity. Enterprise deployments
     * may replace either the resolver or JWT trust store with an OIDC/JWKS, KMS, mTLS or trusted-gateway adapter.
     */
    @Bean
    @ConditionalOnMissingBean
    public IntegrationIdentityResolver integrationIdentityResolver(
            ObjectMapper objectMapper,
            ObjectProvider<IntegrationJwtTrustStore> trustStoreProvider,
            @Value("${gateway.integration.identity.jwt.enabled:false}") boolean jwtEnabled,
            @Value("${gateway.integration.identity.jwt.issuer:}") String jwtIssuer,
            @Value("${gateway.integration.identity.jwt.audience:}") String jwtAudience,
            @Value("${gateway.integration.identity.jwt.trusted-keys-json:}") String trustedKeysJson,
            @Value("${gateway.integration.identity.jwt.revoked-key-ids:}") String revokedKeyIds,
            @Value("${gateway.integration.identity.jwt.revoked-token-ids:}") String revokedTokenIds,
            @Value("${gateway.integration.identity.jwt.jwks-uri:}") String jwksUri,
            @Value("${gateway.integration.identity.jwt.revocations-uri:}") String revocationsUri,
            @Value("${gateway.integration.identity.jwt.refresh-interval-seconds:30}") long refreshIntervalSeconds,
            @Value("${gateway.integration.identity.jwt.unknown-key-refresh-interval-seconds:5}") long unknownKeyRefreshIntervalSeconds,
            @Value("${gateway.integration.identity.jwt.request-timeout-seconds:3}") long requestTimeoutSeconds,
            @Value("${gateway.integration.identity.jwt.outage-policy:FAIL_CLOSED}") String outagePolicy,
            @Value("${gateway.integration.identity.jwt.maximum-stale-seconds:0}") long maximumStaleSeconds,
            @Value("${gateway.integration.identity.jwt.allow-insecure-loopback:false}") boolean allowInsecureLoopback,
            @Value("${gateway.integration.identity.jwt.clock-skew-seconds:30}") long jwtClockSkewSeconds,
            @Value("${gateway.integration.identity.jwt.maximum-lifetime-seconds:900}") long jwtMaximumLifetimeSeconds,
            @Value("${gateway.integration.identity.demo-enabled:true}") boolean demoEnabled,
            @Value("${gateway.integration.identity.demo-token:bloge-aneke-demo-token}") String token,
            @Value("${gateway.integration.identity.identity-id:demo-aneke-workload}") String identityId,
            @Value("${gateway.integration.identity.tenant-id:tenant-a}") String tenantId,
            @Value("${gateway.integration.identity.organization-id:knowledge-governance}") String organizationId,
            @Value("${gateway.integration.identity.project-id:tool-studio}") String projectId,
            @Value("${gateway.integration.identity.environment-id:prod}") String environmentId,
            @Value("${gateway.integration.identity.region:local}") String region,
            @Value("${gateway.integration.identity.actor-id:aneke-sync}") String actorId,
            @Value("${gateway.integration.identity.groups:}") String groups,
            @Value("${gateway.integration.identity.clearance:PUBLIC}") String clearance,
            @Value("${gateway.integration.identity.allowed-purposes:GOVERNANCE_EVIDENCE_INGESTION,PAYLOAD_REPLAY,PAYLOAD_RETENTION_ADMIN,LEGAL_HOLD,GOVERNANCE_GATE_FEEDBACK,CHANGE_SYNC,SIDE_EFFECT_RECONCILIATION}") String allowedPurposes) {
        if (jwtEnabled) {
            IntegrationJwtTrustStore trustStore = trustStoreProvider.getIfAvailable();
            if (trustStore == null) {
                if (jwksUri != null && !jwksUri.isBlank()) {
                    URI revocationAuthority = revocationsUri == null || revocationsUri.isBlank()
                            ? null : URI.create(revocationsUri.trim());
                    trustStore = new DynamicJwksIntegrationJwtTrustStore(objectMapper,
                            new DynamicJwksIntegrationJwtTrustStore.Settings(URI.create(jwksUri.trim()),
                                    revocationAuthority, Duration.ofSeconds(refreshIntervalSeconds),
                                    Duration.ofSeconds(unknownKeyRefreshIntervalSeconds),
                                    Duration.ofSeconds(requestTimeoutSeconds),
                                    DynamicJwksIntegrationJwtTrustStore.OutagePolicy.parse(outagePolicy),
                                    Duration.ofSeconds(maximumStaleSeconds), allowInsecureLoopback));
                } else {
                    trustStore = ConfiguredIntegrationJwtTrustStore.fromJson(objectMapper, trustedKeysJson,
                            commaSeparated(revokedKeyIds), commaSeparated(revokedTokenIds));
                }
            }
            return new SignedJwtIntegrationIdentityResolver(objectMapper, jwtIssuer, jwtAudience, trustStore,
                    Duration.ofSeconds(jwtClockSkewSeconds), Duration.ofSeconds(jwtMaximumLifetimeSeconds));
        }
        if (!demoEnabled) {
            return IntegrationIdentityResolver.unavailable();
        }
        Set<String> purposes = new LinkedHashSet<>();
        Arrays.stream(allowedPurposes.split(",")).map(String::trim).filter(value -> !value.isBlank())
                .forEach(purposes::add);
        IntegrationWorkloadIdentity identity = new IntegrationWorkloadIdentity(identityId, tenantId,
                organizationId, projectId, environmentId, region, "WORKLOAD", actorId, "", purposes,
                Instant.MAX, true, commaSeparated(groups), clearance, "", Instant.MAX);
        return new StaticBearerIntegrationIdentityResolver(token, identity, true);
    }

    /** Central authentication and purpose-policy gate for all protected integration endpoints. */
    @Bean
    @ConditionalOnMissingBean
    public IntegrationRequestAuthenticator integrationRequestAuthenticator(
            IntegrationIdentityResolver resolver,
            IntegrationAccessAuditRepository auditRepository) {
        return new IntegrationRequestAuthenticator(resolver, auditRepository);
    }

    /**
     * HTTP-boundary guard that makes production run protocols structurally incapable of accepting
     * fixture or execution-control fields, independent of Jackson unknown-property configuration.
     */
    @Bean
    @ConditionalOnMissingBean
    public ExecutionControlBoundaryGuardFilter executionControlBoundaryGuardFilter(
            ObjectMapper objectMapper,
            IntegrationAccessAuditRepository auditRepository) {
        return new ExecutionControlBoundaryGuardFilter(objectMapper, auditRepository);
    }

    /** Transactional source of integration change events. */
    @Bean
    @ConditionalOnMissingBean
    public IntegrationChangeEventOutbox integrationChangeEventOutbox(JdbcTemplate jdbc,
                                                                      ObjectMapper objectMapper) {
        return new DatabaseIntegrationChangeEventOutbox(jdbc, objectMapper);
    }

    /**
     * Database-backed resource registry with in-memory caching and H2 persistence.
     *
     * @param jdbc         JDBC template for H2 access
     * @param objectMapper Jackson mapper for descriptor serialization
     * @param evaluator    bloge expression evaluator for write-time validation
     * @return the writable resource registry
     */
    @Bean
    @ConditionalOnMissingBean
    public WritableResourceRegistry writableResourceRegistry(JdbcTemplate jdbc,
                                                              ObjectMapper objectMapper,
                                                              BlgeExpressionEvaluator evaluator) {
        return new DatabaseResourceRegistry(jdbc, objectMapper, evaluator);
    }

    /**
     * Database-backed registry for user-provided visual operator libraries.
     *
     * @param jdbc JDBC template for H2 access
     * @param objectMapper Jackson mapper for library serialization
     * @return visual operator library registry
     */
    @Bean
    @ConditionalOnMissingBean
    public OperatorLibraryRegistry operatorLibraryRegistry(JdbcTemplate jdbc,
                                                           ObjectMapper objectMapper,
                                                           IntegrationChangeEventOutbox outbox) {
        return new DatabaseOperatorLibraryRegistry(jdbc, objectMapper, outbox);
    }

    /**
     * Database-backed registry for resource design contracts used by visual virtual operators.
     *
     * @param jdbc JDBC template for H2 access
     * @param objectMapper Jackson mapper for contract serialization
     * @return visual resource design contract registry
     */
    @Bean
    @ConditionalOnMissingBean
    public ResourceDesignContractRegistry resourceDesignContractRegistry(JdbcTemplate jdbc,
                                                                         ObjectMapper objectMapper) {
        return new DatabaseResourceDesignContractRegistry(jdbc, objectMapper);
    }

    /**
     * Database-backed repository for visual graph drafts.
     *
     * @param jdbc JDBC template for H2 access
     * @param objectMapper Jackson mapper for draft serialization
     * @return graph draft repository
     */
    @Bean
    @ConditionalOnMissingBean
    public GraphDraftRepository graphDraftRepository(JdbcTemplate jdbc,
                                                     ObjectMapper objectMapper,
                                                     IntegrationChangeEventOutbox outbox) {
        return new DatabaseGraphDraftRepository(jdbc, objectMapper, outbox);
    }

    /**
     * Database-backed repository for immutable visual graph publications.
     *
     * @param jdbc JDBC template for H2 access
     * @param objectMapper Jackson mapper for publication serialization
     * @return visual graph publication repository
     */
    @Bean
    @ConditionalOnMissingBean
    public VisualGraphPublicationRepository visualGraphPublicationRepository(JdbcTemplate jdbc,
                                                                             ObjectMapper objectMapper) {
        return new DatabaseVisualGraphPublicationRepository(jdbc, objectMapper);
    }

    /**
     * Database-backed repository for visual graph run history records.
     *
     * @param jdbc JDBC template for H2 access
     * @param objectMapper Jackson mapper for run serialization
     * @return visual graph run history repository
     */
    @Bean
    @ConditionalOnMissingBean
    public VisualPayloadGovernancePolicy visualPayloadGovernancePolicy(
            @Value("${gateway.integration.payload-governance.policy-id:resource-gateway-default}") String policyId,
            @Value("${gateway.integration.payload-governance.policy-version:1}") String policyVersion,
            @Value("${gateway.integration.payload-governance.default-classification:RESTRICTED}") String classification,
            @Value("${gateway.integration.payload-governance.required-groups:}") String requiredGroups,
            @Value("${gateway.integration.payload-governance.retention-days.public:30}") long publicDays,
            @Value("${gateway.integration.payload-governance.retention-days.internal:14}") long internalDays,
            @Value("${gateway.integration.payload-governance.retention-days.confidential:7}") long confidentialDays,
            @Value("${gateway.integration.payload-governance.retention-days.restricted:0}") long restrictedDays) {
        return new ConfiguredVisualPayloadGovernancePolicy(policyId, policyVersion, classification,
                commaSeparated(requiredGroups), Map.of(
                "PUBLIC", Duration.ofDays(publicDays),
                "INTERNAL", Duration.ofDays(internalDays),
                "CONFIDENTIAL", Duration.ofDays(confidentialDays),
                "RESTRICTED", Duration.ofDays(restrictedDays)));
    }

    /** Separate payload vault; run evidence contains only its immutable digest and policy descriptor. */
    @Bean
    @ConditionalOnMissingBean
    public VisualRunPayloadRepository visualRunPayloadRepository(JdbcTemplate jdbc,
                                                                  ObjectMapper objectMapper,
                                                                  VisualPayloadGovernancePolicy policy,
                                                                  VisualEvidenceSigner evidenceSigner,
                                                                  IntegrationChangeEventOutbox outbox) {
        return new DatabaseVisualRunPayloadRepository(jdbc, objectMapper, policy, evidenceSigner, outbox);
    }

    @Bean
    @ConditionalOnMissingBean
    public VisualGraphRunRepository visualGraphRunRepository(JdbcTemplate jdbc,
                                                             ObjectMapper objectMapper,
                                                             VisualEvidenceSigner evidenceSigner,
                                                             IntegrationChangeEventOutbox outbox,
                                                             VisualRunPayloadRepository payloadRepository) {
        return new DatabaseVisualGraphRunRepository(jdbc, objectMapper, evidenceSigner, outbox,
                payloadRepository);
    }

    /** Durable claim/fencing and signed refinement store for UNKNOWN_COMMIT attempts. */
    @Bean
    @ConditionalOnMissingBean
    public SideEffectReconciliationRepository sideEffectReconciliationRepository(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            IntegrationChangeEventOutbox outbox,
            PlatformTransactionManager transactionManager) {
        return new DatabaseSideEffectReconciliationRepository(
                jdbc, objectMapper, outbox, transactionManager);
    }

    /** Provider-owned adapters are discovered once and exposed through an immutable registry. */
    @Bean
    @ConditionalOnMissingBean
    public SideEffectReconcilerRegistry sideEffectReconcilerRegistry(
            ObjectProvider<SideEffectReconciler> reconcilers) {
        return new SideEffectReconcilerRegistry(reconcilers.orderedStream().toList());
    }

    /** Persistent operator contract suites participate in the same integration event stream. */
    @Bean
    @ConditionalOnMissingBean
    public VisualOperatorContractTestSuiteRepository visualOperatorContractTestSuiteRepository(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            IntegrationChangeEventOutbox outbox) {
        return new DatabaseVisualOperatorContractTestSuiteRepository(jdbc, objectMapper, outbox);
    }

    /** Generic private-network adapter for a KMS/HSM signing sidecar. */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "gateway.integration.evidence-signing.managed.enabled", havingValue = "true")
    public ManagedEvidenceSigningProvider managedEvidenceSigningProvider(
            ObjectMapper objectMapper,
            @Value("${gateway.integration.evidence-signing.managed.base-uri:}") String baseUri,
            @Value("${gateway.integration.evidence-signing.managed.provider-name:enterprise-kms}") String providerName,
            @Value("${gateway.integration.evidence-signing.managed.request-timeout-seconds:3}") long requestTimeoutSeconds,
            @Value("${gateway.integration.evidence-signing.managed.allow-insecure-loopback:false}") boolean allowInsecureLoopback) {
        return new HttpManagedEvidenceSigningProvider(objectMapper,
                new HttpManagedEvidenceSigningProvider.Settings(URI.create(baseUri.trim()),
                        Duration.ofSeconds(requestTimeoutSeconds), providerName, allowInsecureLoopback));
    }

    /** Managed signer keeps only public keys locally and verifies every provider signature before persistence. */
    @Bean
    @ConditionalOnMissingBean(VisualEvidenceSigner.class)
    @ConditionalOnProperty(name = "gateway.integration.evidence-signing.managed.enabled", havingValue = "true")
    public VisualEvidenceSigner managedVisualEvidenceSigner(
            ManagedEvidenceSigningProvider provider,
            @Value("${gateway.integration.evidence-signing.managed.refresh-interval-seconds:30}") long refreshIntervalSeconds,
            @Value("${gateway.integration.evidence-signing.managed.unknown-key-refresh-interval-seconds:5}") long unknownKeyRefreshIntervalSeconds,
            @Value("${gateway.integration.evidence-signing.managed.maximum-snapshot-lifetime-seconds:86400}") long maximumSnapshotLifetimeSeconds) {
        return new ManagedVisualEvidenceSigner(provider, new ManagedVisualEvidenceSigner.Settings(
                Duration.ofSeconds(refreshIntervalSeconds), Duration.ofSeconds(unknownKeyRefreshIntervalSeconds),
                Duration.ofSeconds(maximumSnapshotLifetimeSeconds)));
    }

    /** Persistent local signing authority for demos; production should enable managed evidence signing. */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "gateway.integration.evidence-signing.managed.enabled", havingValue = "false",
            matchIfMissing = true)
    public VisualEvidenceSigner visualEvidenceSigner(JdbcTemplate jdbc) {
        return new DatabaseVisualEvidenceSigner(jdbc);
    }

    /**
     * Independent governance trust anchors used to authorize key-set pin publications.
     *
     * <p>Only public Ed25519 material is accepted. The unavailable implementation keeps trust
     * publication fail-closed until a security-owned M-of-N policy is explicitly configured.</p>
     */
    @Bean
    @ConditionalOnMissingBean
    public EvidenceKeySetTrustStore evidenceKeySetTrustStore(
            ObjectMapper objectMapper,
            @Value("${gateway.integration.evidence-trust.enabled:false}") boolean enabled,
            @Value("${gateway.integration.evidence-trust.trust-domain:}") String trustDomain,
            @Value("${gateway.integration.evidence-trust.log-id:}") String logId,
            @Value("${gateway.integration.evidence-trust.signature-threshold:1}") int signatureThreshold,
            @Value("${gateway.integration.evidence-trust.trusted-authorities-json:}") String authoritiesJson) {
        if (!enabled) {
            return EvidenceKeySetTrustStore.unavailable();
        }
        return ConfiguredEvidenceKeySetTrustStore.fromJson(objectMapper, trustDomain, logId,
                signatureThreshold, authoritiesJson);
    }

    /** Durable, transactionally fenced append-only governance trust publication log. */
    @Bean
    @ConditionalOnMissingBean
    public EvidenceKeySetTrustPublicationRepository evidenceKeySetTrustPublicationRepository(
            JdbcTemplate jdbc, ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager) {
        return new DatabaseEvidenceKeySetTrustPublicationRepository(
                jdbc, objectMapper, transactionManager);
    }

    /** Immutable ANEKE governance feedback store. */
    @Bean
    @ConditionalOnMissingBean
    public GovernanceGateResultRepository governanceGateResultRepository(JdbcTemplate jdbc,
                                                                         ObjectMapper objectMapper,
                                                                         IntegrationChangeEventOutbox outbox) {
        return new DatabaseGovernanceGateResultRepository(jdbc, objectMapper, outbox);
    }

    /**
     * Database-backed repository for visual graph golden regression cases.
     *
     * @param jdbc JDBC template for H2 access
     * @param objectMapper Jackson mapper for case serialization
     * @return visual graph golden case repository
     */
    @Bean
    @ConditionalOnMissingBean
    public VisualGraphGoldenCaseRepository visualGraphGoldenCaseRepository(JdbcTemplate jdbc,
                                                                           ObjectMapper objectMapper) {
        return new DatabaseVisualGraphGoldenCaseRepository(jdbc, objectMapper);
    }

    /**
     * Database-backed repository for latest visual graph golden certifications.
     *
     * @param jdbc JDBC template for H2 access
     * @param objectMapper Jackson mapper for certification serialization
     * @return visual graph golden certification repository
     */
    @Bean
    @ConditionalOnMissingBean
    public VisualGraphGoldenCertificationRepository visualGraphGoldenCertificationRepository(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper) {
        return new DatabaseVisualGraphGoldenCertificationRepository(jdbc, objectMapper);
    }

    /**
     * Database-backed repository for runtime implementation binding proposals.
     *
     * @param jdbc JDBC template for H2 access
     * @param objectMapper Jackson mapper for binding serialization
     * @return runtime binding implementation repository
     */
    @Bean
    @ConditionalOnMissingBean
    public VisualRuntimeBindingImplementationRepository visualRuntimeBindingImplementationRepository(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper) {
        return new DatabaseVisualRuntimeBindingImplementationRepository(jdbc, objectMapper);
    }

    /**
     * Database-backed repository for runtime adapter activation facts.
     *
     * @param jdbc JDBC template for H2 access
     * @param objectMapper Jackson mapper for activation serialization
     * @return runtime adapter activation repository
     */
    @Bean
    @ConditionalOnMissingBean
    public VisualRuntimeAdapterActivationRepository visualRuntimeAdapterActivationRepository(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper) {
        return new DatabaseVisualRuntimeAdapterActivationRepository(jdbc, objectMapper);
    }

    /**
     * Database-backed repository for executable lowering integration facts.
     *
     * @param jdbc JDBC template for H2 access
     * @param objectMapper Jackson mapper for integration serialization
     * @return executable lowering integration repository
     */
    @Bean
    @ConditionalOnMissingBean
    public VisualExecutableLoweringIntegrationRepository visualExecutableLoweringIntegrationRepository(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper) {
        return new DatabaseVisualExecutableLoweringIntegrationRepository(jdbc, objectMapper);
    }

    /**
     * Database-backed repository for runtime rollout observation facts.
     *
     * @param jdbc JDBC template for H2 access
     * @param objectMapper Jackson mapper for observation serialization
     * @return runtime rollout observation repository
     */
    @Bean
    @ConditionalOnMissingBean
    public VisualRuntimeRolloutObservationRepository visualRuntimeRolloutObservationRepository(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper) {
        return new DatabaseVisualRuntimeRolloutObservationRepository(jdbc, objectMapper);
    }

    // ── Interceptors ────────────────────────────────────────────────────
    // CircuitBreakerInterceptor, TenantRateLimiterInterceptor, and
    // ResponseCacheInterceptor are registered via @Component scanning.
    // Override them here only if you need non-default constructor args.

    // ── Context carriers ────────────────────────────────────────────────
    // TenantMdcCarrier is registered via @Component scanning.

    private static String operatorName(Object operator) {
        BlogeOperator blogeOperator = operator.getClass().getAnnotation(BlogeOperator.class);
        if (blogeOperator != null && !blogeOperator.value().isBlank()) {
            return blogeOperator.value().trim();
        }
        String constantName = staticName(operator.getClass());
        if (!constantName.isBlank()) {
            return constantName;
        }
        OperatorMeta operatorMeta = operator.getClass().getAnnotation(OperatorMeta.class);
        if (operatorMeta != null) {
            return operator.getClass().getSimpleName();
        }
        return operator.getClass().getSimpleName();
    }

    private static String staticName(Class<?> type) {
        try {
            Field field = type.getField("NAME");
            int modifiers = field.getModifiers();
            if (Modifier.isStatic(modifiers) && String.class.equals(field.getType())) {
                Object value = field.get(null);
                return value == null ? "" : String.valueOf(value).trim();
            }
        } catch (ReflectiveOperationException ex) {
            return "";
        }
        return "";
    }

    private static Set<String> commaSeparated(String value) {
        Set<String> values = new LinkedHashSet<>();
        Arrays.stream(value == null ? new String[0] : value.split(","))
                .map(String::trim).filter(item -> !item.isBlank()).forEach(values::add);
        return Set.copyOf(values);
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
    private interface HttpAuthMixin {
    }
}
