package com.leanowtech.bloge.gateway.capabilitystudio;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.spi.OperatorRegistry;
import com.leanowtech.bloge.gateway.authoring.scenario.ScenarioGovernedCompiler;
import com.leanowtech.bloge.gateway.authoring.scenario.ScenarioGovernedRegistryGateway;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteExecutionService;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Opt-in non-production composition for the Capability Studio demo authority. */
@Configuration(proxyBeanMethods = false)
@Profile("!production & (test | staging)")
@ConditionalOnProperty(prefix = "gateway.capability-studio.demo", name = "enabled", havingValue = "true")
public class CapabilityStudioDemoConfiguration {

    @Bean
    CapabilityStudioGoldenDemoPack capabilityStudioGoldenDemoPack(ObjectMapper mapper) {
        try {
            return new CapabilityStudioGoldenDemoPackLoader().load(mapper);
        } catch (RuntimeException failure) {
            throw new BeanCreationException("capabilityStudioGoldenDemoPack", "Invalid golden demo pack", failure);
        }
    }

    @Bean
    CapabilityStudioTutorialBranchRepository capabilityStudioTutorialBranchRepository(
            JdbcTemplate jdbc) {
        return new CapabilityStudioTutorialBranchRepository(jdbc);
    }

    @Bean
    TransactionTemplate capabilityStudioTutorialBranchTransactions(
            PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }

    @Bean
    CapabilityStudioTutorialBranchAuthority capabilityStudioTutorialBranchAuthority(
            CapabilityStudioTutorialBranchRepository repository,
            CapabilityStudioGoldenDemoPack pack,
            ObjectMapper mapper,
            TransactionTemplate capabilityStudioTutorialBranchTransactions) {
        return new CapabilityStudioTutorialBranchAuthority(
                repository, pack, mapper, capabilityStudioTutorialBranchTransactions);
    }

    @Bean
    CapabilityStudioScenarioDatasetProjector capabilityStudioScenarioDatasetProjector(
            CapabilityStudioGoldenDemoPack pack,
            ObjectMapper mapper) {
        return new CapabilityStudioScenarioDatasetProjector(pack, mapper);
    }

    @Bean
    CapabilityStudioFeatureRehearsalService capabilityStudioFeatureRehearsalService(
            CapabilityStudioGoldenDemoPack pack,
            ObjectMapper mapper,
            OperatorRegistry operatorRegistry) {
        return new CapabilityStudioFeatureRehearsalService(pack, mapper, operatorRegistry);
    }

    @Bean
    CapabilityStudioFeatureRehearsalOracle capabilityStudioFeatureRehearsalOracle(
            ObjectMapper mapper) {
        return new CapabilityStudioFeatureRehearsalOracle(mapper);
    }

    @Bean
    CapabilityStudioFeatureRehearsalBaselineService capabilityStudioFeatureRehearsalBaselineService(
            CapabilityStudioGoldenDemoPack pack,
            CapabilityStudioFeatureRehearsalService rehearsal,
            CapabilityStudioFeatureRehearsalOracle oracle) {
        return new CapabilityStudioFeatureRehearsalBaselineService(pack, rehearsal, oracle);
    }

    @Bean
    CapabilityStudioGovernedCompilationService capabilityStudioGovernedCompilationService(
            ObjectMapper mapper,
            ScenarioGovernedCompiler compiler) {
        return new CapabilityStudioGovernedCompilationService(mapper, compiler);
    }

    @Bean
    CapabilityStudioGovernedAssetPublisher capabilityStudioGovernedAssetPublisher(
            ObjectMapper mapper,
            ScenarioGovernedRegistryGateway registry) {
        return new CapabilityStudioGovernedAssetPublisher(mapper, registry);
    }

    @Bean
    CapabilityStudioGovernedCandidateService capabilityStudioGovernedCandidateService(
            ObjectMapper mapper,
            CapabilityStudioGovernedCompilationService compiler,
            CapabilityStudioGovernedAssetPublisher publisher,
            TestSuiteExecutionService executions) {
        return new CapabilityStudioGovernedCandidateService(mapper, compiler, publisher, executions);
    }

    @Bean
    CapabilityStudioGovernedBaselineService capabilityStudioGovernedBaselineService(
            CapabilityStudioGoldenDemoPack pack,
            ObjectMapper mapper,
            OperatorRegistry operators,
            CapabilityStudioFeatureRehearsalService rehearsal,
            CapabilityStudioScenarioDatasetProjector datasetProjector,
            ScenarioGovernedRegistryGateway registry,
            CapabilityStudioGovernedCandidateService candidate) {
        // This bean is deliberately composed only in the test/staging demo configuration.
        return new CapabilityStudioGovernedBaselineService(
                pack, mapper, operators, rehearsal, datasetProjector, registry, candidate);
    }
}
