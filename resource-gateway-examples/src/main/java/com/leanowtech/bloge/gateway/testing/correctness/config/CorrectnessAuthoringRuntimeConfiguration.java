package com.leanowtech.bloge.gateway.testing.correctness.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.CorrectnessAuthoringRuntimeAvailability;
import com.leanowtech.bloge.gateway.testing.correctness.coverage.CoverageInventoryService;
import com.leanowtech.bloge.gateway.testing.correctness.compilation.CorrectnessCompilationService;
import com.leanowtech.bloge.gateway.testing.correctness.compilation.CorrectnessPublicationService;
import com.leanowtech.bloge.gateway.testing.correctness.fixture.FixtureCatalogService;
import com.leanowtech.bloge.gateway.testing.correctness.fixture.FixtureMaterialService;
import com.leanowtech.bloge.gateway.testing.correctness.oracle.AssertionSetService;
import com.leanowtech.bloge.gateway.testing.correctness.oracle.BusinessOracleService;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.AssertionSetRepository;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.BusinessOracleRepository;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.CorrectnessDefinitionRepository;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.CoverageInventoryRepository;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.DatabaseAssertionSetRepository;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.DatabaseBusinessOracleRepository;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.DatabaseCorrectnessDefinitionRepository;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.DatabaseCoverageInventoryRepository;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.DatabaseFixtureAssetRepository;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.DatabaseScenarioDraftSetV2Repository;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.FixtureAssetRepository;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.ScenarioDraftSetV2Repository;
import com.leanowtech.bloge.gateway.testing.correctness.publication.CorrectnessPublicationRepository;
import com.leanowtech.bloge.gateway.testing.correctness.scenario.ScenarioDraftSetV2Service;
import com.leanowtech.bloge.gateway.testing.correctness.workspace.CorrectnessWorkspaceComponentSource;
import com.leanowtech.bloge.gateway.testing.correctness.workspace.CorrectnessWorkspaceQuery;
import com.leanowtech.bloge.gateway.testing.correctness.workspace.DefinitionOnlyCorrectnessWorkspaceComponentSource;
import com.leanowtech.bloge.gateway.testing.correctness.workspace.FixtureCorrectnessWorkspaceComponentSource;
import com.leanowtech.bloge.gateway.testing.correctness.workspace.InventoryCorrectnessWorkspaceComponentSource;
import com.leanowtech.bloge.gateway.testing.correctness.workspace.OracleAssertionCorrectnessWorkspaceComponentSource;
import com.leanowtech.bloge.gateway.testing.correctness.workspace.PublicationCorrectnessWorkspaceComponentSource;
import com.leanowtech.bloge.gateway.testing.correctness.workspace.ScenarioCorrectnessWorkspaceComponentSource;
import com.leanowtech.bloge.gateway.testing.correctness.workspace.ScenarioV2CoverageFulfillmentSource;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Opt-in production assembly for the payload-free Correctness Workspace read model. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        prefix = "gateway.testing.correctness",
        name = "enabled",
        havingValue = "true")
public class CorrectnessAuthoringRuntimeConfiguration {

    @Bean
    @ConditionalOnMissingBean
    CorrectnessAuthoringSchemaReadiness correctnessAuthoringSchemaReadiness(
            JdbcTemplate jdbc
    ) {
        return new CorrectnessAuthoringSchemaReadiness(jdbc);
    }

    @Bean
    @ConditionalOnMissingBean
    CorrectnessDefinitionRepository correctnessDefinitionRepository(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            CorrectnessAuthoringSchemaReadiness readiness
    ) {
        return new DatabaseCorrectnessDefinitionRepository(jdbc, mapper);
    }

    @Bean
    @ConditionalOnMissingBean
    CoverageInventoryRepository coverageInventoryRepository(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            CorrectnessAuthoringSchemaReadiness readiness
    ) {
        return new DatabaseCoverageInventoryRepository(jdbc, mapper);
    }

    @Bean
    @ConditionalOnMissingBean
    BusinessOracleRepository businessOracleRepository(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            CorrectnessAuthoringSchemaReadiness readiness
    ) {
        return new DatabaseBusinessOracleRepository(jdbc, mapper);
    }

    @Bean
    @ConditionalOnMissingBean
    AssertionSetRepository assertionSetRepository(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            CorrectnessAuthoringSchemaReadiness readiness
    ) {
        return new DatabaseAssertionSetRepository(jdbc, mapper);
    }

    @Bean
    @ConditionalOnMissingBean
    ScenarioDraftSetV2Repository scenarioDraftSetV2Repository(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            CorrectnessAuthoringSchemaReadiness readiness
    ) {
        return new DatabaseScenarioDraftSetV2Repository(jdbc, mapper);
    }

    @Bean
    @ConditionalOnMissingBean
    FixtureAssetRepository fixtureAssetRepository(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            CorrectnessAuthoringSchemaReadiness readiness
    ) {
        return new DatabaseFixtureAssetRepository(jdbc, mapper);
    }

    @Bean
    @ConditionalOnMissingBean
    CorrectnessWorkspaceComponentSource correctnessWorkspaceComponentSource(
            CoverageInventoryRepository inventories,
            BusinessOracleRepository oracles,
            AssertionSetRepository assertionSets,
            ScenarioDraftSetV2Repository scenarios,
            FixtureAssetRepository fixtures,
            ObjectProvider<CorrectnessPublicationRepository> publications
    ) {
        CorrectnessWorkspaceComponentSource source =
                new DefinitionOnlyCorrectnessWorkspaceComponentSource();
        source = new InventoryCorrectnessWorkspaceComponentSource(
                source, inventories, new ScenarioV2CoverageFulfillmentSource(scenarios));
        source = new OracleAssertionCorrectnessWorkspaceComponentSource(
                source, oracles, assertionSets);
        source = new ScenarioCorrectnessWorkspaceComponentSource(source, scenarios);
        source = new FixtureCorrectnessWorkspaceComponentSource(source, scenarios, fixtures);
        CorrectnessPublicationRepository publication = publications.getIfAvailable();
        return publication == null ? source
                : new PublicationCorrectnessWorkspaceComponentSource(source, publication);
    }

    @Bean
    @ConditionalOnMissingBean
    CorrectnessWorkspaceQuery correctnessWorkspaceQuery(
            CorrectnessDefinitionRepository definitions,
            CorrectnessWorkspaceComponentSource components,
            ObjectMapper mapper
    ) {
        return new CorrectnessWorkspaceQuery(definitions, components, mapper);
    }

    @Bean
    @ConditionalOnMissingBean
    CorrectnessAuthoringRuntimeAvailability correctnessAuthoringRuntimeAvailability(
            ObjectProvider<CorrectnessWorkspaceQuery> workspace,
            ObjectProvider<CoverageInventoryService> coverage,
            ObjectProvider<BusinessOracleService> oracles,
            ObjectProvider<AssertionSetService> assertions,
            ObjectProvider<ScenarioDraftSetV2Service> scenarios,
            ObjectProvider<FixtureCatalogService> fixtures,
            ObjectProvider<FixtureMaterialService> materials,
            ObjectProvider<CorrectnessCompilationService> compilation,
            ObjectProvider<CorrectnessPublicationService> publication
    ) {
        return new CorrectnessAuthoringRuntimeAvailability(
                workspace.getIfAvailable() != null,
                coverage.getIfAvailable() != null,
                oracles.getIfAvailable() != null && assertions.getIfAvailable() != null,
                scenarios.getIfAvailable() != null,
                fixtures.getIfAvailable() != null,
                materials.getIfAvailable() != null,
                compilation.getIfAvailable() != null,
                publication.getIfAvailable() != null);
    }
}
