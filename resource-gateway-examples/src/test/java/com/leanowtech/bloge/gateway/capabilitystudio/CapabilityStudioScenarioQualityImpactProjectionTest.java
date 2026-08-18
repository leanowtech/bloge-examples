package com.leanowtech.bloge.gateway.capabilitystudio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CapabilityStudioScenarioQualityImpactProjectionTest {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private final CapabilityStudioGoldenDemoPack pack =
            new CapabilityStudioGoldenDemoPackLoader().load(JSON);
    private final CapabilityStudioScenarioDatasetProjector datasetProjector =
            new CapabilityStudioScenarioDatasetProjector(pack, JSON);

    @Test
    void projectsTruthfulNineDraftAdmissionAndImpactClosure() throws Exception {
        CapabilityStudioScenarioQualityImpactProjection.ScenarioQualityImpactProjection projection =
                projection(datasetProjector.project());

        assertThat(projection.schemaVersion()).isEqualTo(
                "resource-gateway.capability-studio.scenario-quality-impact.v1");
        assertThat(projection.datasetRef()).isEqualTo(datasetProjector.project().datasetRef());
        assertThat(projection.targetRef()).isEqualTo(datasetProjector.project().targetRef());
        assertThat(projection.projectionFingerprint()).matches("sha256:[a-f0-9]{64}");
        assertThat(projection.admission().status()).isEqualTo("BLOCKED");
        assertThat(projection.admission().activeCaseCount()).isZero();
        assertThat(projection.admission().draftCaseCount()).isEqualTo(9);
        assertThat(projection.admission().staleCaseCount()).isZero();
        assertThat(projection.admission().blockers())
                .extracting(CapabilityStudioScenarioQualityImpactProjection.Blocker::code)
                .containsExactly("FRESHNESS_EVIDENCE_MISSING", "NO_ACTIVE_CASES");

        assertThat(projection.quality().status()).isEqualTo("BLOCKED");
        assertThat(projection.quality().ownerCoveragePercent()).isEqualTo(100);
        assertThat(projection.quality().sourceCoveragePercent()).isEqualTo(100);
        assertThat(projection.quality().oracleCoveragePercent()).isEqualTo(100);
        assertThat(projection.quality().contractCoveragePercent()).isEqualTo(100);
        assertThat(projection.quality().behaviorClosurePercent()).isEqualTo(100);
        assertThat(projection.quality().freshnessStatus()).isEqualTo("UNVERIFIED");
        assertThat(projection.quality().payloadExposure()).isEqualTo("NONE");
        assertThat(projection.quality().maskingStatus()).isEqualTo("PAYLOAD_NOT_EXPORTED");

        assertThat(projection.summary().caseCount()).isEqualTo(9);
        assertThat(projection.summary().sourceCount()).isEqualTo(9);
        assertThat(projection.summary().oracleCount()).isEqualTo(9);
        assertThat(projection.summary().contractCount()).isEqualTo(4);
        assertThat(projection.summary().dependencyCount()).isEqualTo(4);
        assertThat(projection.summary().targetCount()).isEqualTo(1);
        assertThat(projection.summary().impactedAssetCount()).isEqualTo(9);
        assertThat(projection.summary().orphanCaseCount()).isZero();
        assertThat(projection.cases()).hasSize(9).allSatisfy(value -> {
            assertThat(value.lifecycle()).isEqualTo("DRAFT");
            assertThat(value.freshnessStatus()).isEqualTo("UNVERIFIED");
            assertThat(value.maskingStatus()).isEqualTo("PAYLOAD_NOT_EXPORTED");
            assertThat(value.contractRefs()).hasSize(1);
            assertThat(value.dependencyRefs()).hasSize(4);
            assertThat(value.impactedAssetCount()).isEqualTo(6);
        });

        assertThat(projection.impactGraph().nodes())
                .extracting(CapabilityStudioScenarioQualityImpactProjection.Node::kind)
                .containsExactlyInAnyOrderElementsOf(List.of(
                        "DATASET", "TARGET",
                        "DATA_CASE", "DATA_CASE", "DATA_CASE", "DATA_CASE", "DATA_CASE",
                        "DATA_CASE", "DATA_CASE", "DATA_CASE", "DATA_CASE",
                        "SOURCE", "SOURCE", "SOURCE", "SOURCE", "SOURCE", "SOURCE", "SOURCE", "SOURCE", "SOURCE",
                        "ORACLE", "ORACLE", "ORACLE", "ORACLE", "ORACLE", "ORACLE", "ORACLE", "ORACLE", "ORACLE",
                        "CONTRACT", "CONTRACT", "CONTRACT", "CONTRACT",
                        "DEPENDENCY", "DEPENDENCY", "DEPENDENCY", "DEPENDENCY"));
        assertThat(projection.impactGraph().edges()).hasSize(81);

        JsonNode tree = JSON.valueToTree(projection);
        assertThat(fieldNames(tree.get("quality"))).containsExactlyInAnyOrder(
                "status", "ownerCoveragePercent", "sourceCoveragePercent", "oracleCoveragePercent",
                "contractCoveragePercent", "behaviorClosurePercent", "freshnessStatus", "payloadExposure",
                "maskingStatus");
        assertThat(projection.impactGraph().nodes()).allSatisfy(node -> {
            assertThat(node.id()).isEqualTo(node.kind() + ":" + node.ref().id());
            assertThat(node.status()).isIn("ACTIVE", "DRAFT", "STALE", "READY", "BLOCKED", "ORPHANED", "RETIRED");
        });
        assertThat(projection.impactGraph().edges()).noneMatch(edge ->
                edge.source().startsWith("DATASET:") && edge.relation().equals("CONTAINS")
                        && edge.target().startsWith("CONTRACT:"));
        assertThat(tree.toString()).doesNotContain("fixture", "mock", "replay", "request", "response");
    }

    @Test
    void isDeterministicAndFingerprintNormalizesOnlyItsOwnField() throws Exception {
        var first = projection(datasetProjector.project());
        var second = projection(new CapabilityStudioScenarioDatasetProjector(pack, JSON).project());

        assertThat(JSON.writeValueAsBytes(first)).isEqualTo(JSON.writeValueAsBytes(second));
        assertThat(first.projectionFingerprint()).isEqualTo(second.projectionFingerprint());
        JsonNode tree = JSON.valueToTree(first);
        String fingerprint = first.projectionFingerprint();
        ((com.fasterxml.jackson.databind.node.ObjectNode) tree).putNull("projectionFingerprint");
        assertThat(fingerprint).isEqualTo(sha256Canonical(tree));
    }

    @Test
    void failsClosedOnMissingRefDuplicateCaseAndCoverageTampering() {
        var dataset = datasetProjector.project();
        var first = dataset.cases().getFirst();
        var missingSource = new CapabilityStudioScenarioDatasetProjector.DataCase(
                first.caseRef(), first.name(), first.businessIntent(), first.category(), first.lifecycle(),
                first.qualityState(), first.owner(), null, first.source(), first.oracleRef(), first.oracle(),
                first.applicableContractRefs(), first.behaviorProfiles());

        assertThatThrownBy(() -> projection(withCase(dataset, missingSource)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("REF_MISSING");

        List<CapabilityStudioScenarioDatasetProjector.DataCase> duplicates = new ArrayList<>(dataset.cases());
        duplicates.add(first);
        assertThatThrownBy(() -> projection(withCases(dataset, duplicates)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DUPLICATE_CASE_IDENTITY");

        var sourceQuality = dataset.quality();
        var inconsistentQuality = new CapabilityStudioScenarioDatasetProjector.Quality(
                sourceQuality.status(), sourceQuality.totalCaseCount(), sourceQuality.activeCaseCount(),
                sourceQuality.staleCaseCount(), 99, sourceQuality.sourceCoveragePercent(),
                sourceQuality.oracleCoveragePercent(), sourceQuality.contractCoveragePercent(),
                sourceQuality.behaviorClosurePercent());
        assertThatThrownBy(() -> projection(new CapabilityStudioScenarioDatasetProjector.ScenarioDatasetProjection(
                dataset.schemaVersion(), dataset.datasetRef(), dataset.name(), dataset.description(),
                dataset.lifecycle(), dataset.classification(), dataset.owner(), dataset.targetRef(),
                dataset.contractRefs(), dataset.cases(), inconsistentQuality)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("COVERAGE_INCONSISTENT");
    }

    @Test
    void failsClosedOnAnOrphanContractIdentity() {
        var dataset = datasetProjector.project();
        var existing = dataset.contractRefs().getFirst();
        var orphan = new CapabilityStudioScenarioDatasetProjector.ExactRef(
                "CONTRACT", "contract-orphan", existing.revision(), existing.fingerprint(),
                existing.authority(), existing.scope());
        List<CapabilityStudioScenarioDatasetProjector.ExactRef> contracts =
                new ArrayList<>(dataset.contractRefs());
        contracts.add(orphan);

        assertThatThrownBy(() -> projection(new CapabilityStudioScenarioDatasetProjector.ScenarioDatasetProjection(
                dataset.schemaVersion(), dataset.datasetRef(), dataset.name(), dataset.description(),
                dataset.lifecycle(), dataset.classification(), dataset.owner(), dataset.targetRef(),
                contracts, dataset.cases(), dataset.quality())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("REF_NOT_AUTHORITATIVE");
    }

    @Test
    void allowsIndependentAuthoritiesButFailsClosedAcrossDatasetScope() {
        var dataset = datasetProjector.project();
        var first = dataset.cases().getFirst();
        var source = first.sourceRef();
        var independentlyGovernedSource = new CapabilityStudioScenarioDatasetProjector.ExactRef(
                source.kind(), source.id(), source.revision(), source.fingerprint(),
                "business-fixture-registry", source.scope());
        var independentAuthorityCase = withSourceRef(first, independentlyGovernedSource);

        assertThat(projection(withCase(dataset, independentAuthorityCase)).cases().getFirst()
                .sourceRef().authority()).isEqualTo("business-fixture-registry");

        var scope = source.scope();
        var crossScopeSource = new CapabilityStudioScenarioDatasetProjector.ExactRef(
                source.kind(), source.id(), source.revision(), source.fingerprint(), source.authority(),
                new CapabilityStudioScenarioDatasetProjector.Scope(
                        scope.tenantId(), scope.organizationId(), scope.projectId(),
                        scope.environmentId(), "production"));
        assertThatThrownBy(() -> projection(withCase(dataset, withSourceRef(first, crossScopeSource))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("REF_SCOPE_MISMATCH");
    }

    private CapabilityStudioScenarioQualityImpactProjection.ScenarioQualityImpactProjection projection(
            CapabilityStudioScenarioDatasetProjector.ScenarioDatasetProjection dataset) {
        return new CapabilityStudioScenarioQualityImpactProjection(pack, dataset, JSON).project();
    }

    private static CapabilityStudioScenarioDatasetProjector.ScenarioDatasetProjection withCase(
            CapabilityStudioScenarioDatasetProjector.ScenarioDatasetProjection dataset,
            CapabilityStudioScenarioDatasetProjector.DataCase replacement) {
        List<CapabilityStudioScenarioDatasetProjector.DataCase> cases = new ArrayList<>(dataset.cases());
        cases.set(0, replacement);
        return withCases(dataset, cases);
    }

    private static CapabilityStudioScenarioDatasetProjector.DataCase withSourceRef(
            CapabilityStudioScenarioDatasetProjector.DataCase source,
            CapabilityStudioScenarioDatasetProjector.ExactRef sourceRef) {
        return new CapabilityStudioScenarioDatasetProjector.DataCase(
                source.caseRef(), source.name(), source.businessIntent(), source.category(), source.lifecycle(),
                source.qualityState(), source.owner(), sourceRef, source.source(), source.oracleRef(),
                source.oracle(), source.applicableContractRefs(), source.behaviorProfiles());
    }

    private static CapabilityStudioScenarioDatasetProjector.ScenarioDatasetProjection withCases(
            CapabilityStudioScenarioDatasetProjector.ScenarioDatasetProjection dataset,
            List<CapabilityStudioScenarioDatasetProjector.DataCase> cases) {
        return new CapabilityStudioScenarioDatasetProjector.ScenarioDatasetProjection(
                dataset.schemaVersion(), dataset.datasetRef(), dataset.name(), dataset.description(),
                dataset.lifecycle(), dataset.classification(), dataset.owner(), dataset.targetRef(),
                dataset.contractRefs(), cases, dataset.quality());
    }

    private static String sha256Canonical(JsonNode value) throws Exception {
        byte[] bytes = JSON.writeValueAsBytes(canonical(value));
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
        return "sha256:" + java.util.HexFormat.of().formatHex(digest);
    }

    private static List<String> fieldNames(JsonNode value) {
        List<String> names = new ArrayList<>();
        value.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static JsonNode canonical(JsonNode value) {
        if (value.isObject()) {
            var object = JSON.createObjectNode();
            List<String> names = new ArrayList<>();
            value.fieldNames().forEachRemaining(names::add);
            names.sort(String::compareTo);
            names.forEach(name -> object.set(name, canonical(value.get(name))));
            return object;
        }
        if (value.isArray()) {
            var array = JSON.createArrayNode();
            value.forEach(item -> array.add(canonical(item)));
            return array;
        }
        return value;
    }
}
