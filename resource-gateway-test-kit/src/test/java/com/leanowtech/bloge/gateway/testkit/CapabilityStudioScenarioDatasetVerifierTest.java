package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CapabilityStudioScenarioDatasetVerifierTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final CapabilityStudioScenarioDatasetVerifier VERIFIER =
            new CapabilityStudioScenarioDatasetVerifier();

    @Test
    void acceptsACompleteActiveDatasetAndAllRequiredChecks() {
        ObjectNode dataset = activeDataset();

        CapabilityStudioScenarioDatasetVerifier.VerificationResult result =
                VERIFIER.verify(dataset);

        assertThat(result.verified()).isTrue();
        assertThat(result.failureKind())
                .isEqualTo(CapabilityStudioScenarioDatasetVerifier.FailureKind.NONE);
        assertThat(result.checks()).containsExactlyInAnyOrder(
                "SCHEMA", "CONTENT_FINGERPRINT", "SCOPE_CLOSURE", "REFERENCE_CLOSURE",
                "QUALITY_COUNTS", "ACTIVE_READINESS");
    }

    @Test
    void rejectsAnUnknownRootField() {
        ObjectNode dataset = activeDataset();
        dataset.put("unexpected", "value");

        assertSchemaFailure(dataset, "RG.CAPABILITY_STUDIO.SCENARIO_DATASET_SCHEMA_INVALID");
    }

    @Test
    void rejectsPayloadMockFixtureAndReplayMaterialFields() {
        for (String forbidden : List.of("payload", "mock", "fixture", "replay", "material")) {
            ObjectNode dataset = activeDataset();
            dataset.put(forbidden, "business-secret");

            CapabilityStudioScenarioDatasetVerifier.VerificationResult result =
                    VERIFIER.verify(dataset);

            assertThat(result.failureKind()).as(forbidden)
                    .isEqualTo(CapabilityStudioScenarioDatasetVerifier.FailureKind.SCHEMA);
            assertThat(result.errorCode()).as(forbidden)
                    .isEqualTo("RG.CAPABILITY_STUDIO.SCENARIO_DATASET_SCHEMA_INVALID");
            assertThat(result.toString()).doesNotContain("business-secret");
        }
    }

    @Test
    void rejectsAContentFingerprintMutation() {
        ObjectNode dataset = activeDataset();
        ((ObjectNode) dataset.path("datasetRef"))
                .put("fingerprint", "sha256:" + "b".repeat(64));

        assertSemanticFailure(dataset, "CONTENT_FINGERPRINT",
                "RG.CAPABILITY_STUDIO.SCENARIO_DATASET_CONTENT_FINGERPRINT_MISMATCH");
    }

    @Test
    void rejectsCrossScopeAtMultipleNestedReferencePositions() {
        for (String location : List.of(
                "targetRef", "contractRefs[0]", "cases[0].caseRef",
                "cases[0].sourceRef", "cases[0].oracleRef",
                "cases[0].applicableContractRefs[0]",
                "cases[0].behaviorProfiles[0].behaviorRef",
                "cases[0].behaviorProfiles[0].dependencyRef")) {
            ObjectNode dataset = activeDataset();
            scopedReference(dataset, location).with("scope").put("region", "eu-west-1");
            refreshFingerprint(dataset);

            assertSemanticFailure(dataset, "SCOPE_CLOSURE",
                    "RG.CAPABILITY_STUDIO.SCENARIO_DATASET_CROSS_SCOPE_REFERENCE");
        }
    }

    @Test
    void rejectsDuplicateCaseAndContractReferences() {
        ObjectNode duplicateCase = activeDataset();
        duplicateCase.withArray("cases").add(duplicateCase.path("cases").get(0).deepCopy());
        ObjectNode duplicateCaseQuality = object(duplicateCase, "quality");
        duplicateCaseQuality.put("totalCaseCount", 3);
        duplicateCaseQuality.put("activeCaseCount", 3);
        duplicateCaseQuality.put("ownerCoveragePercent", 100);
        duplicateCaseQuality.put("sourceCoveragePercent", 100);
        duplicateCaseQuality.put("oracleCoveragePercent", 100);
        duplicateCaseQuality.put("contractCoveragePercent", 100);
        duplicateCaseQuality.put("behaviorClosurePercent", 67);
        refreshFingerprint(duplicateCase);
        assertSemanticFailure(duplicateCase, "REFERENCE_CLOSURE",
                "RG.CAPABILITY_STUDIO.SCENARIO_DATASET_DUPLICATE_CASE_REF");

        ObjectNode duplicateContract = activeDataset();
        duplicateContract.withArray("contractRefs")
                .add(duplicateContract.path("contractRefs").get(0).deepCopy());
        object(duplicateContract, "quality").put("contractCoveragePercent", 100);
        refreshFingerprint(duplicateContract);
        assertSemanticFailure(duplicateContract, "REFERENCE_CLOSURE",
                "RG.CAPABILITY_STUDIO.SCENARIO_DATASET_DUPLICATE_CONTRACT_REF");
    }

    @Test
    void rejectsDuplicateBehaviorReferencesAcrossCases() {
        ObjectNode dataset = activeDataset();
        ObjectNode duplicateCase = ((ObjectNode) dataset.withArray("cases").get(0)).deepCopy();
        duplicateCase.with("caseRef").put("id", "case-timeout-copy");
        duplicateCase.with("caseRef").put("fingerprint", "sha256:" + "c".repeat(64));
        dataset.withArray("cases").add(duplicateCase);
        dataset.set("quality", quality(3, 3, 0, 100, 100, 100, 100, 67));
        refreshFingerprint(dataset);

        assertSemanticFailure(
                dataset,
                "REFERENCE_CLOSURE",
                "RG.CAPABILITY_STUDIO.SCENARIO_DATASET_DUPLICATE_BEHAVIOR_REF");
    }

    @Test
    void doesNotCountABusinessExpectationAsRuntimeControlClosure() {
        ObjectNode dataset = activeDataset();
        ObjectNode profile = (ObjectNode) dataset.path("cases").get(0)
                .path("behaviorProfiles").get(0);
        profile.put("purpose", "BUSINESS_EXPECTATION");
        refreshFingerprint(dataset);

        assertSemanticFailure(dataset, "QUALITY_COUNTS",
                "RG.CAPABILITY_STUDIO.SCENARIO_DATASET_QUALITY_COVERAGE_MISMATCH");
    }

    @Test
    void rejectsAnApplicableContractOutsideTheDatasetContractClosure() {
        ObjectNode dataset = activeDataset();
        ObjectNode foreign = exactRef("CONTRACT", "contract-foreign");
        ((ArrayNode) dataset.path("cases").get(0).path("applicableContractRefs"))
                .set(0, foreign);
        refreshFingerprint(dataset);

        assertSemanticFailure(dataset, "REFERENCE_CLOSURE",
                "RG.CAPABILITY_STUDIO.SCENARIO_DATASET_CONTRACT_CLOSURE_BROKEN");
    }

    @Test
    void rejectsDeclaredQualityCountsThatDoNotMatchCases() {
        ObjectNode dataset = activeDataset();
        object(dataset, "quality").put("activeCaseCount", 1);
        refreshFingerprint(dataset);

        assertSemanticFailure(dataset, "QUALITY_COUNTS",
                "RG.CAPABILITY_STUDIO.SCENARIO_DATASET_QUALITY_COUNTS_MISMATCH");
    }

    @Test
    void rejectsDeclaredCoverageThatDoesNotMatchCaseFields() {
        ObjectNode dataset = activeDataset();
        object(dataset, "quality").put("behaviorClosurePercent", 100);
        refreshFingerprint(dataset);

        assertSemanticFailure(dataset, "QUALITY_COUNTS",
                "RG.CAPABILITY_STUDIO.SCENARIO_DATASET_QUALITY_COVERAGE_MISMATCH");
    }

    @Test
    void rejectsActiveDatasetWithStaleCase() {
        ObjectNode dataset = activeDataset();
        ((ObjectNode) dataset.path("cases").get(1)).put("lifecycle", "STALE");
        object(dataset, "quality").put("activeCaseCount", 1);
        object(dataset, "quality").put("staleCaseCount", 1);
        refreshFingerprint(dataset);

        assertSemanticFailure(dataset, "ACTIVE_READINESS",
                "RG.CAPABILITY_STUDIO.SCENARIO_DATASET_ACTIVE_QUALITY_NOT_READY");
    }

    @Test
    void rejectsActiveCaseWithoutOwnerSourceOrOracle() {
        ObjectNode dataset = activeDataset();
        ObjectNode firstCase = (ObjectNode) dataset.path("cases").get(0);
        firstCase.putNull("owner");
        refreshFingerprint(dataset);

        assertSemanticFailure(dataset, "ACTIVE_READINESS",
                "RG.CAPABILITY_STUDIO.SCENARIO_DATASET_ACTIVE_CASE_INCOMPLETE");
    }

    @Test
    void rejectsActiveCaseWhoseQualityIsNotReady() {
        ObjectNode dataset = activeDataset();
        ((ObjectNode) dataset.withArray("cases").get(0)).put("qualityState", "BLOCKED");
        refreshFingerprint(dataset);

        assertSemanticFailure(
                dataset,
                "ACTIVE_READINESS",
                "RG.CAPABILITY_STUDIO.SCENARIO_DATASET_ACTIVE_CASE_INCOMPLETE");
    }

    @Test
    void validatesDraftCoverageAndRoundedPercentages() {
        ObjectNode dataset = activeDataset();
        dataset.put("lifecycle", "DRAFT");
        ObjectNode secondCase = (ObjectNode) dataset.path("cases").get(1);
        secondCase.put("lifecycle", "DRAFT");
        secondCase.putNull("owner");
        secondCase.putNull("sourceRef");
        secondCase.putNull("source");
        secondCase.putNull("oracleRef");
        secondCase.putNull("oracle");
        secondCase.withArray("behaviorProfiles").removeAll();
        ObjectNode draftQuality = object(dataset, "quality");
        draftQuality.put("ownerCoveragePercent", 50);
        draftQuality.put("sourceCoveragePercent", 50);
        draftQuality.put("oracleCoveragePercent", 50);
        draftQuality.put("contractCoveragePercent", 100);
        draftQuality.put("behaviorClosurePercent", 50);
        draftQuality.put("activeCaseCount", 1);
        refreshFingerprint(dataset);

        assertThat(VERIFIER.verify(dataset).verified()).isTrue();
    }

    @Test
    void rejectsAnOversizedRawWireDocument() {
        byte[] oversized = ("{" + "x".repeat(
                CapabilityStudioScenarioDatasetVerifier.MAXIMUM_DATASET_BYTES) + "}")
                .getBytes(StandardCharsets.UTF_8);

        CapabilityStudioScenarioDatasetVerifier.VerificationResult result =
                VERIFIER.verify(oversized);

        assertThat(result.failureKind())
                .isEqualTo(CapabilityStudioScenarioDatasetVerifier.FailureKind.SCHEMA);
        assertThat(result.errorCode())
                .isEqualTo("RG.CAPABILITY_STUDIO.SCENARIO_DATASET_SIZE_LIMIT");
    }

    @Test
    void rejectsEmptyCasesAtSchemaBoundary() {
        ObjectNode dataset = activeDataset();
        dataset.withArray("cases").removeAll();
        refreshFingerprint(dataset);

        assertSchemaFailure(dataset, "RG.CAPABILITY_STUDIO.SCENARIO_DATASET_SCHEMA_INVALID");
    }

    @Test
    void rejectsInvalidEnumAtSchemaBoundary() {
        ObjectNode dataset = activeDataset();
        dataset.put("classification", "TOP_SECRET");

        assertSchemaFailure(dataset, "RG.CAPABILITY_STUDIO.SCENARIO_DATASET_SCHEMA_INVALID");
    }

    @Test
    void rejectsInvalidJsonWithoutReturningParserDetails() {
        CapabilityStudioScenarioDatasetVerifier.VerificationResult result =
                VERIFIER.verify("{not-json}".getBytes(StandardCharsets.UTF_8));

        assertThat(result.failureKind())
                .isEqualTo(CapabilityStudioScenarioDatasetVerifier.FailureKind.SCHEMA);
        assertThat(result.errorCode())
                .isEqualTo("RG.CAPABILITY_STUDIO.SCENARIO_DATASET_INVALID_JSON");
        assertThat(result.toString()).doesNotContain("not-json");
    }

    @Test
    void resultIsLogSafeAndDoesNotEchoBusinessPayload() {
        ObjectNode dataset = activeDataset();
        dataset.put("description", "customer-secret-business-payload");
        refreshFingerprint(dataset);
        ((ObjectNode) dataset.path("datasetRef"))
                .put("fingerprint", "sha256:" + "c".repeat(64));

        CapabilityStudioScenarioDatasetVerifier.VerificationResult result =
                VERIFIER.verify(dataset);

        assertThat(result.errorCode()).isEqualTo(
                "RG.CAPABILITY_STUDIO.SCENARIO_DATASET_CONTENT_FINGERPRINT_MISMATCH");
        assertThat(result.toString()).doesNotContain("customer-secret-business-payload");
        assertThat(result.toString()).doesNotContain("cases");
    }

    private static ObjectNode activeDataset() {
        ObjectNode dataset = JSON.createObjectNode();
        dataset.put("schemaVersion",
                "resource-gateway.capability-studio.scenario-dataset.v1");
        dataset.set("datasetRef", exactRef("DATASET", "dataset-orders"));
        dataset.put("name", "订单查询正确性场景集");
        dataset.put("description", "验证订单查询在正常与边界条件下的业务表现");
        dataset.put("lifecycle", "ACTIVE");
        dataset.put("classification", "INTERNAL");
        dataset.set("owner", owner("team-customer-service", "客服技术团队"));
        dataset.set("targetRef", exactRef("TOOL", "tool-order-query"));
        dataset.set("contractRefs", JSON.createArrayNode()
                .add(exactRef("CONTRACT", "contract-order-query")));

        ArrayNode cases = dataset.putArray("cases");
        cases.add(dataCase("case-order-found", "ACTIVE", true));
        cases.add(dataCase("case-order-timeout", "ACTIVE", false));
        dataset.set("quality", quality(2, 2, 0, 100, 100, 100, 100, 50));
        refreshFingerprint(dataset);
        return dataset;
    }

    private static ObjectNode dataCase(String id, String lifecycle, boolean withBehavior) {
        ObjectNode dataCase = JSON.createObjectNode();
        dataCase.set("caseRef", exactRef("DATA_CASE", id));
        dataCase.put("name", id);
        dataCase.put("businessIntent", "验证业务场景 " + id);
        dataCase.put("category", withBehavior ? "GOLDEN" : "BOUNDARY");
        dataCase.put("lifecycle", lifecycle);
        dataCase.put("qualityState", "READY");
        dataCase.set("owner", owner("team-customer-service", "客服技术团队"));
        dataCase.set("sourceRef", exactRef("SOURCE", "source-" + id));
        dataCase.set("source", JSON.createObjectNode()
                .put("displayName", "业务回放样本")
                .put("type", "CURATED_BUSINESS_SAMPLE"));
        dataCase.set("oracleRef", exactRef("ORACLE", "oracle-" + id));
        dataCase.set("oracle", JSON.createObjectNode()
                .put("displayName", "订单查询人工确认结果")
                .put("summary", "结果应符合订单状态与展示规则"));
        dataCase.set("applicableContractRefs", JSON.createArrayNode()
                .add(exactRef("CONTRACT", "contract-order-query")));
        ArrayNode behaviors = dataCase.putArray("behaviorProfiles");
        if (withBehavior) {
            ObjectNode behavior = JSON.createObjectNode();
            behavior.set("behaviorRef", exactRef("BEHAVIOR_PROFILE", "behavior-" + id));
            behavior.set("dependencyRef", exactRef("API", "api-order-query"));
            behavior.put("purpose", "RUNTIME_CONTROL");
            behavior.put("behavior", "RETURN");
            behavior.put("summary", "返回脱敏的订单查询结果");
            behaviors.add(behavior);
        }
        return dataCase;
    }

    private static ObjectNode quality(
            int total,
            int active,
            int stale,
            int owners,
            int sources,
            int oracles,
            int contracts,
            int behaviors) {
        return JSON.createObjectNode()
                .put("status", "READY")
                .put("totalCaseCount", total)
                .put("activeCaseCount", active)
                .put("staleCaseCount", stale)
                .put("ownerCoveragePercent", owners)
                .put("sourceCoveragePercent", sources)
                .put("oracleCoveragePercent", oracles)
                .put("contractCoveragePercent", contracts)
                .put("behaviorClosurePercent", behaviors);
    }

    private static ObjectNode exactRef(String kind, String id) {
        return JSON.createObjectNode()
                .put("kind", kind)
                .put("id", id)
                .put("revision", 1)
                .put("fingerprint", "sha256:" + "a".repeat(64))
                .put("authority", "capability-studio-authority")
                .set("scope", scope());
    }

    private static ObjectNode scope() {
        return JSON.createObjectNode()
                .put("tenantId", "tenant-acme")
                .put("organizationId", "org-customer-service")
                .put("projectId", "project-support")
                .put("environmentId", "staging")
                .put("region", "ap-southeast-1");
    }

    private static ObjectNode owner(String id, String name) {
        return JSON.createObjectNode().put("id", id).put("name", name);
    }

    private static void refreshFingerprint(ObjectNode dataset) {
        ObjectNode material = dataset.deepCopy();
        material.with("datasetRef").putNull("fingerprint");
        ((ObjectNode) dataset.path("datasetRef")).put(
                "fingerprint", EvidenceVerificationSupport.sha256Bounded(
                        material, CapabilityStudioScenarioDatasetVerifier.MAXIMUM_DATASET_BYTES));
    }

    private static ObjectNode object(ObjectNode parent, String field) {
        return (ObjectNode) parent.path(field);
    }

    private static ObjectNode scopedReference(ObjectNode dataset, String location) {
        String[] segments = location.replace("]", "").replace("[", ".").split("\\.");
        JsonNode current = dataset;
        for (int i = 0; i < segments.length - 1; i++) {
            current = current.isArray()
                    ? current.path(Integer.parseInt(segments[i]))
                    : current.path(segments[i]);
        }
        String finalSegment = segments[segments.length - 1];
        return (ObjectNode) (current.isArray()
                ? current.path(Integer.parseInt(finalSegment))
                : current.path(finalSegment));
    }

    private static void assertSchemaFailure(ObjectNode dataset, String code) {
        CapabilityStudioScenarioDatasetVerifier.VerificationResult result =
                VERIFIER.verify(dataset);
        assertThat(result.failureKind())
                .isEqualTo(CapabilityStudioScenarioDatasetVerifier.FailureKind.SCHEMA);
        assertThat(result.errorCode()).isEqualTo(code);
    }

    private static void assertSemanticFailure(
            ObjectNode dataset,
            String check,
            String code) {
        CapabilityStudioScenarioDatasetVerifier.VerificationResult result =
                VERIFIER.verify(dataset);
        assertThat(result.failureKind())
                .isEqualTo(CapabilityStudioScenarioDatasetVerifier.FailureKind.SEMANTIC);
        assertThat(result.checks()).containsExactly(check);
        assertThat(result.errorCode()).isEqualTo(code);
    }
}
