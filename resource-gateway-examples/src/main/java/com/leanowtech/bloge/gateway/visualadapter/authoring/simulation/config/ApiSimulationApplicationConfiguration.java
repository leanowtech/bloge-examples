package com.leanowtech.bloge.gateway.visualadapter.authoring.simulation.config;

import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.FixtureSetAuthorityReader;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.ParentFlowApplyCaseCompiler;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowPublicationStore;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowDraftStore;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.ApiResourceCommitStore;
import com.leanowtech.bloge.gateway.visual.authoring.simulation.FixtureAssetSimulationResolver;
import com.leanowtech.bloge.gateway.visual.authoring.simulation.FixturePlanCompiler;
import com.leanowtech.bloge.gateway.visual.authoring.simulation.SimulationFixtureUsageRecorder;
import com.leanowtech.bloge.gateway.visual.authoring.simulation.SimulationModule;
import com.leanowtech.bloge.gateway.visual.authoring.simulation.SimulationModuleV2;
import com.leanowtech.bloge.gateway.visual.authoring.simulation.SimulationReplayResolver;
import com.leanowtech.bloge.gateway.visual.authoring.simulation.SimulationRunStore;
import com.leanowtech.bloge.gateway.visual.authoring.simulation.SimulationRunV2Store;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Opt-in application assembly for exact Fixture Case simulations. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "gateway.authoring.api-resource", name = "enabled", havingValue = "true")
public class ApiSimulationApplicationConfiguration {
    /** Creates the single immutable Fixture Plan compiler shared by v2 execution paths. */
    @Bean
    @ConditionalOnMissingBean
    FixturePlanCompiler fixturePlanCompiler(FixtureSetAuthorityReader fixtures) {
        return new FixturePlanCompiler(fixtures);
    }

    /** Fails startup when any Resource, Fixture or V013 authority is absent. */
    @Bean
    @ConditionalOnMissingBean
    SimulationModule simulationModule(ApiResourceCommitStore resources, FixtureSetAuthorityReader fixtures,
                                      SimulationRunStore runs,
                                      ObjectProvider<ReusableFlowPublicationStore> flows,
                                      ObjectProvider<ReusableFlowDraftStore> drafts,
                                      ObjectProvider<ParentFlowApplyCaseCompiler> parentCompilers,
                                      ObjectProvider<FixtureAssetSimulationResolver> fixtureAssets) {
        ReusableFlowPublicationStore flowAuthority = flows.getIfAvailable();
        ReusableFlowDraftStore draftAuthority = drafts.getIfAvailable();
        ParentFlowApplyCaseCompiler parentCompiler = parentCompilers.getIfAvailable();
        return new SimulationModule(resources, fixtures, new SimulationModule.Authorities(
                flowAuthority, draftAuthority, parentCompiler, fixtureAssets.getIfAvailable()), runs);
    }

    /**
     * Fails startup unless v2 plan, Resource and V013 authorities are all available.
     * Optional protected-material, replay and usage ports remain fail-closed when absent.
     */
    @Bean
    @ConditionalOnMissingBean
    SimulationModuleV2 simulationModuleV2(
            ApiResourceCommitStore resources, FixturePlanCompiler plans, SimulationRunV2Store runs,
            ObjectProvider<FixtureAssetSimulationResolver> fixtureAssets,
            ObjectProvider<SimulationReplayResolver> replays,
            ObjectProvider<SimulationFixtureUsageRecorder> usage) {
        return new SimulationModuleV2(resources, plans, fixtureAssets.getIfAvailable(),
                replays.getIfAvailable(), usage.getIfAvailable(), runs);
    }
}
