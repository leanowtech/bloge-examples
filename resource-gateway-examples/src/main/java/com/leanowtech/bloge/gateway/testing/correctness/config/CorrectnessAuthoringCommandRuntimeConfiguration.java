package com.leanowtech.bloge.gateway.testing.correctness.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.authoring.fixture.AuthoringFixturePayloadProtector;
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
import com.leanowtech.bloge.gateway.testing.correctness.fixture.FixtureMaterialService;
import com.leanowtech.bloge.gateway.testing.correctness.fixture.FixtureReviewAuthorizer;
import com.leanowtech.bloge.gateway.testing.correctness.fixture.FixtureScenarioExternalReferenceSource;
import com.leanowtech.bloge.gateway.testing.correctness.fixture.FixtureSchemaSource;
import com.leanowtech.bloge.gateway.testing.correctness.fixture.FixtureUsageProjectionService;
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
import com.leanowtech.bloge.gateway.testing.correctness.persistence.CoverageInventoryRepository;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.FixtureAssetRepository;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.ScenarioDraftSetV2Repository;
import com.leanowtech.bloge.gateway.testing.correctness.scenario.DatabaseScenarioCanonicalApprovalReceiptRepository;
import com.leanowtech.bloge.gateway.testing.correctness.scenario.LegacyScenarioV1MigrationAdapter;
import com.leanowtech.bloge.gateway.testing.correctness.scenario.ScenarioCanonicalApprovalReceiptRepository;
import com.leanowtech.bloge.gateway.testing.correctness.scenario.ScenarioClosureValidator;
import com.leanowtech.bloge.gateway.testing.correctness.scenario.ScenarioDraftSetV2Service;
import com.leanowtech.bloge.gateway.testing.correctness.scenario.ScenarioExternalReferenceSource;
import com.leanowtech.bloge.gateway.testing.correctness.scenario.ScenarioReviewAuthorizer;

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
}
