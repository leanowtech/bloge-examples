package com.leanowtech.bloge.gateway.visual.authoring.fixture.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.authoring.application.fixture.ReusableFlowFixtureModule;
import com.leanowtech.bloge.gateway.visual.authoring.application.fixture.FixtureSetShareMaterialWriter;
import com.leanowtech.bloge.gateway.visual.authoring.application.fixture.ReusableFlowFixtureShareModule;
import com.leanowtech.bloge.gateway.visual.authoring.application.fixture.FixtureSetReviewMaterialGate;
import com.leanowtech.bloge.gateway.visual.authoring.application.fixture.ReusableFlowFixtureReviewModule;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Opt-in V016-V018 assembly for standalone Fixture authoring and governed sharing. */
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

    /** Creates share orchestration even when the governed writer is intentionally unavailable. */
    @Bean
    @ConditionalOnMissingBean
    ReusableFlowFixtureShareModule reusableFlowFixtureShareModule(
            StandaloneFixtureSetStore store, ReusableFlowPublicationStore publications,
            ObjectProvider<FixtureSetShareMaterialWriter> materialWriter) {
        return new ReusableFlowFixtureShareModule(store, publications,
                materialWriter.getIfAvailable(FixtureSetShareMaterialWriter::unavailable));
    }

    /** Creates review orchestration even when governed review is intentionally unavailable. */
    @Bean
    @ConditionalOnMissingBean
    ReusableFlowFixtureReviewModule reusableFlowFixtureReviewModule(
            StandaloneFixtureSetStore store,
            ObjectProvider<FixtureSetReviewMaterialGate> materialGate) {
        return new ReusableFlowFixtureReviewModule(store,
                materialGate.getIfAvailable(FixtureSetReviewMaterialGate::unavailable));
    }
}
