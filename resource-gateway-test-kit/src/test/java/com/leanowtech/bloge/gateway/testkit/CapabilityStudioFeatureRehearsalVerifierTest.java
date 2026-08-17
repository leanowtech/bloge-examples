package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CapabilityStudioFeatureRehearsalVerifierTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final CapabilityStudioFeatureRehearsalVerifier VERIFIER =
            new CapabilityStudioFeatureRehearsalVerifier();

    @Test
    void verifiesStructureOnlyProjectionAndRecomputesDataLensFingerprint() {
        CapabilityStudioFeatureRehearsalVerifier.VerificationResult result =
                VERIFIER.verify(projection(false));

        assertThat(result.verified()).isTrue();
        assertThat(result.checks()).contains(
                "CANONICAL_CARDINALITY",
                "PERMISSION_BOUNDARY",
                "PAYLOAD_FINGERPRINTS",
                "DATA_LENS_FINGERPRINT");
    }

    @Test
    void acceptsOpaqueSourceFingerprintsWhenStructureOnlyPayloadsAreRedacted() {
        ObjectNode projection = projection(false);
        ObjectNode node = (ObjectNode) child(projection, "dataLens").withArray("nodes").get(0);
        node.put("inputFingerprint", fingerprint('c'));
        node.put("outputFingerprint", fingerprint('d'));
        ObjectNode attempt = (ObjectNode) node.withArray("attempts").get(0);
        attempt.put("inputFingerprint", fingerprint('c'));
        attempt.put("outputFingerprint", fingerprint('d'));
        ObjectNode edge = (ObjectNode) child(projection, "dataLens").withArray("edges").get(0);
        edge.put("valueFingerprint", fingerprint('e'));
        refreshDataLensFingerprint(projection);

        CapabilityStudioFeatureRehearsalVerifier.VerificationResult result =
                VERIFIER.verify(projection);

        assertThat(result.verified()).isTrue();
    }

    @Test
    void verifiesPayloadVisibleProjectionAndPayloadFingerprints() {
        ObjectNode projection = projection(true);

        CapabilityStudioFeatureRehearsalVerifier.VerificationResult result =
                VERIFIER.verify(projection);

        assertThat(result.verified()).isTrue();
        assertThat(projection.path("dataLens").path("nodes").get(0).path("input").path("orderId")
                .asText()).isEqualTo("DEMO-ORDER-20260818-001");
    }

    @Test
    void rejectsUnknownWireFieldsAtSchemaBoundary() {
        ObjectNode projection = projection(false);
        projection.put("unexpected", true);

        CapabilityStudioFeatureRehearsalVerifier.VerificationResult result =
                VERIFIER.verify(projection);

        assertThat(result.failureKind())
                .isEqualTo(CapabilityStudioFeatureRehearsalVerifier.FailureKind.SCHEMA);
        assertThat(result.errorCode())
                .isEqualTo("RG.CAPABILITY_STUDIO.FEATURE_REHEARSAL_SCHEMA_INVALID");
    }

    @Test
    void rejectsRunAndDataLensMismatch() {
        ObjectNode projection = projection(false);
        child(projection, "dataLens").put("runId", "run-other");

        CapabilityStudioFeatureRehearsalVerifier.VerificationResult result =
                VERIFIER.verify(projection);

        assertThat(result.failureKind())
                .isEqualTo(CapabilityStudioFeatureRehearsalVerifier.FailureKind.SEMANTIC);
        assertThat(result.errorCode())
                .isEqualTo("RG.CAPABILITY_STUDIO.FEATURE_REHEARSAL_RUN_ID_MISMATCH");
    }

    @Test
    void rejectsDanglingEdgeInvocationSite() {
        ObjectNode projection = projection(false);
        ((ObjectNode) child(projection, "dataLens").withArray("edges").get(0))
                .put("toInvocationSite", "/root/unknown#RESOURCE");

        CapabilityStudioFeatureRehearsalVerifier.VerificationResult result =
                VERIFIER.verify(projection);

        assertThat(result.failureKind())
                .isEqualTo(CapabilityStudioFeatureRehearsalVerifier.FailureKind.SEMANTIC);
        assertThat(result.errorCode())
                .isEqualTo("RG.CAPABILITY_STUDIO.FEATURE_REHEARSAL_DANGLING_EDGE");
    }

    @Test
    void rejectsStructureOnlyPayloadLeakage() {
        ObjectNode projection = projection(false);
        ObjectNode input = JSON.createObjectNode().put("secret", "business-payload");
        ObjectNode node = (ObjectNode) child(projection, "dataLens").withArray("nodes").get(0);
        node.set("input", input);
        node.put("inputFingerprint", EvidenceVerificationSupport.sha256(input));

        CapabilityStudioFeatureRehearsalVerifier.VerificationResult result =
                VERIFIER.verify(projection);

        assertThat(result.failureKind())
                .isEqualTo(CapabilityStudioFeatureRehearsalVerifier.FailureKind.SEMANTIC);
        assertThat(result.errorCode())
                .isEqualTo("RG.CAPABILITY_STUDIO.FEATURE_REHEARSAL_PAYLOAD_LEAK");
        assertThat(result.toString()).doesNotContain("business-payload");
    }

    @Test
    void rejectsNonZeroRealExternalCallCount() {
        ObjectNode projection = projection(false);
        child(projection, "run").put("realExternalCallCount", 1);

        CapabilityStudioFeatureRehearsalVerifier.VerificationResult result =
                VERIFIER.verify(projection);

        assertThat(result.failureKind())
                .isEqualTo(CapabilityStudioFeatureRehearsalVerifier.FailureKind.SEMANTIC);
        assertThat(result.errorCode())
                .isEqualTo("RG.CAPABILITY_STUDIO.FEATURE_REHEARSAL_REAL_EXTERNAL_CALLS");
    }

    @Test
    void rejectsTamperedDataLensFingerprint() {
        ObjectNode projection = projection(false);
        child(projection, "dataLens").put("fingerprint", "sha256:" + "b".repeat(64));

        CapabilityStudioFeatureRehearsalVerifier.VerificationResult result =
                VERIFIER.verify(projection);

        assertThat(result.failureKind())
                .isEqualTo(CapabilityStudioFeatureRehearsalVerifier.FailureKind.SEMANTIC);
        assertThat(result.errorCode()).isEqualTo(
                "RG.CAPABILITY_STUDIO.FEATURE_REHEARSAL_DATA_LENS_FINGERPRINT_MISMATCH");
    }

    @Test
    void rejectsTamperedPayloadFingerprintWhenPayloadIsVisible() {
        ObjectNode projection = projection(true);
        ObjectNode node = (ObjectNode) child(projection, "dataLens").withArray("nodes").get(0);
        node.put("inputFingerprint", fingerprint('b'));
        refreshDataLensFingerprint(projection);

        CapabilityStudioFeatureRehearsalVerifier.VerificationResult result =
                VERIFIER.verify(projection);

        assertThat(result.failureKind())
                .isEqualTo(CapabilityStudioFeatureRehearsalVerifier.FailureKind.SEMANTIC);
        assertThat(result.errorCode()).isEqualTo(
                "RG.CAPABILITY_STUDIO.FEATURE_REHEARSAL_PAYLOAD_FINGERPRINT_MISMATCH");
    }

    @Test
    void rejectsNonCanonicalNodeAndEdgeCounts() {
        ObjectNode projection = projection(false);
        child(projection, "dataLens").withArray("edges").remove(0);

        CapabilityStudioFeatureRehearsalVerifier.VerificationResult result =
                VERIFIER.verify(projection);

        assertThat(result.failureKind())
                .isEqualTo(CapabilityStudioFeatureRehearsalVerifier.FailureKind.SEMANTIC);
        assertThat(result.errorCode()).isEqualTo(
                "RG.CAPABILITY_STUDIO.FEATURE_REHEARSAL_CANONICAL_CARDINALITY_MISMATCH");
    }

    private static ObjectNode projection(boolean payloadVisible) {
        ObjectNode root = JSON.createObjectNode();
        root.put("schemaVersion", "resource-gateway.capability-studio.feature-rehearsal.v1");
        root.set("scenario", JSON.createObjectNode()
                .put("id", "case-standard-cancellation-fee")
                .put("name", "标准取消费")
                .put("expectedResult", "输出自动报价"));
        root.set("graph", JSON.createObjectNode()
                .put("id", "feature-cancellation-dispute-context")
                .put("fingerprint", fingerprint('a')));
        root.set("run", JSON.createObjectNode()
                .put("runId", "run-feature-1")
                .put("status", "PASSED")
                .put("semanticFingerprint", fingerprint('a'))
                .put("realExternalCallCount", 0)
                .put("bindingMode", "FIXTURE_CONTROLLED_NON_PRODUCTION"));
        ObjectNode lens = JSON.createObjectNode()
                .put("schemaVersion", "resource-gateway.capability-studio.data-lens.v1")
                .put("runId", "run-feature-1")
                .put("runStatus", "PASSED")
                .put("permissionMode", payloadVisible ? "PAYLOAD_VISIBLE" : "STRUCTURE_ONLY");
        var nodes = lens.putArray("nodes");
        for (int index = 0; index < 6; index++) {
            nodes.add(node(index, payloadVisible));
        }
        var edges = lens.putArray("edges");
        for (int index = 0; index < 5; index++) {
            edges.add(edge(index, payloadVisible));
        }
        lens.putNull("firstDifference");
        lens.set("truncation", JSON.createObjectNode()
                .put("nodesTruncated", false)
                .put("omittedNodes", 0)
                .put("edgesTruncated", false)
                .put("omittedEdges", 0)
                .put("attemptsTruncated", false)
                .put("omittedAttempts", 0));
        lens.put("fingerprint", "");
        root.set("dataLens", lens);
        lens.put("fingerprint", dataLensFingerprint(lens));
        return root;
    }

    private static ObjectNode node(int index, boolean payloadVisible) {
        ObjectNode node = JSON.createObjectNode()
                .put("nodeId", "node-" + index)
                .put("operatorRef", index < 4 ? "httpResource" : "capabilityStudio.compute")
                .put("status", "SUCCESS")
                .put("fidelity", "FIXTURE")
                .put("graphPath", "/root")
                .put("invocationSite", "/root/node-" + index + "#PRIMARY")
                .put("correlation", "correlation-" + index)
                .put("occurrence", 0)
                .put("graphOccurrence", 0)
                .put("errorCode", "")
                .put("durationMs", 1)
                .put("retryCount", 0)
                .putNull("fallbackStatus");
        JsonNode input = payloadVisible
                ? JSON.createObjectNode().put("orderId", "DEMO-ORDER-20260818-001")
                : JSON.nullNode();
        JsonNode output = payloadVisible
                ? JSON.createObjectNode().put("status", "OK")
                : JSON.nullNode();
        node.set("input", input);
        node.put("inputFingerprint", fingerprintFor(input));
        node.set("output", output);
        node.put("outputFingerprint", fingerprintFor(output));
        ObjectNode attempt = JSON.createObjectNode()
                .put("attempt", 0)
                .put("status", "SUCCESS")
                .put("fidelity", "FIXTURE")
                .set("input", input);
        attempt.put("inputFingerprint", fingerprintFor(input));
        attempt.set("output", output);
        attempt.put("outputFingerprint", fingerprintFor(output));
        attempt.put("errorCode", "");
        attempt.put("durationMs", 1);
        node.putArray("attempts").add(attempt);
        return node;
    }

    private static ObjectNode edge(int index, boolean payloadVisible) {
        ObjectNode edge = JSON.createObjectNode()
                .put("edgeId", "edge-" + index)
                .put("status", "TRANSFERRED")
                .put("graphPath", "/root")
                .put("correlation", "correlation-" + index)
                .put("graphOccurrence", 0)
                .put("fromInvocationSite", "/root/node-" + index + "#PRIMARY")
                .put("toInvocationSite", "/root/node-" + (index + 1) + "#PRIMARY");
        JsonNode value = payloadVisible
                ? JSON.createObjectNode().put("edge", index)
                : JSON.nullNode();
        edge.set("value", value);
        edge.put("valueFingerprint", fingerprintFor(value));
        return edge;
    }

    private static String dataLensFingerprint(ObjectNode lens) {
        ObjectNode material = JSON.createObjectNode();
        material.set("runId", lens.get("runId"));
        material.set("runStatus", lens.get("runStatus"));
        material.set("permissionMode", lens.get("permissionMode"));
        material.set("nodes", lens.get("nodes"));
        material.set("edges", lens.get("edges"));
        material.set("firstDifference", lens.get("firstDifference"));
        material.set("truncation", lens.get("truncation"));
        return EvidenceVerificationSupport.sha256(material);
    }

    private static void refreshDataLensFingerprint(ObjectNode projection) {
        ObjectNode lens = child(projection, "dataLens");
        lens.put("fingerprint", dataLensFingerprint(lens));
    }

    private static String fingerprintFor(JsonNode value) {
        return value == null || value.isNull() ? "" : EvidenceVerificationSupport.sha256(value);
    }

    private static ObjectNode child(ObjectNode root, String field) {
        return (ObjectNode) root.get(field);
    }

    private static String fingerprint(char fill) {
        return "sha256:" + String.valueOf(fill).repeat(64);
    }
}
