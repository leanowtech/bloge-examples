package com.leanowtech.bloge.gateway.solution.feature;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddExecutionService;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddStateRepository;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibraryRegistry;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftRepository;
import com.leanowtech.bloge.gateway.visual.importer.DslImportService;
import com.leanowtech.bloge.gateway.visual.simulation.VisualGraphSimulationService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Connects protected Feature suites to the same compile-and-simulate kernel exposed by the
 * Agent TDD rehearsal boundary.
 *
 * <p>The runner receives decrypted case material only for the duration of one controlled run.
 * Its public result is payload-free, and the reused simulation kernel keeps external egress at
 * zero before suite evidence can satisfy engineering fulfillment.</p>
 */
@Configuration(proxyBeanMethods = false)
public class FeatureControlledExecutionConfiguration {

    /**
     * Creates the production controlled-case adapter unless a deployment supplies a stricter one.
     *
     * @param libraries governed operator libraries used by the authored Feature graph
     * @param drafts exact persisted Feature graph drafts
     * @param projection production BLOGE DSL projection service
     * @param simulation zero-egress visual simulation service
     * @param mapper canonical JSON mapper
     * @param states durable Agent TDD state used for exact case resolution
     * @return controlled Feature runner backed by the production rehearsal kernel
     */
    @Bean
    @ConditionalOnMissingBean(FeatureControlledCaseRunner.class)
    public FeatureControlledCaseRunner featureControlledCaseRunner(
            OperatorLibraryRegistry libraries,
            GraphDraftRepository drafts,
            DslImportService projection,
            VisualGraphSimulationService simulation,
            ObjectMapper mapper,
            AgentTddStateRepository states) {
        AgentTddExecutionService execution = new AgentTddExecutionService(
                libraries, drafts, projection, simulation, mapper, states);
        return FeatureControlledCaseRunner.using(execution, mapper);
    }
}
