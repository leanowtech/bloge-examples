package com.leanowtech.bloge.gateway.visualadapter.authoring.connection.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.authoring.application.connection.ApiConnectionAuthoringFacade;
import com.leanowtech.bloge.gateway.visual.authoring.application.connection.ApiConnectionCheckGateway;
import com.leanowtech.bloge.gateway.visual.authoring.connection.ApiConnectionDecisions;
import com.leanowtech.bloge.gateway.visual.authoring.connection.persistence.ApiConnectionAuthoringStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Opt-in application assembly for standalone reusable Connection authoring. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "gateway.authoring.api-resource", name = "enabled", havingValue = "true")
public class ApiConnectionAuthoringApplicationConfiguration {
    /** Creates the pure Connection authority decisions shared by facade and persistence. */
    @Bean
    @ConditionalOnMissingBean
    ApiConnectionDecisions apiConnectionDecisions(ObjectMapper mapper) {
        return new ApiConnectionDecisions(mapper);
    }

    /** Creates the deep application module over one lifecycle-complete store seam. */
    @Bean
    @ConditionalOnMissingBean
    ApiConnectionAuthoringFacade apiConnectionAuthoringFacade(ApiConnectionAuthoringStore store,
                                                               ApiConnectionDecisions decisions,
                                                               ObjectProvider<ApiConnectionCheckGateway> checkGateway) {
        return new ApiConnectionAuthoringFacade(store, decisions,
                checkGateway.getIfAvailable(ApiConnectionCheckGateway::unavailable));
    }
}
