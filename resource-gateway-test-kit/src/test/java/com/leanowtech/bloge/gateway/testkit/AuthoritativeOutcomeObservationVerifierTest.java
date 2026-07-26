package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class AuthoritativeOutcomeObservationVerifierTest {
    private static final Instant CREATED_AT =
            Instant.parse("2026-07-01T00:00:00Z");
    private static final Instant ACTION_AT =
            Instant.parse("2026-07-02T00:00:00Z");
    private static final Instant CLOSES_AT =
            Instant.parse("2026-07-09T00:00:00Z");
    private static final Instant RECONCILED_AT =
            Instant.parse("2026-07-10T00:00:00Z");
    private static final Instant SIGNED_AT =
            Instant.parse("2026-07-10T00:00:01Z");

    private AuthoritativeOutcomeObservationVerifier verifier;
    private KeyPair keyPair;
    private EvidenceVerificationKey key;
    private ObjectNode observation;

    @BeforeEach
    void setUp() throws Exception {
        verifier =
                new AuthoritativeOutcomeObservationVerifier();
        keyPair = KeyPairGenerator.getInstance(
                "Ed25519").generateKeyPair();
        key = new EvidenceVerificationKey(
                TestingProtocol.EVIDENCE_VERIFICATION_KEY_V1,
                "outcome-observation-key-1",
                "Ed25519",
                Base64.getEncoder().encodeToString(
                        keyPair.getPublic().getEncoded()),
                CREATED_AT,
                "ACTIVE",
                "test");
        observation = observation();
        resign();
    }

    @Test
    void verifiesDerivationAddressSignatureAndExternalAuthorityClosure() {
        AuthoritativeOutcomeObservationVerifier
                .VerificationResult result =
                verifier.verify(
                        observation,
                        key,
                        candidate -> true);

        assertThat(result.verified())
                .as(result.reasonCode())
                .isTrue();
        assertThat(result.observationId())
                .isEqualTo("outcome-refund-boundary");
        assertThat(result.unitId())
                .isEqualTo("refund-boundary");
        assertThat(result.reconciliation())
                .isEqualTo("MATCH");
    }

    @Test
    void refusesToTreatTheGatewaySealAsBusinessAuthority() {
        assertThat(verifier.verify(
                observation,
                key,
                null).outcome())
                .isEqualTo(
                        AuthoritativeOutcomeObservationVerifier
                                .Outcome.AUTHORITY_UNAVAILABLE);
        assertThat(verifier.verify(
                observation,
                key,
                candidate -> false).reasonCode())
                .isEqualTo(
                        "OUTCOME_AUTHORITY_CLOSURE_REJECTED");
    }

    @Test
    void rejectsInvalidGatewaySealBeforeCallingExternalAuthority() {
        observation.withObject("/observationSeal")
                .put(
                        "signature",
                        Base64.getEncoder()
                                .encodeToString(
                                        new byte[64]));
        AtomicBoolean authorityCalled =
                new AtomicBoolean();

        AuthoritativeOutcomeObservationVerifier
                .VerificationResult result =
                verifier.verify(
                        observation,
                        key,
                        candidate -> {
                            authorityCalled.set(true);
                            return true;
                        });

        assertThat(result.reasonCode())
                .isEqualTo(
                        "OUTCOME_OBSERVATION_SIGNATURE_INVALID");
        assertThat(authorityCalled).isFalse();
    }

    @Test
    void rejectsResignedProducerReconciliationAndAttributionDrift()
            throws Exception {
        observation.put("reconciliation", "MISMATCH");
        resign();
        assertThat(verifier.verify(
                observation,
                key,
                candidate -> true).reasonCode())
                .isEqualTo(
                        "OUTCOME_RECONCILIATION_DERIVATION_INVALID");

        observation = observation();
        ((ObjectNode) observation.path(
                "authorityFacts").get(0))
                .put(
                        "subjectFingerprint",
                        fingerprint('f'));
        resign();
        assertThat(verifier.verify(
                observation,
                key,
                candidate -> true).reasonCode())
                .isEqualTo(
                        "OUTCOME_ATTRIBUTION_CLOSURE_INVALID");
    }

    @Test
    void rejectsActionTimeSelectionAndReorderedDuplicateAuthorityRefs()
            throws Exception {
        observation.withObject("/selectionProof")
                .put(
                        "selectedAt",
                        ACTION_AT.toString());
        resign();
        assertThat(verifier.verify(
                observation,
                key,
                candidate -> true).reasonCode())
                .isEqualTo(
                        "OUTCOME_COHORT_SELECTION_INVALID");

        observation = observation();
        ObjectNode duplicate = JsonNodeFactory.instance
                .objectNode();
        duplicate.put(
                "authorityId",
                "warehouse-ledger");
        ObjectNode reordered = duplicate.putObject(
                "watermarkRef");
        reordered.put(
                "fingerprint",
                fingerprint('c'));
        reordered.put("revision", 1);
        reordered.put(
                "id",
                "settlement-watermark");
        reordered.put(
                "kind",
                "AUTHORITATIVE_OUTCOME_SOURCE_WATERMARK");
        duplicate.put(
                "eventTimeThrough",
                CLOSES_AT.toString());
        duplicate.put(
                "publishedAt",
                RECONCILED_AT.minusSeconds(1)
                        .toString());
        observation.withArray(
                        "/authorityWatermarks")
                .add(duplicate);
        resign();

        assertThat(verifier.verify(
                observation,
                key,
                candidate -> true).reasonCode())
                .isEqualTo(
                        "OUTCOME_AUTHORITY_WATERMARK_INVALID");
    }

    @Test
    void packagesTheStrictSchema() {
        assertThat(getClass().getResource(
                CapabilityMirrorProtocol
                        .AUTHORITATIVE_OUTCOME_OBSERVATION_SCHEMA_RESOURCE))
                .isNotNull();
        CapabilityMirrorSchemaValidator.require(
                observation,
                CapabilityMirrorProtocol
                        .AUTHORITATIVE_OUTCOME_OBSERVATION_SCHEMA_RESOURCE,
                "RG.MIRROR.CLIENT.OUTCOME_OBSERVATION_SCHEMA_INVALID");
    }

    @Test
    void rejectsAValidSignatureWithAFutureBoundAttestationTime()
            throws Exception {
        observation.put(
                "attestedAt",
                SIGNED_AT.plusSeconds(300)
                        .toString());
        resign();

        assertThat(verifier.verify(
                observation,
                key,
                candidate -> true,
                SIGNED_AT).reasonCode())
                .isEqualTo(
                        "OUTCOME_OBSERVATION_KEY_POLICY_REJECTED");
    }

    @Test
    void rejectsDetachedSealTimeOutsideTheSignedAttestationWindow() {
        observation.withObject("/observationSeal")
                .put(
                        "signedAt",
                        SIGNED_AT.plusSeconds(121)
                                .toString());

        assertThat(verifier.verify(
                observation,
                key,
                candidate -> true,
                SIGNED_AT.plusSeconds(121))
                .reasonCode())
                .isEqualTo(
                        "OUTCOME_OBSERVATION_SEAL_TIME_INVALID");
    }

    @Test
    void rejectsEquivalentButNonCanonicalTimestampEncoding()
            throws Exception {
        observation.put(
                "attestedAt",
                "2026-07-10T00:00:01+00:00");
        resign();

        assertThat(verifier.verify(
                observation,
                key,
                candidate -> true,
                SIGNED_AT).reasonCode())
                .isEqualTo(
                        "OUTCOME_ATTESTATION_TIME_INVALID");
    }

    @Test
    void sanitizesUntrustedCoordinatesBeforeReturningSchemaFailure() {
        observation.put(
                "observationId",
                "outcome\nforged-log-line");
        observation.withObject("/observationSeal")
                .put(
                        "keyId",
                        "key\rforged-log-line");

        AuthoritativeOutcomeObservationVerifier
                .VerificationResult result =
                verifier.verify(
                        observation,
                        key,
                        candidate -> true);

        assertThat(result.reasonCode())
                .isEqualTo(
                        "OUTCOME_OBSERVATION_SCHEMA_INVALID");
        assertThat(result.observationId())
                .isEqualTo(
                        "outcome?forged-log-line");
        assertThat(result.keyId())
                .isEqualTo(
                        "key?forged-log-line");
    }

    @Test
    void ordersFactsByInstantRatherThanTimestampText()
            throws Exception {
        ObjectNode later = fact().deepCopy();
        later.set(
                "sourceRef",
                ref(
                        "AUTHORITATIVE_OUTCOME_SOURCE_RECORD",
                        "settlement-002",
                        'e'));
        later.put(
                "occurredAt",
                CLOSES_AT.minusSeconds(1)
                        .plusMillis(100)
                        .toString());
        later.put(
                "recordedAt",
                CLOSES_AT.plusSeconds(31)
                        .toString());
        observation.withArray("/authorityFacts")
                .add(later);
        resign();

        assertThat(verifier.verify(
                observation,
                key,
                candidate -> true).verified())
                .isTrue();
    }

    private ObjectNode observation() {
        ObjectNode value =
                JsonNodeFactory.instance.objectNode();
        value.put(
                "schemaVersion",
                CapabilityMirrorProtocol
                        .AUTHORITATIVE_OUTCOME_OBSERVATION_V1);
        value.put(
                "observationId",
                "outcome-refund-boundary");
        value.put("revision", 1);
        value.put("observationFingerprint", "");
        value.set("scope", scope());
        value.set(
                "inventoryRef",
                ref(
                        "DOMAIN_FIDELITY_INVENTORY",
                        "refund-support",
                        '1'));
        value.put("unitId", "refund-boundary");
        value.set(
                "scenarioCaseRef",
                ref(
                        "SCENARIO_CASE",
                        "refund-boundary",
                        '2'));
        value.set(
                "targetCapabilityRef",
                ref("CAPABILITY", "refund", '3'));
        value.set(
                "outcomeDefinitionRef",
                ref(
                        "OUTCOME_DEFINITION",
                        "refund-settled",
                        '4'));
        value.set(
                "attributionPolicyRef",
                ref(
                        "OUTCOME_ATTRIBUTION_POLICY",
                        "refund-seven-day-window",
                        '5'));
        value.set(
                "authoritySetRef",
                ref(
                        "OUTCOME_AUTHORITY_SET",
                        "refund-ledgers",
                        '6'));
        value.set("selectionProof", selectionProof());
        value.put("subjectFingerprint", fingerprint('a'));
        value.put(
                "attributionKeyFingerprint",
                fingerprint('b'));
        value.put(
                "modelOutcomeFingerprint",
                fingerprint('a'));
        value.set(
                "attributionWindow",
                attributionWindow());
        value.put(
                "reconciledAt",
                RECONCILED_AT.toString());
        value.put(
                "attestedAt",
                SIGNED_AT.toString());
        ArrayNode watermarks =
                value.putArray("authorityWatermarks");
        watermarks.add(watermark());
        ArrayNode facts = value.putArray("authorityFacts");
        facts.add(fact());
        value.put("reconciliation", "MATCH");
        value.put("evidenceComplete", true);
        value.set("observationSeal", unsignedSeal());
        return value;
    }

    private static ObjectNode scope() {
        ObjectNode value =
                JsonNodeFactory.instance.objectNode();
        value.put("tenantId", "tenant-a");
        value.put("organizationId", "support");
        value.put("projectId", "refunds");
        value.put("environmentId", "staging");
        value.put("region", "sg");
        return value;
    }

    private static ObjectNode selectionProof() {
        ObjectNode value =
                JsonNodeFactory.instance.objectNode();
        value.set(
                "cohortRef",
                ref(
                        "OUTCOME_CALIBRATION_COHORT",
                        "refunds-2026-07",
                        '7'));
        value.set(
                "samplingFrameRef",
                ref(
                        "OUTCOME_SAMPLING_FRAME",
                        "eligible-refunds-2026-07",
                        '8'));
        value.put(
                "stratumId",
                "amount-band-100-500");
        value.put(
                "inclusionFingerprint",
                fingerprint('9'));
        value.put(
                "selectedAt",
                ACTION_AT.minusSeconds(1).toString());
        value.put("eligiblePopulationSize", 10_000);
        value.put("selectedPopulationSize", 1_000);
        value.put("sampleOrdinal", 42);
        value.put("selectionMode", "HASH_PARTITION");
        return value;
    }

    private static ObjectNode attributionWindow() {
        ObjectNode value =
                JsonNodeFactory.instance.objectNode();
        value.put(
                "actionOccurredAt",
                ACTION_AT.toString());
        value.put("opensAt", ACTION_AT.toString());
        value.put("closesAt", CLOSES_AT.toString());
        return value;
    }

    private static ObjectNode watermark() {
        ObjectNode value =
                JsonNodeFactory.instance.objectNode();
        value.put("authorityId", "settlement-ledger");
        value.set(
                "watermarkRef",
                ref(
                        "AUTHORITATIVE_OUTCOME_SOURCE_WATERMARK",
                        "settlement-watermark",
                        'c'));
        value.put(
                "eventTimeThrough",
                CLOSES_AT.toString());
        value.put(
                "publishedAt",
                RECONCILED_AT.minusSeconds(1)
                        .toString());
        return value;
    }

    private static ObjectNode fact() {
        ObjectNode value =
                JsonNodeFactory.instance.objectNode();
        value.put("authorityId", "settlement-ledger");
        value.set(
                "sourceRef",
                ref(
                        "AUTHORITATIVE_OUTCOME_SOURCE_RECORD",
                        "settlement-001",
                        'd'));
        value.put("subjectFingerprint", fingerprint('a'));
        value.put(
                "attributionKeyFingerprint",
                fingerprint('b'));
        value.put("outcomeFingerprint", fingerprint('a'));
        value.put(
                "occurredAt",
                CLOSES_AT.minusSeconds(1).toString());
        value.put(
                "recordedAt",
                CLOSES_AT.plusSeconds(30).toString());
        value.put("evidenceComplete", true);
        return value;
    }

    private static ObjectNode ref(
            String kind,
            String id,
            char material) {
        ObjectNode value =
                JsonNodeFactory.instance.objectNode();
        value.put("kind", kind);
        value.put("id", id);
        value.put("revision", 1);
        value.put("fingerprint", fingerprint(material));
        return value;
    }

    private static ObjectNode unsignedSeal() {
        ObjectNode value =
                JsonNodeFactory.instance.objectNode();
        value.put(
                "schemaVersion",
                "bloge.visualRunEvidenceSeal.v1");
        value.put("materialFingerprint", "");
        value.put("algorithm", "");
        value.put("keyId", "");
        value.put(
                "signedAt",
                Instant.EPOCH.toString());
        value.put("signature", "");
        return value;
    }

    private void resign() throws Exception {
        observation.put(
                "observationFingerprint",
                EvidenceVerificationSupport
                        .sha256Bounded(
                                AuthoritativeOutcomeObservationVerifier
                                        .producerFingerprintMaterial(
                                                observation),
                                AuthoritativeOutcomeObservationVerifier
                                        .MAXIMUM_OBSERVATION_BYTES));
        String material =
                EvidenceVerificationSupport
                        .sha256Bounded(
                                AuthoritativeOutcomeObservationVerifier
                                        .attestationMaterial(
                                                observation),
                                AuthoritativeOutcomeObservationVerifier
                                        .MAXIMUM_ATTESTATION_BYTES);
        Signature signer =
                Signature.getInstance("Ed25519");
        signer.initSign(keyPair.getPrivate());
        signer.update(
                material.getBytes(
                        StandardCharsets.UTF_8));
        ObjectNode seal = observation.withObject(
                "/observationSeal");
        seal.put("materialFingerprint", material);
        seal.put("algorithm", "Ed25519");
        seal.put("keyId", key.keyId());
        seal.put("signedAt", SIGNED_AT.toString());
        seal.put(
                "signature",
                Base64.getEncoder().encodeToString(
                        signer.sign()));
    }

    private static String fingerprint(char material) {
        char safe = Character.toLowerCase(material);
        if (safe < 'a' || safe > 'f') {
            safe = 'a';
        }
        return "sha256:" + String.valueOf(safe).repeat(64);
    }
}
