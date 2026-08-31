package com.leanowtech.bloge.gateway.visualadapter.authoring.fixture.config;

import com.leanowtech.bloge.gateway.visual.authoring.application.fixture.ApiFixtureSetAuthoringFacade;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.ApiFixtureSetCommitStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Opt-in application assembly for private Fixture Set reads. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "gateway.authoring.api-resource", name = "enabled", havingValue = "true")
public class ApiFixtureSetApplicationConfiguration {
    /** Creates the deep read module only when the V012 authority store is available. */
    @Bean
    @ConditionalOnMissingBean
    ApiFixtureSetAuthoringFacade apiFixtureSetAuthoringFacade(ApiFixtureSetCommitStore store) {
        return new ApiFixtureSetAuthoringFacade(store);
    }
}
