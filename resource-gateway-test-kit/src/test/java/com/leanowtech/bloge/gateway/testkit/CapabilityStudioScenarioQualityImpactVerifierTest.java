package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CapabilityStudioScenarioQualityImpactVerifierTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final CapabilityStudioScenarioQualityImpactVerifier VERIFIER =
            new CapabilityStudioScenarioQualityImpactVerifier();

    @Test
    void packagesTheStrictGp09Schema() throws Exception {
        try (InputStream input = getClass().getResourceAsStream(
                CapabilityStudioSchemaSupport.SCENARIO_QUALITY_IMPACT_RESOURCE)) {
            assertThat(input).isNotNull();
            JsonNode schema = JSON.readTree(input);
            assertThat(schema.path("$schema").asText())
                    .isEqualTo("https://json-schema.org/draft/2020-12/schema");
            assertThat(schema.path("$id").asText())
                    .endsWith("capability-studio-scenario-quality-impact-v1.schema.json");
            assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
            assertThat(schema.path("properties").path("impactGraph").isMissingNode()).isFalse();
            assertThat(schema.path("$defs").path("node").path("additionalProperties").asBoolean())
                    .isFalse();
            assertThat(schema.path("$defs").path("case").path("properties")
                    .path("dependencyRefs").path("items").path("allOf").get(1)
                    .path("properties").path("kind").path("const").asText()).isEqualTo("API");
            assertThat(schema.path("$defs").path("quality").path("properties")
                    .path("totalCaseCount").isMissingNode()).isTrue();
            assertThat(schema.path("$defs").path("quality").path("properties")
                    .path("activeCaseCount").isMissingNode()).isTrue();
            assertThat(schema.path("$defs").path("quality").path("properties")
                    .path("staleCaseCount").isMissingNode()).isTrue();
            assertThat(schema.path("$defs").path("edge").path("properties").path("id")
                    .path("$ref").asText()).isEqualTo("#/$defs/edgeIdentifier");
        }
    }

    @Test
    void acceptsReadyProjectionAndRecomputesAllCounters() {
        CapabilityStudioScenarioQualityImpactVerifier.VerificationResult result =
                VERIFIER.verify(readyProjection());

        assertThat(result.verified()).isTrue();
        assertThat(result.checks()).containsExactlyInAnyOrder(
                "SCHEMA", "PAYLOAD_BOUNDARY", "PROJECTION_FINGERPRINT",
                "SCOPE_EXACT_REFERENCE_CLOSURE", "STABLE_ORDER_UNIQUENESS", "CASE_CLOSURE",
                "IMPACT_GRAPH_CLOSURE", "SUMMARY_CARDINALITY", "READINESS_SEMANTICS");
    }

    @Test
    void acceptsBlockedProjectionWhenFreshnessEvidenceIsUnverified() {
        ObjectNode projection = readyProjection();
        ((ObjectNode) projection.path("cases").get(0)).put("freshnessStatus", "UNVERIFIED");
        ObjectNode admission = (ObjectNode) projection.path("admission");
        admission.put("status", "BLOCKED");
        ((ObjectNode) projection.path("quality")).put("status", "BLOCKED")
                .put("freshnessStatus", "UNVERIFIED");
        ArrayNode blockers = (ArrayNode) admission.path("blockers");
        blockers.removeAll();
        blockers.addObject().put("code", "FRESHNESS_EVIDENCE_MISSING")
                .put("message", "Freshness evidence is not available");
        refresh(projection);

        assertThat(VERIFIER.verify(projection).verified()).isTrue();
    }

    @Test
    void acceptsProducerBlockerOrderWithoutInventingASortContract() {
        ObjectNode projection = readyProjection();
        ((ObjectNode) projection.path("cases").get(0))
                .put("lifecycle", "DRAFT")
                .put("freshnessStatus", "UNVERIFIED");
        ((ObjectNode) projection.path("impactGraph").path("nodes").get(2))
                .put("status", "DRAFT");
        ObjectNode admission = (ObjectNode) projection.path("admission");
        admission.put("status", "BLOCKED")
                .put("activeCaseCount", 0)
                .put("draftCaseCount", 1);
        ArrayNode blockers = (ArrayNode) admission.path("blockers");
        blockers.addObject().put("code", "NO_ACTIVE_CASES").put("message", "No active cases");
        blockers.addObject().put("code", "FRESHNESS_EVIDENCE_MISSING")
                .put("message", "Freshness evidence is not available");
        ((ObjectNode) projection.path("quality"))
                .put("status", "BLOCKED")
                .put("freshnessStatus", "UNVERIFIED");
        refresh(projection);

        assertThat(VERIFIER.verify(projection).verified()).isTrue();
    }

    @Test
    void acceptsCanonicalArrowEdgeIdentifiers() {
        ObjectNode projection = readyProjection();
        for (JsonNode edge : projection.path("impactGraph").path("edges")) {
            ObjectNode mutable = (ObjectNode) edge;
            mutable.put("id", mutable.path("source").asText()
                    + "->" + mutable.path("relation").asText()
                    + "->" + mutable.path("target").asText());
        }
        refresh(projection);

        assertThat(VERIFIER.verify(projection).verified()).isTrue();
    }

    @Test
    void rejectsUnknownFieldsAndPayloadBearingFieldNames() {
        ObjectNode unknown = readyProjection();
        unknown.put("unexpected", "value");
        assertSchemaFailure(unknown,
                "RG.CAPABILITY_STUDIO.SCENARIO_QUALITY_IMPACT_SCHEMA_INVALID");

        ObjectNode payload = readyProjection();
        ((ObjectNode) payload.path("cases").get(0).path("source"))
                .put("requestBody", "secret");
        assertSchemaFailure(payload,
                "RG.CAPABILITY_STUDIO.SCENARIO_QUALITY_IMPACT_SCHEMA_INVALID");

        ObjectNode allowedMetadata = readyProjection();
        assertThat(VERIFIER.verify(allowedMetadata).verified()).isTrue();
    }

    @Test
    void rejectsProjectionFingerprintTampering() {
        ObjectNode projection = readyProjection();
        projection.put("projectionFingerprint", fingerprint('f'));

        assertSemanticFailure(projection, "PROJECTION_FINGERPRINT",
                "RG.CAPABILITY_STUDIO.SCENARIO_QUALITY_IMPACT_PROJECTION_FINGERPRINT_MISMATCH");
    }

    @Test
    void rejectsCrossScopeButAcceptsDistinctCompleteAuthorities() {
        ObjectNode scope = readyProjection();
        ((ObjectNode) scope.path("impactGraph").path("nodes").get(5)
                .path("ref").path("scope")).put("region", "eu-west-1");
        refresh(scope);
        assertSemanticFailure(scope, "SCOPE_EXACT_REFERENCE_CLOSURE",
                "RG.CAPABILITY_STUDIO.SCENARIO_QUALITY_IMPACT_CROSS_SCOPE_REFERENCE");

        ObjectNode authority = readyProjection();
        assertThat(authority.path("datasetRef").path("authority").asText())
                .isEqualTo("capability-studio-stage0");
        assertThat(authority.path("cases").get(0).path("caseRef")
                .path("authority").asText()).isEqualTo("capability-studio-demo-pack");
        assertThat(VERIFIER.verify(authority).verified()).isTrue();
    }

    @Test
    void rejectsLegacyUnprefixedNodeIdentifiers() {
        ObjectNode projection = readyProjection();
        Map<String, String> replacements = new HashMap<>();
        for (JsonNode node : projection.path("impactGraph").path("nodes")) {
            ObjectNode mutable = (ObjectNode) node;
            String prefixed = mutable.path("id").asText();
            String legacy = mutable.path("ref").path("id").asText();
            replacements.put(prefixed, legacy);
            mutable.put("id", legacy);
        }
        for (JsonNode edge : projection.path("impactGraph").path("edges")) {
            ObjectNode mutable = (ObjectNode) edge;
            mutable.put("source", replacements.get(mutable.path("source").asText()));
            mutable.put("target", replacements.get(mutable.path("target").asText()));
        }
        ArrayNode canonical = (ArrayNode) projection.path("impactGraph").path("edges");
        ArrayNode legacy = JSON.createArrayNode();
        legacy.add(canonical.get(1));
        legacy.add(canonical.get(2));
        legacy.add(canonical.get(3));
        legacy.add(canonical.get(4));
        legacy.add(canonical.get(5));
        legacy.add(canonical.get(0));
        ((ObjectNode) projection.path("impactGraph")).set("edges", legacy);
        refresh(projection);

        assertSemanticFailure(projection, "IMPACT_GRAPH_CLOSURE",
                "RG.CAPABILITY_STUDIO.SCENARIO_QUALITY_IMPACT_NODE_ID_INVALID");
    }

    @Test
    void rejectsLegacyEdgeIdOnlySort() {
        ObjectNode projection = readyProjection();
        ArrayNode canonical = (ArrayNode) projection.path("impactGraph").path("edges");
        ArrayNode legacy = JSON.createArrayNode();
        legacy.add(canonical.get(1));
        legacy.add(canonical.get(2));
        legacy.add(canonical.get(3));
        legacy.add(canonical.get(4));
        legacy.add(canonical.get(5));
        legacy.add(canonical.get(0));
        ((ObjectNode) projection.path("impactGraph")).set("edges", legacy);
        refresh(projection);

        assertSemanticFailure(projection, "STABLE_ORDER_UNIQUENESS",
                "RG.CAPABILITY_STUDIO.SCENARIO_QUALITY_IMPACT_EDGE_ORDER_INVALID");
    }

    @Test
    void rejectsLegacyDependencyAndTargetRefKinds() {
        ObjectNode dependency = readyProjection();
        ((ObjectNode) dependency.path("cases").get(0).path("dependencyRefs").get(0))
                .put("kind", "DEPENDENCY");
        assertSchemaFailure(dependency,
                "RG.CAPABILITY_STUDIO.SCENARIO_QUALITY_IMPACT_SCHEMA_INVALID");

        ObjectNode target = readyProjection();
        ((ObjectNode) target.path("impactGraph").path("nodes").get(6).path("ref"))
                .put("kind", "TARGET");
        refresh(target);
        assertSemanticFailure(target, "IMPACT_GRAPH_CLOSURE",
                "RG.CAPABILITY_STUDIO.SCENARIO_QUALITY_IMPACT_NODE_REF_KIND_INVALID");

        ObjectNode rootTarget = readyProjection();
        ((ObjectNode) rootTarget.path("targetRef")).put("kind", "API");
        assertSchemaFailure(rootTarget,
                "RG.CAPABILITY_STUDIO.SCENARIO_QUALITY_IMPACT_SCHEMA_INVALID");
    }

    @Test
    void rejectsGraphThatDoesNotMatchTheDeclaredRootTarget() {
        ObjectNode projection = readyProjection();
        ((ObjectNode) projection.path("targetRef")).put("id", "target-other");
        refresh(projection);

        assertSemanticFailure(projection, "IMPACT_GRAPH_CLOSURE",
                "RG.CAPABILITY_STUDIO.SCENARIO_QUALITY_IMPACT_TARGET_NODE_INVALID");
    }

    @Test
    void rejectsLegacyTargetOnlyImpactCounts() {
        ObjectNode projection = readyProjection();
        ((ObjectNode) projection.path("summary")).put("impactedAssetCount", 1);
        ((ObjectNode) projection.path("cases").get(0)).put("impactedAssetCount", 1);
        refresh(projection);

        assertSemanticFailure(projection, "SUMMARY_CARDINALITY",
                "RG.CAPABILITY_STUDIO.SCENARIO_QUALITY_IMPACT_SUMMARY_MISMATCH");
    }

    @Test
    void rejectsInventedNodesAndStatusDrift() {
        ObjectNode invented = readyProjection();
        ArrayNode inventedNodes = (ArrayNode) invented.path("impactGraph").path("nodes");
        inventedNodes.insert(1, node(
                "CONTRACT:contract-invented",
                "CONTRACT",
                "Invented contract",
                exactRef("CONTRACT", "contract-invented", '8', "capability-studio-demo-pack"),
                "DRAFT"));
        ((ObjectNode) invented.path("summary")).put("contractCount", 2);
        refresh(invented);
        assertSemanticFailure(invented, "IMPACT_GRAPH_CLOSURE",
                "RG.CAPABILITY_STUDIO.SCENARIO_QUALITY_IMPACT_UNDECLARED_NODE");

        ObjectNode status = readyProjection();
        ((ObjectNode) status.path("impactGraph").path("nodes").get(5))
                .put("status", "DRAFT");
        refresh(status);
        assertSemanticFailure(status, "IMPACT_GRAPH_CLOSURE",
                "RG.CAPABILITY_STUDIO.SCENARIO_QUALITY_IMPACT_NODE_STATUS_INVALID");
    }

    @Test
    void rejectsGraphRefsThatDoNotExactlyMatchCaseDeclarations() {
        ObjectNode projection = readyProjection();
        ((ObjectNode) projection.path("impactGraph").path("nodes").get(0)
                .path("ref")).put("authority", "different-authority");
        refresh(projection);

        assertSemanticFailure(projection, "IMPACT_GRAPH_CLOSURE",
                "RG.CAPABILITY_STUDIO.SCENARIO_QUALITY_IMPACT_UNDECLARED_NODE");
    }

    @Test
    void rejectsUnstableSortAndDuplicateGraphFacts() {
        ObjectNode nodes = readyProjection();
        ArrayNode nodeArray = (ArrayNode) nodes.path("impactGraph").path("nodes");
        JsonNode first = nodeArray.get(0);
        nodeArray.set(0, nodeArray.get(1));
        nodeArray.set(1, first);
        refresh(nodes);
        assertSemanticFailure(nodes, "STABLE_ORDER_UNIQUENESS",
                "RG.CAPABILITY_STUDIO.SCENARIO_QUALITY_IMPACT_NODE_ORDER_INVALID");

        ObjectNode duplicateRef = readyProjection();
        ObjectNode duplicate = ((ObjectNode) duplicateRef.path("impactGraph")
                .path("nodes").get(1)).deepCopy();
        duplicate.put("id", "contract-copy");
        ((ArrayNode) duplicateRef.path("impactGraph").path("nodes")).add(duplicate);
        refresh(duplicateRef);
        assertSemanticFailure(duplicateRef, "STABLE_ORDER_UNIQUENESS",
                "RG.CAPABILITY_STUDIO.SCENARIO_QUALITY_IMPACT_NODE_ORDER_INVALID");
    }

    @Test
    void rejectsDanglingAndInvalidGraphEdges() {
        ObjectNode dangling = readyProjection();
        ((ObjectNode) dangling.path("impactGraph").path("edges").get(0))
                .put("target", "missing-node");
        refresh(dangling);
        assertSemanticFailure(dangling, "IMPACT_GRAPH_CLOSURE",
                "RG.CAPABILITY_STUDIO.SCENARIO_QUALITY_IMPACT_EDGE_CLOSURE_INVALID");

        ObjectNode relation = readyProjection();
        ((ObjectNode) relation.path("impactGraph").path("edges").get(0))
                .put("relation", "CONTROLS");
        refresh(relation);
        assertSemanticFailure(relation, "IMPACT_GRAPH_CLOSURE",
                "RG.CAPABILITY_STUDIO.SCENARIO_QUALITY_IMPACT_EDGE_RELATION_INVALID");
    }

    @Test
    void rejectsSummaryAndPerCaseImpactCardinalityTampering() {
        ObjectNode summary = readyProjection();
        ((ObjectNode) summary.path("summary")).put("impactedAssetCount", 2);
        refresh(summary);
        assertSemanticFailure(summary, "SUMMARY_CARDINALITY",
                "RG.CAPABILITY_STUDIO.SCENARIO_QUALITY_IMPACT_SUMMARY_MISMATCH");

        ObjectNode caseCount = readyProjection();
        ((ObjectNode) caseCount.path("cases").get(0)).put("impactedAssetCount", 2);
        refresh(caseCount);
        assertSemanticFailure(caseCount, "SUMMARY_CARDINALITY",
                "RG.CAPABILITY_STUDIO.SCENARIO_QUALITY_IMPACT_CASE_IMPACT_COUNT_MISMATCH");
    }

    @Test
    void rejectsMissingCaseClosureAndRuntimeDependencyEdge() {
        ObjectNode owner = readyProjection();
        ((ObjectNode) owner.path("cases").get(0)).putNull("owner");
        refresh(owner);
        assertSemanticFailure(owner, "CASE_CLOSURE",
                "RG.CAPABILITY_STUDIO.SCENARIO_QUALITY_IMPACT_CASE_OWNER_MISSING");

        ObjectNode dependency = readyProjection();
        ArrayNode dependencyEdges = (ArrayNode) dependency.path("impactGraph").path("edges");
        for (int index = dependencyEdges.size() - 1; index >= 0; index--) {
            if ("CONTROLS".equals(dependencyEdges.get(index).path("relation").asText())) {
                dependencyEdges.remove(index);
            }
        }
        refresh(dependency);
        assertSemanticFailure(dependency, "IMPACT_GRAPH_CLOSURE",
                "RG.CAPABILITY_STUDIO.SCENARIO_QUALITY_IMPACT_RUNTIME_CLOSURE_INVALID");
    }

    @Test
    void rejectsReadinessContradictionsAndMissingFreshnessBlocker() {
        ObjectNode readyWithBlocker = readyProjection();
        ((ArrayNode) readyWithBlocker.path("admission").path("blockers"))
                .addObject().put("code", "MANUAL_BLOCK").put("message", "blocked");
        refresh(readyWithBlocker);
        assertSemanticFailure(readyWithBlocker, "READINESS_SEMANTICS",
                "RG.CAPABILITY_STUDIO.SCENARIO_QUALITY_IMPACT_READY_CONTRADICTION");

        ObjectNode noFreshnessEvidence = readyProjection();
        ((ObjectNode) noFreshnessEvidence.path("cases").get(0))
                .put("freshnessStatus", "UNVERIFIED");
        ((ObjectNode) noFreshnessEvidence.path("admission")).put("status", "BLOCKED");
        ((ObjectNode) noFreshnessEvidence.path("quality")).put("status", "BLOCKED")
                .put("freshnessStatus", "UNVERIFIED");
        refresh(noFreshnessEvidence);
        assertSemanticFailure(noFreshnessEvidence, "READINESS_SEMANTICS",
                "RG.CAPABILITY_STUDIO.SCENARIO_QUALITY_IMPACT_FRESHNESS_EVIDENCE_MISSING");
    }

    @Test
    void rejectsInvalidWireSizeJsonAndTrailingTokens() throws Exception {
        assertThat(VERIFIER.verify(new byte[
                CapabilityStudioScenarioQualityImpactVerifier.MAXIMUM_PROJECTION_BYTES + 1])
                .errorCode()).isEqualTo(
                "RG.CAPABILITY_STUDIO.SCENARIO_QUALITY_IMPACT_SIZE_LIMIT");
        assertThat(VERIFIER.verify("not-json".getBytes()).errorCode()).isEqualTo(
                "RG.CAPABILITY_STUDIO.SCENARIO_QUALITY_IMPACT_INVALID_JSON");
        byte[] trailing = (JSON.writeValueAsString(readyProjection()) + " {}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(VERIFIER.verify(trailing).errorCode()).isEqualTo(
                "RG.CAPABILITY_STUDIO.SCENARIO_QUALITY_IMPACT_INVALID_JSON");
    }

    private static ObjectNode readyProjection() {
        ObjectNode root = JSON.createObjectNode()
                .put("schemaVersion", CapabilityStudioScenarioQualityImpactVerifier.SCHEMA_VERSION)
                .put("projectionFingerprint", "");
        ObjectNode datasetRef = exactRef(
                "DATASET", "dataset-cancellation", 'a', "capability-studio-stage0");
        root.set("datasetRef", datasetRef);
        ObjectNode targetRef = exactRef(
                "TOOL", "target-cancellation-tool", '7', "capability-studio-demo-pack");
        root.set("targetRef", targetRef);
        root.set("admission", JSON.createObjectNode()
                .put("status", "READY")
                .put("activeCaseCount", 1)
                .put("draftCaseCount", 0)
                .put("staleCaseCount", 0)
                .set("blockers", JSON.createArrayNode()));
        root.set("quality", JSON.createObjectNode()
                .put("status", "READY")
                .put("ownerCoveragePercent", 100)
                .put("sourceCoveragePercent", 100)
                .put("oracleCoveragePercent", 100)
                .put("contractCoveragePercent", 100)
                .put("behaviorClosurePercent", 100)
                .put("freshnessStatus", "CURRENT")
                .put("payloadExposure", "NONE")
                .put("maskingStatus", "PAYLOAD_NOT_EXPORTED"));
        root.set("summary", JSON.createObjectNode()
                .put("caseCount", 1)
                .put("sourceCount", 1)
                .put("oracleCount", 1)
                .put("contractCount", 1)
                .put("dependencyCount", 1)
                .put("targetCount", 1)
                .put("impactedAssetCount", 3)
                .put("orphanCaseCount", 0));

        ObjectNode dataCase = JSON.createObjectNode();
        dataCase.set("caseRef", exactRef(
                "DATA_CASE", "case-cancellation", 'b', "capability-studio-demo-pack"));
        dataCase.put("name", "Cancellation fee dispute")
                .put("lifecycle", "ACTIVE")
                .put("qualityState", "READY");
        dataCase.set("owner", JSON.createObjectNode().put("id", "team-customer-care").put("name", "Customer Care"));
        dataCase.set("sourceRef", exactRef(
                "SOURCE", "source-customer-case", 'c', "capability-studio-demo-pack"));
        dataCase.set("source", JSON.createObjectNode().put("displayName", "Customer case corpus").put("type", "CASE_CORPUS"));
        dataCase.set("oracleRef", exactRef(
                "ORACLE", "oracle-cancellation", 'd', "capability-studio-demo-pack"));
        dataCase.set("oracle", JSON.createObjectNode().put("displayName", "Cancellation oracle").put("summary", "Matches the approved business outcome"));
        dataCase.set("contractRefs", JSON.createArrayNode().add(exactRef(
                "CONTRACT", "contract-cancellation", 'e', "capability-studio-demo-pack")));
        dataCase.set("dependencyRefs", JSON.createArrayNode().add(exactRef(
                "API", "dependency-cancellation", 'f', "capability-studio-demo-pack")));
        dataCase.put("freshnessStatus", "CURRENT")
                .put("maskingStatus", "PAYLOAD_NOT_EXPORTED")
                .put("impactedAssetCount", 3);
        root.set("cases", JSON.createArrayNode().add(dataCase));

        ArrayNode nodes = JSON.createArrayNode();
        nodes.add(node("CONTRACT:contract-cancellation", "CONTRACT", "Cancellation contract", dataCase.path("contractRefs").get(0), "DRAFT"));
        nodes.add(node("DATASET:dataset-cancellation", "DATASET", "Cancellation scenarios", datasetRef, "BLOCKED"));
        nodes.add(node("DATA_CASE:case-cancellation", "DATA_CASE", "Cancellation fee dispute", dataCase.path("caseRef"), "ACTIVE"));
        nodes.add(node("DEPENDENCY:dependency-cancellation", "DEPENDENCY", "Cancellation lookup", dataCase.path("dependencyRefs").get(0), "DRAFT"));
        nodes.add(node("ORACLE:oracle-cancellation", "ORACLE", "Cancellation oracle", dataCase.path("oracleRef"), "DRAFT"));
        nodes.add(node("SOURCE:source-customer-case", "SOURCE", "Customer case corpus", dataCase.path("sourceRef"), "BLOCKED"));
        nodes.add(node("TARGET:target-cancellation-tool", "TARGET", "Cancellation fee tool", targetRef, "DRAFT"));
        ArrayNode edges = JSON.createArrayNode();
        edges.add(edge("e-dataset-case", "DATASET:dataset-cancellation", "DATA_CASE:case-cancellation", "CONTAINS"));
        edges.add(edge("e-case-contract", "DATA_CASE:case-cancellation", "CONTRACT:contract-cancellation", "VALIDATES"));
        edges.add(edge("e-case-dependency", "DATA_CASE:case-cancellation", "DEPENDENCY:dependency-cancellation", "CONTROLS"));
        edges.add(edge("e-case-oracle", "DATA_CASE:case-cancellation", "ORACLE:oracle-cancellation", "CHECKED_BY"));
        edges.add(edge("e-case-source", "DATA_CASE:case-cancellation", "SOURCE:source-customer-case", "SOURCED_BY"));
        edges.add(edge("e-case-target", "DATA_CASE:case-cancellation", "TARGET:target-cancellation-tool", "VALIDATES_TARGET"));
        ObjectNode impactGraph = JSON.createObjectNode();
        impactGraph.set("nodes", nodes);
        impactGraph.set("edges", edges);
        root.set("impactGraph", impactGraph);
        refresh(root);
        return root;
    }

    private static ObjectNode node(String id, String kind, String label, JsonNode ref, String status) {
        ObjectNode node = JSON.createObjectNode().put("id", id).put("kind", kind).put("label", label);
        node.set("ref", ref.deepCopy());
        node.put("status", status);
        return node;
    }

    private static ObjectNode edge(String id, String source, String target, String relation) {
        return JSON.createObjectNode().put("id", id).put("source", source)
                .put("target", target).put("relation", relation);
    }

    private static ObjectNode exactRef(String kind, String id, char fill) {
        return exactRef(kind, id, fill, "capability-studio-demo-pack");
    }

    private static ObjectNode exactRef(
            String kind,
            String id,
            char fill,
            String authority) {
        return JSON.createObjectNode()
                .put("kind", kind)
                .put("id", id)
                .put("revision", 1)
                .put("fingerprint", fingerprint(fill))
                .put("authority", authority)
                .set("scope", JSON.createObjectNode()
                        .put("tenantId", "tenant-demo")
                        .put("organizationId", "org-demo")
                        .put("projectId", "project-demo")
                        .put("environmentId", "test")
                        .put("region", "ap-southeast-1"));
    }

    private static String fingerprint(char fill) {
        return "sha256:" + String.valueOf(fill).repeat(64);
    }

    private static void refresh(ObjectNode projection) {
        ObjectNode material = projection.deepCopy();
        material.putNull("projectionFingerprint");
        projection.put("projectionFingerprint",
                EvidenceVerificationSupport.sha256Bounded(material,
                        CapabilityStudioScenarioQualityImpactVerifier.MAXIMUM_PROJECTION_BYTES));
    }

    private static void assertSchemaFailure(ObjectNode projection, String code) {
        CapabilityStudioScenarioQualityImpactVerifier.VerificationResult result =
                VERIFIER.verify(projection);
        assertThat(result.failureKind()).isEqualTo(
                CapabilityStudioScenarioQualityImpactVerifier.FailureKind.SCHEMA);
        assertThat(result.errorCode()).isEqualTo(code);
    }

    private static void assertSemanticFailure(
            ObjectNode projection,
            String check,
            String code) {
        CapabilityStudioScenarioQualityImpactVerifier.VerificationResult result =
                VERIFIER.verify(projection);
        assertThat(result.failureKind()).isEqualTo(
                CapabilityStudioScenarioQualityImpactVerifier.FailureKind.SEMANTIC);
        assertThat(result.checks()).contains(check);
        assertThat(result.errorCode()).isEqualTo(code);
    }
}
