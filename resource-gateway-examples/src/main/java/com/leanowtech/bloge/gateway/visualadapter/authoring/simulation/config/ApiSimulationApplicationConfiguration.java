package com.leanowtech.bloge.gateway.visualadapter.authoring.simulation.config;

import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.FixtureSetAuthorityReader;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.ParentFlowApplyCaseCompiler;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowPublicationStore;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowDraftStore;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.ApiResourceCommitStore;
import com.leanowtech.bloge.gateway.visual.authoring.simulation.SimulationModule;
import com.leanowtech.bloge.gateway.visual.authoring.simulation.SimulationRunStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Opt-in application assembly for exact Fixture Case simulations. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "gateway.authoring.api-resource", name = "enabled", havingValue = "true")
public class ApiSimulationApplicationConfiguration {
    /** Fails startup when any Resource, Fixture or V013 authority is absent. */
    @Bean
    @ConditionalOnMissingBean
    SimulationModule simulationModule(ApiResourceCommitStore resources, FixtureSetAuthorityReader fixtures,
                                      SimulationRunStore runs,
                                      ObjectProvider<ReusableFlowPublicationStore> flows,
                                      ObjectProvider<ReusableFlowDraftStore> drafts,
                                      ObjectProvider<ParentFlowApplyCaseCompiler> parentCompilers) {
        ReusableFlowPublicationStore flowAuthority = flows.getIfAvailable();
        ReusableFlowDraftStore draftAuthority = drafts.getIfAvailable();
        ParentFlowApplyCaseCompiler parentCompiler = parentCompilers.getIfAvailable();
        if (flowAuthority == null && draftAuthority == null) {
            return new SimulationModule(resources, fixtures, runs);
        }
        return new SimulationModule(resources, fixtures, flowAuthority, draftAuthority,
                parentCompiler, runs);
    }
}
