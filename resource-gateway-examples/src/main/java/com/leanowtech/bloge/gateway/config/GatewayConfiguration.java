package com.leanowtech.bloge.gateway.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.NamedType;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.leanowtech.bloge.gateway.carrier.TenantMdcCarrier;
import com.leanowtech.bloge.gateway.expression.BlgeExpressionEvaluator;
import com.leanowtech.bloge.gateway.interceptor.QuotaConfigProvider;
import com.leanowtech.bloge.gateway.operator.HttpResourceOperator;
import com.leanowtech.bloge.gateway.operator.PayloadExtractor;
import com.leanowtech.bloge.gateway.operator.ResponseValidator;
import com.leanowtech.bloge.gateway.operator.UrlTemplateRenderer;
import com.leanowtech.bloge.gateway.resource.DatabaseResourceRegistry;
import com.leanowtech.bloge.gateway.resource.WritableResourceRegistry;
import com.leanowtech.bloge.gateway.visual.catalog.DatabaseOperatorLibraryRegistry;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibraryRegistry;
import com.leanowtech.bloge.gateway.visual.draft.DatabaseGraphDraftRepository;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftRepository;
import com.leanowtech.bloge.gateway.visual.publication.DatabaseVisualGraphPublicationRepository;
import com.leanowtech.bloge.gateway.visual.publication.VisualGraphPublicationRepository;
import com.leanowtech.bloge.gateway.visual.resource.DatabaseResourceDesignContractRegistry;
import com.leanowtech.bloge.gateway.visual.resource.ResourceDesignContractRegistry;
import com.leanowtech.bloge.gateway.visual.runtime.DatabaseVisualGraphRunRepository;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphRunRepository;
import com.leanowtech.bloge.operators.http.HttpRequestInput;
import com.leanowtech.bloge.operators.http.HttpRequestOperator;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

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

    // ── Persistence ─────────────────────────────────────────────────────

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
                                                           ObjectMapper objectMapper) {
        return new DatabaseOperatorLibraryRegistry(jdbc, objectMapper);
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
                                                     ObjectMapper objectMapper) {
        return new DatabaseGraphDraftRepository(jdbc, objectMapper);
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
                                                             ObjectMapper objectMapper) {
        return new DatabaseVisualGraphRunRepository(jdbc, objectMapper);
    }

    // ── Interceptors ────────────────────────────────────────────────────
    // CircuitBreakerInterceptor, TenantRateLimiterInterceptor, and
    // ResponseCacheInterceptor are registered via @Component scanning.
    // Override them here only if you need non-default constructor args.

    // ── Context carriers ────────────────────────────────────────────────
    // TenantMdcCarrier is registered via @Component scanning.

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
    private interface HttpAuthMixin {
    }
}
