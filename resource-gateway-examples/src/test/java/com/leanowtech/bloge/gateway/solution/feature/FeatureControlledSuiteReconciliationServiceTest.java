package com.leanowtech.bloge.gateway.solution.feature;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddMutationService;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddStoredAsset;
import com.leanowtech.bloge.gateway.agenttdd.InMemoryAgentTddStateRepository;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.solution.FeatureContract;
import com.leanowtech.bloge.gateway.solution.SolutionEntityRegistry;
import com.leanowtech.bloge.gateway.testing.authoring.fixture.AuthoringFixturePayloadProtector;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureAssetDescriptor.FixtureSource;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureAssetDescriptor.RetentionDescriptor;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureAssetDescriptor.SourceKind;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureMaterialProtocolV2.Receipt;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureMaterialProtocolV2.WriteRequest;
import com.leanowtech.bloge.gateway.testing.correctness.fixture.DatabaseProtectedFixtureMaterialRepository;
import com.leanowtech.bloge.gateway.testing.correctness.fixture.FixtureMaterialService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Specifies fail-closed metadata repair and retention-safe Feature suite material collection. */
class FeatureControlledSuiteReconciliationServiceTest {
    private static final String FEATURE_REF = "cancel.withinFree";
    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final InMemoryAgentTddStateRepository states = new InMemoryAgentTddStateRepository();
    private final SolutionEntityRegistry registry = new SolutionEntityRegistry(states, mapper);
    private JdbcTemplate jdbc;
    private DatabaseProtectedFixtureMaterialRepository repository;
    private FixtureMaterialService vault;
    private FeatureControlledMaterialStore materials;
    private FeatureControlledSuiteService suites;
    private FeatureControlledSuiteReconciliationService reconciliation;

    @BeforeEach
    void setUp() {
        var database = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true).build();
        new ResourceDatabasePopulator(new ClassPathResource(
                "correctness/h2-correctness-fixture-material-schema.sql")).execute(database);
        jdbc = new JdbcTemplate(database);
        repository = new DatabaseProtectedFixtureMaterialRepository(jdbc, mapper);
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        vault = new FixtureMaterialService(repository,
                AuthoringFixturePayloadProtector.fromConfiguration(
                        "feature-reconcile", "feature-reconcile=" + key), mapper, clock);
        materials = new FeatureControlledMaterialStore(vault, repository, mapper, clock);
        registry.upsertFeature(scope(), feature());
        FeatureControlledSuiteProperties properties = new FeatureControlledSuiteProperties();
        suites = new FeatureControlledSuiteService(states, registry, materials,
                (request, identity) -> new FeatureControlledCaseRunner.RunResult(
                        "sha256:" + "e".repeat(64), 3,
                        List.of(new FeatureControlledCaseRunner.CaseResult(
                                "inside-window", "RED_PASS", List.of("node:within-window"))), 0),
                mapper, properties);
        reconciliation = new FeatureControlledSuiteReconciliationService(states, materials, mapper);
    }

    @Test
    void marksMissingCurrentMaterialFailedClosedAndNeverPersistsPayloadInTheReport() {
        FeatureControlledSuiteService.SuiteSummary draft = suites.upsert(definition(0), author());
        suites.run(FEATURE_REF, draft.revision(), executor());
        AgentTddStoredAsset before = currentSuite();
        Receipt receipt = receipt(before);
        assertThat(receipt.payloadFingerprint())
                .isEqualTo(before.data().path("definitionFingerprint").asText());
        jdbc.update("DELETE FROM rg_fixture_material_v2_revisions WHERE fixture_asset_id = ?",
                receipt.fixtureAssetId());

        var report = reconciliation.reconcile(NOW, 100, reconciler());

        assertThat(report.counts().falseMetadataCount()).isEqualTo(1);
        assertThat(report.counts().markedFailedClosedCount()).isEqualTo(1);
        AgentTddStoredAsset failed = currentSuite();
        assertThat(failed.data().path("status").asText()).isEqualTo("FAILED_CLOSED");
        assertThat(failed.data().path("evidenceFingerprint").asText()).isEmpty();
        assertThat(failed.data().has("latestEvidence")).isFalse();
        assertThat(failed.data().path("materialReconciliation").path("issueCode").asText())
                .isEqualTo("MATERIAL_UNAVAILABLE");
        assertThat(mapper.valueToTree(report).toString())
                .doesNotContain("SECRET-ORDER-17", "SECRET-STUB-42", "inside-window");

        long failedRevision = failed.revision();
        var repeated = reconciliation.reconcile(NOW, 100, reconciler());
        assertThat(repeated.counts().markedFailedClosedCount()).isZero();
        assertThat(currentSuite().revision()).isEqualTo(failedRevision);
    }

    @Test
    void marksAReceiptWhoseFeatureTargetFingerprintDoesNotMatchTheSuiteMetadata() {
        suites.upsert(definition(0), author());
        AgentTddStoredAsset original = currentSuite();
        ObjectNode mismatchedState = original.data().deepCopy();
        ObjectNode mismatchedReceipt = (ObjectNode) mismatchedState.path("materialReceipt");
        ((ObjectNode) mismatchedReceipt.path("target"))
                .put("fingerprint", "sha256:" + "9".repeat(64));
        states.saveIfRevision(scope(), original.kind(), original.assetRef(),
                original.revision(), mismatchedState);

        var report = reconciliation.reconcile(NOW, 100, reconciler());

        assertThat(report.counts().falseMetadataCount()).isEqualTo(1);
        assertThat(currentSuite().data().path("materialReconciliation").path("issueCode").asText())
                .isEqualTo("METADATA_MISMATCH");
        assertThat(report.counts().reclaimedMaterialCount()).isZero();
    }

    @Test
    void retainsAnExpiredDirectSuccessorWhileItsPredecessorRemainsCurrentAndRecoverable() {
        suites.upsert(definition(0), author());
        Receipt predecessor = receipt(currentSuite());
        Receipt successor = successor(predecessor, Duration.ofDays(1));

        var report = reconciliation.reconcile(NOW.plus(Duration.ofDays(2)), 100, reconciler());

        assertThat(report.counts().directSuccessorOrphanCount()).isEqualTo(1);
        assertThat(report.counts().recoverableSuccessorCount()).isEqualTo(1);
        assertThat(report.counts().reclaimedMaterialCount()).isZero();
        assertThat(state(successor)).isEqualTo("AVAILABLE");
        assertThat(protectedPayload(successor)).isNotBlank();
    }

    @Test
    void reclaimsOnlyAnExpiredUnreferencedDirectSuccessorAndKeepsItsLineageTombstone() {
        JsonNode receiptNode = materials.write(FEATURE_REF, 1, featureFingerprint(), 1,
                definitionFingerprint(), definition(0), author());
        Receipt predecessor = treeReceipt(receiptNode);
        Receipt successor = successor(predecessor, Duration.ofDays(1));

        var report = reconciliation.reconcile(NOW.plus(Duration.ofDays(2)), 100, reconciler());

        assertThat(report.counts().suiteMetadataCount()).isZero();
        assertThat(report.counts().directSuccessorOrphanCount()).isEqualTo(1);
        assertThat(report.counts().reclaimedMaterialCount()).isEqualTo(1);
        assertThat(state(predecessor)).isEqualTo("AVAILABLE");
        assertThat(state(successor)).isEqualTo("EXPIRED");
        assertThat(protectedPayload(successor)).isNull();
        String receiptJson = jdbc.queryForObject("""
                SELECT receipt_json FROM rg_fixture_material_v2_revisions
                WHERE fixture_asset_id = ? AND revision = ?
                """, String.class, successor.fixtureAssetId(), successor.materialRef().revision());
        assertThat(receiptJson).contains(predecessor.materialRef().fingerprint());
        assertThat(jdbc.queryForObject("""
                SELECT outcome FROM rg_fixture_material_access_audit
                WHERE material_id = ? AND material_revision = ? AND action = 'EXPIRE'
                """, String.class, successor.fixtureAssetId(), successor.materialRef().revision()))
                .isEqualTo("ORPHAN_RECLAIMED");
    }

    @Test
    void neverReclaimsAnExpiredSuccessorThatCurrentMetadataReferences() {
        suites.upsert(definition(0), author());
        AgentTddStoredAsset original = currentSuite();
        Receipt successor = successor(receipt(original), Duration.ofDays(1));
        ObjectNode successorState = original.data().deepCopy();
        successorState.set("materialReceipt", mapper.valueToTree(successor));
        states.saveIfRevision(scope(), original.kind(), original.assetRef(),
                original.revision(), successorState);

        var report = reconciliation.reconcile(NOW.plus(Duration.ofDays(2)), 100, reconciler());

        assertThat(report.counts().healthyMetadataCount()).isEqualTo(1);
        assertThat(report.counts().reclaimedMaterialCount()).isZero();
        assertThat(state(successor)).isEqualTo("AVAILABLE");
        assertThat(protectedPayload(successor)).isNotBlank();
    }

    private Receipt successor(Receipt predecessor, Duration retention) {
        WriteRequest request = new WriteRequest(
                WriteRequest.SCHEMA_VERSION, predecessor.fixtureAssetId(),
                predecessor.materialRef().revision(),
                new FixtureSource(SourceKind.REPLAY_DERIVATION, predecessor.materialRef()),
                predecessor.subject(), predecessor.target(), predecessor.schemaRef(),
                predecessor.classification(),
                new RetentionDescriptor("rg.featureControlledSuite.gc-test", 2, NOW.plus(retention)),
                predecessor.redaction(),
                mapper.convertValue(definition(0).protectedMaterial(), Object.class));
        return vault.write(request, materialWriter());
    }

    private String state(Receipt receipt) {
        return jdbc.queryForObject("""
                SELECT state FROM rg_fixture_material_v2_revisions
                WHERE fixture_asset_id = ? AND revision = ?
                """, String.class, receipt.fixtureAssetId(), receipt.materialRef().revision());
    }

    private String protectedPayload(Receipt receipt) {
        return jdbc.queryForObject("""
                SELECT protected_payload FROM rg_fixture_material_v2_revisions
                WHERE fixture_asset_id = ? AND revision = ?
                """, String.class, receipt.fixtureAssetId(), receipt.materialRef().revision());
    }

    private AgentTddStoredAsset currentSuite() {
        return states.find(scope(), FeatureControlledSuiteService.FEATURE_CONTROLLED_SUITE, FEATURE_REF)
                .orElseThrow();
    }

    private Receipt receipt(AgentTddStoredAsset suite) {
        return treeReceipt(suite.data().path("materialReceipt"));
    }

    private Receipt treeReceipt(JsonNode value) {
        return mapper.convertValue(value, Receipt.class);
    }

    private String definitionFingerprint() {
        return com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint.fromCanonicalValue(
                mapper, mapper.convertValue(definition(0).protectedMaterial(), Object.class),
                16 * 1024 * 1024);
    }

    private String featureFingerprint() {
        return states.find(scope(), SolutionEntityRegistry.FEATURE, FEATURE_REF).orElseThrow()
                .data().path("contractFingerprint").asText();
    }

    private FeatureControlledSuiteDefinition definition(long expectedRevision) {
        return new FeatureControlledSuiteDefinition(
                FEATURE_REF, "graph:cancel-window-v1", expectedRevision,
                List.of("lib:time-v1"), List.of("node:within-window"),
                List.of(new FeatureControlledSuiteDefinition.Case(
                        "inside-window", "Order inside free window",
                        mapper.valueToTree(Map.of("orderId", "SECRET-ORDER-17")),
                        List.of(new FeatureControlledSuiteDefinition.NodeBehavior(
                                "order-api", mapper.valueToTree(Map.of(
                                "behavior", "RETURN", "value", "SECRET-STUB-42")))),
                        mapper.valueToTree(true), List.of("node:within-window"))));
    }

    private FeatureContract feature() {
        return new FeatureContract(FEATURE_REF, mapper.valueToTree(Map.of("type", "boolean")),
                FeatureContract.EvaluationKind.DAG, FeatureContract.Determinism.DETERMINISTIC,
                mapper.valueToTree(Map.of("orderId", "string")), "", "", "",
                "Whether cancellation is inside the free window.");
    }

    private static IntegrationRequestContext author() {
        return identity("WORKLOAD", "agent", "AGENT_TDD_AUTHORING", "");
    }

    private static IntegrationRequestContext executor() {
        return identity("WORKLOAD", "runner", "AGENT_TDD_EXECUTION", "");
    }

    private static IntegrationRequestContext reconciler() {
        return identity("PLATFORM", "reconciler", FeatureControlledMaterialStore.RECONCILE_PURPOSE,
                "RESTRICTED");
    }

    private static IntegrationRequestContext materialWriter() {
        return identity("PLATFORM", "fixture-writer", FixtureMaterialService.WRITE_PURPOSE,
                "RESTRICTED");
    }

    private static IntegrationRequestContext identity(
            String actorType, String actorId, String purpose, String clearance) {
        return new IntegrationRequestContext(
                "tenant-a", "org-a", "project-a", "test", "sg",
                actorType, actorId, "", purpose, "corr-reconcile", java.util.Set.of(), clearance, "");
    }

    private static String scope() {
        return AgentTddMutationService.scopeKey(author());
    }
}
