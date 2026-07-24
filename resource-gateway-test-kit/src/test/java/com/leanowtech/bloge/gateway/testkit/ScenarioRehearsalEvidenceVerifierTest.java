package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class ScenarioRehearsalEvidenceVerifierTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant COMPLETED =
            Instant.parse("2026-07-24T08:00:01Z");
    private static final Instant SIGNED =
            Instant.parse("2026-07-24T08:00:02Z");
    private static final String RUN_ID = scenarioRunId();
    private KeyPair keyPair;
    private EvidenceVerificationKey key;
    private ScenarioRehearsalEvidenceVerifier verifier;

    @BeforeEach
    void setUp() throws Exception {
        keyPair = KeyPairGenerator.getInstance("Ed25519")
                .generateKeyPair();
        key = new EvidenceVerificationKey(
                TestingProtocol.EVIDENCE_VERIFICATION_KEY_V1,
                "scenario-key-1",
                "Ed25519",
                Base64.getEncoder().encodeToString(
                        keyPair.getPublic().getEncoded()),
                SIGNED.minusSeconds(60),
                "ACTIVE",
                "test");
        verifier = new ScenarioRehearsalEvidenceVerifier();
    }

    @Test
    void independentlyVerifiesAllContentAddressesAndSignature()
            throws Exception {
        ObjectNode bundle = bundle("INDETERMINATE");

        ScenarioRehearsalEvidenceVerifier.VerificationResult result =
                verifier.verify(bundle, key);

        assertThat(result.verified()).isTrue();
        assertThat(result.runId()).isEqualTo(RUN_ID);
        assertThat(result.requestId())
                .isEqualTo("scenario-request-1");
        assertThat(result.resultFingerprint())
                .isEqualTo(bundle.path("result")
                        .path("resultFingerprint").asText());
    }

    @Test
    void verifiesAnEvidenceBackedCaseAndAssertionClosure()
            throws Exception {
        ObjectNode bundle = evidenceBackedBundle();

        ScenarioRehearsalEvidenceVerifier.VerificationResult result =
                verifier.verify(bundle, key);

        assertThat(result.verified()).isTrue();
        assertThat(bundle.path("result").path("summary")
                .path("assertionResults").asInt()).isEqualTo(1);
    }

    @Test
    void rejectsSignedProducerOutcomeThatContradictsCaseClosure()
            throws Exception {
        ObjectNode bundle = bundle("FAIL");

        ScenarioRehearsalEvidenceVerifier.VerificationResult result =
                verifier.verify(bundle, key);

        assertThat(result.outcome())
                .isEqualTo(
                        ScenarioRehearsalEvidenceVerifier.Outcome.INVALID);
        assertThat(result.reasonCode())
                .isEqualTo("SCENARIO_RESULT_OUTCOME_INVALID");
    }

    @Test
    void distinguishesMissingKeyFromSignatureTampering()
            throws Exception {
        ObjectNode bundle = bundle("INDETERMINATE");

        assertThat(verifier.verify(bundle, null).outcome())
                .isEqualTo(
                        ScenarioRehearsalEvidenceVerifier.Outcome
                                .KEY_UNAVAILABLE);

        ((ObjectNode) bundle.path("attestation"))
                .put("signature", Base64.getEncoder()
                        .encodeToString(new byte[64]));
        sealBundleFingerprint(bundle);
        assertThat(verifier.verify(bundle, key).reasonCode())
                .isEqualTo("SCENARIO_EVIDENCE_SIGNATURE_INVALID");
    }

    @Test
    void rejectsAValidSignatureOverANonCanonicalRunIdentity()
            throws Exception {
        ObjectNode bundle = bundle("INDETERMINATE");
        ObjectNode attestation =
                (ObjectNode) bundle.path("attestation");
        attestation.put(
                "runId", "scenario-" + "9".repeat(64));
        attestation.put(
                "signature",
                sign(signatureMaterial(attestation)));
        sealBundleFingerprint(bundle);

        assertThat(verifier.verify(bundle, key).reasonCode())
                .isEqualTo(
                        "SCENARIO_EVIDENCE_ATTESTATION_IDENTITY_INVALID");
    }

    private ObjectNode bundle(String aggregateOutcome)
            throws Exception {
        ObjectNode caseResult = caseResult();
        sealFingerprint(
                caseResult,
                "resultFingerprint",
                ScenarioRehearsalEvidenceVerifier.MAXIMUM_CASE_BYTES);
        ObjectNode aggregate = aggregate(
                aggregateOutcome, caseResult);
        sealFingerprint(
                aggregate,
                "resultFingerprint",
                ScenarioRehearsalEvidenceVerifier.MAXIMUM_RESULT_BYTES);
        return signedBundle(aggregate);
    }

    private ObjectNode evidenceBackedBundle() throws Exception {
        ObjectNode assertion = JSON.createObjectNode();
        assertion.put(
                "schemaVersion",
                "resourceGateway.scenarioHandlingAssertionResult.v1");
        assertion.put("resultFingerprint", "");
        assertion.put("runId", "mirror-run-1");
        assertion.put(
                "evidenceBundleFingerprint", fingerprint('7'));
        assertion.put("planFingerprint", fingerprint('e'));
        assertion.set(
                "assertionRef",
                ref(
                        "CASE_HANDLING_ASSERTION",
                        "support-certifiable", '2'));
        assertion.put("observation", "GOVERNANCE_EXPECTATION");
        assertion.put("outcome", "PASS");
        assertion.put("severity", "BLOCKER");
        assertion.put("governanceCode", "SCENARIO_CERTIFIABLE");
        assertion.put("reasonCode", "ASSERTION_MATCHED");
        ObjectNode observed = assertion.putObject("observed");
        observed.putArray("statuses").add("CERTIFIABLE");
        observed.putArray("errorCodes");
        observed.putArray("fingerprints");
        observed.putArray("sources").add("CERTIFIABLE");
        observed.put("booleanValue", true);
        observed.putArray("limitations");
        sealFingerprint(
                assertion,
                "resultFingerprint",
                ScenarioRehearsalEvidenceVerifier
                        .MAXIMUM_ASSERTION_BYTES);

        ObjectNode caseResult = caseResult();
        caseResult.put("outcome", "PASS");
        caseResult.put("runId", "mirror-run-1");
        caseResult.put(
                "evidenceBundleFingerprint", fingerprint('7'));
        caseResult.put("evidenceStatus", "PASSED");
        caseResult.put("evidenceClass", "CERTIFIABLE");
        ((ArrayNode) caseResult.path("assertionResults"))
                .add(assertion);
        caseResult.put("diagnosticCode", "");
        sealFingerprint(
                caseResult,
                "resultFingerprint",
                ScenarioRehearsalEvidenceVerifier.MAXIMUM_CASE_BYTES);

        ObjectNode aggregate = aggregate("PASS", caseResult);
        ObjectNode summary =
                (ObjectNode) aggregate.path("summary");
        summary.put("passedCases", 1);
        summary.put("indeterminateCases", 0);
        summary.put("assertionResults", 1);
        sealFingerprint(
                aggregate,
                "resultFingerprint",
                ScenarioRehearsalEvidenceVerifier.MAXIMUM_RESULT_BYTES);
        return signedBundle(aggregate);
    }

    private ObjectNode signedBundle(ObjectNode aggregate)
            throws Exception {
        ObjectNode attestation = JSON.createObjectNode();
        attestation.put(
                "schemaVersion",
                "resourceGateway.scenarioRehearsalEvidenceAttestation.v1");
        attestation.put("signatureStatus", "VERIFIED");
        attestation.put("runId", RUN_ID);
        attestation.put("requestId", "scenario-request-1");
        attestation.put(
                "compiledPlanFingerprint", fingerprint('a'));
        attestation.put(
                "resultFingerprint",
                aggregate.path("resultFingerprint").asText());
        attestation.put("signedAt", SIGNED.toString());
        attestation.put("keyId", key.keyId());
        attestation.put("algorithm", "Ed25519");
        attestation.put(
                "signature",
                sign(signatureMaterial(attestation)));
        attestation.put("independentlyVerifiable", true);

        ObjectNode bundle = JSON.createObjectNode();
        bundle.put(
                "schemaVersion",
                "resourceGateway.scenarioRehearsalEvidenceBundle.v1");
        bundle.put("bundleFingerprint", fingerprint('0'));
        bundle.put("payloadPolicy", "HASH_ONLY");
        bundle.set("attestation", attestation);
        bundle.set("result", aggregate);
        sealBundleFingerprint(bundle);
        return bundle;
    }

    private static void sealBundleFingerprint(ObjectNode bundle) {
        ObjectNode exactMaterial = JSON.createObjectNode();
        exactMaterial.set(
                "schemaVersion", bundle.path("schemaVersion"));
        exactMaterial.set(
                "payloadPolicy", bundle.path("payloadPolicy"));
        exactMaterial.set(
                "attestation", bundle.path("attestation"));
        exactMaterial.set("result", bundle.path("result"));
        bundle.put(
                "bundleFingerprint",
                EvidenceVerificationSupport.sha256Bounded(
                        exactMaterial,
                        ScenarioRehearsalEvidenceVerifier
                                .MAXIMUM_BUNDLE_BYTES));
    }

    private static ObjectNode caseResult() {
        ObjectNode value = JSON.createObjectNode();
        value.put(
                "schemaVersion",
                "resourceGateway.scenarioCaseRehearsalResult.v1");
        value.put("resultFingerprint", "");
        value.put("caseIndex", 0);
        value.set(
                "scenarioCaseRef",
                ref("SCENARIO_CASE", "support-golden", 'c'));
        value.put("caseType", "GOLDEN");
        value.set(
                "testSuiteRef",
                ref("TEST_SUITE", "support-suite", 'd'));
        value.put("testCaseId", "golden");
        value.set(
                "mirrorPlanRef",
                ref("MIRROR_PLAN", "support-plan", 'e'));
        value.set(
                "fixtureBundleRef",
                ref("FIXTURE_BUNDLE", "support-fixture", 'f'));
        value.putNull("sessionCheckpointRef");
        value.put("childRequestId", "scenario-request-1:case:000");
        value.put("outcome", "INDETERMINATE");
        value.put("runId", "");
        value.put("evidenceBundleFingerprint", "");
        value.putNull("evidenceStatus");
        value.putNull("evidenceClass");
        value.putArray("assertionResults");
        value.put(
                "diagnosticCode",
                "RG.MIRROR.REHEARSAL.RUNTIME_UNAVAILABLE");
        value.put("startedAt", "2026-07-24T08:00:00Z");
        value.put("completedAt", COMPLETED.toString());
        return value;
    }

    private static ObjectNode aggregate(
            String outcome, ObjectNode caseResult) {
        ObjectNode value = JSON.createObjectNode();
        value.put(
                "schemaVersion",
                "resourceGateway.scenarioRehearsalResult.v1");
        value.put("resultFingerprint", "");
        value.put("requestId", "scenario-request-1");
        value.set(
                "compiledPlanRef",
                ref(
                        "COMPILED_REHEARSAL_PLAN",
                        "support-rehearsal-compiled", 'a'));
        ObjectNode scope = value.putObject("scope");
        scope.put("tenantId", "tenant-a");
        scope.put("organizationId", "org-a");
        scope.put("projectId", "support");
        scope.put("environmentId", "test");
        scope.put("region", "sg");
        value.set(
                "targetCapabilityRef",
                ref("CAPABILITY", "support", '1'));
        value.put("outcome", outcome);
        ArrayNode cases = value.putArray("caseResults");
        cases.add(caseResult);
        ObjectNode summary = value.putObject("summary");
        summary.put("totalCases", 1);
        summary.put("passedCases", 0);
        summary.put("failedCases", 0);
        summary.put("indeterminateCases", 1);
        summary.put("assertionResults", 0);
        summary.put("blockerFailures", 0);
        summary.put("blockerIndeterminate", 0);
        summary.put("warningFailures", 0);
        summary.put("warningIndeterminate", 0);
        value.put("startedAt", "2026-07-24T08:00:00Z");
        value.put("completedAt", COMPLETED.toString());
        return value;
    }

    private static ObjectNode signatureMaterial(
            ObjectNode attestation) {
        ObjectNode value = JSON.createObjectNode();
        value.put(
                "domain",
                "RESOURCE_GATEWAY_SCENARIO_REHEARSAL_EVIDENCE_V1");
        value.set(
                "schemaVersion",
                attestation.path("schemaVersion"));
        value.set("runId", attestation.path("runId"));
        value.set("requestId", attestation.path("requestId"));
        value.set(
                "compiledPlanFingerprint",
                attestation.path("compiledPlanFingerprint"));
        value.set(
                "resultFingerprint",
                attestation.path("resultFingerprint"));
        value.set("signedAt", attestation.path("signedAt"));
        return value;
    }

    private String sign(ObjectNode material) throws Exception {
        String fingerprint =
                EvidenceVerificationSupport.sha256Bounded(
                        material, 8 * 1024);
        Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(keyPair.getPrivate());
        signature.update(
                fingerprint.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(
                signature.sign());
    }

    private static void sealFingerprint(
            ObjectNode value, String field, int limit) {
        value.put(field, "");
        value.put(
                field,
                EvidenceVerificationSupport.sha256Bounded(
                        value, limit));
    }

    private static ObjectNode ref(
            String kind, String id, char material) {
        ObjectNode value = JSON.createObjectNode();
        value.put("kind", kind);
        value.put("id", id);
        value.put("revision", 1);
        value.put("fingerprint", fingerprint(material));
        return value;
    }

    private static String fingerprint(char material) {
        return "sha256:" + String.valueOf(material).repeat(64);
    }

    private static String scenarioRunId() {
        ObjectNode material = JSON.createObjectNode();
        material.put(
                "domain",
                "RESOURCE_GATEWAY_SCENARIO_REHEARSAL_RUN_ID_V1");
        ObjectNode scope = material.putObject("scope");
        scope.put("tenantId", "tenant-a");
        scope.put("organizationId", "org-a");
        scope.put("projectId", "support");
        scope.put("environmentId", "test");
        scope.put("region", "sg");
        material.put("requestId", "scenario-request-1");
        return "scenario-" + EvidenceVerificationSupport
                .sha256Bounded(material, 16 * 1024)
                .substring("sha256:".length());
    }
}
