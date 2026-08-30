package com.leanowtech.bloge.gateway.visualadapter.authoring.resource.config;

import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.ApiResourceConnectionProjectionResolver;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.ApiResourceProjectionCompiler;
import com.leanowtech.bloge.gateway.visualadapter.authoring.resource.DefaultApiResourceProjectionCompiler;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Opt-in adapter-side wiring for API Resource projections.
 *
 * <p>The compiler crosses from visual-owned contracts into gateway runtime
 * descriptor types, so it is assembled behind the visual adapter boundary.
 * A resolver is deliberately required; enabling the authoring runtime without
 * server-side Connection metadata fails during context startup.</p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "gateway.authoring.api-resource", name = "enabled", havingValue = "true")
public class ApiResourceProjectionAdapterConfiguration {

    /** Creates the default compiler only when no application override exists. */
    @Bean
    @ConditionalOnMissingBean(ApiResourceProjectionCompiler.class)
    ApiResourceProjectionCompiler apiResourceProjectionCompiler(ApiResourceConnectionProjectionResolver resolver) {
        return new DefaultApiResourceProjectionCompiler(resolver);
    }
}
