package com.leanowtech.bloge.gateway.visualadapter.authoring.flow.config;

import com.leanowtech.bloge.gateway.visual.authoring.flow.ComposableCatalog;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowCompiler;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowDraftStore;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowModule;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.ApiResourceCommitStore;
import com.leanowtech.bloge.gateway.visualadapter.authoring.flow.ApiResourceComposableCatalog;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Fail-closed application assembly for reusable Tool/Solution draft authoring. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "gateway.authoring.reusable-flow", name = "enabled", havingValue = "true")
public class ReusableFlowAuthoringApplicationConfiguration {
    /** Resolves only exact committed API Resource revisions in this slice. */
    @Bean
    @ConditionalOnMissingBean(ComposableCatalog.class)
    ComposableCatalog reusableFlowComposableCatalog(ApiResourceCommitStore resources) {
        return new ApiResourceComposableCatalog(resources);
    }

    /** Creates the single Mapping/DAG compiler over the exact catalog. */
    @Bean
    @ConditionalOnMissingBean
    ReusableFlowCompiler reusableFlowCompiler(ComposableCatalog catalog) {
        return new ReusableFlowCompiler(catalog);
    }

    /** Creates the deep compile/save/read module over one durable store. */
    @Bean
    @ConditionalOnMissingBean
    ReusableFlowModule reusableFlowModule(ReusableFlowCompiler compiler, ReusableFlowDraftStore store) {
        return new ReusableFlowModule(compiler, store);
    }
}
