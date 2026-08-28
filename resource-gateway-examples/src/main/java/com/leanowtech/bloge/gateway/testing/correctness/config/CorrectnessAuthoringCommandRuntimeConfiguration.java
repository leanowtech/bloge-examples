package com.leanowtech.bloge.gateway.testing.correctness.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.authoring.fixture.AuthoringFixturePayloadProtector;
import com.leanowtech.bloge.gateway.testing.api.TestExecutionApiService;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteExecutionService;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteRegistryService;
import com.leanowtech.bloge.gateway.testing.correctness.compilation.CorrectnessCompilationService;
import com.leanowtech.bloge.gateway.testing.correctness.compilation.CorrectnessCompilationReferenceSource;
import com.leanowtech.bloge.gateway.testing.correctness.compilation.CorrectnessCompiler;
import com.leanowtech.bloge.gateway.testing.correctness.compilation.CorrectnessPublicationService;
import com.leanowtech.bloge.gateway.testing.correctness.compilation.CorrectnessTestingRegistryGateway;
import com.leanowtech.bloge.gateway.testing.correctness.compilation.TestingControlPlaneCorrectnessRegistryGateway;
import com.leanowtech.bloge.gateway.testing.correctness.compilation.ScenarioExternalCompilationReferenceSource;
import com.leanowtech.bloge.gateway.testing.correctness.coverage.CoverageDerivationSource;
import com.leanowtech.bloge.gateway.testing.correctness.coverage.CoverageFreezeReceiptRepository;
import com.leanowtech.bloge.gateway.testing.correctness.coverage.CoverageInventoryService;
import com.leanowtech.bloge.gateway.testing.correctness.coverage.CoverageReviewAuthorizer;
import com.leanowtech.bloge.gateway.testing.correctness.coverage.DatabaseCoverageFreezeReceiptRepository;
import com.leanowtech.bloge.gateway.testing.correctness.fixture.DatabaseFixtureApprovalReceiptRepository;
import com.leanowtech.bloge.gateway.testing.correctness.fixture.DatabaseProtectedFixtureMaterialRepository;
import com.leanowtech.bloge.gateway.testing.correctness.fixture.FixtureApprovalReceiptRepository;
import com.leanowtech.bloge.gateway.testing.correctness.fixture.FixtureCatalogService;
import com.leanowtech.bloge.gateway.testing.correctness.fixture.FixtureMaterialMetadataSource;
import com.leanowtech.bloge.gateway.testing.correctness.fixture.FixtureMaterialRepository;
import com.leanowtech.bloge.gateway.testing.correctness.fixture.FixtureMaterialResolver;
import com.leanowtech.bloge.gateway.testing.correctness.fixture.FixtureMaterialService;
import com.leanowtech.bloge.gateway.testing.correctness.fixture.FixtureReviewAuthorizer;
import com.leanowtech.bloge.gateway.testing.correctness.fixture.FixtureScenarioExternalReferenceSource;
import com.leanowtech.bloge.gateway.testing.correctness.fixture.FixtureSchemaSource;
import com.leanowtech.bloge.gateway.testing.correctness.fixture.FixtureUsageProjectionService;
import com.leanowtech.bloge.gateway.testing.correctness.governance.CorrectnessGovernanceRepository;
import com.leanowtech.bloge.gateway.testing.correctness.governance.CorrectnessGovernanceService;
import com.leanowtech.bloge.gateway.visual.catalog.VisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftRepository;
import com.leanowtech.bloge.gateway.testing.correctness.fixture.RepositoryFixtureMaterialMetadataSource;
import com.leanowtech.bloge.gateway.testing.correctness.oracle.AssertionEvaluatorProfile;
import com.leanowtech.bloge.gateway.testing.correctness.oracle.AssertionSetCompiler;
import com.leanowtech.bloge.gateway.testing.correctness.oracle.AssertionSetService;
import com.leanowtech.bloge.gateway.testing.correctness.oracle.BusinessOracleService;
import com.leanowtech.bloge.gateway.testing.correctness.oracle.DatabaseOracleApprovalReceiptRepository;
import com.leanowtech.bloge.gateway.testing.correctness.oracle.OracleApprovalReceiptRepository;
import com.leanowtech.bloge.gateway.testing.correctness.oracle.OracleBasisSource;
import com.leanowtech.bloge.gateway.testing.correctness.oracle.OracleReviewAuthorizer;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.AssertionSetRepository;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.BusinessOracleRepository;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.CorrectnessDefinitionRepository;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.CoverageInventoryRepository;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.FixtureAssetRepository;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.ScenarioDraftSetV2Repository;
import com.leanowtech.bloge.gateway.testing.correctness.publication.CorrectnessPublicationRepository;
import com.leanowtech.bloge.gateway.testing.correctness.publication.DatabaseCorrectnessPublicationRepository;
import com.leanowtech.bloge.gateway.testing.correctness.run.CorrectnessPreflightFacade;
import com.leanowtech.bloge.gateway.testing.correctness.run.CorrectnessEvidenceCompanionFactory;
import com.leanowtech.bloge.gateway.testing.correctness.run.CorrectnessEvidenceRepository;
import com.leanowtech.bloge.gateway.testing.correctness.run.CorrectnessRunService;
import com.leanowtech.bloge.gateway.testing.correctness.run.CorrectnessVerdictProjector;
import com.leanowtech.bloge.gateway.testing.correctness.run.DatabaseCorrectnessEvidenceRepository;
import com.leanowtech.bloge.gateway.testing.correctness.scenario.DatabaseScenarioCanonicalApprovalReceiptRepository;
import com.leanowtech.bloge.gateway.testing.correctness.scenario.LegacyScenarioV1MigrationAdapter;
import com.leanowtech.bloge.gateway.testing.correctness.scenario.ScenarioCanonicalApprovalReceiptRepository;
import com.leanowtech.bloge.gateway.testing.correctness.scenario.ScenarioClosureValidator;
import com.leanowtech.bloge.gateway.testing.correctness.scenario.ScenarioDraftSetV2Service;
import com.leanowtech.bloge.gateway.testing.correctness.scenario.ScenarioExternalReferenceSource;
import com.leanowtech.bloge.gateway.testing.correctness.scenario.ScenarioReviewAuthorizer;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.visual.simulation.VisualGraphSimulationService;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.util.List;

/** Conditional command/material assembly; missing enterprise authorities fail closed. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        prefix = "gateway.testing.correctness",
        name = "enabled",
        havingValue = "true")
public class CorrectnessAuthoringCommandRuntimeConfiguration {

    @Bean
    @ConditionalOnMissingBean
    CoverageFreezeReceiptRepository coverageFreezeReceiptRepository(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            CorrectnessAuthoringSchemaReadiness readiness
    ) {
        return new DatabaseCoverageFreezeReceiptRepository(jdbc, mapper);
    }

    @Bean
    @ConditionalOnBean({CoverageReviewAuthorizer.class, CoverageDerivationSource.class})
    @ConditionalOnMissingBean
    CoverageInventoryService coverageInventoryService(
            CoverageInventoryRepository inventories,
            CoverageReviewAuthorizer authorizer,
            CoverageDerivationSource derivationSource,
            CoverageFreezeReceiptRepository receipts,
            ObjectMapper mapper
    ) {
        return new CoverageInventoryService(
                inventories, authorizer, derivationSource, receipts, mapper, Clock.systemUTC());
    }

    @Bean
    @ConditionalOnMissingBean
    OracleApprovalReceiptRepository oracleApprovalReceiptRepository(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            CorrectnessAuthoringSchemaReadiness readiness
    ) {
        return new DatabaseOracleApprovalReceiptRepository(jdbc, mapper);
    }

    @Bean
    @ConditionalOnBean({OracleReviewAuthorizer.class, OracleBasisSource.class})
    @ConditionalOnMissingBean
    BusinessOracleService businessOracleService(
            BusinessOracleRepository oracles,
            OracleReviewAuthorizer authorizer,
            OracleBasisSource basisSource,
            OracleApprovalReceiptRepository receipts,
            ObjectMapper mapper
    ) {
        return new BusinessOracleService(
                oracles, authorizer, basisSource, receipts, mapper, Clock.systemUTC());
    }

    @Bean
    @ConditionalOnMissingBean
    AssertionSetCompiler assertionSetCompiler(ObjectMapper mapper) {
        return new AssertionSetCompiler(mapper);
    }

    @Bean
    @ConditionalOnMissingBean
    AssertionEvaluatorProfile assertionEvaluatorProfile() {
        return AssertionEvaluatorProfile.fixtureEvaluatorV1();
    }

    @Bean
    @ConditionalOnMissingBean
    CorrectnessCompiler correctnessCompiler(
            ObjectMapper mapper,
            AssertionSetCompiler assertionCompiler,
            AssertionEvaluatorProfile evaluatorProfile
    ) {
        return new CorrectnessCompiler(mapper, assertionCompiler, evaluatorProfile);
    }

    @Bean
    @ConditionalOnMissingBean
    AssertionSetService assertionSetService(
            AssertionSetRepository assertionSets,
            BusinessOracleRepository oracles,
            AssertionSetCompiler compiler,
            AssertionEvaluatorProfile profile
    ) {
        return new AssertionSetService(assertionSets, oracles, compiler, profile);
    }

    @Bean
    @ConditionalOnMissingBean
    ScenarioCanonicalApprovalReceiptRepository scenarioCanonicalApprovalReceiptRepository(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            CorrectnessAuthoringSchemaReadiness readiness
    ) {
        return new DatabaseScenarioCanonicalApprovalReceiptRepository(jdbc, mapper);
    }

    @Bean
    @ConditionalOnBean({ScenarioReviewAuthorizer.class, ScenarioExternalReferenceSource.class})
    @ConditionalOnMissingBean
    ScenarioDraftSetV2Service scenarioDraftSetV2Service(
            ScenarioDraftSetV2Repository scenarios,
            CoverageInventoryRepository inventories,
            BusinessOracleRepository oracles,
            AssertionSetRepository assertionSets,
            FixtureAssetRepository fixtures,
            List<ScenarioExternalReferenceSource> externalSources,
            ScenarioReviewAuthorizer authorizer,
            ScenarioCanonicalApprovalReceiptRepository receipts,
            ObjectMapper mapper
    ) {
        var fixtureSource = new FixtureScenarioExternalReferenceSource(fixtures);
        ScenarioExternalReferenceSource external = (scope, target, reference) ->
                fixtureSource.referenceIsCurrent(scope, target, reference)
                        || externalSources.stream().anyMatch(source ->
                                source.referenceIsCurrent(scope, target, reference));
        var validator = new ScenarioClosureValidator(
                inventories, oracles, assertionSets, external, mapper);
        return new ScenarioDraftSetV2Service(
                scenarios, validator, authorizer, receipts, mapper, Clock.systemUTC());
    }

    @Bean
    @ConditionalOnBean(ScenarioDraftSetV2Service.class)
    @ConditionalOnMissingBean
    LegacyScenarioV1MigrationAdapter legacyScenarioV1MigrationAdapter() {
        return new LegacyScenarioV1MigrationAdapter();
    }

    @Bean
    @ConditionalOnMissingBean
    FixtureUsageProjectionService fixtureUsageProjectionService(
            ScenarioDraftSetV2Repository scenarios,
            FixtureAssetRepository fixtures
    ) {
        return new FixtureUsageProjectionService(scenarios, fixtures);
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "gateway.testing.correctness.fixture-material",
            name = "enabled",
            havingValue = "true")
    @ConditionalOnMissingBean
    CorrectnessFixtureMaterialSchemaReadiness correctnessFixtureMaterialSchemaReadiness(
            JdbcTemplate jdbc
    ) {
        return new CorrectnessFixtureMaterialSchemaReadiness(jdbc);
    }

    @Bean
    @ConditionalOnBean(CorrectnessFixtureMaterialSchemaReadiness.class)
    @ConditionalOnMissingBean
    FixtureMaterialRepository fixtureMaterialRepository(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            CorrectnessFixtureMaterialSchemaReadiness readiness
    ) {
        return new DatabaseProtectedFixtureMaterialRepository(jdbc, mapper);
    }

    @Bean
    @ConditionalOnBean(FixtureMaterialRepository.class)
    @ConditionalOnMissingBean
    FixtureMaterialService fixtureMaterialService(
            FixtureMaterialRepository repository,
            ObjectMapper mapper,
            @Value("${gateway.testing.correctness.fixture-material.active-key-id:}")
            String activeKeyId,
            @Value("${gateway.testing.correctness.fixture-material.key-ring:}") String keyRing
    ) {
        return new FixtureMaterialService(
                repository,
                AuthoringFixturePayloadProtector.fromConfiguration(activeKeyId, keyRing),
                mapper);
    }

    @Bean
    @ConditionalOnBean(FixtureMaterialRepository.class)
    @ConditionalOnMissingBean
    FixtureMaterialMetadataSource fixtureMaterialMetadataSource(
            FixtureMaterialRepository materials
    ) {
        return new RepositoryFixtureMaterialMetadataSource(materials);
    }

    @Bean
    @ConditionalOnMissingBean
    FixtureApprovalReceiptRepository fixtureApprovalReceiptRepository(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            CorrectnessAuthoringSchemaReadiness readiness
    ) {
        return new DatabaseFixtureApprovalReceiptRepository(jdbc, mapper);
    }

    @Bean
    @ConditionalOnBean({FixtureMaterialMetadataSource.class, FixtureSchemaSource.class,
            FixtureReviewAuthorizer.class})
    @ConditionalOnMissingBean
    FixtureCatalogService fixtureCatalogService(
            FixtureAssetRepository fixtures,
            FixtureMaterialMetadataSource materials,
            FixtureSchemaSource schemas,
            FixtureReviewAuthorizer authorizer,
            FixtureApprovalReceiptRepository receipts,
            ObjectMapper mapper
    ) {
        return new FixtureCatalogService(
                fixtures, materials, schemas, authorizer, receipts, mapper, Clock.systemUTC());
    }

    /**
     * Exposes the graph-node promotion slice only when protected materials are also available.
     *
     * @param drafts authoritative graph-draft repository
     * @param operators exact operator-catalog view
     * @param fixtures governed fixture catalog
     * @param materials protected material write boundary
     * @param mapper canonical JSON mapper
     * @return promotion service
     */
    @Bean
    @ConditionalOnBean({GraphDraftRepository.class, VisualOperatorCatalog.class,
            FixtureCatalogService.class, FixtureMaterialService.class})
    @ConditionalOnMissingBean
    com.leanowtech.bloge.gateway.visualadapter.fixture.GraphNodeFixturePromotionService
            graphNodeFixturePromotionService(
                    GraphDraftRepository drafts,
                    VisualOperatorCatalog operators,
                    FixtureCatalogService fixtures,
                    FixtureMaterialService materials,
                    ObjectMapper mapper) {
        return new com.leanowtech.bloge.gateway.visualadapter.fixture.GraphNodeFixturePromotionService(
                drafts,
                operators,
                fixtures,
                materials::write,
                mapper,
                java.time.Clock.systemUTC());
    }

    /**
     * Exposes the authenticated, payload-free governed Fixture resolver used by visual simulation.
     *
     * @param fixtures scope-authorized Fixture catalog
     * @param materials protected material resolver
     * @return governed simulation resolver
     */
    @Bean
    @ConditionalOnBean({FixtureAssetRepository.class, FixtureMaterialResolver.class})
    @ConditionalOnMissingBean
            com.leanowtech.bloge.gateway.visualadapter.fixture.GovernedFixtureSimulationResolver
            governedFixtureSimulationResolver(
                    FixtureAssetRepository fixtures, FixtureMaterialResolver materials,
                    VisualOperatorCatalog catalog, ObjectMapper mapper) {
        return new com.leanowtech.bloge.gateway.visualadapter.fixture.GovernedFixtureSimulationResolver(
                fixtures, materials, catalog, mapper);
    }

    /** Composes authenticated correctness services behind the visual simulation port. */
    @Bean
    @ConditionalOnBean({VisualGraphSimulationService.class, IntegrationRequestAuthenticator.class})
    @ConditionalOnMissingBean(com.leanowtech.bloge.gateway.visual.simulation.VisualGovernedFixtureSimulationPort.class)
    com.leanowtech.bloge.gateway.visualadapter.GovernedFixtureSimulationAdapter
            governedFixtureSimulationAdapter(
                    VisualGraphSimulationService simulation,
                    IntegrationRequestAuthenticator authenticator,
                    com.leanowtech.bloge.gateway.visualadapter.fixture.GovernedFixtureSimulationResolver resolver) {
        return new com.leanowtech.bloge.gateway.visualadapter.GovernedFixtureSimulationAdapter(
                simulation, authenticator, resolver);
    }

    @Bean
    @ConditionalOnBean(ScenarioExternalReferenceSource.class)
    @ConditionalOnMissingBean
    CorrectnessCompilationReferenceSource correctnessCompilationReferenceSource(
            List<ScenarioExternalReferenceSource> externalSources
    ) {
        return new ScenarioExternalCompilationReferenceSource(externalSources);
    }

    @Bean
    @ConditionalOnBean({FixtureMaterialResolver.class,
            CorrectnessCompilationReferenceSource.class})
    @ConditionalOnMissingBean
    CorrectnessCompilationService correctnessCompilationService(
            CorrectnessDefinitionRepository definitions,
            CoverageInventoryRepository inventories,
            BusinessOracleRepository oracles,
            AssertionSetRepository assertionSets,
            ScenarioDraftSetV2Repository scenarios,
            FixtureAssetRepository fixtures,
            FixtureMaterialResolver materials,
            CorrectnessCompilationReferenceSource externalReferences,
            CorrectnessCompiler compiler,
            ObjectMapper mapper
    ) {
        return new CorrectnessCompilationService(
                definitions, inventories, oracles, assertionSets, scenarios, fixtures,
                materials, externalReferences, compiler, mapper);
    }

    @Bean
    @ConditionalOnMissingBean
    CorrectnessPublicationRepository correctnessPublicationRepository(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            CorrectnessAuthoringSchemaReadiness readiness
    ) {
        return new DatabaseCorrectnessPublicationRepository(jdbc, mapper);
    }

    @Bean
    @ConditionalOnMissingBean
    CorrectnessEvidenceRepository correctnessEvidenceRepository(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            CorrectnessAuthoringSchemaReadiness readiness
    ) {
        return new DatabaseCorrectnessEvidenceRepository(jdbc, mapper);
    }

    @Bean
    @ConditionalOnMissingBean
    CorrectnessVerdictProjector correctnessVerdictProjector() {
        return new CorrectnessVerdictProjector();
    }

    @Bean
    @ConditionalOnMissingBean
    CorrectnessEvidenceCompanionFactory correctnessEvidenceCompanionFactory(
            ScenarioDraftSetV2Repository scenarios,
            FixtureAssetRepository fixtures,
            CorrectnessVerdictProjector verdicts,
            ObjectMapper mapper
    ) {
        return new CorrectnessEvidenceCompanionFactory(
                scenarios, fixtures, verdicts, mapper);
    }

    @Bean
    @ConditionalOnBean({TestExecutionApiService.class, TestSuiteRegistryService.class})
    @ConditionalOnMissingBean
    CorrectnessTestingRegistryGateway correctnessTestingRegistryGateway(
            TestExecutionApiService executions,
            TestSuiteRegistryService suites
    ) {
        return new TestingControlPlaneCorrectnessRegistryGateway(executions, suites);
    }

    @Bean
    @ConditionalOnBean({CorrectnessCompilationService.class,
            CorrectnessTestingRegistryGateway.class})
    @ConditionalOnMissingBean
    CorrectnessPublicationService correctnessPublicationService(
            CorrectnessCompilationService compilation,
            CorrectnessPublicationRepository publications,
            CorrectnessTestingRegistryGateway registry,
            ObjectMapper mapper
    ) {
        return new CorrectnessPublicationService(
                compilation, publications, registry, mapper);
    }

    @Bean
    @ConditionalOnBean({CorrectnessPublicationRepository.class,
            CorrectnessTestingRegistryGateway.class, TestExecutionApiService.class})
    @ConditionalOnMissingBean
    CorrectnessPreflightFacade correctnessPreflightFacade(
            CorrectnessPublicationRepository publications,
            CorrectnessTestingRegistryGateway registry,
            TestExecutionApiService executions,
            ObjectMapper mapper
    ) {
        return new CorrectnessPreflightFacade(
                publications, registry, executions, mapper);
    }

    @Bean
    @ConditionalOnBean({CorrectnessPreflightFacade.class,
            CorrectnessPublicationRepository.class, TestSuiteExecutionService.class,
            CorrectnessEvidenceCompanionFactory.class, CorrectnessEvidenceRepository.class})
    @ConditionalOnMissingBean
    CorrectnessRunService correctnessRunService(
            CorrectnessPreflightFacade preflight,
            CorrectnessPublicationRepository publications,
            TestSuiteExecutionService suiteExecutions,
            CorrectnessEvidenceCompanionFactory companions,
            CorrectnessEvidenceRepository evidence,
            ObjectMapper mapper
    ) {
        return new CorrectnessRunService(
                preflight, publications, suiteExecutions, companions, evidence, mapper);
    }

    @Bean
    @ConditionalOnMissingBean
    CorrectnessGovernanceService correctnessGovernanceService(
            CorrectnessGovernanceRepository governance,
            CorrectnessPublicationRepository publications,
            CorrectnessEvidenceRepository evidence,
            ObjectMapper mapper
    ) {
        return new CorrectnessGovernanceService(
                governance, publications, evidence, mapper, Clock.systemUTC());
    }
}
