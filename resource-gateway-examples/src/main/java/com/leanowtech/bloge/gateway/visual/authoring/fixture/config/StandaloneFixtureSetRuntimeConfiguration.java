package com.leanowtech.bloge.gateway.visual.authoring.fixture.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.authoring.application.fixture.ReusableFlowFixtureModule;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.ParentFlowApplyCaseCompiler;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.WholeFlowFixtureMaterializer;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.FixtureSetAuthorityReader;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.JdbcStandaloneFixtureSetStore;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.StandaloneFixtureSetSchemaReadiness;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.StandaloneFixtureSetStore;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ComposableCatalog;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowDraftStore;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowPublicationStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Opt-in V016 assembly for standalone whole-flow Fixture Set authoring. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "gateway.authoring.reusable-flow", name = "enabled", havingValue = "true")
public class StandaloneFixtureSetRuntimeConfiguration {
    @Bean
    @ConditionalOnMissingBean
    StandaloneFixtureSetSchemaReadiness standaloneFixtureSetSchemaReadiness(JdbcTemplate jdbc) {
        return new StandaloneFixtureSetSchemaReadiness(jdbc);
    }

    @Bean
    @ConditionalOnMissingBean(StandaloneFixtureSetStore.class)
    StandaloneFixtureSetStore standaloneFixtureSetStore(
            JdbcTemplate jdbc, PlatformTransactionManager transactionManager, ObjectMapper mapper,
            StandaloneFixtureSetSchemaReadiness readiness) {
        if (readiness == null) throw new IllegalStateException("Standalone Fixture schema is not ready");
        return new JdbcStandaloneFixtureSetStore(
                jdbc, new TransactionTemplate(transactionManager), mapper);
    }

    @Bean
    @ConditionalOnMissingBean
    WholeFlowFixtureMaterializer wholeFlowFixtureMaterializer() {
        return new WholeFlowFixtureMaterializer();
    }

    @Bean
    @ConditionalOnMissingBean
    ParentFlowApplyCaseCompiler parentFlowApplyCaseCompiler(
            ComposableCatalog catalog, FixtureSetAuthorityReader fixtures) {
        return new ParentFlowApplyCaseCompiler(catalog, fixtures);
    }

    @Bean
    @ConditionalOnMissingBean
    ReusableFlowFixtureModule reusableFlowFixtureModule(
            ReusableFlowPublicationStore publications, ReusableFlowDraftStore drafts,
            StandaloneFixtureSetStore store,
            WholeFlowFixtureMaterializer materializer, ParentFlowApplyCaseCompiler parentCompiler) {
        return new ReusableFlowFixtureModule(publications, drafts, store, materializer, parentCompiler);
    }
}
