package com.leanowtech.bloge.gateway.capabilitystudio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CapabilityStudioScenarioDatasetProjectorTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> REF_FIELDS = Set.of(
            "kind", "id", "revision", "fingerprint", "authority", "scope");
    private static final Set<String> SCOPE_FIELDS = Set.of(
            "tenantId", "organizationId", "projectId", "environmentId", "region");
    private final CapabilityStudioGoldenDemoPack pack =
            new CapabilityStudioGoldenDemoPackLoader().load(JSON);

    @Test
    void projectsTheStrictPayloadFreeNineCaseDataset() throws Exception {
        CapabilityStudioScenarioDatasetProjector.ScenarioDatasetProjection projection = projection(pack);
        JsonNode tree = JSON.valueToTree(projection);

        assertThat(tree.fieldNames()).toIterable().containsExactly(
                "schemaVersion", "datasetRef", "name", "description", "lifecycle",
                "classification", "owner", "targetRef", "contractRefs", "cases", "quality");
        assertThat(tree.path("schemaVersion").asText())
                .isEqualTo("resource-gateway.capability-studio.scenario-dataset.v1");
        assertThat(projection.datasetRef().kind()).isEqualTo("DATASET");
        assertThat(projection.targetRef().kind()).isEqualTo("TOOL");
        assertThat(projection.lifecycle()).isEqualTo("REVIEW_READY");
        assertThat(projection.quality().status()).isEqualTo("BLOCKED");
        assertThat(projection.cases()).hasSize(9);
        assertThat(projection.cases()).extracting(
                CapabilityStudioScenarioDatasetProjector.DataCase::category)
                .contains("GOLDEN", "NEGATIVE", "BOUNDARY", "REGRESSION", "SECURITY");
        assertThat(projection.cases()).allSatisfy(value -> {
            assertThat(value.lifecycle()).isEqualTo("DRAFT");
            assertThat(value.qualityState()).isEqualTo("DESIGNED_NOT_RUN");
            assertThat(value.behaviorProfiles().stream()
                    .filter(profile -> "RUNTIME_CONTROL".equals(profile.purpose())))
                    .hasSize(4)
                    .extracting(profile -> profile.dependencyRef().id())
                    .containsExactlyInAnyOrder(
                            "api-order-lookup",
                            "api-cancellation-responsibility",
                            "api-city-pricing-policy",
                            "api-compensation-history");
        });
        assertThat(caseById(projection, "case-duplicate-cancellation").behaviorProfiles())
                .filteredOn(profile -> "BUSINESS_EXPECTATION".equals(profile.purpose()))
                .singleElement()
                .satisfies(profile -> {
                    assertThat(profile.dependencyRef().id())
                            .isEqualTo("tool-cancellation-fee-dispute-handling");
                    assertThat(profile.behavior()).isEqualTo("RETURN");
                });
        assertThat(caseById(projection, "case-forbidden-write-effect").behaviorProfiles())
                .filteredOn(profile -> "BUSINESS_EXPECTATION".equals(profile.purpose()))
                .singleElement()
                .satisfies(profile -> assertThat(profile.behavior()).isEqualTo("MUST_NOT_CALL"));
        assertThat(projection.quality().totalCaseCount()).isEqualTo(9);
        assertThat(projection.quality().activeCaseCount()).isZero();
        assertThat(projection.quality().staleCaseCount()).isZero();
        assertThat(projection.quality().ownerCoveragePercent()).isEqualTo(100);
        assertThat(projection.quality().sourceCoveragePercent()).isEqualTo(100);
        assertThat(projection.quality().oracleCoveragePercent()).isEqualTo(100);
        assertThat(projection.quality().contractCoveragePercent()).isEqualTo(100);
        assertThat(projection.quality().behaviorClosurePercent()).isEqualTo(100);
        assertThat(tree.toString()).doesNotContain("payload", "fixture", "mock", "replay", "material");
    }

    @Test
    void closesEveryReferenceOnOneScopeAndClosesApplicableContracts() {
        CapabilityStudioScenarioDatasetProjector.ScenarioDatasetProjection projection = projection(pack);
        JsonNode tree = JSON.valueToTree(projection);
        JsonNode expectedScope = tree.path("datasetRef").path("scope");
        List<JsonNode> references = references(tree);

        assertThat(references).isNotEmpty();
        assertThat(references).allSatisfy(ref -> {
            assertThat(ref.fieldNames()).toIterable().containsExactlyInAnyOrderElementsOf(REF_FIELDS);
            assertThat(ref.path("scope").fieldNames()).toIterable()
                    .containsExactlyInAnyOrderElementsOf(SCOPE_FIELDS);
            assertThat(ref.path("scope")).isEqualTo(expectedScope);
            assertThat(ref.path("authority").asText()).isNotBlank();
            assertThat(ref.path("fingerprint").asText()).matches("sha256:[a-f0-9]{64}");
        });

        Set<String> declaredContracts = new HashSet<>();
        tree.path("contractRefs").forEach(ref -> declaredContracts.add(identity(ref)));
        tree.path("cases").forEach(dataCase -> dataCase.path("applicableContractRefs")
                .forEach(ref -> assertThat(declaredContracts).contains(identity(ref))));
        assertThat(tree.path("contractRefs").findValuesAsText("id"))
                .containsExactly(
                        "contract-cancellation-dispute-context",
                        "contract-cancellation-fee-dispute-tool",
                        "contract-cancellation-responsibility",
                        "contract-city-pricing-policy",
                        "contract-compensation-history",
                        "contract-order-lookup");
        assertThat(tree.path("cases").get(0).path("sourceRef").path("fingerprint").asText())
                .isEqualTo(pack.scenarios().stream().sorted(Comparator.comparing(
                        CapabilityStudioGoldenDemoPack.TestScenario::id)).findFirst().orElseThrow()
                        .sourceRef().fingerprint());
    }

    @Test
    void usesIndependentContentFingerprintsForDatasetCasesAndBehaviors() throws Exception {
        JsonNode tree = JSON.valueToTree(projection(pack));
        assertThat(tree.path("datasetRef").path("fingerprint").asText())
                .isEqualTo(fingerprintWithNullRef(tree, "datasetRef"));
        tree.path("cases").forEach(dataCase -> {
            assertThat(dataCase.path("caseRef").path("fingerprint").asText())
                    .isEqualTo(fingerprintWithNullRef(dataCase, "caseRef"));
            dataCase.path("behaviorProfiles").forEach(profile -> assertThat(
                    profile.path("behaviorRef").path("fingerprint").asText())
                    .isEqualTo(fingerprintWithNullRef(profile, "behaviorRef")));
        });
        List<String> behaviorRefs = new ArrayList<>();
        tree.path("cases").forEach(dataCase -> {
            dataCase.path("behaviorProfiles")
                    .forEach(profile -> behaviorRefs.add(identity(profile.path("behaviorRef"))));
        });
        assertThat(behaviorRefs).doesNotHaveDuplicates();
        assertThat(behaviorRefs).noneMatch(value -> value.contains("null"));
    }

    @Test
    void isDeterministicAndReflectsGoldenPackFactsWithoutASecondScenarioTable() throws Exception {
        CapabilityStudioScenarioDatasetProjector first = new CapabilityStudioScenarioDatasetProjector(pack, JSON);
        CapabilityStudioScenarioDatasetProjector second = new CapabilityStudioScenarioDatasetProjector(pack, JSON);
        assertThat(JSON.writeValueAsString(first.project())).isEqualTo(JSON.writeValueAsString(second.project()));
        assertThat(first.project().datasetRef().fingerprint())
                .isEqualTo(second.project().datasetRef().fingerprint());

        CapabilityStudioGoldenDemoPack.TestScenario original = pack.scenarios().getFirst();
        CapabilityStudioGoldenDemoPack.TestScenario changed = new CapabilityStudioGoldenDemoPack.TestScenario(
                original.id(), "来自 golden pack 的事实变化", original.ref(), original.owner(),
                original.contractRef(), original.sourceRef(), original.oracleRef(), original.source(),
                original.oracle(), original.applicableContractRefs(), original.category(),
                original.expectedResult(), original.lifecycle(), original.qualityState(),
                original.dependencyBehaviors());
        List<CapabilityStudioGoldenDemoPack.TestScenario> scenarios = new ArrayList<>(pack.scenarios());
        scenarios.set(0, changed);
        CapabilityStudioGoldenDemoPack changedPack = new CapabilityStudioGoldenDemoPack(
                pack.schemaVersion(), pack.packId(), pack.revision(), pack.packFingerprint(), pack.displayName(),
                pack.owner(), pack.readiness(), pack.canonicalBaseline(), pack.apiCapabilities(),
                pack.featureCapabilities(), pack.toolCapabilities(), pack.supportingRefs(), scenarios,
                pack.tutorialBranch());
        CapabilityStudioScenarioDatasetProjector.ScenarioDatasetProjection changedProjection =
                projection(changedPack);

        assertThat(changedProjection.cases().stream()
                .filter(value -> value.caseRef().id().equals(original.id()))
                .findFirst().orElseThrow().name()).isEqualTo("来自 golden pack 的事实变化");
        assertThat(changedProjection.datasetRef().fingerprint())
                .isNotEqualTo(first.project().datasetRef().fingerprint());
    }

    private CapabilityStudioScenarioDatasetProjector.ScenarioDatasetProjection projection(
            CapabilityStudioGoldenDemoPack value) {
        return new CapabilityStudioScenarioDatasetProjector(value, JSON).project();
    }

    private static CapabilityStudioScenarioDatasetProjector.DataCase caseById(
            CapabilityStudioScenarioDatasetProjector.ScenarioDatasetProjection projection,
            String caseId) {
        return projection.cases().stream()
                .filter(dataCase -> caseId.equals(dataCase.caseRef().id()))
                .findFirst()
                .orElseThrow();
    }

    private static List<JsonNode> references(JsonNode tree) {
        List<JsonNode> result = new ArrayList<>();
        result.add(tree.path("datasetRef"));
        result.add(tree.path("targetRef"));
        tree.path("contractRefs").forEach(result::add);
        tree.path("cases").forEach(dataCase -> {
            result.add(dataCase.path("caseRef"));
            result.add(dataCase.path("sourceRef"));
            result.add(dataCase.path("oracleRef"));
            dataCase.path("applicableContractRefs").forEach(result::add);
            dataCase.path("behaviorProfiles").forEach(profile -> {
                result.add(profile.path("behaviorRef"));
                result.add(profile.path("dependencyRef"));
            });
        });
        return result;
    }

    private static String identity(JsonNode ref) {
        return ref.path("kind").asText() + "|" + ref.path("id").asText()
                + "|" + ref.path("revision").asInt() + "|" + ref.path("fingerprint").asText();
    }

    private static String fingerprintWithNullRef(JsonNode value, String field) {
        ObjectNode material = value.deepCopy();
        material.with(field).putNull("fingerprint");
        return sha256(canonical(material));
    }

    private static String sha256(JsonNode value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(new ObjectMapper().writeValueAsBytes(value));
            StringBuilder result = new StringBuilder("sha256:");
            for (byte item : digest) {
                result.append(String.format("%02x", item));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException | IOException failure) {
            throw new AssertionError("Test fingerprint algorithm unavailable", failure);
        }
    }

    private static JsonNode canonical(JsonNode value) {
        if (value.isObject()) {
            ObjectNode sorted = JSON.createObjectNode();
            List<String> names = new ArrayList<>();
            value.fieldNames().forEachRemaining(names::add);
            names.sort(Comparator.naturalOrder());
            names.forEach(name -> sorted.set(name, canonical(value.get(name))));
            return sorted;
        }
        if (value.isArray()) {
            ArrayNode array = JSON.createArrayNode();
            value.forEach(item -> array.add(canonical(item)));
            return array;
        }
        return value.deepCopy();
    }
}
