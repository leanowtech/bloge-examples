package com.leanowtech.bloge.gateway.visualadapter.authoring.resource.config;

import com.leanowtech.bloge.gateway.visual.authoring.application.resource.ApiResourceAuthoringFacade;
import com.leanowtech.bloge.gateway.visual.authoring.connection.persistence.ApiConnectionAuthoringStore;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceDecisions;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.ApiResourceCommitStore;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.ApiResourceConnectionProjectionResolver;
import com.leanowtech.bloge.gateway.visualadapter.authoring.resource.ApiConnectionStoreResourceProjectionResolver;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Opt-in adapter-side assembly for the first compound Resource-save tracer. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "gateway.authoring.api-resource", name = "enabled", havingValue = "true")
public class ApiResourceAuthoringApplicationConfiguration {

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
                                                           ApiResourceDecisions decisions) {
        return new ApiResourceAuthoringFacade(resources, connections, decisions);
    }
}
