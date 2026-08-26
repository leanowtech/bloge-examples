package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestRunControlEvidenceProjectionTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String RUN = "run-1";
    private static final String TARGET = "sha256:" + "a".repeat(64);
    private static final String PLAN = "sha256:" + "b".repeat(64);
    private static final String FUNCTION_PLAN = "sha256:" + "c".repeat(64);
    private static final String FUNCTION_FP = "sha256:" + "d".repeat(64);
    private static final String RUNTIME_FP = "sha256:" + "e".repeat(64);
    private static final String SCOPE_FP = "sha256:" + "f".repeat(64);
    private static final String ARGS_FP = "sha256:" + "0".repeat(64);
    private static final String RESULT_FP = "sha256:" + "1".repeat(64);
    private static final String SITE = "bloge.functionInvocationSite.v1:L3Jvb3Q.Zm9ybWF0.dXBwZXJjYXNl.1.2";

    @Test
    void decodesPayloadFreeFunctionProjectionAndRecomputesAllFingerprints() {
        TestRunControlEvidenceProjection projection =
                TestRunControlEvidenceProjection.from(projectionJson());

        assertThat(projection.function().observations()).hasSize(1);
        assertThat(projection.function().observations().getFirst().resultFingerprint())
                .isEqualTo(RESULT_FP);
        assertThat(projection.toString()).doesNotContain("secret", "return-value", "schema");
        assertThat(projection.stableSemanticMaterial()).doesNotContainKey("runId");
    }

    @Test
    void tamperUnknownEnumSiteAndPayloadFieldsFailClosed() {
        ObjectNode tampered = projectionJson();
        tampered.put("projectionFingerprint", RESULT_FP);
        assertThatThrownBy(() -> TestRunControlEvidenceProjection.from(tampered))
                .isInstanceOf(IllegalArgumentException.class);

        ObjectNode unknown = projectionJson();
        unknown.put("secretPayload", "secret");
        assertThatThrownBy(() -> TestRunControlEvidenceProjection.from(unknown))
                .isInstanceOf(IllegalArgumentException.class);

        ObjectNode badSite = projectionJson();
        badSite.at("/function/bindings/0").asText();
        ((ObjectNode) badSite.at("/function/bindings/0")).put("siteKey", "foreign");
        assertThatThrownBy(() -> TestRunControlEvidenceProjection.from(badSite))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void verifiesRunAndReservedMetadataBinding() {
        ObjectNode response = JSON.createObjectNode();
        ObjectNode evidence = response.putObject("evidence");
        evidence.putObject("metadata").set("controlEvidenceProjection", projectionJson());
        TestRun run = new TestRun(RUN, TestRun.Status.PASSED, TestRun.EvidenceClass.EXPLORATORY,
                TARGET, "", PLAN, "", List.of(), List.of(), List.of(), List.of(), List.of(),
                TestRun.Integrity.legacyUnsigned(), response);

        assertThat(run.controlEvidence().runId()).isEqualTo(RUN);
        assertThat(TestRunControlEvidenceVerifier.verify(run, "", "", FUNCTION_PLAN)
                .functionPlanFingerprint()).isEqualTo(FUNCTION_PLAN);

        ObjectNode spoofed = response.deepCopy();
        ((ObjectNode) spoofed.at("/evidence/metadata")).putObject("functionControlEvidence")
                .put("payload", "secret");
        TestRun spoofedRun = new TestRun(RUN, TestRun.Status.PASSED,
                TestRun.EvidenceClass.EXPLORATORY, TARGET, "", PLAN, "", List.of(), List.of(),
                List.of(), List.of(), List.of(), TestRun.Integrity.legacyUnsigned(), spoofed);
        assertThatThrownBy(spoofedRun::controlEvidence)
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void packagedGoldenFixtureIsAValidStrictProjection() throws Exception {
        try (InputStream input = getClass().getResourceAsStream(
                "/protocol/test-run-control-evidence-v1-golden.json")) {
            assertThat(input).isNotNull();
            assertThat(TestRunControlEvidenceProjection.from(JSON.readTree(input))
                    .projectionFingerprint()).isEqualTo(
                    "sha256:a1e3742d599a9a037071c14ea6883314c59cb371acb0c164b7345565efab2fcb");
        }
    }

    @Test
    void dynamicCoordinateCanonicalKeyDoesNotCollideOnSeparators() {
        TestRunControlEvidenceProjection.Coordinate first =
                new TestRunControlEvidenceProjection.Coordinate(
                        "/root|nested", "node", 1, 1, 1, "site|one");
        TestRunControlEvidenceProjection.Coordinate second =
                new TestRunControlEvidenceProjection.Coordinate(
                        "/root", "nested|node", 1, 1, 1, "site|one");

        assertThat(first.canonicalKey()).isNotEqualTo(second.canonicalKey());
    }

    private static ObjectNode projectionJson() {
        ObjectNode root = JSON.createObjectNode();
        root.put("schemaVersion", TestRunControlEvidenceProjection.SCHEMA_VERSION);
        root.put("runId", RUN);
        root.put("scenarioFingerprint", "");
        root.put("worldFingerprint", "");
        root.put("targetFingerprint", TARGET);
        root.put("executionPlanFingerprint", PLAN);
        root.put("functionPlanFingerprint", FUNCTION_PLAN);
        root.putNull("state");
        ObjectNode function = root.putObject("function");
        function.put("planFingerprint", FUNCTION_PLAN);
        function.put("evidenceCeiling", "CERTIFIABLE");
        ArrayNode bindings = function.putArray("bindings");
        ObjectNode binding = bindings.addObject();
        binding.put("siteKey", SITE);
        binding.put("graphPath", "/root");
        binding.put("nodeId", "format");
        binding.put("functionName", "uppercase");
        binding.put("line", 1);
        binding.put("column", 2);
        binding.put("functionFingerprint", FUNCTION_FP);
        binding.put("runtimeFingerprint", RUNTIME_FP);
        binding.put("mode", "CONTROLLED");
        binding.put("evidenceCeiling", "CERTIFIABLE");
        binding.put("downgradeReason", "");
        ArrayNode consumptions = function.putArray("consumptions");
        ObjectNode consumption = consumptions.addObject();
        consumption.put("ruleId", "rule-1");
        consumption.put("minimum", 1);
        consumption.put("maximum", 1);
        consumption.put("used", 1);
        consumption.put("status", "MAX_REACHED");
        ArrayNode observations = function.putArray("observations");
        ObjectNode observation = observations.addObject();
        observation.put("siteKey", SITE);
        observation.put("ruleId", "rule-1");
        observation.put("behavior", "RETURN");
        observation.put("invocationScopeFingerprint", SCOPE_FP);
        observation.put("argumentsFingerprint", ARGS_FP);
        observation.put("resultFingerprint", RESULT_FP);
        observation.put("errorFingerprint", "");
        observation.put("occurrence", 1);
        observation.put("logicalDurationMillis", 0);
        String functionEvidence = ProtocolCanonical.fingerprint(functionMaterial());
        function.put("evidenceFingerprint", functionEvidence);
        root.put("projectionFingerprint", ProtocolCanonical.fingerprint(projectionMaterial(function)));
        return root;
    }

    private static Map<String, Object> functionMaterial() {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("planFingerprint", FUNCTION_PLAN);
        material.put("evidenceCeiling", "CERTIFIABLE");
        material.put("bindings", List.of(bindingMaterial()));
        Map<String, Object> consumption = new LinkedHashMap<>();
        consumption.put("ruleId", "rule-1");
        consumption.put("minimum", 1);
        consumption.put("maximum", 1);
        consumption.put("used", 1);
        consumption.put("status", "MAX_REACHED");
        material.put("consumptions", List.of(consumption));
        material.put("observations", List.of(SITE + "|rule-1|RETURN|" + SCOPE_FP + "|" + ARGS_FP
                + "|" + RESULT_FP + "||1|0"));
        return material;
    }

    private static Map<String, Object> bindingMaterial() {
        Map<String, Object> site = new LinkedHashMap<>();
        site.put("graphPath", "/root");
        site.put("nodeId", "format");
        site.put("functionName", "uppercase");
        site.put("line", 1);
        site.put("column", 2);
        Map<String, Object> binding = new LinkedHashMap<>();
        binding.put("site", site);
        binding.put("functionFingerprint", FUNCTION_FP);
        binding.put("runtimeFingerprint", RUNTIME_FP);
        binding.put("mode", "CONTROLLED");
        binding.put("evidenceCeiling", "CERTIFIABLE");
        binding.put("downgradeReason", "");
        return binding;
    }

    private static Map<String, Object> projectionMaterial(ObjectNode function) {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("schemaVersion", TestRunControlEvidenceProjection.SCHEMA_VERSION);
        material.put("runId", RUN);
        material.put("scenarioFingerprint", "");
        material.put("worldFingerprint", "");
        material.put("targetFingerprint", TARGET);
        material.put("executionPlanFingerprint", PLAN);
        material.put("functionPlanFingerprint", FUNCTION_PLAN);
        material.put("state", null);
        material.put("function", JSON.convertValue(function, Object.class));
        return material;
    }
}
