package com.leanowtech.bloge.gateway.capabilitystudio;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.spi.OperatorRegistry;
import com.leanowtech.bloge.gateway.authoring.scenario.ScenarioGovernedCompiler;
import com.leanowtech.bloge.gateway.authoring.scenario.ScenarioGovernedRegistryGateway;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteExecutionService;
import com.leanowtech.bloge.gateway.testing.api.TestExecutionApiService;
import com.leanowtech.bloge.gateway.resource.ResourceDescriptor;
import com.leanowtech.bloge.gateway.resource.WritableResourceRegistry;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.annotation.Value;
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
    CapabilityStudioDeploymentCandidateAuthority capabilityStudioDeploymentCandidateAuthority(
            @Value("${gateway.capability-studio.acceptance.candidate-build.authority:}")
            String authority,
            @Value("${gateway.capability-studio.acceptance.candidate-build.instance-id:}")
            String instanceId,
            @Value("${gateway.capability-studio.acceptance.candidate-build.build-ref:}")
            String buildRef,
            @Value("${gateway.capability-studio.acceptance.candidate-build.revision:}")
            String revision,
            @Value("${gateway.capability-studio.acceptance.candidate-build.source-commit:}")
            String sourceCommit,
            @Value("${gateway.capability-studio.acceptance.candidate-build.source-tree-status:}")
            String sourceTreeStatus,
            @Value("${gateway.capability-studio.acceptance.candidate-build.artifact-fingerprint:}")
            String artifactFingerprint) {
        return new CapabilityStudioDeploymentCandidateAuthority(
                authority, instanceId, buildRef, revision, sourceCommit, sourceTreeStatus,
                artifactFingerprint);
    }

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
    CapabilityStudioScenarioQualityImpactProjection capabilityStudioScenarioQualityImpactProjection(
            CapabilityStudioGoldenDemoPack pack,
            CapabilityStudioScenarioDatasetProjector datasetProjector,
            ObjectMapper mapper) {
        return new CapabilityStudioScenarioQualityImpactProjection(pack, datasetProjector, mapper);
    }

    @Bean
    CapabilityStudioFeatureRehearsalService capabilityStudioFeatureRehearsalService(
            CapabilityStudioGoldenDemoPack pack,
            ObjectMapper mapper,
            OperatorRegistry operatorRegistry,
            WritableResourceRegistry resourceRegistry) {
        bindDemoResourceDescriptors(resourceRegistry);
        return new CapabilityStudioFeatureRehearsalService(
                pack, mapper, operatorRegistry, resourceRegistry);
    }

    static void bindDemoResourceDescriptors(WritableResourceRegistry registry) {
        for (ResourceDescriptor descriptor
                : CapabilityStudioFeatureRehearsalService.demoResourceDescriptors()) {
            if (!registry.contains(descriptor.resourceId())) {
                registry.register(descriptor);
            } else if (!descriptor.equals(registry.resolve(descriptor.resourceId()))) {
                throw new BeanCreationException(
                        "capabilityStudioFeatureRehearsalService",
                        "Conflicting Resource descriptor: " + descriptor.resourceId());
            }
        }
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
            TestSuiteExecutionService executions,
            TestExecutionApiService childExecutions,
            CapabilityStudioDeploymentCandidateAuthority candidateAuthority) {
        return new CapabilityStudioGovernedCandidateService(
                mapper, compiler, publisher, executions, childExecutions, candidateAuthority);
    }

    @Bean
    CapabilityStudioGovernedBaselineService capabilityStudioGovernedBaselineService(
            CapabilityStudioGoldenDemoPack pack,
            ObjectMapper mapper,
            OperatorRegistry operators,
            CapabilityStudioFeatureRehearsalService rehearsal,
            CapabilityStudioScenarioDatasetProjector datasetProjector,
            ScenarioGovernedRegistryGateway registry,
            CapabilityStudioGovernedCandidateService candidate,
            CapabilityStudioDeploymentCandidateAuthority candidateAuthority) {
        // This bean is deliberately composed only in the test/staging demo configuration.
        return new CapabilityStudioGovernedBaselineService(
                pack, mapper, operators, rehearsal, datasetProjector, registry, candidate,
                candidateAuthority);
    }

    @Bean
    CapabilityStudioGovernedRunEvidenceService capabilityStudioGovernedRunEvidenceService(
            CapabilityStudioGoldenDemoPack pack,
            ObjectMapper mapper,
            CapabilityStudioScenarioDatasetProjector datasetProjector,
            ScenarioGovernedRegistryGateway registry,
            CapabilityStudioGovernedCompilationService governedCompilation,
            TestExecutionApiService executions) {
        // Evidence reads are deliberately available only with the non-production demo authority.
        return new CapabilityStudioGovernedRunEvidenceService(
                pack, mapper, datasetProjector, registry, governedCompilation, executions);
    }
}
