package com.leanowtech.bloge.graphengine.server.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.checkpoint.CheckpointCodec;
import com.leanowtech.bloge.core.checkpoint.TaskStore;
import com.leanowtech.bloge.core.runtime.checkpoint.ExecutionCheckpointStore;
import com.leanowtech.bloge.core.runtime.event.EventMatcherStore;
import com.leanowtech.bloge.core.runtime.execution.ExecutionStore;
import com.leanowtech.bloge.core.runtime.registry.GraphRegistryStore;
import com.leanowtech.bloge.core.runtime.wait.WaitStore;
import com.leanowtech.bloge.core.runtime.work.WorkItemNotifier;
import com.leanowtech.bloge.core.runtime.work.WorkItemStore;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.core.spi.JsonCodec;
import com.leanowtech.bloge.core.spi.OperatorRegistry;
import com.leanowtech.bloge.core.spi.ScriptOperatorFactory;
import com.leanowtech.bloge.core.spi.TimeSource;
import com.leanowtech.bloge.durable.control.ControlPlaneService;
import com.leanowtech.bloge.durable.codec.JacksonCheckpointCodec;
import com.leanowtech.bloge.graphengine.ai.GraphAuthoringService;
import com.leanowtech.bloge.graphengine.ai.prompt.PromptContextBuilder;
import com.leanowtech.bloge.graphengine.ai.validate.DslValidationPipeline;
import com.leanowtech.bloge.graphengine.mybatis.GraphEngineStoreFactory;
import com.leanowtech.bloge.graphengine.server.rest.CallerContextFilter;
import com.leanowtech.bloge.graphengine.server.rest.BpmnImportController;
import com.leanowtech.bloge.graphengine.server.rest.GraphAuthoringController;
import com.leanowtech.bloge.graphengine.server.rest.GraphEngineConsoleController;
import com.leanowtech.bloge.graphengine.server.rest.GraphDeadLetterController;
import com.leanowtech.bloge.graphengine.server.rest.GraphDefinitionController;
import com.leanowtech.bloge.graphengine.server.rest.GraphDeploymentController;
import com.leanowtech.bloge.graphengine.server.rest.GraphInstanceEventController;
import com.leanowtech.bloge.graphengine.server.rest.GraphInstanceEventFeed;
import com.leanowtech.bloge.graphengine.server.rest.GraphEngineRequestScopeResolver;
import com.leanowtech.bloge.graphengine.server.rest.GraphInstanceController;
import com.leanowtech.bloge.graphengine.server.rest.GraphOperationsController;
import com.leanowtech.bloge.graphengine.server.rest.GraphOperatorInventoryController;
import com.leanowtech.bloge.graphengine.server.rest.GraphRemoteWorkerController;
import com.leanowtech.bloge.graphengine.server.rest.GraphSseConnectionLimiter;
import com.leanowtech.bloge.graphengine.server.rest.GraphTaskController;
import com.leanowtech.bloge.graphengine.server.rest.GraphVersionController;
import com.leanowtech.bloge.graphengine.server.rest.GlobalExceptionHandler;
import com.leanowtech.bloge.graphengine.service.DefaultGraphEngineService;
import com.leanowtech.bloge.graphengine.service.GraphEngineMetricsObserver;
import com.leanowtech.bloge.graphengine.service.GraphEngineRuntimeSupport;
import com.leanowtech.bloge.graphengine.service.GraphEngineService;
import com.leanowtech.bloge.graphengine.service.VersionCompilerCacheSettings;
import com.leanowtech.bloge.graphengine.service.metrics.MicrometerGraphEngineMetricsObserver;
import com.leanowtech.bloge.graphengine.store.GraphEngineStores;
import com.leanowtech.bloge.operators.spi.LlmProvider;
import com.leanowtech.bloge.runtime.audit.AuditJournalStore;
import com.leanowtech.bloge.runtime.engine.DurableGraphEngine;
import com.leanowtech.bloge.runtime.eventjournal.ExecutionEventStore;
import com.leanowtech.bloge.runtime.task.TaskInboxStore;
import com.leanowtech.bloge.runtime.timer.TimerService;
import io.micrometer.core.instrument.MeterRegistry;

import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import javax.sql.DataSource;

/**
 * Auto-configuration for the graph-engine Spring Boot control-plane server.
 *
 * <p>This layer composes the product metadata stores with the existing BLOGE
 * Spring durable runtime wiring, then imports the REST controllers that expose
 * the product control-plane API.</p>
 */
@AutoConfiguration(
        after = DataSourceAutoConfiguration.class,
        afterName = {
                "com.leanowtech.bloge.spring.autoconfigure.BlogeAutoConfiguration",
                "com.leanowtech.bloge.spring.autoconfigure.BlogeDurableAutoConfiguration"
        }
)
@EnableConfigurationProperties(GraphEngineServerProperties.class)
@ConditionalOnClass({GraphEngineService.class, Jackson2ObjectMapperBuilder.class})
public class GraphEngineServerAutoConfiguration {

    /**
     * Contributes the graph-engine server's Jackson polymorphic mix-ins.
     *
     * @return Jackson builder customizer
     */
    @Bean
    Jackson2ObjectMapperBuilderCustomizer graphEngineServerJacksonCustomizer() {
        return GraphEngineServerJacksonSupport::customize;
    }

    /**
     * Provides a durable checkpoint codec when the application has not declared one.
     *
     * @param objectMapper object mapper used for JSON payloads
     * @return checkpoint codec backed by Jackson
     */
    @Bean
    @ConditionalOnMissingBean(CheckpointCodec.class)
    CheckpointCodec checkpointCodec(ObjectProvider<ObjectMapper> objectMapper) {
        ObjectMapper mapper = objectMapper.getIfAvailable();
        return mapper == null ? new JacksonCheckpointCodec() : new JacksonCheckpointCodec(mapper.copy());
    }

    /**
     * Creates a dedicated MyBatis session factory that includes the graph-engine
     * product-layer mappers in addition to the shared durable mapper set.
     *
     * @param dataSource graph-engine data source
     * @param checkpointCodec durable checkpoint codec
     * @param properties server properties
     * @return graph-engine session factory
     */
    @Bean(name = "graphEngineSqlSessionFactory")
    @ConditionalOnMissingBean(name = "graphEngineSqlSessionFactory")
    @ConditionalOnBean(DataSource.class)
    SqlSessionFactory graphEngineSqlSessionFactory(
            @Qualifier("blogeDurableDataSource") ObjectProvider<DataSource> durableDataSource,
            ObjectProvider<DataSource> defaultDataSource,
            CheckpointCodec checkpointCodec,
            GraphEngineServerProperties properties
    ) {
        DataSource dataSource = resolveGraphEngineDataSource(durableDataSource, defaultDataSource);
        if (properties.isMigrateSchema()) {
            GraphEngineStoreFactory.migrateSchema(dataSource);
        }
        return GraphEngineStoreFactory.createSqlSessionFactory(dataSource, checkpointCodec);
    }

    /**
     * Creates the aggregate graph-engine metadata stores used by the product service.
     *
     * @param dataSource graph-engine data source
     * @param checkpointCodec durable checkpoint codec
     * @param sqlSessionFactory graph-engine MyBatis session factory
     * @param timeSource optional logical time source
     * @return aggregate graph-engine stores
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(name = "graphEngineSqlSessionFactory")
    GraphEngineStores graphEngineStores(
            @Qualifier("blogeDurableDataSource") ObjectProvider<DataSource> durableDataSource,
            ObjectProvider<DataSource> defaultDataSource,
            CheckpointCodec checkpointCodec,
            @Qualifier("graphEngineSqlSessionFactory") SqlSessionFactory sqlSessionFactory,
            ObjectProvider<TimeSource> timeSource
    ) {
        DataSource dataSource = resolveGraphEngineDataSource(durableDataSource, defaultDataSource);
        GraphEngineStoreFactory.Builder builder = GraphEngineStoreFactory.builder(dataSource, checkpointCodec)
                .sqlSessionFactory(sqlSessionFactory);
        TimeSource resolvedTimeSource = timeSource.getIfAvailable();
        if (resolvedTimeSource != null) {
            builder.timeSource(resolvedTimeSource);
        }
        return builder.graphEngineStores();
    }

    /**
     * Aggregates the lower-level durable runtime collaborators needed by the
     * product-layer service facade.
     *
     * @return runtime support aggregate
     */
    @Bean
    @ConditionalOnMissingBean
    GraphEngineRuntimeSupport graphEngineRuntimeSupport(ObjectProvider<DurableGraphEngine> durableGraphEngine,
                                                         ObjectProvider<OperatorRegistry> operatorRegistry,
                                                         ObjectProvider<GraphRegistryStore> graphRegistryStore,
                                                         ObjectProvider<ExecutionStore> executionStore,
                                                         ObjectProvider<ExecutionCheckpointStore> executionCheckpointStore,
                                                         ObjectProvider<EventMatcherStore> eventMatcherStore,
                                                         ObjectProvider<WaitStore> waitStore,
                                                         ObjectProvider<TaskInboxStore> taskInboxStore,
                                                         ObjectProvider<TaskStore> taskStore,
                                                         ObjectProvider<AuditJournalStore> auditJournalStore,
                                                         ObjectProvider<WorkItemStore> workItemStore,
                                                         ObjectProvider<WorkItemNotifier> workItemNotifier,
                                                         ObjectProvider<CheckpointCodec> checkpointCodec,
                                                         ObjectProvider<ControlPlaneService> controlPlaneService,
                                                         ObjectProvider<TimerService> timerService,
                                                          ObjectProvider<ScriptOperatorFactory> scriptOperatorFactory,
                                                          ObjectProvider<JsonCodec> jsonCodec,
                                                          ObjectProvider<TimeSource> timeSource,
                                                          ObjectProvider<GraphEngineMetricsObserver> metricsObserver,
                                                          GraphEngineServerProperties properties) {
        return GraphEngineRuntimeSupport.builder()
                .durableGraphEngine(durableGraphEngine.getIfAvailable())
                .operatorRegistry(operatorRegistry.getIfAvailable())
                .graphRegistryStore(graphRegistryStore.getIfAvailable())
                .executionStore(executionStore.getIfAvailable())
                .executionCheckpointStore(executionCheckpointStore.getIfAvailable())
                .eventMatcherStore(eventMatcherStore.getIfAvailable())
                .waitStore(waitStore.getIfAvailable())
                .taskInboxStore(taskInboxStore.getIfAvailable())
                .taskStore(taskStore.getIfAvailable())
                .auditJournalStore(auditJournalStore.getIfAvailable())
                .workItemStore(workItemStore.getIfAvailable())
                .workItemNotifier(workItemNotifier.getIfAvailable(() -> WorkItemNotifier.NOOP))
                .checkpointCodec(checkpointCodec.getIfAvailable())
                .controlPlaneService(controlPlaneService.getIfAvailable())
                .timerService(timerService.getIfAvailable())
                .scriptOperatorFactory(scriptOperatorFactory.getIfAvailable())
                .jsonCodec(jsonCodec.getIfAvailable())
                .timeSource(timeSource.getIfAvailable())
                .metricsObserver(metricsObserver.getIfAvailable())
                .versionCompilerCacheSettings(new VersionCompilerCacheSettings(
                        properties.getCompileCache().isEnabled(),
                        properties.getCompileCache().getMaxSize(),
                        properties.getCompileCache().getTtl()
                ))
                .build();
    }

    /**
     * Creates the product-layer graph-engine service facade used by the HTTP API.
     *
     * @param graphEngineStores product metadata stores
     * @param runtimeSupport durable runtime support aggregate
     * @return graph-engine service facade
     */
    @Bean
    @ConditionalOnMissingBean(GraphEngineService.class)
    @ConditionalOnBean(GraphEngineStores.class)
    GraphEngineService graphEngineService(GraphEngineStores graphEngineStores,
                                          GraphEngineRuntimeSupport runtimeSupport) {
        return new DefaultGraphEngineService(graphEngineStores, runtimeSupport);
    }

    /**
     * Creates the reusable DSL validation pipeline used by the AI authoring endpoints.
     *
     * @param runtimeSupport durable runtime support aggregate
     * @return AI validation pipeline
     */
    @Bean
    @ConditionalOnMissingBean
    DslValidationPipeline dslValidationPipeline(GraphEngineRuntimeSupport runtimeSupport) {
        return DslValidationPipeline.builder()
                .operatorRegistry(runtimeSupport.operatorRegistry())
                .eventMatcherStore(runtimeSupport.eventMatcherStore())
                .executionCheckpointStore(runtimeSupport.executionCheckpointStore())
                .executionStore(runtimeSupport.executionStore())
                .scriptOperatorFactory(runtimeSupport.scriptOperatorFactory())
                .workItemStore(runtimeSupport.workItemStore())
                .workItemNotifier(runtimeSupport.workItemNotifier())
                .jsonCodec(runtimeSupport.jsonCodec())
                .build();
    }

    /**
     * Creates the AI authoring service when the application provides an {@link LlmProvider}.
     *
     * @param validationPipeline shared DSL validation pipeline
     * @param llmProvider model provider used for generation/repair
     * @param operatorRegistry operator registry exposed in the prompt context
     * @return AI authoring service
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(LlmProvider.class)
    GraphAuthoringService graphAuthoringService(DslValidationPipeline validationPipeline,
                                                LlmProvider llmProvider,
                                                ObjectProvider<OperatorRegistry> operatorRegistry) {
        return new GraphAuthoringService(
                llmProvider,
                new PromptContextBuilder(operatorRegistry.getIfAvailable(DefaultOperatorRegistry::new)),
                validationPipeline
        );
    }

    /**
     * Web-only slice that imports the graph-engine REST controllers and error handling.
     */
    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @Import({
            GraphEngineRequestScopeResolver.class,
            GraphAuthoringController.class,
            GraphDefinitionController.class,
            GraphVersionController.class,
            GraphDeploymentController.class,
            GraphInstanceController.class,
            GraphInstanceEventController.class,
            GraphOperationsController.class,
            GraphOperatorInventoryController.class,
            GraphRemoteWorkerController.class,
            GraphDeadLetterController.class,
            GraphTaskController.class,
            GraphEngineConsoleController.class,
            GlobalExceptionHandler.class
    })
    static class WebApiConfiguration {

        @Bean
        @ConditionalOnMissingBean
        GraphSseConnectionLimiter graphSseConnectionLimiter(GraphEngineServerProperties properties) {
            return new GraphSseConnectionLimiter(properties.getMaxSseConnectionsPerTenant());
        }

        @Bean
        @ConditionalOnMissingBean
        GraphInstanceEventFeed graphInstanceEventFeed(ObjectProvider<ExecutionEventStore> executionEventStore,
                                                      ObjectProvider<JsonCodec> jsonCodec) {
            return new GraphInstanceEventFeed(
                    executionEventStore.getIfAvailable(),
                    jsonCodec.getIfAvailable(() -> JsonCodec.DEFAULT)
            );
        }

        /**
         * Registers the {@link CallerContextFilter} so the graph-engine service
         * can enforce RBAC policies on every HTTP request.
         *
         * @return filter registration
         */
        @Bean
        FilterRegistrationBean<CallerContextFilter> callerContextFilterRegistration() {
            FilterRegistrationBean<CallerContextFilter> registration = new FilterRegistrationBean<>();
            registration.setFilter(new CallerContextFilter());
            registration.addUrlPatterns("/api/*");
            registration.setOrder(org.springframework.core.Ordered.HIGHEST_PRECEDENCE + 10);
            return registration;
        }
    }

    /**
     * Conditional slice that imports the BPMN import controller when the
     * {@code bloge-bpmn-transformer} module is on the classpath.
     */
    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(name = "com.leanowtech.bloge.bpmn.api.BpmnTranslator")
    @Import(BpmnImportController.class)
    static class BpmnImportConfiguration {
    }

    /**
     * Conditional slice that publishes the graph-engine metrics observer bean.
     * <p>
     * When a {@link MeterRegistry} bean is present the server wires the
     * Micrometer-backed implementation; otherwise it exposes
     * {@link GraphEngineMetricsObserver#NOOP} so the service layer still
     * receives a stable collaborator.
     */
    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(MeterRegistry.class)
    static class GraphEngineMetricsConfiguration {

        @Bean
        @ConditionalOnMissingBean(GraphEngineMetricsObserver.class)
        GraphEngineMetricsObserver graphEngineMetricsObserver(ObjectProvider<MeterRegistry> meterRegistry) {
            MeterRegistry registry = meterRegistry.getIfAvailable();
            return registry == null
                    ? GraphEngineMetricsObserver.NOOP
                    : new MicrometerGraphEngineMetricsObserver(registry);
        }
    }

    /**
     * Prefers BLOGE's routed durable data source when available, but keeps the standalone server
     * bootable with a plain primary {@link DataSource} during example/test wiring.
     *
     * @param durableDataSource durable/routed BLOGE data source, if the durable slice created one
     * @param defaultDataSource application primary data source fallback
     * @return data source used for graph-engine metadata stores
     */
    static DataSource resolveGraphEngineDataSource(
            ObjectProvider<DataSource> durableDataSource,
            ObjectProvider<DataSource> defaultDataSource
    ) {
        DataSource dataSource = durableDataSource.getIfAvailable();
        if (dataSource != null) {
            return dataSource;
        }
        dataSource = defaultDataSource.getIfAvailable();
        if (dataSource == null) {
            throw new IllegalStateException("GraphEngineServer requires a DataSource");
        }
        return dataSource;
    }
}
