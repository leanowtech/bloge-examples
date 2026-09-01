package com.leanowtech.bloge.gateway.visualadapter.authoring.resource.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.authoring.application.resource.ApiResourceAuthoringFacade;
import com.leanowtech.bloge.gateway.visual.authoring.connection.persistence.ApiConnectionAuthoringStore;
import com.leanowtech.bloge.gateway.visual.authoring.connection.ApiConnectionDecisions;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.DefaultFixtureSetMaterializer;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.ApiFixtureSetCommitStore;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceDecisions;
import com.leanowtech.bloge.gateway.visual.authoring.resource.openapi.OpenApiPreviewModule;
import com.leanowtech.bloge.gateway.visual.authoring.resource.openapi.RemoteOpenApiDocumentGateway;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.ApiResourceCommitStore;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.ApiResourceConnectionProjectionResolver;
import com.leanowtech.bloge.gateway.visual.resource.OpenApiResourceDesignContractImporter;
import com.leanowtech.bloge.gateway.visual.simulation.JsonSchemaSampleGenerator;
import com.leanowtech.bloge.gateway.visualadapter.authoring.resource.ApiConnectionStoreResourceProjectionResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionOperations;

/** Opt-in adapter-side assembly for the first compound Resource-save tracer. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "gateway.authoring.api-resource", name = "enabled", havingValue = "true")
public class ApiResourceAuthoringApplicationConfiguration {

    /** Validates nested credential-free Connection commands before consuming a Resource claim. */
    @Bean
    @ConditionalOnMissingBean
    ApiConnectionDecisions apiConnectionDecisions(ObjectMapper mapper) {
        return new ApiConnectionDecisions(mapper);
    }

    /** Creates the side-effect-free OpenAPI preview module over the existing visual importer. */
    @Bean
    @ConditionalOnMissingBean
    OpenApiPreviewModule openApiPreviewModule(OpenApiResourceDesignContractImporter importer,
                                              JsonSchemaSampleGenerator samples,
                                              ObjectMapper mapper,
                                              ApiResourceDecisions decisions,
                                              ObjectProvider<RemoteOpenApiDocumentGateway> remoteDocuments) {
        return new OpenApiPreviewModule(importer, samples, mapper, decisions,
                remoteDocuments.getIfAvailable(RemoteOpenApiDocumentGateway::unavailable));
    }

    /** Resolves projection inputs from the same committed Connection authority used by the facade. */
    @Bean
    @ConditionalOnMissingBean(ApiResourceConnectionProjectionResolver.class)
    ApiResourceConnectionProjectionResolver apiResourceConnectionProjectionResolver(
            ApiConnectionAuthoringStore connections) {
        return new ApiConnectionStoreResourceProjectionResolver(connections);
    }

    /** Creates the application facade only when both lifecycle authorities are explicit. */
    @Bean
    @ConditionalOnMissingBean
    ApiResourceAuthoringFacade apiResourceAuthoringFacade(ApiResourceCommitStore resources,
                                                           ApiConnectionAuthoringStore connections,
                                                           ApiResourceDecisions decisions,
                                                           ApiConnectionDecisions connectionDecisions,
                                                           ObjectProvider<ApiFixtureSetCommitStore> fixtures,
                                                           ObjectProvider<DefaultFixtureSetMaterializer> materializer,
                                                           ObjectProvider<PlatformTransactionManager> transactionManager) {
        ApiFixtureSetCommitStore fixtureStore = fixtures.getIfAvailable();
        DefaultFixtureSetMaterializer fixtureMaterializer = materializer.getIfAvailable();
        if (fixtureStore == null && fixtureMaterializer == null) {
            return new ApiResourceAuthoringFacade(resources, connections, decisions,
                    null, null, TransactionOperations.withoutTransaction(), connectionDecisions);
        }
        if (fixtureStore == null || fixtureMaterializer == null || transactionManager.getIfAvailable() == null) {
            throw new IllegalStateException("Default Fixture persistence requires one transaction manager");
        }
        return new ApiResourceAuthoringFacade(resources, connections, decisions, fixtureStore,
                fixtureMaterializer, new TransactionTemplate(transactionManager.getObject()), connectionDecisions);
    }
}
