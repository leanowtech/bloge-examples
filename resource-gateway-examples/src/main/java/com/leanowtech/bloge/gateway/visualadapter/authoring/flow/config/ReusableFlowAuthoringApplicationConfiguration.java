package com.leanowtech.bloge.gateway.visualadapter.authoring.flow.config;

import com.leanowtech.bloge.gateway.visual.authoring.flow.ComposableCatalog;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowCompiler;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowDraftStore;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowModule;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowPublicationStore;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.ApiResourceCommitStore;
import com.leanowtech.bloge.gateway.visual.importer.DslImportService;
import com.leanowtech.bloge.gateway.visualadapter.authoring.flow.ApiResourceComposableCatalog;
import com.leanowtech.bloge.gateway.visualadapter.authoring.flow.ReusableFlowDslProjector;
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
    ComposableCatalog reusableFlowComposableCatalog(ApiResourceCommitStore resources,
                                                     ReusableFlowPublicationStore publications) {
        return new ApiResourceComposableCatalog(resources, publications);
    }

    /** Creates the single Mapping/DAG compiler over the exact catalog. */
    @Bean
    @ConditionalOnMissingBean
    ReusableFlowCompiler reusableFlowCompiler(ComposableCatalog catalog) {
        return new ReusableFlowCompiler(catalog);
    }

    /** Reuses the official BLOGE importer to project DSL-first requests into the canonical command. */
    @Bean
    @ConditionalOnMissingBean
    ReusableFlowDslProjector reusableFlowDslProjector(DslImportService importer) {
        return new ReusableFlowDslProjector(importer);
    }

    /** Creates the deep compile/save/read module over one durable store. */
    @Bean
    @ConditionalOnMissingBean
    ReusableFlowModule reusableFlowModule(ReusableFlowCompiler compiler, ReusableFlowDraftStore store,
                                          ReusableFlowPublicationStore publications) {
        return new ReusableFlowModule(compiler, store, publications);
    }
}
