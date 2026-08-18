package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CapabilityStudioGovernedRunEvidenceVerifierTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final CapabilityStudioGovernedRunEvidenceVerifier VERIFIER =
            new CapabilityStudioGovernedRunEvidenceVerifier();

    @Test
    void acceptsStrictPayloadFreeProjectionAndRecomputesBothFingerprints() throws Exception {
        ObjectNode projection = projection();

        CapabilityStudioGovernedRunEvidenceVerifier.VerificationResult result =
                VERIFIER.verify(JSON.writeValueAsBytes(projection));

        assertThat(result.verified()).isTrue();
        assertThat(result.checks()).contains(
                "STRUCTURE_ONLY_PAYLOAD_BOUNDARY",
                "BINDING_REFERENCE_CLOSURE",
                "BINDING_PLAN_FINGERPRINT",
                "PROJECTION_FINGERPRINT");
    }

    @Test
    void rejectsUnknownFieldsAndPayloadLeaksAtTheStrictSchemaBoundary() {
        ObjectNode unknown = projection();
        unknown.put("unknown", "must-fail");
        assertThat(VERIFIER.verify(bytes(unknown)).failureKind())
                .isEqualTo(CapabilityStudioGovernedRunEvidenceVerifier.FailureKind.SCHEMA);

        ObjectNode payload = projection();
        ((ObjectNode) payload.path("dataLens").path("nodes").get(0))
                .set("input", JSON.createObjectNode().put("secret", "payload"));
        assertThat(VERIFIER.verify(bytes(payload)).failureKind())
                .isEqualTo(CapabilityStudioGovernedRunEvidenceVerifier.FailureKind.SCHEMA);
    }

    @Test
    void rejectsRunLensDriftAndCaseDrift() {
        ObjectNode runDrift = projection();
        ((ObjectNode) runDrift.path("run")).put("runId", "run-other");
        assertThat(VERIFIER.verify(bytes(runDrift)).errorCode())
                .isEqualTo("RG.CAPABILITY_STUDIO.GOVERNED_RUN_EVIDENCE_RUN_LENS_DRIFT");

        ObjectNode caseDrift = projection();
        ((ObjectNode) caseDrift.path("scenario")).put("caseId", "case-other");
        assertThat(VERIFIER.verify(bytes(caseDrift)).errorCode())
                .isEqualTo("RG.CAPABILITY_STUDIO.GOVERNED_RUN_EVIDENCE_CASE_DRIFT");
    }

    @Test
    void rejectsDanglingFocusAndEdge() {
        ObjectNode focusDrift = projection();
        focusDrift.put("focusNodeId", "node-missing");
        assertThat(VERIFIER.verify(bytes(focusDrift)).errorCode())
                .isEqualTo("RG.CAPABILITY_STUDIO.GOVERNED_RUN_EVIDENCE_FOCUS_NODE_MISSING");

        ObjectNode edgeDrift = projection();
        ((ObjectNode) edgeDrift.path("dataLens").path("edges").get(0))
                .put("toInvocationSite", "/root/missing#PRIMARY");
        assertThat(VERIFIER.verify(bytes(edgeDrift)).errorCode())
                .isEqualTo("RG.CAPABILITY_STUDIO.GOVERNED_RUN_EVIDENCE_DANGLING_EDGE");
    }

    @Test
    void rejectsContractAndRuntimeTargetClosureDrift() {
        ObjectNode contract = projection();
        ((ObjectNode) contract.path("scenario").path("applicableContractRefs").get(0))
                .put("id", "contract-other");
        assertThat(VERIFIER.verify(bytes(contract)).errorCode()).isEqualTo(
                "RG.CAPABILITY_STUDIO.GOVERNED_RUN_EVIDENCE_TOP_CONTRACT_NOT_APPLICABLE");

        ObjectNode target = projection();
        ((ObjectNode) target.path("runtimeTarget")).put("id", "tool-other");
        assertThat(VERIFIER.verify(bytes(target)).errorCode()).isEqualTo(
                "RG.CAPABILITY_STUDIO.GOVERNED_RUN_EVIDENCE_RUNTIME_TARGET_DRIFT");

        ObjectNode runtimeFingerprint = projection();
        ((ObjectNode) runtimeFingerprint.path("runtimeTarget"))
                .put("fingerprint", fingerprint('9'));
        assertThat(VERIFIER.verify(bytes(runtimeFingerprint)).errorCode()).isEqualTo(
                "RG.CAPABILITY_STUDIO.GOVERNED_RUN_EVIDENCE_PROJECTION_FINGERPRINT_TAMPERED");
    }

    @Test
    void rejectsRepeatedAndOverlappingBindingReferences() {
        ObjectNode duplicate = projection();
        ObjectNode behavior = (ObjectNode) duplicate.path("bindingPlan").path("behaviorRefs").get(0);
        ((ArrayNode) duplicate.path("bindingPlan").path("behaviorRefs")).add(behavior.deepCopy());
        assertThat(VERIFIER.verify(bytes(duplicate)).errorCode()).isEqualTo(
                "RG.CAPABILITY_STUDIO.GOVERNED_RUN_EVIDENCE_DUPLICATE_BEHAVIOR_REF");

        ObjectNode overlap = projection();
        ObjectNode dependency = (ObjectNode) overlap.path("bindingPlan").path("dependencyRefs").get(0);
        dependency.put("id", behavior.path("id").textValue())
                .put("revision", behavior.path("revision").intValue())
                .put("fingerprint", behavior.path("fingerprint").textValue());
        assertThat(VERIFIER.verify(bytes(overlap)).errorCode()).isEqualTo(
                "RG.CAPABILITY_STUDIO.GOVERNED_RUN_EVIDENCE_BEHAVIOR_DEPENDENCY_OVERLAP");
    }

    @Test
    void rejectsFallbackAndInvalidPassedCounts() {
        ObjectNode fallback = projection();
        ((ObjectNode) fallback.path("bindingPlan")).put("fallbackToReal", true);
        assertThat(VERIFIER.verify(bytes(fallback)).failureKind())
                .isEqualTo(CapabilityStudioGovernedRunEvidenceVerifier.FailureKind.SCHEMA);

        ObjectNode counts = projection();
        ((ObjectNode) counts.path("run")).put("assertionsPassed", 1);
        assertThat(VERIFIER.verify(bytes(counts)).errorCode())
                .isEqualTo(
                        "RG.CAPABILITY_STUDIO.GOVERNED_RUN_EVIDENCE_PASSED_COUNTS_INCOMPLETE");
    }

    @Test
    void rejectsInvalidTruncationAndPayloadBearingErrorCodes() {
        ObjectNode truncation = projection();
        ((ObjectNode) truncation.path("dataLens").path("truncation"))
                .put("nodesTruncated", true).put("omittedNodes", 1);
        assertThat(VERIFIER.verify(bytes(truncation)).errorCode()).isEqualTo(
                "RG.CAPABILITY_STUDIO.GOVERNED_RUN_EVIDENCE_TRUNCATION_INVALID");

        ObjectNode error = projection();
        ((ObjectNode) error.path("dataLens").path("nodes").get(0))
                .put("errorCode", "SECRET payload");
        assertThat(VERIFIER.verify(bytes(error)).failureKind())
                .isEqualTo(CapabilityStudioGovernedRunEvidenceVerifier.FailureKind.SCHEMA);
    }

    @Test
    void acceptsNestedBlogeEdgeTraceIdentifierAndEscapedPathWithRecomputedFingerprints() {
        ObjectNode nested = projection();
        ObjectNode lens = (ObjectNode) nested.path("dataLens");
        ObjectNode node = (ObjectNode) lens.path("nodes").get(0);
        ObjectNode edge = (ObjectNode) lens.path("edges").get(0);
        String escapedGraphPath = "/root/~0subject";
        String invocationSite = escapedGraphPath + "/node-1#PRIMARY";

        node.put("graphPath", escapedGraphPath)
                .put("invocationSite", invocationSite);
        edge.put("edgeId",
                        "/root/subject/feature-cancellation-dispute-context/orderLookup->"
                                + "aggregateCancellationContext")
                .put("graphPath", escapedGraphPath)
                .put("fromInvocationSite", invocationSite)
                .put("toInvocationSite", invocationSite);
        recomputeFingerprints(nested);

        CapabilityStudioGovernedRunEvidenceVerifier.VerificationResult result =
                VERIFIER.verify(bytes(nested));

        assertThat(result.verified()).isTrue();
        assertThat(result.checks()).contains(
                "INVOCATION_SITE_CLOSURE",
                "DATA_LENS_FINGERPRINT",
                "PROJECTION_FINGERPRINT");
    }

    @Test
    void rejectsTraceIdentifiersWithControlsOrMoreThan256CharactersAndKeepsExactRefsStrict() {
        ObjectNode control = projection();
        ((ObjectNode) control.path("dataLens").path("edges").get(0))
                .put("edgeId", "/root/subject" + (char) 1
                        + "/orderLookup->aggregateCancellationContext");
        assertThat(VERIFIER.verify(bytes(control)).failureKind())
                .isEqualTo(CapabilityStudioGovernedRunEvidenceVerifier.FailureKind.SCHEMA);

        ObjectNode oversized = projection();
        ((ObjectNode) oversized.path("dataLens").path("edges").get(0))
                .put("edgeId", "e".repeat(257));
        assertThat(VERIFIER.verify(bytes(oversized)).failureKind())
                .isEqualTo(CapabilityStudioGovernedRunEvidenceVerifier.FailureKind.SCHEMA);

        ObjectNode exactRef = projection();
        ((ObjectNode) exactRef.path("graphRef")).put("id",
                "/root/subject/feature-cancellation-dispute-context/orderLookup->"
                        + "aggregateCancellationContext");
        assertThat(VERIFIER.verify(bytes(exactRef)).failureKind())
                .isEqualTo(CapabilityStudioGovernedRunEvidenceVerifier.FailureKind.SCHEMA);
    }

    @Test
    void rejectsDataLensFingerprintTampering() {
        ObjectNode lens = projection();
        ((ObjectNode) lens.path("dataLens")).put("fingerprint", fingerprint('9'));
        assertThat(VERIFIER.verify(bytes(lens)).errorCode()).isEqualTo(
                "RG.CAPABILITY_STUDIO.GOVERNED_RUN_EVIDENCE_DATA_LENS_FINGERPRINT_TAMPERED");
    }

    @Test
    void rejectsBindingAndProjectionFingerprintTampering() {
        ObjectNode binding = projection();
        ((ObjectNode) binding.path("bindingPlan").path("ref"))
                .put("fingerprint", fingerprint('9'));
        assertThat(VERIFIER.verify(bytes(binding)).errorCode())
                .isEqualTo(
                        "RG.CAPABILITY_STUDIO.GOVERNED_RUN_EVIDENCE_BINDING_FINGERPRINT_TAMPERED");

        ObjectNode top = projection();
        ((ObjectNode) top.path("graphRef")).put("fingerprint", fingerprint('9'));
        assertThat(VERIFIER.verify(bytes(top)).errorCode())
                .isEqualTo(
                        "RG.CAPABILITY_STUDIO.GOVERNED_RUN_EVIDENCE_PROJECTION_FINGERPRINT_TAMPERED");
    }

    @Test
    void rejectsOversizeInvalidJsonAndTrailingTokens() throws Exception {
        assertThat(VERIFIER.verify(new byte[
                CapabilityStudioGovernedRunEvidenceVerifier.MAXIMUM_GOVERNED_RUN_EVIDENCE_BYTES + 1])
                .errorCode()).isEqualTo(
                "RG.CAPABILITY_STUDIO.GOVERNED_RUN_EVIDENCE_SIZE_LIMIT");
        assertThat(VERIFIER.verify("not-json".getBytes()).errorCode()).isEqualTo(
                "RG.CAPABILITY_STUDIO.GOVERNED_RUN_EVIDENCE_INVALID_JSON");
        byte[] trailing = (JSON.writeValueAsString(projection()) + " {}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(VERIFIER.verify(trailing).errorCode()).isEqualTo(
                "RG.CAPABILITY_STUDIO.GOVERNED_RUN_EVIDENCE_INVALID_JSON");
    }

    private static ObjectNode projection() {
        ObjectNode root = JSON.createObjectNode()
                .put("schemaVersion",
                        "resource-gateway.capability-studio.governed-run-evidence.v1")
                .put("verificationStatus", "EXACT_VERIFIED")
                .put("baselineId", "capability-studio-governed-9x3-v1")
                .put("projectionFingerprint", "");
        root.set("scenario", scenario());
        root.set("graphRef", ref("FEATURE", "feature-cancellation-dispute-context", 'a'));
        root.set("capabilityRef", ref("TOOL", "tool-cancellation-fee-dispute-handling", 'b'));
        root.set("contractRef", ref("CONTRACT", "contract-cancellation-fee-dispute-tool", 'c'));
        root.set("datasetRef", ref("DATASET", "cancellation-fee-scenario-dataset", 'd'));
        root.set("caseRef", ref("DATA_CASE", "case-standard-cancellation-fee", 'e'));
        root.set("runtimeTarget", JSON.createObjectNode()
                .put("kind", "OPERATOR")
                .put("id", "tool-cancellation-fee-dispute-handling")
                .put("fingerprint", fingerprint('f')));
        root.set("bindingPlan", bindingPlan());
        root.set("run", JSON.createObjectNode()
                .put("runId", "run-governed-1")
                .put("status", "PASSED")
                .put("evidenceClass", "EXPLORATORY")
                .put("evidenceFingerprint", fingerprint('0'))
                .put("semanticResultFingerprint", fingerprint('1'))
                .put("assertionsEvaluated", 2)
                .put("assertionsPassed", 2)
                .put("fixtureControlsEvaluated", 1)
                .put("fixtureControlsSatisfied", 1));
        root.put("focusNodeId", "node-1");
        root.set("dataLens", dataLens());
        root.put("projectionFingerprint", fingerprintMaterial(root));
        return root;
    }

    private static ObjectNode scenario() {
        ObjectNode value = JSON.createObjectNode()
                .put("caseId", "case-standard-cancellation-fee")
                .put("name", "Standard cancellation fee")
                .put("businessIntent", "Return a governed fee conclusion")
                .put("category", "GOLDEN")
                .put("lifecycle", "DRAFT")
                .put("qualityState", "DESIGNED_NOT_RUN");
        value.set("owner", JSON.createObjectNode().put("id", "customer-service-platform")
                .put("name", "Customer Service Platform"));
        value.set("scenarioRef", ref("SCENARIO", "case-standard-cancellation-fee", '1'));
        value.set("caseRef", ref("DATA_CASE", "case-standard-cancellation-fee", 'e'));
        value.set("sourceRef", ref("SOURCE", "source-standard-cancellation-fee", '2'));
        value.set("oracleRef", ref("ORACLE", "oracle-standard-cancellation-fee", '3'));
        value.putArray("applicableContractRefs")
                .add(ref("CONTRACT", "contract-cancellation-fee-dispute-tool", 'c'));
        return value;
    }

    private static ObjectNode bindingPlan() {
        ObjectNode value = JSON.createObjectNode();
        value.set("ref", ref("BINDING_PLAN", "binding-plan-case-standard-cancellation-fee", '0'));
        value.set("fixtureBundleRef", ref("FIXTURE_BUNDLE", "fixture-case-standard", '4'));
        value.put("effectiveExecutionPlanFingerprint", fingerprint('5'));
        value.putArray("behaviorRefs")
                .add(ref("BEHAVIOR_PROFILE", "behavior-profile-case-standard-runtime-api", '6'));
        value.putArray("dependencyRefs")
                .add(ref("API", "api-order-lookup", '7'));
        value.put("fallbackToReal", false)
                .put("sourceMapFingerprint", fingerprint('8'))
                .put("provenanceFingerprint", fingerprint('a'));
        ObjectNode material = JSON.createObjectNode()
                .put("refKind", "BINDING_PLAN")
                .put("refId", "binding-plan-case-standard-cancellation-fee")
                .put("refRevision", 1)
                .set("fixtureBundleRef", value.get("fixtureBundleRef"));
        material.put("effectiveExecutionPlanFingerprint",
                value.path("effectiveExecutionPlanFingerprint").textValue());
        material.set("behaviorRefs", value.get("behaviorRefs"));
        material.set("dependencyRefs", value.get("dependencyRefs"));
        material.put("fallbackToReal", false)
                .put("sourceMapFingerprint", value.path("sourceMapFingerprint").textValue())
                .put("provenanceFingerprint", value.path("provenanceFingerprint").textValue());
        ((ObjectNode) value.path("ref"))
                .put("fingerprint", EvidenceVerificationSupport.sha256(material));
        return value;
    }

    private static ObjectNode dataLens() {
        ObjectNode lens = JSON.createObjectNode()
                .put("schemaVersion", "resource-gateway.capability-studio.data-lens.v1")
                .put("runId", "run-governed-1")
                .put("runStatus", "PASSED")
                .put("permissionMode", "STRUCTURE_ONLY");
        ObjectNode node = JSON.createObjectNode()
                .put("nodeId", "node-1")
                .put("operatorRef", "httpResource")
                .put("status", "MOCKED")
                .put("fidelity", "OUTPUT_LEVEL")
                .put("graphPath", "/root")
                .put("invocationSite", "/root/node-1#PRIMARY")
                .put("correlation", "correlation-1")
                .put("occurrence", 0)
                .put("graphOccurrence", 0)
                .putNull("input")
                .put("inputFingerprint", fingerprint('b'))
                .putNull("output")
                .put("outputFingerprint", fingerprint('c'))
                .put("errorCode", "")
                .put("durationMs", 1)
                .put("retryCount", 0)
                .putNull("fallbackStatus");
        node.putArray("attempts").add(JSON.createObjectNode()
                .put("attempt", 0)
                .put("status", "MOCKED")
                .put("fidelity", "OUTPUT_LEVEL")
                .putNull("input")
                .put("inputFingerprint", fingerprint('b'))
                .putNull("output")
                .put("outputFingerprint", fingerprint('c'))
                .put("errorCode", "")
                .put("durationMs", 1));
        lens.putArray("nodes").add(node);
        lens.putArray("edges").add(JSON.createObjectNode()
                .put("edgeId", "edge-1")
                .put("status", "TRANSFERRED")
                .put("graphPath", "/root")
                .put("correlation", "correlation-1")
                .put("graphOccurrence", 0)
                .put("fromInvocationSite", "/root/node-1#PRIMARY")
                .put("toInvocationSite", "/root/node-1#PRIMARY")
                .putNull("value")
                .put("valueFingerprint", fingerprint('d')));
        lens.putNull("firstDifference");
        lens.set("truncation", JSON.createObjectNode()
                .put("nodesTruncated", false)
                .put("omittedNodes", 0)
                .put("edgesTruncated", false)
                .put("omittedEdges", 0)
                .put("attemptsTruncated", false)
                .put("omittedAttempts", 0));
        ObjectNode material = JSON.createObjectNode();
        material.set("runId", lens.get("runId"));
        material.set("runStatus", lens.get("runStatus"));
        material.set("permissionMode", lens.get("permissionMode"));
        material.set("nodes", lens.get("nodes"));
        material.set("edges", lens.get("edges"));
        material.set("firstDifference", lens.get("firstDifference"));
        material.set("truncation", lens.get("truncation"));
        lens.put("fingerprint", EvidenceVerificationSupport.sha256(material));
        return lens;
    }

    private static String fingerprintMaterial(ObjectNode projection) {
        ObjectNode material = projection.deepCopy();
        material.remove("projectionFingerprint");
        return EvidenceVerificationSupport.sha256(material);
    }

    private static void recomputeFingerprints(ObjectNode projection) {
        ObjectNode lens = (ObjectNode) projection.path("dataLens");
        ObjectNode material = JSON.createObjectNode();
        material.set("runId", lens.get("runId"));
        material.set("runStatus", lens.get("runStatus"));
        material.set("permissionMode", lens.get("permissionMode"));
        material.set("nodes", lens.get("nodes"));
        material.set("edges", lens.get("edges"));
        material.set("firstDifference", lens.get("firstDifference"));
        material.set("truncation", lens.get("truncation"));
        lens.put("fingerprint", EvidenceVerificationSupport.sha256(material));
        projection.put("projectionFingerprint", fingerprintMaterial(projection));
    }

    private static ObjectNode ref(String kind, String id, char fill) {
        return JSON.createObjectNode()
                .put("kind", kind)
                .put("id", id)
                .put("revision", 1)
                .put("fingerprint", fingerprint(fill));
    }

    private static String fingerprint(char fill) {
        return "sha256:" + String.valueOf(fill).repeat(64);
    }

    private static byte[] bytes(JsonNode value) {
        try {
            return JSON.writeValueAsBytes(value);
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }
}
