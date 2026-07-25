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

import static org.assertj.core.api.Assertions.assertThat;

class DomainFidelityProfileVerifierTest {
    private static final Instant APPROVED_AT =
            Instant.parse("2026-07-01T00:00:00Z");
    private static final Instant EFFECTIVE_AT =
            Instant.parse("2026-07-02T00:00:00Z");
    private static final Instant OBSERVED_AT =
            Instant.parse("2026-07-24T00:00:00Z");
    private static final Instant MEASURED_AT =
            Instant.parse("2026-07-25T00:00:00Z");
    private static final Instant VALID_UNTIL =
            Instant.parse("2026-08-23T00:00:00Z");
    private static final Instant INVENTORY_EXPIRES_AT =
            Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant SIGNED_AT =
            Instant.parse("2026-07-25T00:00:01Z");

    private DomainFidelityProfileVerifier verifier;
    private KeyPair keyPair;
    private EvidenceVerificationKey key;
    private ObjectNode inventory;
    private ObjectNode profile;

    @BeforeEach
    void setUp() throws Exception {
        verifier = new DomainFidelityProfileVerifier();
        keyPair = KeyPairGenerator.getInstance(
                "Ed25519").generateKeyPair();
        key = new EvidenceVerificationKey(
                TestingProtocol.EVIDENCE_VERIFICATION_KEY_V1,
                "fidelity-profile-key-1",
                "Ed25519",
                Base64.getEncoder().encodeToString(
                        keyPair.getPublic().getEncoded()),
                APPROVED_AT,
                "ACTIVE",
                "test");
        inventory = inventory();
        profile = profile(inventory);
        resign(profile, SIGNED_AT);
    }

    @Test
    void verifiesCompleteProfileWithoutTrustingServerClasses()
            throws Exception {
        DomainFidelityProfileVerifier.VerificationResult result =
                verifier.verify(profile, inventory, key);

        assertThat(result.verified())
                .as(result.reasonCode())
                .isTrue();
        assertThat(result.outcome()).isEqualTo(
                DomainFidelityProfileVerifier.Outcome.VERIFIED);
        assertThat(result.domainId())
                .isEqualTo("customer-service");
        assertThat(result.assessment())
                .isEqualTo("COMPLETE");
        assertThat(result.limitations()).isEmpty();
        assertThat(profile.has("score")).isFalse();
    }

    @Test
    void rejectsValidlySignedForgedWilsonInterval()
            throws Exception {
        ObjectNode confidence = (ObjectNode) profile.at(
                "/dimensions/0/confidence");
        confidence.put("lowerBound", 0.9d);
        resign(profile, SIGNED_AT);

        assertThat(verifier.verify(profile, inventory, key)
                .reasonCode()).isEqualTo(
                "DOMAIN_FIDELITY_DERIVED_ARITHMETIC_INVALID");
    }

    @Test
    void rejectsValidlySignedPolicyExpiryExtension()
            throws Exception {
        ((ObjectNode) profile.at("/unitAssessments/0"))
                .put(
                        "expiresAt",
                        "2026-09-23T00:00:00Z");
        profile.put(
                "validUntil",
                "2026-09-23T00:00:00Z");
        resign(profile, SIGNED_AT);

        assertThat(verifier.verify(profile, inventory, key)
                .reasonCode()).isEqualTo(
                "DOMAIN_FIDELITY_SOURCE_TIME_INVALID");
    }

    @Test
    void rejectsValidlySignedRepairedCoverageDenominator()
            throws Exception {
        profile.withObject("/denominator")
                .put("totalObligations", 1);
        profile.withObject("/abstentionDebt")
                .put("totalObligations", 1);
        resign(profile, SIGNED_AT);

        assertThat(verifier.verify(profile, inventory, key)
                .reasonCode()).isEqualTo(
                "DOMAIN_FIDELITY_DERIVED_ARITHMETIC_INVALID");
    }

    @Test
    void rejectsInventoryTamperBeforeProfileInterpretation() {
        inventory.withObject("/units/0/targetCapabilityRef")
                .put("id", "different-capability");

        assertThat(verifier.verify(profile, inventory, key)
                .reasonCode()).isEqualTo(
                "DOMAIN_FIDELITY_INVENTORY_FINGERPRINT_INVALID");
    }

    @Test
    void rejectsUnknownSourceTrustBoundaryEvenWhenResigned()
            throws Exception {
        profile.withObject("/unitAssessments/0/sourceRef")
                .put("kind", "UNVERIFIED_REPORT");
        resign(profile, SIGNED_AT);

        assertThat(verifier.verify(profile, inventory, key)
                .reasonCode()).isEqualTo(
                "DOMAIN_FIDELITY_SCHEMA_INVALID");
    }

    @Test
    void strictSchemaRejectsPayloadAndCompositeScore()
            throws Exception {
        profile.put("score", 0.99d);
        profile.withObject("/unitAssessments/0")
                .put("rawPayload", "customer-secret");
        resign(profile, SIGNED_AT);

        assertThat(verifier.verify(profile, inventory, key)
                .reasonCode()).isEqualTo(
                "DOMAIN_FIDELITY_SCHEMA_INVALID");
    }

    @Test
    void reportsUnavailableAndMismatchedKeysWithoutPayload() {
        assertThat(verifier.verify(
                profile, inventory, null).outcome())
                .isEqualTo(
                        DomainFidelityProfileVerifier.Outcome
                                .KEY_UNAVAILABLE);

        EvidenceVerificationKey wrong =
                new EvidenceVerificationKey(
                        TestingProtocol
                                .EVIDENCE_VERIFICATION_KEY_V1,
                        "different-key",
                        "Ed25519",
                        key.encodedPublicKey(),
                        key.createdAt(),
                        "ACTIVE",
                        "test");
        DomainFidelityProfileVerifier.VerificationResult result =
                verifier.verify(profile, inventory, wrong);
        assertThat(result.outcome()).isEqualTo(
                DomainFidelityProfileVerifier.Outcome.INVALID);
        assertThat(result.reasonCode()).isEqualTo(
                "DOMAIN_FIDELITY_VERIFICATION_KEY_ID_MISMATCH");
        assertThat(result.toString())
                .doesNotContain("customer-secret");
    }

    @Test
    void rejectsSealPredatingMeasurementAsPolicyViolation()
            throws Exception {
        resign(
                profile,
                MEASURED_AT.minusSeconds(1));

        assertThat(verifier.verify(profile, inventory, key)
                .outcome()).isEqualTo(
                DomainFidelityProfileVerifier.Outcome
                        .POLICY_REJECTED);
    }

    @Test
    void packagesBothSchemasAndTheirReferenceClosure() {
        assertThat(
                DomainFidelityProfileVerifierTest.class
                        .getResource(
                                CapabilityMirrorProtocol
                                        .DOMAIN_FIDELITY_INVENTORY_SCHEMA_RESOURCE))
                .isNotNull();
        assertThat(
                DomainFidelityProfileVerifierTest.class
                        .getResource(
                                CapabilityMirrorProtocol
                                        .DOMAIN_FIDELITY_PROFILE_SCHEMA_RESOURCE))
                .isNotNull();
        CapabilityMirrorSchemaValidator.require(
                inventory,
                CapabilityMirrorProtocol
                        .DOMAIN_FIDELITY_INVENTORY_SCHEMA_RESOURCE,
                "INVENTORY_INVALID");
        CapabilityMirrorSchemaValidator.require(
                profile,
                CapabilityMirrorProtocol
                        .DOMAIN_FIDELITY_PROFILE_SCHEMA_RESOURCE,
                "PROFILE_INVALID");
    }

    private static ObjectNode inventory() {
        ObjectNode value =
                JsonNodeFactory.instance.objectNode();
        value.put(
                "schemaVersion",
                CapabilityMirrorProtocol
                        .DOMAIN_FIDELITY_INVENTORY_V1);
        value.put("inventoryId", "customer-service-v1");
        value.put("revision", 1);
        value.put("fingerprint", "");
        value.set("scope", scope());
        value.put("domainId", "customer-service");
        value.set(
                "taxonomyRef",
                ref(
                        "DOMAIN_FIDELITY_TAXONOMY",
                        "fidelity-taxonomy",
                        '1'));
        ArrayNode units = value.putArray("units");
        ObjectNode unit = units.addObject();
        unit.put("unitId", "refund-golden");
        unit.set(
                "scenarioCaseRef",
                ref("SCENARIO_CASE", "refund-golden", '2'));
        unit.set(
                "targetCapabilityRef",
                ref("CAPABILITY", "refund", '3'));
        unit.put("caseType", "GOLDEN");
        unit.putArray("requiredDimensions")
                .add("BEHAVIOR")
                .add("CONTRACT");
        ObjectNode provenance =
                value.putObject("provenance");
        provenance.put(
                "schemaVersion",
                CapabilityMirrorProtocol
                        .ARTIFACT_PROVENANCE_V1);
        provenance.put("sourceType", "OWNER");
        provenance.putArray("sourceRefs");
        provenance.put("tenantId", "tenant-a");
        provenance.put(
                "purpose",
                "Approved customer-service fidelity denominator");
        provenance.putNull("sampleFrom");
        provenance.putNull("sampleTo");
        provenance.putNull("sampleCount");
        provenance.putNull("confidence");
        provenance.putArray("biasRisks");
        provenance.put("approvedBy", "domain-owner-a");
        provenance.put(
                "approvedAt", APPROVED_AT.toString());
        provenance.put(
                "expiresAt",
                INVENTORY_EXPIRES_AT.toString());
        provenance.put("revocationRef", "");
        value.put("lifecycle", "ACTIVE");
        value.put(
                "effectiveAt", EFFECTIVE_AT.toString());
        value.put(
                "expiresAt",
                INVENTORY_EXPIRES_AT.toString());
        ObjectNode material = value.deepCopy();
        material.put("fingerprint", "");
        value.put(
                "fingerprint",
                EvidenceVerificationSupport.sha256Bounded(
                        material,
                        DomainFidelityProfileVerifier
                                .MAXIMUM_INVENTORY_BYTES));
        return value;
    }

    private static ObjectNode profile(
            ObjectNode exactInventory) {
        ObjectNode value =
                JsonNodeFactory.instance.objectNode();
        value.put(
                "schemaVersion",
                CapabilityMirrorProtocol
                        .DOMAIN_FIDELITY_PROFILE_V1);
        value.put("profileFingerprint", "");
        value.set("scope", scope());
        value.put("domainId", "customer-service");
        ObjectNode inventoryRef = value.putObject(
                "inventoryRef");
        inventoryRef.put(
                "kind", "DOMAIN_FIDELITY_INVENTORY");
        inventoryRef.put(
                "id",
                exactInventory.path("inventoryId").asText());
        inventoryRef.put(
                "revision",
                exactInventory.path("revision").asLong());
        inventoryRef.put(
                "fingerprint",
                exactInventory.path("fingerprint").asText());
        value.set(
                "taxonomyRef",
                exactInventory.path("taxonomyRef")
                        .deepCopy());
        ObjectNode policy = value.putObject("policy");
        policy.put("minimumAssessedUnits", 1);
        policy.put("freshnessWindow", "PT720H");
        policy.put(
                "certifiableEvidenceRequired", true);
        policy.put(
                "confidenceMethod", "WILSON_95_V1");
        value.put(
                "measuredAt", MEASURED_AT.toString());
        value.put(
                "validUntil", VALID_UNTIL.toString());
        ObjectNode denominator =
                value.putObject("denominator");
        denominator.put("totalUnits", 1);
        denominator.put("totalObligations", 2);
        ArrayNode denominatorDimensions =
                denominator.putArray("dimensions");
        dimensionDenominator(
                denominatorDimensions, "BEHAVIOR");
        dimensionDenominator(
                denominatorDimensions, "CONTRACT");
        ObjectNode assessment =
                value.putArray("unitAssessments")
                        .addObject();
        assessment.put("unitId", "refund-golden");
        assessment.set(
                "scenarioCaseRef",
                exactInventory.at(
                        "/units/0/scenarioCaseRef")
                        .deepCopy());
        assessment.set(
                "sourceRef",
                ref(
                        "SCENARIO_REHEARSAL_WORKBOOK_SEED",
                        "refund-rehearsal",
                        '4'));
        assessment.put(
                "observedAt", OBSERVED_AT.toString());
        assessment.put(
                "expiresAt", VALID_UNTIL.toString());
        assessment.put("sourceMode", "RECORDED");
        ArrayNode results =
                assessment.putArray("results");
        passingResult(results, "BEHAVIOR");
        passingResult(results, "CONTRACT");
        ArrayNode metrics = value.putArray("dimensions");
        passingMetric(metrics, "BEHAVIOR");
        passingMetric(metrics, "CONTRACT");
        ObjectNode debt =
                value.putObject("abstentionDebt");
        debt.put("totalObligations", 2);
        debt.put("abstainedObligations", 0);
        debt.put("ratio", 0.0d);
        debt.putArray("reasons");
        ObjectNode composition =
                value.putObject("sourceComposition");
        composition.put("totalUnits", 1);
        composition.put("recordedUnits", 1);
        composition.put("synthesizedUnits", 0);
        composition.put("ownerDeclaredUnits", 0);
        composition.put("authoritativeUnits", 0);
        composition.put("unknownUnits", 0);
        composition.put("synthesizedRatio", 0.0d);
        composition.put("unknownRatio", 0.0d);
        value.put("assessment", "COMPLETE");
        value.putArray("limitations");
        value.set("profileSeal", unsignedSeal());
        return value;
    }

    private static void dimensionDenominator(
            ArrayNode values, String dimension) {
        ObjectNode value = values.addObject();
        value.put("dimension", dimension);
        value.put("requiredUnits", 1);
    }

    private static void passingResult(
            ArrayNode values, String dimension) {
        ObjectNode value = values.addObject();
        value.put("dimension", dimension);
        value.put("outcome", "PASS");
        value.put("reason", "ASSERTIONS_PASSED");
    }

    private static void passingMetric(
            ArrayNode values, String dimension) {
        ObjectNode value = values.addObject();
        value.put("dimension", dimension);
        value.put("requiredUnits", 1);
        value.put("freshEvidenceUnits", 1);
        value.put("assessedUnits", 1);
        value.put("passedUnits", 1);
        value.put("failedUnits", 0);
        value.put("abstainedUnits", 0);
        value.put("staleUnits", 0);
        value.put("missingUnits", 0);
        value.put("coverageRatio", 1.0d);
        value.put("abstentionRatio", 0.0d);
        value.set("confidence", wilsonPass());
        value.put("sufficiency", "MEASURED");
    }

    private static ObjectNode wilsonPass() {
        double z = 1.959963984540054d;
        double denominator = 1.0d + z * z;
        double center = 1.0d + z * z / 2.0d;
        double spread = z * Math.sqrt(
                z * z / 4.0d);
        ObjectNode value =
                JsonNodeFactory.instance.objectNode();
        value.put("point", 1.0d);
        value.put(
                "lowerBound",
                Math.max(
                        0.0d,
                        (center - spread) / denominator));
        value.put("upperBound", 1.0d);
        value.put("method", "WILSON_95_V1");
        return value;
    }

    private void resign(
            ObjectNode value, Instant signedAt)
            throws Exception {
        value.put("profileFingerprint", "");
        value.set("profileSeal", unsignedSeal());
        String profileFingerprint =
                EvidenceVerificationSupport.sha256Bounded(
                        value,
                        DomainFidelityProfileVerifier
                                .MAXIMUM_PROFILE_BYTES);
        value.put(
                "profileFingerprint",
                profileFingerprint);
        ObjectNode material =
                JsonNodeFactory.instance.objectNode();
        material.put(
                "domain",
                "RESOURCE_GATEWAY_DOMAIN_FIDELITY_PROFILE_V1");
        material.set(
                "schemaVersion",
                value.path("schemaVersion").deepCopy());
        material.set(
                "domainId",
                value.path("domainId").deepCopy());
        material.set(
                "inventoryRef",
                value.path("inventoryRef").deepCopy());
        material.set(
                "measuredAt",
                value.path("measuredAt").deepCopy());
        material.put(
                "profileFingerprint",
                profileFingerprint);
        String materialFingerprint =
                EvidenceVerificationSupport.sha256Bounded(
                        material,
                        DomainFidelityProfileVerifier
                                .MAXIMUM_ATTESTATION_BYTES);
        Signature signature =
                Signature.getInstance("Ed25519");
        signature.initSign(keyPair.getPrivate());
        signature.update(
                materialFingerprint.getBytes(
                        StandardCharsets.UTF_8));
        ObjectNode seal = value.putObject("profileSeal");
        seal.put(
                "schemaVersion",
                "bloge.visualRunEvidenceSeal.v1");
        seal.put(
                "materialFingerprint",
                materialFingerprint);
        seal.put("algorithm", "Ed25519");
        seal.put("keyId", key.keyId());
        seal.put("signedAt", signedAt.toString());
        seal.put(
                "signature",
                Base64.getEncoder().encodeToString(
                        signature.sign()));
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

    private static ObjectNode scope() {
        ObjectNode value =
                JsonNodeFactory.instance.objectNode();
        value.put("tenantId", "tenant-a");
        value.put("organizationId", "org-a");
        value.put("projectId", "support");
        value.put("environmentId", "test");
        value.put("region", "sg");
        return value;
    }

    private static ObjectNode ref(
            String kind, String id, char hash) {
        ObjectNode value =
                JsonNodeFactory.instance.objectNode();
        value.put("kind", kind);
        value.put("id", id);
        value.put("revision", 1);
        value.put("fingerprint", fingerprint(hash));
        return value;
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value)
                .repeat(64);
    }
}
