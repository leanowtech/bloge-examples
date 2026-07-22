package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
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

class MirrorEvidenceVerifierTest {
    private static final String RUN_ID = "mirror-run-offline-1";
    private static final String PLAN = fingerprint('1');
    private static final String REQUEST = fingerprint('2');
    private static final String OUTPUT = fingerprint('3');
    private static final String SITE = "/root/loadCustomer#PRIMARY";
    private static final Instant STARTED_AT = Instant.parse("2026-07-22T23:59:58Z");
    private static final Instant COMPLETED_AT = Instant.parse("2026-07-22T23:59:59Z");
    private static final Instant SIGNED_AT = Instant.parse("2026-07-23T00:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();

    private MirrorEvidenceVerifier verifier;
    private KeyPair keyPair;
    private EvidenceVerificationKey key;
    private ObjectNode bundle;

    @BeforeEach
    void setUp() throws Exception {
        verifier = new MirrorEvidenceVerifier();
        keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        key = verificationKey("mirror-key-1", "ACTIVE", keyPair);
        bundle = signedBundle();
    }

    @Test
    void verifiesCompletePayloadFreeEvidenceOffline() {
        MirrorEvidenceVerifier.VerificationResult result = verifier.verify(bundle, key);

        assertThat(result.verified()).isTrue();
        assertThat(result.outcome()).isEqualTo(MirrorEvidenceVerifier.Outcome.VERIFIED);
        assertThat(result.reasonCode()).isEqualTo("VERIFIED");
        assertThat(result.runId()).isEqualTo(RUN_ID);
        assertThat(result.planFingerprint()).isEqualTo(PLAN);
        assertThat(result.bundleFingerprint()).isEqualTo(bundle.path("bundleFingerprint").asText());
        assertThat(result.evidenceFingerprint())
                .isEqualTo(bundle.at("/attestation/evidenceFingerprint").asText());
        assertThat(result.keyId()).isEqualTo(key.keyId());
    }

    @Test
    void validatesStructureAndFingerprintsBeforeReportingAMissingKey() {
        assertThat(verifier.verify(bundle, null).outcome())
                .isEqualTo(MirrorEvidenceVerifier.Outcome.KEY_UNAVAILABLE);

        bundle.withObject("/evidence/nodeTraces/0").putObject("input")
                .put("customerSecret", "must-not-leak");
        MirrorEvidenceVerifier.VerificationResult invalid = verifier.verify(bundle, null);

        assertThat(invalid.outcome()).isEqualTo(MirrorEvidenceVerifier.Outcome.INVALID);
        assertThat(invalid.reasonCode()).isEqualTo("MIRROR_EVIDENCE_SCHEMA_INVALID");
        assertThat(invalid.toString()).doesNotContain("must-not-leak");
    }

    @Test
    void detectsNestedResolutionSealAndAggregateFingerprintTampering() throws Exception {
        bundle.withObject("/evidence/resolutions/0")
                .put("resolutionFingerprint", fingerprint('f'));
        resignAggregate(bundle, false);

        assertThat(verifier.verify(bundle, key).reasonCode())
                .isEqualTo("MIRROR_RESOLUTION_FINGERPRINT_INVALID");

        bundle = signedBundle();
        bundle.withObject("/evidence/nodeTraces/0").put("outputFingerprint", fingerprint('e'));
        refreshBundleFingerprint(bundle);

        assertThat(verifier.verify(bundle, key).reasonCode())
                .isEqualTo("MIRROR_EVIDENCE_FINGERPRINT_INVALID");
    }

    @Test
    void provesExactResolutionClosureForEveryExecutedExternalAttempt() throws Exception {
        bundle.withObject("/evidence").putArray("resolutions");
        resignAggregate(bundle, false);

        assertThat(verifier.verify(bundle, key).reasonCode())
                .isEqualTo("MIRROR_RESOLUTION_CLOSURE_INCOMPLETE");

        bundle = signedBundle();
        ObjectNode duplicate = bundle.withArray("/evidence/resolutions").get(0).deepCopy();
        duplicate.put("attempt", 2);
        bundle.withArray("/evidence/resolutions").add(duplicate);
        resealResolution(duplicate);
        resignAggregate(bundle, false);

        assertThat(verifier.verify(bundle, key).reasonCode())
                .isEqualTo("MIRROR_RESOLUTION_WITHOUT_ATTEMPT");
    }

    @Test
    void rejectsResolutionRequestAndOutputFingerprintsNotBoundToTheAttempt() throws Exception {
        ObjectNode resolution = bundle.withObject("/evidence/resolutions/0");
        resolution.put("requestFingerprint", fingerprint('a'));
        resealResolution(resolution);
        resignAggregate(bundle, false);

        assertThat(verifier.verify(bundle, key).reasonCode())
                .isEqualTo("MIRROR_RESOLUTION_REQUEST_FINGERPRINT_MISMATCH");

        bundle = signedBundle();
        resolution = bundle.withObject("/evidence/resolutions/0");
        resolution.put("outputFingerprint", fingerprint('b'));
        resealResolution(resolution);
        resignAggregate(bundle, false);

        assertThat(verifier.verify(bundle, key).reasonCode())
                .isEqualTo("MIRROR_RESOLUTION_OUTPUT_FINGERPRINT_MISMATCH");
    }

    @Test
    void rejectsPayloadBearingResolutionEvenWhenItIsValidlySigned() throws Exception {
        ObjectNode resolution = bundle.withObject("/evidence/resolutions/0");
        resolution.put("payloadVisibility", "FULL");
        resolution.put("outputIncluded", true);
        resolution.putObject("output").put("customerSecret", "must-not-leak");
        resealResolution(resolution);
        resignAggregate(bundle, false);

        MirrorEvidenceVerifier.VerificationResult result = verifier.verify(bundle, key);

        assertThat(result.reasonCode()).isEqualTo("MIRROR_RESOLUTION_PAYLOAD_POLICY_INVALID");
        assertThat(result.toString()).doesNotContain("must-not-leak");
    }

    @Test
    void rejectsNonCanonicalTraceAndProvenanceOrdering() throws Exception {
        ObjectNode second = bundle.withArray("/evidence/nodeTraces").get(0).deepCopy();
        second.put("nodeId", "earlier");
        second.put("invocationSiteId", "/root/a#PRIMARY");
        second.put("graphPath", "/root");
        second.putArray("attempts");
        bundle.withArray("/evidence/nodeTraces").add(second);
        resignAggregate(bundle, false);

        assertThat(verifier.verify(bundle, key).reasonCode())
                .isEqualTo("MIRROR_NODE_TRACE_ORDER_INVALID");

        bundle = signedBundle();
        ArrayNode rules = bundle.withArray("/evidence/resolutions/0/matchedRuleRefs");
        rules.insert(0, "z-last");
        resealResolution(bundle.withObject("/evidence/resolutions/0"));
        resignAggregate(bundle, false);

        assertThat(verifier.verify(bundle, key).reasonCode())
                .isEqualTo("MIRROR_RESOLUTION_PROVENANCE_ORDER_INVALID");
    }

    @Test
    void appliesSigningTimeAlgorithmAndKeyLifecyclePolicy() throws Exception {
        EvidenceVerificationKey wrongId = verificationKey("other-key", "ACTIVE", keyPair);
        assertThat(verifier.verify(bundle, wrongId).reasonCode())
                .isEqualTo("MIRROR_VERIFICATION_KEY_ID_MISMATCH");

        EvidenceVerificationKey disabled = verificationKey("mirror-key-1", "DISABLED", keyPair);
        assertThat(verifier.verify(bundle, disabled).outcome())
                .isEqualTo(MirrorEvidenceVerifier.Outcome.POLICY_REJECTED);

        EvidenceVerificationKey wrongAlgorithm = new EvidenceVerificationKey(
                TestingProtocol.EVIDENCE_VERIFICATION_KEY_V1, "mirror-key-1", "RSA",
                key.encodedPublicKey(), key.createdAt(), "ACTIVE", "test");
        assertThat(verifier.verify(bundle, wrongAlgorithm).reasonCode())
                .isEqualTo("MIRROR_SIGNATURE_ALGORITHM_REJECTED");

        ObjectNode attestation = bundle.withObject("/attestation");
        attestation.put("signedAt", COMPLETED_AT.minusSeconds(1).toString());
        resignAttestation(attestation);
        refreshBundleFingerprint(bundle);
        assertThat(verifier.verify(bundle, key).reasonCode())
                .isEqualTo("MIRROR_ATTESTATION_TIME_INVALID");
    }

    @Test
    void detectsSignatureTamperingAfterAllMaterialChecksPass() throws Exception {
        ObjectNode attestation = bundle.withObject("/attestation");
        signAttestation(attestation,
                KeyPairGenerator.getInstance("Ed25519").generateKeyPair());
        refreshBundleFingerprint(bundle);

        assertThat(verifier.verify(bundle, key).reasonCode())
                .isEqualTo("MIRROR_ATTESTATION_SIGNATURE_INVALID");
    }

    private ObjectNode signedBundle() throws Exception {
        ObjectNode evidence = evidence();
        ObjectNode resolution = evidence.withArray("resolutions").get(0).deepCopy();
        resealResolution(resolution);
        evidence.withArray("resolutions").set(0, resolution);
        String evidenceFingerprint = EvidenceVerificationSupport.sha256(evidence);

        ObjectNode attestation = JSON.createObjectNode();
        attestation.put("schemaVersion", CapabilityMirrorProtocol.MIRROR_EVIDENCE_ATTESTATION_V1);
        attestation.put("signatureStatus", "VERIFIED");
        attestation.put("runId", RUN_ID);
        attestation.put("planFingerprint", PLAN);
        attestation.put("evidenceFingerprint", evidenceFingerprint);
        attestation.put("signedAt", SIGNED_AT.toString());
        attestation.put("keyId", key.keyId());
        attestation.put("algorithm", "Ed25519");
        attestation.put("signature", "");
        attestation.put("independentlyVerifiable", true);
        resignAttestation(attestation);

        ObjectNode value = JSON.createObjectNode();
        value.put("schemaVersion", CapabilityMirrorProtocol.MIRROR_EVIDENCE_BUNDLE_V1);
        value.put("bundleFingerprint", "");
        value.put("payloadPolicy", "HASH_ONLY");
        value.set("attestation", attestation);
        value.set("evidence", evidence);
        refreshBundleFingerprint(value);
        return value;
    }

    private ObjectNode evidence() {
        ObjectNode value = JSON.createObjectNode();
        value.put("schemaVersion", CapabilityMirrorProtocol.MIRROR_RUN_EVIDENCE_V1);
        value.put("runId", RUN_ID);
        value.put("requestId", "mirror-request-1");
        value.put("requestContextFingerprint", fingerprint('4'));
        value.put("planId", "support-plan");
        value.put("planFingerprint", PLAN);
        value.put("capabilityClosureFingerprint", fingerprint('5'));
        value.put("executionControlFingerprint", fingerprint('6'));
        ObjectNode root = artifactRef("CAPABILITY", "graph:support", 7, fingerprint('7'));
        ObjectNode fixture = artifactRef("FIXTURE_BUNDLE", "support-fixtures", 3,
                fingerprint('8'));
        ObjectNode external = artifactRef("CAPABILITY", "resource:customer.lookup", 4,
                fingerprint('9'));
        value.set("rootCapability", root);
        value.set("fixtureBundleRef", fixture);
        ObjectNode binding = value.putArray("externalBindings").addObject();
        binding.set("parentCapabilityRef", root.deepCopy());
        binding.put("dependencyNodeId", "loadCustomer");
        binding.set("capabilityRef", external.deepCopy());
        binding.put("invocationSiteId", SITE);
        binding.put("graphPath", "/root");
        value.putObject("scope")
                .put("tenantId", "tenant-a")
                .put("organizationId", "org-a")
                .put("projectId", "support")
                .put("environmentId", "test")
                .put("region", "sg");
        value.put("authorizedPurpose", "MIRROR_REHEARSAL");
        value.put("status", "PASSED");
        value.put("evidenceClass", "EXPLORATORY");
        value.put("semanticResultFingerprint", fingerprint('a'));
        value.put("startedAt", STARTED_AT.toString());
        value.put("completedAt", COMPLETED_AT.toString());

        ObjectNode node = value.putArray("nodeTraces").addObject();
        node.put("nodeId", "loadCustomer");
        node.put("operatorRef", "customer.lookup");
        node.put("status", "MOCKED");
        node.put("fidelity", "OUTPUT_LEVEL");
        node.put("inputFingerprint", REQUEST);
        node.put("outputFingerprint", OUTPUT);
        node.put("errorCode", "");
        node.put("durationMs", 4);
        node.put("invocationSiteId", SITE);
        node.put("graphPath", "/root");
        node.put("correlationKey", "C-1");
        node.put("occurrence", 1);
        node.put("graphOccurrence", 1);
        node.putArray("attempts").addObject()
                .put("attempt", 1)
                .put("status", "MOCKED")
                .put("fidelity", "OUTPUT_LEVEL")
                .put("inputFingerprint", REQUEST)
                .put("outputFingerprint", OUTPUT)
                .put("errorCode", "")
                .put("durationMs", 4);

        value.putArray("edgeTraces").addObject()
                .put("edgeId", "loadCustomer->format")
                .put("status", "TRANSFERRED")
                .put("valueFingerprint", OUTPUT)
                .put("graphPath", "/root")
                .put("correlationKey", "C-1")
                .put("graphOccurrence", 1)
                .put("fromInvocationSiteId", SITE)
                .put("toInvocationSiteId", "/root/format#PRIMARY");
        value.putArray("resolutions").add(resolution(external, fixture));

        ObjectNode isolation = value.putObject("isolation");
        isolation.put("engineMode", "INDEPENDENT_TEST_ENGINE");
        isolation.putArray("interceptorTypes");
        isolation.putArray("listenerTypes").add("InvocationRecorder");
        isolation.put("durableStoresAttached", false);
        isolation.put("productionContextCarriersAttached", false);
        isolation.put("productionExtensionListenersAttached", false);
        isolation.put("realExternalCallsAllowed", false);
        isolation.put("externalCredentialsAllowed", false);
        isolation.put("networkEgressAllowed", false);
        isolation.put("deploymentEgressEnforced", false);
        isolation.putNull("deploymentIsolationRef");
        isolation.putArray("limitations").add("DEPLOYMENT_EGRESS_NOT_ATTESTED");
        value.putArray("limitations").add("DEPLOYMENT_EGRESS_NOT_ATTESTED");
        return value;
    }

    private ObjectNode resolution(ObjectNode external, ObjectNode fixture) {
        ObjectNode value = JSON.createObjectNode();
        value.put("schemaVersion", CapabilityMirrorProtocol.MIRROR_RESOLUTION_V1);
        value.put("resolutionFingerprint", "");
        value.put("runId", RUN_ID);
        value.put("planFingerprint", PLAN);
        value.set("capabilityRef", external.deepCopy());
        value.put("invocationSiteId", SITE);
        value.put("graphPath", "/root");
        value.put("correlationKey", "C-1");
        value.put("occurrence", 1);
        value.put("attempt", 1);
        value.put("requestFingerprint", REQUEST);
        value.put("status", "RESOLVED");
        value.put("source", "OWNER_SPECIFIED");
        value.put("payloadVisibility", "HASH_ONLY");
        value.put("outputIncluded", false);
        value.putNull("output");
        value.put("outputFingerprint", OUTPUT);
        value.putNull("error");
        value.putArray("matchedArtifactRefs").add(fixture.deepCopy());
        value.putArray("matchedRuleRefs").add("customer-response");
        value.putObject("confidence")
                .put("point", 1)
                .put("lowerBound", 1)
                .put("upperBound", 1)
                .put("method", "owner-rule-v1");
        value.put("freshness", 1);
        value.putArray("limitations").add("PAYLOAD_HASH_ONLY");
        return value;
    }

    private void resignAggregate(ObjectNode value, boolean resealResolutions) throws Exception {
        if (resealResolutions) {
            for (JsonNode resolution : value.withArray("/evidence/resolutions")) {
                resealResolution((ObjectNode) resolution);
            }
        }
        ObjectNode evidence = value.withObject("/evidence");
        ObjectNode attestation = value.withObject("/attestation");
        attestation.put("evidenceFingerprint", EvidenceVerificationSupport.sha256(evidence));
        resignAttestation(attestation);
        refreshBundleFingerprint(value);
    }

    private void resignAttestation(ObjectNode attestation) throws Exception {
        signAttestation(attestation, keyPair);
    }

    private static void signAttestation(ObjectNode attestation, KeyPair signingKey)
            throws Exception {
        ObjectNode material = JSON.createObjectNode();
        material.put("domain", "RESOURCE_GATEWAY_MIRROR_EVIDENCE_V1");
        material.put("schemaVersion", CapabilityMirrorProtocol.MIRROR_EVIDENCE_ATTESTATION_V1);
        material.put("runId", attestation.path("runId").asText());
        material.put("planFingerprint", attestation.path("planFingerprint").asText());
        material.put("evidenceFingerprint", attestation.path("evidenceFingerprint").asText());
        material.put("signedAt", attestation.path("signedAt").asText());
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(signingKey.getPrivate());
        signer.update(EvidenceVerificationSupport.sha256(material)
                .getBytes(StandardCharsets.UTF_8));
        attestation.put("signature", Base64.getEncoder().encodeToString(signer.sign()));
    }

    private static void refreshBundleFingerprint(ObjectNode value) {
        ObjectNode material = JSON.createObjectNode();
        material.set("schemaVersion", value.path("schemaVersion"));
        material.set("payloadPolicy", value.path("payloadPolicy"));
        material.set("attestation", value.path("attestation"));
        material.set("evidence", value.path("evidence"));
        value.put("bundleFingerprint", EvidenceVerificationSupport.sha256(material));
    }

    private static void resealResolution(ObjectNode resolution) {
        resolution.put("resolutionFingerprint", "");
        resolution.put("resolutionFingerprint", EvidenceVerificationSupport.sha256(resolution));
    }

    private static ObjectNode artifactRef(
            String kind, String id, long revision, String fingerprint) {
        ObjectNode value = JSON.createObjectNode();
        value.put("kind", kind);
        value.put("id", id);
        value.put("revision", revision);
        value.put("fingerprint", fingerprint);
        return value;
    }

    private static EvidenceVerificationKey verificationKey(
            String keyId, String state, KeyPair pair) {
        return new EvidenceVerificationKey(TestingProtocol.EVIDENCE_VERIFICATION_KEY_V1,
                keyId, "Ed25519", Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()),
                Instant.parse("2026-07-22T23:00:00Z"), state, "test");
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
