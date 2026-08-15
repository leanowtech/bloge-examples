package com.leanowtech.bloge.gateway.testing.correctness.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.integration.IntegrationCapabilities;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessDefinition;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.AuditMetadata;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactTargetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalKind;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.RiskLevel;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.TargetKind;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessVerdict;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.CorrectnessDefinitionRepository;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.StoredCorrectnessDefinition;
import com.leanowtech.bloge.gateway.testing.correctness.workspace.CorrectnessWorkspaceComponentSource.Components;
import com.leanowtech.bloge.gateway.testing.correctness.workspace.CorrectnessWorkspaceProjection.Availability;
import com.leanowtech.bloge.gateway.testing.correctness.workspace.CorrectnessWorkspaceProjection.CasePage;
import com.leanowtech.bloge.gateway.testing.correctness.workspace.CorrectnessWorkspaceProjection.CaseSummary;
import com.leanowtech.bloge.gateway.testing.correctness.workspace.CorrectnessWorkspaceProjection.CommandPolicy;
import com.leanowtech.bloge.gateway.testing.correctness.workspace.CorrectnessWorkspaceProjection.CoverageSummary;
import com.leanowtech.bloge.gateway.testing.correctness.workspace.CorrectnessWorkspaceProjection.FixtureCatalogSummary;
import com.leanowtech.bloge.gateway.testing.correctness.workspace.CorrectnessWorkspaceProjection.OracleAssertionSummary;
import com.leanowtech.bloge.gateway.testing.correctness.workspace.CorrectnessWorkspaceProjection.ReviewSummary;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CorrectnessWorkspaceQueryTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void returnsBoundedMetadataOnlyPageForFiveHundredCaseWorkspace() throws Exception {
        InMemoryDefinitions definitions = new InMemoryDefinitions();
        definitions.add(stored("definition-a", scope("tenant-a"), target()));
        CorrectnessWorkspaceQuery query = new CorrectnessWorkspaceQuery(
                definitions, fiveHundredCaseSource(), mapper);

        long started = System.nanoTime();
        CorrectnessWorkspaceProjection result = query.get(
                TargetKind.GRAPH, "loan-graph", fingerprint('a'), "", "", 100,
                identity("tenant-a"));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertThat(result.cases().total()).isEqualTo(500);
        assertThat(result.cases().rows()).hasSize(100);
        assertThat(result.cases().nextCursor()).isEqualTo("case:100");
        assertThat(result.capabilities()).contains(
                "CORRECTNESS_WORKSPACE_V1", "CORRECTNESS_CASE_SUMMARY_V1");
        assertThat(elapsed).isLessThan(Duration.ofSeconds(1));

        String json = mapper.writeValueAsString(result);
        assertThat(json).doesNotContain(
                "SECRET-MARKER", "\"given\"", "\"input\"", "\"output\"",
                "\"payload\"", "\"materialRef\"");
        assertThat(json).contains("\"dependencyCount\":1", "\"businessIntent\"");
    }

    @Test
    void isolatesEveryWorkspaceReadByFullEnterpriseScope() {
        InMemoryDefinitions definitions = new InMemoryDefinitions();
        definitions.add(stored("definition-a", scope("tenant-a"), target()));
        CorrectnessWorkspaceQuery query = new CorrectnessWorkspaceQuery(
                definitions, fiveHundredCaseSource(), mapper);

        assertThatThrownBy(() -> query.get(
                TargetKind.GRAPH, "loan-graph", fingerprint('a'), "", "", 20,
                identity("tenant-b")))
                .isInstanceOfSatisfying(IntegrationProblemException.class, failure ->
                        assertThat(failure.problem().code())
                                .isEqualTo("RG.CORRECTNESS.DEFINITION_NOT_FOUND"));

        IntegrationRequestContext wrongRegion = new IntegrationRequestContext(
                "tenant-a", "org-a", "credit", "test", "us", "WORKLOAD",
                "author-a", "", "CORRECTNESS_READ", "corr-region");
        assertThatThrownBy(() -> query.get(
                TargetKind.GRAPH, "loan-graph", fingerprint('a'), "", "", 20,
                wrongRegion))
                .isInstanceOfSatisfying(IntegrationProblemException.class, failure ->
                        assertThat(failure.problem().status()).isEqualTo(404));
    }

    @Test
    void requiresExplicitDefinitionWhenExactTargetHasMultipleHeads() {
        InMemoryDefinitions definitions = new InMemoryDefinitions();
        definitions.add(stored("definition-a", scope("tenant-a"), target()));
        definitions.add(stored("definition-b", scope("tenant-a"), target()));
        CorrectnessWorkspaceQuery query = new CorrectnessWorkspaceQuery(
                definitions, fiveHundredCaseSource(), mapper);

        assertThatThrownBy(() -> query.get(
                TargetKind.GRAPH, "loan-graph", fingerprint('a'), "", "", 20,
                identity("tenant-a")))
                .isInstanceOfSatisfying(IntegrationProblemException.class, failure -> {
                    assertThat(failure.problem().code())
                            .isEqualTo("RG.CORRECTNESS.DEFINITION_AMBIGUOUS");
                    assertThat(failure.problem().details().get("definitionIds"))
                            .isEqualTo(List.of("definition-a", "definition-b"));
                });

        assertThat(query.get(
                TargetKind.GRAPH, "loan-graph", fingerprint('a'), "definition-b", "", 20,
                identity("tenant-a")).definition().definitionRef().id())
                .isEqualTo("definition-b");
    }

    @Test
    void rejectsDriftAndUnboundedComponentProjectionsFailClosed() {
        InMemoryDefinitions definitions = new InMemoryDefinitions();
        definitions.add(stored("definition-a", scope("tenant-a"), target()));
        CorrectnessWorkspaceQuery driftQuery = new CorrectnessWorkspaceQuery(
                definitions, fiveHundredCaseSource(), mapper);

        assertThatThrownBy(() -> driftQuery.get(
                TargetKind.GRAPH, "other-graph", fingerprint('a'), "definition-a", "", 20,
                identity("tenant-a")))
                .isInstanceOfSatisfying(IntegrationProblemException.class, failure ->
                        assertThat(failure.problem().code())
                                .isEqualTo("RG.CORRECTNESS.REFERENCE_DRIFT"));

        CorrectnessWorkspaceQuery unbounded = new CorrectnessWorkspaceQuery(
                definitions,
                (coordinate, page) -> components(page, 500, 21, ""),
                mapper);
        assertThatThrownBy(() -> unbounded.get(
                TargetKind.GRAPH, "loan-graph", fingerprint('a'), "definition-a", "", 20,
                identity("tenant-a")))
                .isInstanceOfSatisfying(IntegrationProblemException.class, failure ->
                        assertThat(failure.problem().code())
                                .isEqualTo("RG.CORRECTNESS.PROJECTION_INVALID"));
    }

    @Test
    void publishedWorkspaceSchemasAreClosedBoundedAndPayloadFree() throws Exception {
        var projectionSchema = mapper.readTree(Files.readString(Path.of(
                "..", "docs", "schemas",
                "bloge-correctness-workspace-projection-v1.schema.json")));
        var envelopeSchema = mapper.readTree(Files.readString(Path.of(
                "..", "docs", "schemas", "bloge-correctness-api-envelope-v1.schema.json")));

        assertThat(projectionSchema.path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(projectionSchema.path("required"))
                .extracting(com.fasterxml.jackson.databind.JsonNode::asText)
                .contains("oracleAssertions");
        assertThat(projectionSchema.at("/$defs/oracleAssertionSummary/additionalProperties")
                .asBoolean(true)).isFalse();
        assertThat(projectionSchema.at("/$defs/casePage/properties/rows/maxItems").asInt())
                .isEqualTo(100);
        assertThat(projectionSchema.at("/$defs/caseSummary/required"))
                .extracting(com.fasterxml.jackson.databind.JsonNode::asText)
                .contains("scenarioDraftSetRef");
        assertThat(projectionSchema.at("/$defs/fixtureSummary/properties/materialFingerprint")
                .path("$ref").asText()).isEqualTo("#/$defs/fingerprint");
        assertThat(projectionSchema.toString()).doesNotContain(
                "fixturePayload", "requestPayload", "responsePayload", "materialRef");
        assertThat(envelopeSchema.path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(envelopeSchema.at("/properties/data/$ref").asText())
                .isEqualTo("bloge-correctness-workspace-projection-v1.schema.json");

        IntegrationCapabilities capabilities = IntegrationCapabilities.current();
        assertThat(capabilities.supportedObjects())
                .containsEntry("correctnessWorkspaceProjection",
                        List.of(CorrectnessWorkspaceProjection.SCHEMA_VERSION))
                .containsEntry("correctnessApiEnvelope",
                        List.of(CorrectnessApiEnvelope.PROTOCOL_VERSION));
        assertThat(capabilities.features())
                .containsEntry("correctnessAuthoringProtocol", true)
                .containsEntry("correctnessWorkspaceProtocol", true)
                .containsEntry("correctnessWorkspaceApi", false)
                .containsEntry("correctnessCoverageProtocol", true)
                .containsEntry("correctnessCoverageApi", false)
                .containsEntry("correctnessOracleAssertionProtocol", true)
                .containsEntry("correctnessOracleAssertionApi", false)
                .containsEntry("correctnessScenarioV2Protocol", true)
                .containsEntry("correctnessScenarioV2Api", false);
        assertThat(capabilities.supportedObjects())
                .containsEntry("scenarioClosureReport",
                        List.of("bloge.scenarioClosureReport.v1"))
                .containsEntry("scenarioCanonicalApprovalReceipt",
                        List.of("bloge.scenarioCanonicalApprovalReceipt.v1"))
                .containsEntry("scenarioV1MigrationPreview",
                        List.of("bloge.scenarioV1MigrationPreview.v1"));
    }

    private CorrectnessWorkspaceComponentSource fiveHundredCaseSource() {
        return (coordinate, page) -> components(page, 500, page.limit(),
                page.limit() < 500 ? "case:" + page.limit() : "");
    }

    private Components components(
            CorrectnessWorkspaceComponentSource.PageRequest page,
            int total,
            int returned,
            String nextCursor
    ) {
        List<CaseSummary> rows = new ArrayList<>();
        var scenarioRef = new com.leanowtech.bloge.gateway.testing.correctness.domain
                .CorrectnessProtocol.ExactAssetRef(
                "SCENARIO_DRAFT_SET", "suite-a", 4, fingerprint('e'));
        for (int index = 0; index < returned; index++) {
            rows.add(new CaseSummary(
                    scenarioRef,
                    "case-" + index, fingerprint("0123456789abcdef".charAt(index % 16)),
                    "Case " + index, "Prove business branch " + index,
                    index % 2 == 0 ? "GOLDEN" : "BOUNDARY", RiskLevel.HIGH, owner(),
                    "CANONICAL", 1, 1, 1, 1, "APPROVED", List.of("loan")));
        }
        return new Components(
                CoverageSummary.unavailable(),
                OracleAssertionSummary.unavailable(),
                new CasePage(Availability.AVAILABLE, scenarioRef,
                        total, rows, nextCursor, page.queryFingerprint()),
                FixtureCatalogSummary.unavailable(), ReviewSummary.empty(), null, null,
                blockedVerdict(), List.of(), List.of("CORRECTNESS_CASE_SUMMARY_V1"),
                CommandPolicy.readOnly());
    }

    private CorrectnessVerdict blockedVerdict() {
        return new CorrectnessVerdict(
                CorrectnessVerdict.ExecutionVerdict.NOT_RUN,
                CorrectnessVerdict.AssertionVerdict.NONE,
                CorrectnessVerdict.CoverageVerdict.UNFROZEN,
                CorrectnessVerdict.EvidenceVerdict.NONE,
                CorrectnessVerdict.GateVerdict.BLOCKED,
                CorrectnessVerdict.ProofLevel.STRUCTURAL,
                List.of(new CorrectnessVerdict.Reason(
                        "AUTHORING_ASSETS_INCOMPLETE", "GATE", "correctness.assets.incomplete")),
                List.of(new CorrectnessVerdict.Remediation(
                        "OPEN_COVERAGE_INVENTORY", "AUTHORING_ASSETS_INCOMPLETE")));
    }

    private StoredCorrectnessDefinition stored(
            String definitionId,
            EnterpriseScope scope,
            ExactTargetRef target
    ) {
        Instant now = Instant.parse("2026-08-15T00:00:00Z");
        CorrectnessDefinition definition = new CorrectnessDefinition(
                "", definitionId, 1, scope, target, "Loan correctness",
                "No ineligible approval", List.of("Reject ineligible applicants"),
                RiskLevel.CRITICAL, owner(), List.of(), null, null,
                CorrectnessDefinition.DefinitionLifecycle.DRAFT, null,
                new AuditMetadata(now, now, owner(), owner()));
        return StoredCorrectnessDefinition.verified(mapper, definition);
    }

    private ExactTargetRef target() {
        return new ExactTargetRef(TargetKind.GRAPH, "loan-graph", 3, fingerprint('a'));
    }

    private EnterpriseScope scope(String tenant) {
        return new EnterpriseScope(tenant, "org-a", "credit", "test", "sg");
    }

    private IntegrationRequestContext identity(String tenant) {
        return new IntegrationRequestContext(
                tenant, "org-a", "credit", "test", "sg", "WORKLOAD",
                "author-a", "", "CORRECTNESS_READ", "corr-1");
    }

    private PrincipalRef owner() {
        return new PrincipalRef("author-a", PrincipalKind.USER, "Author A");
    }

    private static String fingerprint(char seed) {
        return "sha256:" + String.valueOf(seed).repeat(64);
    }

    private static final class InMemoryDefinitions implements CorrectnessDefinitionRepository {
        private final List<StoredCorrectnessDefinition> values = new ArrayList<>();

        void add(StoredCorrectnessDefinition value) {
            values.add(value);
        }

        @Override
        public Optional<StoredCorrectnessDefinition> findHead(
                EnterpriseScope scope,
                String definitionId
        ) {
            return values.stream().filter(value -> value.definition().scope().equals(scope)
                    && value.definition().definitionId().equals(definitionId)).findFirst();
        }

        @Override
        public List<StoredCorrectnessDefinition> findHeadCandidatesByTarget(
                EnterpriseScope scope,
                TargetKind targetKind,
                String targetId,
                String targetFingerprint
        ) {
            return values.stream().filter(value -> value.definition().scope().equals(scope)
                    && value.definition().target().kind() == targetKind
                    && value.definition().target().id().equals(targetId)
                    && value.definition().target().fingerprint().equals(targetFingerprint))
                    .limit(2).toList();
        }

        @Override
        public Optional<StoredCorrectnessDefinition> findRevision(
                EnterpriseScope scope,
                String definitionId,
                long revision
        ) {
            return findHead(scope, definitionId)
                    .filter(value -> value.definition().revision() == revision);
        }

        @Override
        public List<StoredCorrectnessDefinition> revisions(
                EnterpriseScope scope,
                String definitionId
        ) {
            return findHead(scope, definitionId).stream().toList();
        }

        @Override
        public Optional<StoredCorrectnessDefinition> saveIfRevision(
                long expectedRevision,
                CorrectnessDefinition candidate,
                PrincipalRef actor
        ) {
            throw new UnsupportedOperationException();
        }
    }
}
