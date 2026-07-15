package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TestSuiteEvidenceVerifierTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SUITE = "sha256:" + "a".repeat(64);
    private static final String TARGET = "sha256:" + "b".repeat(64);
    private static final String FIXTURE = "sha256:" + "c".repeat(64);
    private static final String REQUEST = "sha256:" + "d".repeat(64);
    private static final String CHILD = "sha256:" + "e".repeat(64);
    private static final Instant SIGNED_AT = Instant.parse("2026-07-16T10:15:30Z");

    @Test
    void verifiesPortableBundleWithoutTrustingProducerStatusClaim() throws Exception {
        Fixture fixture = fixture(List.of(child("golden", "child-run-1", CHILD)));

        TestSuiteEvidenceVerifier.VerificationResult result =
                new TestSuiteEvidenceVerifier().verify(fixture.bundle(), fixture.key());

        assertThat(result.verified()).isTrue();
        assertThat(result.reasonCode()).isEqualTo("VERIFIED");
        assertThat(fixture.bundle().rawResponse().toString())
                .doesNotContain("\"input\":", "\"output\":", "\"requestMetadata\":");
    }

    @Test
    void aggregateMutationAndSignatureMutationAreRejected() throws Exception {
        Fixture fixture = fixture(List.of(child("golden", "child-run-1", CHILD)));
        ObjectNode alteredEvidence = (ObjectNode) fixture.bundle().evidence();
        alteredEvidence.withObject("/metadata").put("tampered", true);
        TestSuiteEvidenceBundle altered = new TestSuiteEvidenceBundle(fixture.bundle().suiteRunId(),
                fixture.bundle().bundleFingerprint(), fixture.bundle().payloadPolicy(),
                fixture.bundle().attestation(), alteredEvidence, fixture.bundle().rawResponse());
        TestSuiteRunAttestation signed = fixture.bundle().attestation();
        TestSuiteRunAttestation badSignature = new TestSuiteRunAttestation(signed.schemaVersion(),
                signed.signatureStatus(), signed.scope(), signed.suiteRunId(), signed.suiteRef(),
                signed.requestFingerprint(), signed.aggregateEvidenceFingerprint(),
                signed.childEvidenceRefs(), signed.signedAt(), signed.keyId(), signed.algorithm(),
                Base64.getEncoder().encodeToString(new byte[64]), true);
        TestSuiteEvidenceBundle invalidSignature = new TestSuiteEvidenceBundle(
                fixture.bundle().suiteRunId(), fixture.bundle().bundleFingerprint(),
                fixture.bundle().payloadPolicy(), badSignature, fixture.bundle().evidence(),
                fixture.bundle().rawResponse());

        assertThat(new TestSuiteEvidenceVerifier().verify(altered, fixture.key()).reasonCode())
                .isEqualTo("AGGREGATE_FINGERPRINT_INVALID");
        assertThat(new TestSuiteEvidenceVerifier().verify(invalidSignature, fixture.key()).verified())
                .isFalse();
    }

    @Test
    void signedChildClosureOrderIsCheckedAgainstSuiteCaseOrder() throws Exception {
        Fixture fixture = fixture(List.of(
                child("other", "child-run-2", "sha256:" + "f".repeat(64)),
                child("golden", "child-run-1", CHILD)));

        TestSuiteEvidenceVerifier.VerificationResult result =
                new TestSuiteEvidenceVerifier().verify(fixture.bundle(), fixture.key());

        assertThat(result.outcome()).isEqualTo(TestSuiteEvidenceVerifier.Outcome.INVALID);
        assertThat(result.reasonCode()).isEqualTo("CHILD_EVIDENCE_CLOSURE_INVALID");
    }

    @Test
    void missingOrRevokedKeyCannotProduceVerifiedResult() throws Exception {
        Fixture fixture = fixture(List.of(child("golden", "child-run-1", CHILD)));
        EvidenceVerificationKey revoked = new EvidenceVerificationKey(
                fixture.key().schemaVersion(), fixture.key().keyId(), fixture.key().algorithm(),
                fixture.key().encodedPublicKey(), fixture.key().createdAt(), "REVOKED", "test");

        assertThat(new TestSuiteEvidenceVerifier().verify(fixture.bundle(), null).outcome())
                .isEqualTo(TestSuiteEvidenceVerifier.Outcome.KEY_UNAVAILABLE);
        assertThat(new TestSuiteEvidenceVerifier().verify(fixture.bundle(), revoked).outcome())
                .isEqualTo(TestSuiteEvidenceVerifier.Outcome.POLICY_REJECTED);
    }

    private static Fixture fixture(List<TestSuiteRunAttestation.ChildEvidenceRef> children)
            throws Exception {
        KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        ObjectNode evidence = evidence();
        String aggregateFingerprint = fingerprint(evidence);
        ObjectNode material = signatureMaterial(aggregateFingerprint, children);
        String materialFingerprint = fingerprint(material);
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(keyPair.getPrivate());
        signer.update(materialFingerprint.getBytes(StandardCharsets.UTF_8));
        String signature = Base64.getEncoder().encodeToString(signer.sign());
        String keyId = "test-ed25519-1";
        TestSuiteRunAttestation attestation = new TestSuiteRunAttestation(
                TestingProtocol.TEST_SUITE_RUN_ATTESTATION_V1,
                TestSuiteRunAttestation.SignatureStatus.VERIFIED,
                TestSuiteRunAttestation.Scope.TERMINAL, "suite-run-1",
                new TestSuiteRunAttestation.SuiteRef("suite-a", 3, SUITE), REQUEST,
                aggregateFingerprint, children, SIGNED_AT, keyId, "Ed25519", signature, true);
        ObjectNode attestationJson = attestationJson(attestation);
        ObjectNode bundleMaterial = JSON.createObjectNode();
        bundleMaterial.put("payloadPolicy", "OMITTED");
        bundleMaterial.set("attestation", attestationJson);
        bundleMaterial.set("evidence", evidence);
        ObjectNode response = JSON.createObjectNode();
        response.put("schemaVersion", TestingProtocol.TEST_SUITE_EVIDENCE_BUNDLE_V1);
        response.put("suiteRunId", "suite-run-1");
        response.put("bundleFingerprint", fingerprint(bundleMaterial));
        response.put("payloadPolicy", "OMITTED");
        response.set("attestation", attestationJson);
        response.set("evidence", evidence);
        TestSuiteEvidenceBundle bundle = TestSuiteEvidenceBundle.from(response);
        EvidenceVerificationKey key = new EvidenceVerificationKey(
                TestingProtocol.EVIDENCE_VERIFICATION_KEY_V1, keyId, "Ed25519",
                Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()),
                SIGNED_AT.minusSeconds(60), "ACTIVE", "test");
        return new Fixture(bundle, key);
    }

    private static ObjectNode evidence() {
        ObjectNode value = JSON.createObjectNode();
        value.put("schemaVersion", TestingProtocol.TEST_SUITE_RUN_EVIDENCE_V1);
        value.put("suiteRunId", "suite-run-1");
        value.put("clientRequestId", "request-1");
        value.put("status", "PASSED");
        value.put("executionPurpose", "TEST_SUITE_EXECUTION");
        exactRef(value.putObject("suiteRef"), "suiteId", "suite-a", 3, SUITE);
        ObjectNode target = value.putObject("target");
        target.put("kind", "GRAPH");
        target.put("id", "graph-a");
        target.put("fingerprint", TARGET);
        value.put("startedAt", "2026-07-16T10:15:00Z");
        value.put("completedAt", SIGNED_AT.toString());
        ObjectNode result = value.putArray("caseResults").addObject();
        result.put("caseId", "golden");
        result.put("caseType", "GOLDEN");
        exactRef(result.putObject("fixtureBundleRef"), "fixtureBundleId", "fixture-a", 1, FIXTURE);
        result.put("status", "PASSED");
        result.put("runId", "child-run-1");
        result.put("evidenceStatus", "PASSED");
        result.put("evidenceClass", "CERTIFIABLE");
        result.put("assertionsEvaluated", 1);
        result.put("assertionsPassed", 1);
        result.put("diagnosticCode", "");
        result.put("diagnostic", "");
        ObjectNode coverage = value.putObject("coverage");
        coverage.put("status", "SATISFIED");
        coverage.put("minimumCases", 1);
        coverage.put("completedCases", 1);
        coverage.putArray("requiredCaseTypes").add("GOLDEN");
        coverage.putArray("observedCaseTypes").add("GOLDEN");
        coverage.putArray("missingCaseTypes");
        coverage.putArray("requiredInvocationSiteIds");
        coverage.putArray("observedInvocationSiteIds");
        coverage.putArray("missingInvocationSiteIds");
        coverage.putArray("requiredEdgeTransfers");
        coverage.putArray("observedEdgeTransfers");
        coverage.putArray("missingEdgeTransfers");
        coverage.put("minimumAssertionsPerCase", 1);
        coverage.putArray("assertionDensityViolations");
        coverage.putArray("fixtureConsumptionViolations");
        coverage.put("allCasesCompleted", true);
        ObjectNode promotion = value.putObject("promotion");
        promotion.put("status", "ELIGIBLE");
        promotion.putArray("reasons");
        promotion.put("allCasesPassed", true);
        promotion.put("certifiableCases", 1);
        promotion.put("minimumCertifiableCases", 1);
        promotion.put("targetCertificationEligible", true);
        promotion.put("coverageSatisfied", true);
        promotion.put("allCasesCompleted", true);
        value.putArray("diagnostics");
        value.putObject("metadata").put("requestMetadataFingerprint", REQUEST);
        return value;
    }

    private static ObjectNode signatureMaterial(
            String aggregateFingerprint, List<TestSuiteRunAttestation.ChildEvidenceRef> children) {
        ObjectNode value = JSON.createObjectNode();
        value.put("schemaVersion", TestingProtocol.TEST_SUITE_RUN_ATTESTATION_V1);
        value.put("scope", "TERMINAL");
        value.put("suiteRunId", "suite-run-1");
        exactRef(value.putObject("suiteRef"), "suiteId", "suite-a", 3, SUITE);
        value.put("requestFingerprint", REQUEST);
        value.put("aggregateEvidenceFingerprint", aggregateFingerprint);
        ArrayNode refs = value.putArray("childEvidenceRefs");
        children.forEach(child -> {
            ObjectNode ref = refs.addObject();
            ref.put("caseId", child.caseId());
            ref.put("runId", child.runId());
            ref.put("evidenceFingerprint", child.evidenceFingerprint());
        });
        value.put("signedAt", SIGNED_AT.toString());
        return value;
    }

    private static ObjectNode attestationJson(TestSuiteRunAttestation value) {
        ObjectNode node = signatureMaterial(value.aggregateEvidenceFingerprint(),
                value.childEvidenceRefs());
        node.put("signatureStatus", value.signatureStatus().name());
        node.put("keyId", value.keyId());
        node.put("algorithm", value.algorithm());
        node.put("signature", value.signature());
        node.put("independentlyVerifiable", value.independentlyVerifiable());
        return node;
    }

    private static void exactRef(ObjectNode target, String idField, String id,
                                 long revision, String fingerprint) {
        target.put(idField, id);
        target.put("revision", revision);
        target.put("fingerprint", fingerprint);
    }

    private static TestSuiteRunAttestation.ChildEvidenceRef child(
            String caseId, String runId, String evidenceFingerprint) {
        return new TestSuiteRunAttestation.ChildEvidenceRef(caseId, runId, evidenceFingerprint);
    }

    private static String fingerprint(JsonNode value) throws Exception {
        byte[] canonical = JSON.writeValueAsBytes(canonical(value));
        return "sha256:" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(canonical));
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

    private record Fixture(TestSuiteEvidenceBundle bundle, EvidenceVerificationKey key) {
    }
}
