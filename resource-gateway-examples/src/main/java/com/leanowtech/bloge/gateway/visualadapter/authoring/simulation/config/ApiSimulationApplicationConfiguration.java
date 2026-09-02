package com.leanowtech.bloge.gateway.visualadapter.authoring.simulation.config;

import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.FixtureSetAuthorityReader;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.ParentFlowApplyCaseCompiler;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowPublicationStore;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowDraftStore;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.ApiResourceCommitStore;
import com.leanowtech.bloge.gateway.visual.authoring.simulation.FixtureAssetSimulationResolver;
import com.leanowtech.bloge.gateway.visual.authoring.simulation.FlowFixturePlanCompilerV2;
import com.leanowtech.bloge.gateway.visual.authoring.simulation.FlowSimulationModuleV2;
import com.leanowtech.bloge.gateway.visual.authoring.simulation.FixturePlanCompiler;
import com.leanowtech.bloge.gateway.visual.authoring.simulation.SimulationFixtureUsageRecorder;
import com.leanowtech.bloge.gateway.visual.authoring.simulation.SimulationModule;
import com.leanowtech.bloge.gateway.visual.authoring.simulation.SimulationModuleV2;
import com.leanowtech.bloge.gateway.visual.authoring.simulation.SimulationReplayResolver;
import com.leanowtech.bloge.gateway.visual.authoring.simulation.SimulationRunStore;
import com.leanowtech.bloge.gateway.visual.authoring.simulation.SimulationRunV2Store;
import com.leanowtech.bloge.gateway.visual.authoring.simulation.ComponentSimulationAuthorityV2;
import com.leanowtech.bloge.gateway.visual.authoring.simulation.ComponentCallSiteRuntimeV2;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibraryRegistry;
import com.leanowtech.bloge.gateway.visualadapter.authoring.simulation.CatalogComponentSimulationAuthorityV2;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Opt-in application assembly for exact Fixture Case simulations. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "gateway.authoring.api-resource", name = "enabled", havingValue = "true")
public class ApiSimulationApplicationConfiguration {
    /** Resolves exact Operator revisions and the immutable server built-in catalog. */
    @Bean
    @ConditionalOnMissingBean
    ComponentSimulationAuthorityV2 componentSimulationAuthorityV2(
            ObjectProvider<OperatorLibraryRegistry> libraries) {
        return new CatalogComponentSimulationAuthorityV2(
                libraries.getIfAvailable(OperatorLibraryRegistry::empty));
    }

    /** Creates the single immutable Fixture Plan compiler shared by v2 execution paths. */
    @Bean
    @ConditionalOnMissingBean
    FixturePlanCompiler fixturePlanCompiler(FixtureSetAuthorityReader fixtures) {
        return new FixturePlanCompiler(fixtures);
    }

    /** Compiles exact draft/version topology when reusable-Flow authorities are installed. */
    @Bean
    @ConditionalOnMissingBean
    FlowFixturePlanCompilerV2 flowFixturePlanCompilerV2(
            ObjectProvider<ReusableFlowPublicationStore> publications,
            ObjectProvider<ReusableFlowDraftStore> drafts, FixturePlanCompiler fixtures,
            ComponentSimulationAuthorityV2 components) {
        return new FlowFixturePlanCompilerV2(
                publications.getIfAvailable(), drafts.getIfAvailable(), fixtures, components);
    }

    /** Executes local Flow topology while external API fallbacks remain fail-closed. */
    @Bean
    @ConditionalOnMissingBean
    FlowSimulationModuleV2 flowSimulationModuleV2(
            ApiResourceCommitStore resources, FlowFixturePlanCompilerV2 plans,
            ObjectProvider<FixtureAssetSimulationResolver> fixtureAssets,
            ObjectProvider<SimulationReplayResolver> replays,
            ObjectProvider<SimulationFixtureUsageRecorder> usage,
            ObjectProvider<ComponentCallSiteRuntimeV2> componentRuntime,
            SimulationRunV2Store runs) {
        return new FlowSimulationModuleV2(resources, plans, fixtureAssets.getIfAvailable(),
                replays.getIfAvailable(), usage.getIfAvailable(), runs,
                componentRuntime.getIfAvailable());
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
            ObjectProvider<SimulationFixtureUsageRecorder> usage,
            FlowSimulationModuleV2 flows, ComponentSimulationAuthorityV2 components) {
        return new SimulationModuleV2(resources, plans, fixtureAssets.getIfAvailable(),
                replays.getIfAvailable(), usage.getIfAvailable(), runs, flows, components);
    }
}
