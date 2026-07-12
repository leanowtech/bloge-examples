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
import com.leanowtech.bloge.gateway.integration.DatabaseIntegrationChangeEventOutbox;
import com.leanowtech.bloge.gateway.integration.GovernanceGateResultRepository;
import com.leanowtech.bloge.gateway.integration.IntegrationChangeEventOutbox;
import com.leanowtech.bloge.gateway.operator.HttpResourceOperator;
import com.leanowtech.bloge.gateway.operator.PayloadExtractor;
import com.leanowtech.bloge.gateway.operator.ResponseValidator;
import com.leanowtech.bloge.gateway.operator.UrlTemplateRenderer;
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
import com.leanowtech.bloge.gateway.visual.runtime.DatabaseVisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.DatabaseVisualRuntimeAdapterActivationRepository;
import com.leanowtech.bloge.gateway.visual.runtime.DatabaseVisualRuntimeRolloutObservationRepository;
import com.leanowtech.bloge.gateway.visual.runtime.VisualExecutableLoweringIntegrationRepository;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphPublicationOperator;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphRunRepository;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphRunService;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRuntimeAdapterActivationRepository;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRuntimeRolloutObservationRepository;
import com.leanowtech.bloge.gateway.visual.testing.DatabaseVisualOperatorContractTestSuiteRepository;
import com.leanowtech.bloge.gateway.visual.testing.VisualOperatorContractTestSuiteRepository;
import com.leanowtech.bloge.operators.http.HttpRequestInput;
import com.leanowtech.bloge.operators.http.HttpRequestOperator;
import com.leanowtech.bloge.spring.annotation.BlogeOperator;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
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
    public VisualGraphRunRepository visualGraphRunRepository(JdbcTemplate jdbc,
                                                             ObjectMapper objectMapper,
                                                             VisualEvidenceSigner evidenceSigner,
                                                             IntegrationChangeEventOutbox outbox) {
        return new DatabaseVisualGraphRunRepository(jdbc, objectMapper, evidenceSigner, outbox);
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

    /** Persistent local signing authority; replace with a KMS-backed bean in enterprise deployments. */
    @Bean
    @ConditionalOnMissingBean
    public VisualEvidenceSigner visualEvidenceSigner(JdbcTemplate jdbc) {
        return new DatabaseVisualEvidenceSigner(jdbc);
    }

    /** Immutable ANEKE governance feedback store. */
    @Bean
    @ConditionalOnMissingBean
    public GovernanceGateResultRepository governanceGateResultRepository(JdbcTemplate jdbc,
                                                                         ObjectMapper objectMapper) {
        return new DatabaseGovernanceGateResultRepository(jdbc, objectMapper);
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

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
    private interface HttpAuthMixin {
    }
}
