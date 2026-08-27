package com.leanowtech.bloge.gateway.visual.fixture;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.authoring.fixture.AuthoringFixturePayloadProtector;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureAssetDescriptor;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureMaterialProtocolV2.Receipt;
import com.leanowtech.bloge.gateway.testing.correctness.fixture.DatabaseProtectedFixtureMaterialRepository;
import com.leanowtech.bloge.gateway.testing.correctness.fixture.FixtureCatalogCommandException;
import com.leanowtech.bloge.gateway.testing.correctness.fixture.FixtureCatalogService;
import com.leanowtech.bloge.gateway.testing.correctness.fixture.FixtureMaterialCommandException;
import com.leanowtech.bloge.gateway.testing.correctness.fixture.FixtureMaterialService;
import com.leanowtech.bloge.gateway.testing.correctness.fixture.RepositoryFixtureMaterialMetadataSource;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.DatabaseFixtureAssetRepository;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.StoredFixtureAsset;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.VisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftRepository;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Proves that graph Fixture promotion is one database transaction at the service boundary.
 *
 * <p>The test deliberately uses the same JDBC datasource for material and catalog repositories,
 * then obtains the promotion service through Spring's transaction proxy. This guards against a
 * false green test that only exercises repository-local transactions.</p>
 */
class GraphNodeFixturePromotionTransactionTest {
    private static final Instant NOW = Instant.parse("2026-08-27T12:00:00Z");

    private DataSource database;
    private JdbcTemplate jdbc;
    private DatabaseProtectedFixtureMaterialRepository materials;
    private DatabaseFixtureAssetRepository fixtures;
    private GraphDraftRepository drafts;
    private VisualOperatorCatalog operators;
    private AnnotationConfigApplicationContext context;
    private IntegrationRequestContext identity;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .build();
        ResourceDatabasePopulator schema = new ResourceDatabasePopulator(
                new ClassPathResource("correctness/h2-correctness-fixture-schema.sql"),
                new ClassPathResource("correctness/h2-correctness-fixture-material-schema.sql"));
        schema.execute(database);
        jdbc = new JdbcTemplate(database);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        materials = new DatabaseProtectedFixtureMaterialRepository(jdbc, mapper);
        fixtures = new DatabaseFixtureAssetRepository(jdbc, mapper, Clock.fixed(NOW, ZoneOffset.UTC));
        drafts = Mockito.mock(GraphDraftRepository.class);
        operators = Mockito.mock(VisualOperatorCatalog.class);
        identity = new IntegrationRequestContext(
                "tenant-a", "org-a", "project-a", "test", "sg", "USER", "author-1", "",
                IntegrationOperation.CORRECTNESS_FIXTURE_MATERIAL_WRITE.acceptedPurposes()
                        .iterator().next(),
                "correlation-1", Set.of(), "RESTRICTED", "");
        when(drafts.find("draft-1")).thenReturn(Optional.of(draft()));
        when(operators.find("resource:applicant")).thenReturn(Optional.of(resourceOperator()));
    }

    @AfterEach
    void tearDown() {
        if (context != null) context.close();
        if (database instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception ignored) {
                // The embedded database is test infrastructure; no production state is lost here.
            }
        }
    }

    @Test
    void commitsMaterialAndDescriptorTogetherOnSuccess() {
        FixtureCatalogService catalog = realCatalog();
        GraphNodeFixturePromotionService service = proxiedService(catalog);

        GraphNodeFixturePromotionService.PromotionResult result = service.promote(
                "draft-1", "node_1", request("fixture-1"), identity);

        EnterpriseScope scope = scope();
        assertThat(result.revision()).isEqualTo(1);
        assertThat(materials.latestRevision(scope, "fixture-1")).isEqualTo(1);
        assertThat(fixtures.findHead(scope, "fixture-1")).isPresent();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rg_fixture_material_access_audit", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rg_fixture_asset_revisions", Integer.class)).isEqualTo(1);
    }

    @Test
    void rollsBackMaterialAndWriteAuditWhenCatalogRejectsAfterMaterialWrite() {
        FixtureCatalogService catalog = Mockito.mock(FixtureCatalogService.class);
        when(catalog.saveDraft(eq(0L), any(), any())).thenThrow(new FixtureCatalogCommandException(
                "RG.CORRECTNESS.REVISION_CONFLICT", "Fixture id already exists"));
        GraphNodeFixturePromotionService service = proxiedService(catalog);

        assertThatThrownBy(() -> service.promote(
                "draft-1", "node_1", request("fixture-conflict"), identity))
                .isInstanceOfSatisfying(GraphNodeFixturePromotionException.class, failure ->
                        assertThat(failure.status()).isEqualTo(409));

        EnterpriseScope scope = scope();
        assertThat(materials.latestRevision(scope, "fixture-conflict")).isZero();
        assertThat(fixtures.findHead(scope, "fixture-conflict")).isEmpty();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rg_fixture_material_v2_revisions", Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rg_fixture_material_access_audit", Integer.class)).isZero();
    }

    @Test
    void materialCasRejectsSecondPromotionWithoutCreatingAnotherDescriptor() {
        GraphNodeFixturePromotionService service = proxiedService(realCatalog());
        service.promote("draft-1", "node_1", request("fixture-cas"), identity);

        assertThatThrownBy(() -> service.promote(
                "draft-1", "node_1", request("fixture-cas"), identity))
                .isInstanceOfSatisfying(FixtureMaterialCommandException.class, failure -> {
                    assertThat(failure.status()).isEqualTo(409);
                    assertThat(failure.code())
                            .isEqualTo("RG.CORRECTNESS.FIXTURE_MATERIAL_REVISION_CONFLICT");
                });

        EnterpriseScope scope = scope();
        assertThat(materials.latestRevision(scope, "fixture-cas")).isEqualTo(1);
        assertThat(fixtures.findHead(scope, "fixture-cas")).isPresent();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rg_fixture_asset_revisions", Integer.class)).isEqualTo(1);
    }

    private GraphNodeFixturePromotionService proxiedService(FixtureCatalogService catalog) {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        FixtureMaterialService materialService = new FixtureMaterialService(
                materials,
                AuthoringFixturePayloadProtector.fromConfiguration("fixture-v2", "fixture-v2=" + key),
                mapper,
                Clock.fixed(NOW, ZoneOffset.UTC));
        context = new AnnotationConfigApplicationContext();
        context.registerBean(DataSource.class, () -> database);
        context.registerBean(DataSourceTransactionManager.class,
                () -> new DataSourceTransactionManager(database));
        context.registerBean(GraphNodeFixturePromotionService.class, () ->
                new GraphNodeFixturePromotionService(
                        drafts, operators, catalog, materialService::write, mapper,
                        Clock.fixed(NOW, ZoneOffset.UTC)));
        context.register(TransactionConfiguration.class);
        context.refresh();
        GraphNodeFixturePromotionService service = context.getBean(GraphNodeFixturePromotionService.class);
        assertThat(AopUtils.isAopProxy(service)).as("promotion service must be transaction proxied").isTrue();
        return service;
    }

    private FixtureCatalogService realCatalog() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        return new FixtureCatalogService(
                fixtures,
                new RepositoryFixtureMaterialMetadataSource(materials),
                (scope, schema) -> true,
                (scope, descriptor, actor) ->
                        com.leanowtech.bloge.gateway.testing.correctness.fixture.FixtureReviewAuthorizer
                                .ApprovalDecision.ownerReview(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private GraphNodeFixturePromotionRequest request(String fixtureId) {
        return new GraphNodeFixturePromotionRequest(
                GraphNodeFixturePromotionRequest.SCHEMA_VERSION, fixtureId, "restricted", 3,
                List.of("/score"));
    }

    private EnterpriseScope scope() {
        return new EnterpriseScope("tenant-a", "org-a", "project-a", "test", "sg");
    }

    private static GraphDraft draft() {
        return new GraphDraft(
                null, "draft-1", 1, "Loan tool", "tenant-a", "project-a", "test",
                GraphDraft.STATUS_DRAFT, SchemaEnvelope.opaque(),
                List.of(new GraphDraft.DraftNode(
                        "node_1", "resource:applicant", "Applicant", Map.of(), Map.of(), null)),
                List.of(), Map.of(), Map.of("node_1",
                        new GraphDraft.NodeFixture(Map.of("score", 760))), null, Map.of(), Map.of(),
                GraphDraft.RevisionMetadata.empty());
    }

    private static OperatorDefinition resourceOperator() {
        return new OperatorDefinition(
                "", "resource:applicant", "1.0.0", "",
                new OperatorDefinition.Display("Applicant profile", "", List.of()),
                new OperatorDefinition.Source(
                        "resource-descriptor", "applicant", "GET", "/applicants/{id}", true),
                new OperatorDefinition.Ports(
                        List.of(),
                        List.of(new OperatorDefinition.Port(
                                "payload", SchemaEnvelope.object(
                                        Map.of("score", Map.of("type", "integer")), List.of("score")),
                                true, ""))),
                SchemaEnvelope.object(Map.of(), List.of()),
                OperatorDefinition.Capabilities.pure(), null,
                new OperatorDefinition.Lowering("resource-descriptor", "httpResource", Map.of()),
                List.of());
    }

    /** Spring test configuration that exposes the real datasource transaction manager. */
    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class TransactionConfiguration {
    }
}
