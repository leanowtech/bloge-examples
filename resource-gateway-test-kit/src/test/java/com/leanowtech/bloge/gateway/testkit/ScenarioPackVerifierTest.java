package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScenarioPackVerifierTest {
    private static final Instant NOW =
            Instant.parse("2026-07-24T05:00:00Z");
    private static final String SHA_A =
            "sha256:" + "a".repeat(64);
    private static final String SHA_B =
            "sha256:" + "b".repeat(64);
    private final ObjectMapper mapper = new ObjectMapper();
    private final ScenarioPackVerifier verifier =
            new ScenarioPackVerifier();

    @Test
    void independentlyVerifiesAnExactScenarioClosure() {
        Closure closure = closure();

        ScenarioPackVerifier.VerifiedScenarioPack verified =
                verifier.verify(
                        closure.pack(),
                        closure.cases(),
                        closure.assertions(),
                        NOW);

        assertThat(verified.packId())
                .isEqualTo("refund-rehearsal");
        assertThat(verified.targetCapabilityId())
                .isEqualTo("refund-orchestration");
        assertThat(verified.caseIds())
                .containsExactly("refund-golden");
        assertThat(verified.caseTypes())
                .containsExactly("GOLDEN");
        assertThat(verified.assertionCount()).isOne();
        assertThat(verified.certificationRequired()).isTrue();
    }

    @Test
    void packagedCompatibilityFixtureIsStrictAndIndependentlyVerified() {
        JsonNode fixture =
                CapabilityMirrorProtocol
                        .scenarioPackCompatibilityFixture();

        assertThat(fixture.path("schemaVersion").asText())
                .isEqualTo(
                        CapabilityMirrorProtocol
                                .SCENARIO_PACK_COMPATIBILITY_V1);
        assertThat(fixture.at(
                "/expected/fingerprint").asText())
                .isEqualTo(fixture.at(
                "/scenarioPack/fingerprint").asText());
        assertThat(fixture.toString())
                .doesNotContain(
                        "\"input\"",
                        "\"output\"",
                        "\"request\"",
                        "\"response\"",
                        "\"payload\"",
                        "\"credentials\"");
    }

    @Test
    void rejectsNestedTamperingAndUnknownFields() {
        Closure closure = closure();
        ObjectNode tampered = closure.assertions().getFirst().deepCopy();
        tampered.put("governanceCode",
                "RG.MIRROR.SCENARIO.TAMPERED");

        assertThatThrownBy(() -> verifier.verify(
                closure.pack(),
                closure.cases(),
                List.of(tampered),
                NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "RG.MIRROR.CLIENT.SCENARIO_ASSERTION_FINGERPRINT_INVALID");

        ObjectNode unknown = closure.pack().deepCopy();
        unknown.put("businessPayload", "forbidden");
        assertThatThrownBy(() -> verifier.verify(
                unknown,
                closure.cases(),
                closure.assertions(),
                NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "RG.MIRROR.CLIENT.SCENARIO_PACK_SCHEMA_INVALID");
    }

    @Test
    void rejectsCrossScopeCaseEvenWhenItIsCorrectlyResealed() {
        Closure closure = closure();
        ObjectNode crossScope = closure.cases().getFirst().deepCopy();
        ((ObjectNode) crossScope.path("scope"))
                .put("organizationId", "org-b");
        seal(crossScope, ScenarioPackVerifier.MAXIMUM_CASE_BYTES);
        ObjectNode pack = closure.pack().deepCopy();
        pack.withArray("caseRefs").removeAll()
                .add(reference(
                        "SCENARIO_CASE",
                        crossScope.path("caseId").asText(),
                        crossScope.path("fingerprint").asText()));
        seal(pack, ScenarioPackVerifier.MAXIMUM_PACK_BYTES);

        assertThatThrownBy(() -> verifier.verify(
                pack,
                List.of(crossScope),
                closure.assertions(),
                NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "RG.MIRROR.CLIENT.SCENARIO_SCOPE_INVALID");
    }

    @Test
    void rejectsExpiredActiveArtifactsAndIncompleteClosures() {
        Closure closure = closure();
        ObjectNode expired = closure.pack().deepCopy();
        ((ObjectNode) expired.path("provenance"))
                .put("expiresAt", "2026-07-24T04:59:59Z");
        seal(expired, ScenarioPackVerifier.MAXIMUM_PACK_BYTES);

        assertThatThrownBy(() -> verifier.verify(
                expired,
                closure.cases(),
                closure.assertions(),
                NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "RG.MIRROR.CLIENT.SCENARIO_LIFECYCLE_INVALID");
        assertThatThrownBy(() -> verifier.verify(
                closure.pack(),
                List.of(),
                closure.assertions(),
                NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "RG.MIRROR.CLIENT.SCENARIO_CASE_CLOSURE_INVALID");
    }

    @Test
    void rejectsSharedStatefulCheckpointAcrossCases() {
        Closure base = closure();
        ObjectNode stateAssertion = stateAssertion();
        ObjectNode first = scenarioCase(
                "refund-recovery-a",
                "STATE_TRANSITION",
                stateAssertion,
                checkpointRef());
        ObjectNode second = scenarioCase(
                "refund-recovery-b",
                "STATE_TRANSITION",
                stateAssertion,
                checkpointRef());
        ObjectNode pack = scenarioPack(
                List.of(first, second),
                List.of(stateAssertion));

        assertThatThrownBy(() -> verifier.verify(
                pack,
                List.of(first, second),
                List.of(stateAssertion),
                NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "RG.MIRROR.CLIENT.SCENARIO_SESSION_NOT_ISOLATED");
    }

    @Test
    void rejectsFaultCasesWithoutExplicitFixtureFaultRules() {
        ObjectNode assertion = outputAssertion();
        ObjectNode fault = scenarioCase(
                "refund-timeout", "FAULT",
                assertion, null);
        fault.withArray("faultRuleRefs").removeAll();
        seal(fault, ScenarioPackVerifier.MAXIMUM_CASE_BYTES);
        ObjectNode pack = scenarioPack(
                List.of(fault), List.of(assertion));

        assertThatThrownBy(() -> verifier.verify(
                pack,
                List.of(fault),
                List.of(assertion),
                NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "RG.MIRROR.CLIENT.SCENARIO_CASE_SEMANTICS_INVALID");
    }

    private Closure closure() {
        ObjectNode assertion = outputAssertion();
        ObjectNode scenarioCase = scenarioCase(
                "refund-golden", "GOLDEN",
                assertion, null);
        ObjectNode pack = scenarioPack(
                List.of(scenarioCase), List.of(assertion));
        return new Closure(
                pack, List.of(scenarioCase),
                List.of(assertion));
    }

    private ObjectNode outputAssertion() {
        ObjectNode assertion = mapper.createObjectNode();
        assertion.put("schemaVersion",
                CapabilityMirrorProtocol
                        .CASE_HANDLING_ASSERTION_V1);
        assertion.put("assertionId", "refund-output");
        assertion.put("revision", 1);
        assertion.put("fingerprint", "");
        assertion.set("scope", scope());
        assertion.put("observation",
                "GRAPH_OUTPUT_SCHEMA");
        ObjectNode selector = assertion.putObject("selector");
        selector.put("nodeId", "");
        selector.put("edgeId", "");
        selector.put("invocationSiteId", "");
        selector.putNull("capabilityRef");
        selector.put("path", "/refund");
        ObjectNode expectation =
                assertion.putObject("expectation");
        expectation.putArray("statuses");
        expectation.put("errorCode", "");
        expectation.put("schemaFingerprint", SHA_A);
        expectation.put("valueFingerprint", "");
        expectation.putNull("minimumOccurrences");
        expectation.putNull("maximumOccurrences");
        expectation.putNull("maximumDurationMillis");
        expectation.putNull("expectedBoolean");
        assertion.put("severity", "BLOCKER");
        assertion.put(
                "governanceCode",
                "RG.MIRROR.SCENARIO.REFUND_OUTPUT_INVALID");
        assertion.set("provenance", provenance());
        assertion.put("lifecycle", "ACTIVE");
        assertion.put("createdAt", NOW.toString());
        return seal(
                assertion,
                ScenarioPackVerifier.MAXIMUM_ASSERTION_BYTES);
    }

    private ObjectNode stateAssertion() {
        ObjectNode assertion = outputAssertion().deepCopy();
        assertion.put("assertionId", "refund-state");
        assertion.put("observation", "STATE_TRANSITION");
        ((ObjectNode) assertion.path("selector"))
                .put("path", "");
        ObjectNode expectation =
                (ObjectNode) assertion.path("expectation");
        expectation.put("schemaFingerprint", "");
        expectation.putArray("statuses").add("COMMITTED");
        expectation.put("expectedBoolean", true);
        assertion.put(
                "governanceCode",
                "RG.MIRROR.SCENARIO.REFUND_STATE_INVALID");
        return seal(
                assertion,
                ScenarioPackVerifier.MAXIMUM_ASSERTION_BYTES);
    }

    private ObjectNode scenarioCase(
            String caseId,
            String caseType,
            ObjectNode assertion,
            ObjectNode checkpoint) {
        ObjectNode scenarioCase = mapper.createObjectNode();
        scenarioCase.put("schemaVersion",
                CapabilityMirrorProtocol.SCENARIO_CASE_V1);
        scenarioCase.put("caseId", caseId);
        scenarioCase.put("revision", 1);
        scenarioCase.put("fingerprint", "");
        scenarioCase.set("scope", scope());
        scenarioCase.put("caseType", caseType);
        scenarioCase.set(
                "targetCapabilityRef", capabilityRef());
        scenarioCase.set(
                "testSuiteRef",
                reference("TEST_SUITE",
                        "refund-contract-suite", SHA_A));
        scenarioCase.put("testCaseId", caseId);
        scenarioCase.set(
                "mirrorPlanRef",
                reference("MIRROR_PLAN",
                        "refund-plan", SHA_B));
        scenarioCase.set(
                "fixtureBundleRef",
                reference("FIXTURE_BUNDLE",
                        "refund-fixtures", SHA_A));
        if (checkpoint == null) {
            scenarioCase.putNull("sessionCheckpointRef");
        } else {
            scenarioCase.set(
                    "sessionCheckpointRef", checkpoint);
        }
        ObjectNode services =
                scenarioCase.putObject("executionServices");
        services.put("logicalClock", NOW.toString());
        services.put("randomSeed", 42L);
        services.putNull("identityFixtureRef");
        services.putNull("featureFlagFixtureRef");
        ArrayNode faultRules =
                scenarioCase.putArray("faultRuleRefs");
        if ("FAULT".equals(caseType)) {
            faultRules.add("refund-provider-timeout");
        }
        scenarioCase.putArray("assertionRefs")
                .add(reference(
                        "CASE_HANDLING_ASSERTION",
                        assertion.path("assertionId").asText(),
                        assertion.path("fingerprint").asText()));
        scenarioCase.set("provenance", provenance());
        scenarioCase.put("lifecycle", "ACTIVE");
        scenarioCase.put("createdAt", NOW.toString());
        return seal(
                scenarioCase,
                ScenarioPackVerifier.MAXIMUM_CASE_BYTES);
    }

    private ObjectNode scenarioPack(
            List<ObjectNode> cases,
            List<ObjectNode> assertions) {
        ObjectNode pack = mapper.createObjectNode();
        pack.put("schemaVersion",
                CapabilityMirrorProtocol.SCENARIO_PACK_V1);
        pack.put("packId", "refund-rehearsal");
        pack.put("revision", 1);
        pack.put("fingerprint", "");
        pack.set("scope", scope());
        pack.set("targetCapabilityRef", capabilityRef());
        ArrayNode caseRefs = pack.putArray("caseRefs");
        cases.forEach(value -> caseRefs.add(reference(
                "SCENARIO_CASE",
                value.path("caseId").asText(),
                value.path("fingerprint").asText())));
        ArrayNode assertionRefs =
                pack.putArray("assertionRefs");
        assertions.forEach(value -> assertionRefs.add(reference(
                "CASE_HANDLING_ASSERTION",
                value.path("assertionId").asText(),
                value.path("fingerprint").asText())));
        pack.putArray("writeEffectRefs")
                .add(reference(
                        "WRITE_EFFECT",
                        "create-refund", SHA_A));
        pack.putNull("corpusSnapshotRef");
        pack.putArray("stateModelRefs")
                .add(reference(
                        "STATE_MODEL",
                        "refund-world", SHA_B));
        ObjectNode policy = pack.putObject("policy");
        policy.put("scheduling", "SEQUENTIAL");
        policy.put("isolatedCaseSessions", true);
        policy.put("realExternalCallsAllowed", false);
        policy.put("externalCredentialsAllowed", false);
        policy.put("networkEgressAllowed", false);
        policy.put("evidenceMode", "HASH_ONLY");
        policy.put("maximumCases", 16);
        policy.put("maximumInvocationsPerCase", 1000);
        policy.put("caseTimeout", "PT2M");
        policy.put("totalTimeout", "PT30M");
        policy.put("certificationRequired", true);
        policy.put("maximumClassification", "CONFIDENTIAL");
        policy.putArray("allowedRegions").add("sg");
        pack.set("provenance", provenance());
        pack.put("lifecycle", "ACTIVE");
        pack.put("createdAt", NOW.toString());
        return seal(
                pack, ScenarioPackVerifier.MAXIMUM_PACK_BYTES);
    }

    private ObjectNode scope() {
        ObjectNode value = mapper.createObjectNode();
        value.put("tenantId", "tenant-a");
        value.put("organizationId", "org-a");
        value.put("projectId", "tool-studio");
        value.put("environmentId", "test");
        value.put("region", "sg");
        return value;
    }

    private ObjectNode provenance() {
        ObjectNode value = mapper.createObjectNode();
        value.put("schemaVersion",
                CapabilityMirrorProtocol.ARTIFACT_PROVENANCE_V1);
        value.put("sourceType", "OWNER");
        value.putArray("sourceRefs");
        value.put("tenantId", "tenant-a");
        value.put("purpose",
                "customer-support-simulation");
        value.putNull("sampleFrom");
        value.putNull("sampleTo");
        value.putNull("sampleCount");
        value.putNull("confidence");
        value.putArray("biasRisks");
        value.put("approvedBy", "refund-owner");
        value.put("approvedAt", NOW.toString());
        value.put(
                "expiresAt",
                "2026-07-25T05:00:00Z");
        value.put("revocationRef", "");
        return value;
    }

    private ObjectNode capabilityRef() {
        return reference(
                "CAPABILITY",
                "refund-orchestration", SHA_A);
    }

    private ObjectNode checkpointRef() {
        return reference(
                "MIRROR_SESSION_CHECKPOINT",
                "refund-checkpoint", SHA_B);
    }

    private ObjectNode reference(
            String kind, String id, String fingerprint) {
        ObjectNode value = mapper.createObjectNode();
        value.put("kind", kind);
        value.put("id", id);
        value.put("revision", 1);
        value.put("fingerprint", fingerprint);
        return value;
    }

    private static ObjectNode seal(
            ObjectNode value, int maximumBytes) {
        value.put("fingerprint", "");
        value.put(
                "fingerprint",
                EvidenceVerificationSupport
                        .sha256Bounded(value, maximumBytes));
        return value;
    }

    private record Closure(
            ObjectNode pack,
            List<JsonNode> cases,
            List<JsonNode> assertions
    ) {
    }
}
