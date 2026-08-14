package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Offline verifier for a regional deployment contract, certification, and isolation binding.
 *
 * <p>The verifier links neither Spring nor Resource Gateway server classes. It validates the
 * packaged strict Schemas, recomputes every producer content address, verifies exact component
 * and rotation closure, and delegates only the customer-owned certification seal decision to a
 * caller-supplied trust callback.</p>
 */
public final class RegionalDataPlaneCertificationVerifier {
    /** Packaged deployment-contract Schema. */
    public static final String CONTRACT_SCHEMA_RESOURCE =
            "/schemas/resource-gateway-mirror/"
                    + "regional-data-plane-deployment-contract-v1.schema.json";
    /** Packaged regional-certification Schema. */
    public static final String CERTIFICATION_SCHEMA_RESOURCE =
            "/schemas/resource-gateway-mirror/"
                    + "regional-data-plane-certification-v1.schema.json";
    /** Packaged isolation decision v2 Schema. */
    public static final String ISOLATION_BUNDLE_SCHEMA_RESOURCE =
            "/schemas/resource-gateway-mirror/"
                    + "mirror-deployment-isolation-attestation-bundle-v2.schema.json";
    /** Maximum canonical deployment contract size. */
    public static final int MAXIMUM_CONTRACT_BYTES = 2 * 1024 * 1024;
    /** Maximum canonical certification size. */
    public static final int MAXIMUM_CERTIFICATION_BYTES = 4 * 1024 * 1024;
    /** Maximum canonical isolation decision size. */
    public static final int MAXIMUM_ISOLATION_BUNDLE_BYTES = 2 * 1024 * 1024;
    /** Producer signature domain. */
    public static final String SIGNATURE_DOMAIN =
            "RESOURCE_GATEWAY_REGIONAL_DATA_PLANE_CERTIFICATION_V1";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> COMPONENTS = List.of(
            "EVIDENCE_KMS", "PAYLOAD_VAULT", "SECRET_AUTHORITY",
            "SESSION_STATE_STORE", "FIXTURE_RESOLVER", "MUTUAL_TLS",
            "EGRESS_ISOLATION");
    private static final List<String> ROTATIONS = List.of(
            "EVIDENCE_KMS_KEY", "MUTUAL_TLS_CA");

    /** Customer-owned trust callback for one already structured external seal. */
    @FunctionalInterface
    public interface ExternalSealVerifier {
        /**
         * Verifies certification authority identity, key lifecycle, and detached signature.
         *
         * @param seal defensive copy of the external seal
         * @param certification defensive copy of the addressed certification
         * @return true only when the external trust decision passes
         */
        boolean verify(JsonNode seal, JsonNode certification);
    }

    /** Creates a verifier backed by the Schemas packaged in the Test Kit. */
    public RegionalDataPlaneCertificationVerifier() {
    }

    /**
     * Verifies the complete regional certification closure for one run window.
     *
     * @param contract decoded deployment contract
     * @param certification decoded externally signed certification
     * @param isolationDecision decoded v2 isolation decision
     * @param executionStartedAt inclusive execution start
     * @param executionCompletedAt execution completion covered before certification expiry
     * @param externalSealVerifier customer-owned certification trust callback
     * @return immutable payload-free verified coordinates
     */
    public VerifiedCoordinates require(
            JsonNode contract,
            JsonNode certification,
            JsonNode isolationDecision,
            Instant executionStartedAt,
            Instant executionCompletedAt,
            ExternalSealVerifier externalSealVerifier) {
        JsonNode exactContract = copy(contract,
                "RG.MIRROR.CLIENT.REGIONAL_CONTRACT_INVALID");
        JsonNode exactCertification = copy(certification,
                "RG.MIRROR.CLIENT.REGIONAL_CERTIFICATION_INVALID");
        JsonNode exactIsolation = copy(isolationDecision,
                "RG.MIRROR.CLIENT.REGIONAL_ISOLATION_DECISION_INVALID");
        CapabilityMirrorSchemaValidator.require(exactContract, CONTRACT_SCHEMA_RESOURCE,
                "RG.MIRROR.CLIENT.REGIONAL_CONTRACT_SCHEMA_INVALID");
        CapabilityMirrorSchemaValidator.require(exactCertification,
                CERTIFICATION_SCHEMA_RESOURCE,
                "RG.MIRROR.CLIENT.REGIONAL_CERTIFICATION_SCHEMA_INVALID");
        CapabilityMirrorSchemaValidator.require(exactIsolation,
                ISOLATION_BUNDLE_SCHEMA_RESOURCE,
                "RG.MIRROR.CLIENT.REGIONAL_ISOLATION_SCHEMA_INVALID");

        verifyFingerprints(exactContract, exactCertification, exactIsolation);
        verifyIdentityClosure(exactContract, exactCertification, exactIsolation);
        verifyWindow(exactContract, exactCertification,
                executionStartedAt, executionCompletedAt);
        verifyComponents(exactContract, exactCertification, executionStartedAt);
        verifyRotations(exactContract, exactCertification, executionStartedAt);
        if (exactCertification.path("externalBusinessWriteAttemptCount").asLong(-1) != 0
                || exactCertification.path("writeEscapeCount").asLong(-1) != 0) {
            throw invalid("RG.MIRROR.CLIENT.REGIONAL_WRITE_ESCAPE_OBSERVED");
        }
        JsonNode seal = exactCertification.path("certificationSeal");
        if (externalSealVerifier == null
                || !externalSealVerifier.verify(
                seal.deepCopy(), exactCertification.deepCopy())) {
            throw invalid("RG.MIRROR.CLIENT.REGIONAL_CERTIFICATION_AUTHORITY_REJECTED");
        }
        return new VerifiedCoordinates(
                exactContract.path("contractId").asText(),
                exactContract.path("revision").asLong(),
                exactContract.path("contractFingerprint").asText(),
                exactCertification.path("certificationId").asText(),
                exactCertification.path("revision").asLong(),
                exactCertification.path("certificationFingerprint").asText(),
                exactIsolation.path("bundleFingerprint").asText(),
                executionStartedAt, executionCompletedAt);
    }

    private static void verifyFingerprints(
            JsonNode contract, JsonNode certification, JsonNode isolation) {
        ObjectNode contractEnvelope = JSON.createObjectNode();
        contractEnvelope.put("schemaVersion", contract.path("schemaVersion").asText());
        contractEnvelope.put("contractFingerprint", "");
        contractEnvelope.set("material", contractMaterial(contract));
        if (!contract.path("contractFingerprint").asText().equals(
                EvidenceVerificationSupport.sha256Bounded(
                        contractEnvelope, MAXIMUM_CONTRACT_BYTES))) {
            throw invalid("RG.MIRROR.CLIENT.REGIONAL_CONTRACT_FINGERPRINT_INVALID");
        }

        ObjectNode certificationMaterial = certificationMaterial(certification);
        ObjectNode signatureEnvelope = JSON.createObjectNode();
        signatureEnvelope.put("domain", SIGNATURE_DOMAIN);
        signatureEnvelope.put("schemaVersion",
                certification.path("schemaVersion").asText());
        signatureEnvelope.set("material", certificationMaterial);
        String materialFingerprint = EvidenceVerificationSupport.sha256Bounded(
                signatureEnvelope, MAXIMUM_CERTIFICATION_BYTES);
        if (!materialFingerprint.equals(certification.path("certificationSeal")
                .path("materialFingerprint").asText())) {
            throw invalid(
                    "RG.MIRROR.CLIENT.REGIONAL_CERTIFICATION_MATERIAL_FINGERPRINT_INVALID");
        }
        ObjectNode certificationEnvelope = JSON.createObjectNode();
        certificationEnvelope.put("schemaVersion",
                certification.path("schemaVersion").asText());
        certificationEnvelope.put("certificationFingerprint", "");
        certificationEnvelope.set("material", certificationMaterial);
        certificationEnvelope.set("certificationSeal",
                certification.path("certificationSeal").deepCopy());
        if (!certification.path("certificationFingerprint").asText().equals(
                EvidenceVerificationSupport.sha256Bounded(
                        certificationEnvelope, MAXIMUM_CERTIFICATION_BYTES))) {
            throw invalid("RG.MIRROR.CLIENT.REGIONAL_CERTIFICATION_FINGERPRINT_INVALID");
        }

        ObjectNode isolationEnvelope = (ObjectNode) isolation.deepCopy();
        isolationEnvelope.put("bundleFingerprint", "");
        if (!isolation.path("bundleFingerprint").asText().equals(
                EvidenceVerificationSupport.sha256Bounded(
                        isolationEnvelope, MAXIMUM_ISOLATION_BUNDLE_BYTES))) {
            throw invalid("RG.MIRROR.CLIENT.REGIONAL_ISOLATION_FINGERPRINT_INVALID");
        }
    }

    private static void verifyIdentityClosure(
            JsonNode contract, JsonNode certification, JsonNode isolation) {
        if (!contract.path("scope").equals(certification.path("scope"))
                || !contract.path("scope").equals(isolation.path("scope"))
                || !contract.path("deployment").equals(certification.path("deployment"))
                || !contract.path("deployment").equals(
                isolation.at("/attestation/material/deployment"))
                || !contract.path("region").equals(certification.path("region"))
                || !artifactEquals(certification.path("contractRef"),
                "REGIONAL_DATA_PLANE_DEPLOYMENT_CONTRACT",
                contract.path("contractId").asText(), contract.path("revision").asLong(),
                contract.path("contractFingerprint").asText())
                || !artifactEquals(isolation.path("regionalDataPlaneCertificationRef"),
                "REGIONAL_DATA_PLANE_CERTIFICATION",
                certification.path("certificationId").asText(),
                certification.path("revision").asLong(),
                certification.path("certificationFingerprint").asText())
                || !"ACTIVE".equals(isolation.at("/status/material/state").asText())) {
            throw invalid("RG.MIRROR.CLIENT.REGIONAL_IDENTITY_CLOSURE_INVALID");
        }
    }

    private static void verifyWindow(
            JsonNode contract,
            JsonNode certification,
            Instant startedAt,
            Instant completedAt) {
        if (!covered(contract, startedAt, completedAt)
                || !covered(certification, startedAt, completedAt)) {
            throw invalid("RG.MIRROR.CLIENT.REGIONAL_EXECUTION_WINDOW_REJECTED");
        }
    }

    private static void verifyComponents(
            JsonNode contract, JsonNode certification, Instant startedAt) {
        JsonNode requirements = contract.path("requiredComponents");
        JsonNode observations = certification.path("componentObservations");
        for (int index = 0; index < COMPONENTS.size(); index++) {
            JsonNode required = requirements.get(index);
            JsonNode observed = observations.get(index);
            String expectedKind = COMPONENTS.get(index);
            if (!expectedKind.equals(required.path("kind").asText())
                    || !expectedKind.equals(observed.path("kind").asText())
                    || !required.path("authorityId").equals(observed.path("authorityId"))
                    || !required.path("policyRef").equals(observed.path("policyRef"))
                    || observed.path("generation").asLong()
                    < required.path("minimumGeneration").asLong()) {
                throw invalid("RG.MIRROR.CLIENT.REGIONAL_COMPONENT_COORDINATES_INVALID");
            }
            Duration age = Duration.between(
                    instant(observed.path("observedAt").asText()), startedAt);
            if (age.isNegative() || age.compareTo(Duration.ofSeconds(
                    required.path("maximumObservationAgeSeconds").asLong())) > 0
                    || instant(observed.path("observedAt").asText()).isAfter(
                    instant(certification.path("observedAt").asText()))) {
                throw invalid("RG.MIRROR.CLIENT.REGIONAL_COMPONENT_STALE");
            }
            if (!"READY".equals(observed.path("status").asText())
                    || !observed.path("privateTransportEnforced").asBoolean()
                    || !observed.path("failClosed").asBoolean()
                    || !observed.path("regionalResidencyEnforced").asBoolean()
                    || !observed.path("externalBusinessWriteDenied").asBoolean()) {
                throw invalid("RG.MIRROR.CLIENT.REGIONAL_COMPONENT_NOT_READY");
            }
        }
    }

    private static void verifyRotations(
            JsonNode contract, JsonNode certification, Instant executionStartedAt) {
        JsonNode rotations = certification.path("rotationObservations");
        JsonNode policy = contract.path("rotationPolicy");
        for (int index = 0; index < ROTATIONS.size(); index++) {
            JsonNode rotation = rotations.get(index);
            if (!ROTATIONS.get(index).equals(rotation.path("kind").asText())
                    || rotation.path("activeGeneration").asLong()
                    <= rotation.path("previousGeneration").asLong()
                    || !rotation.path("previousGenerationRevoked").asBoolean()
                    || !rotation.path("allReplicasConverged").asBoolean()
                    || !rotation.path("staleSessionsDrained").asBoolean()
                    || !rotation.path("restartFree").asBoolean()
                    || rotation.path("overlapAchievedSeconds").asLong(-1)
                    < policy.path("minimumOverlapSeconds").asLong()) {
                throw invalid("RG.MIRROR.CLIENT.REGIONAL_ROTATION_NOT_CONVERGED");
            }
            long maximumAgeSeconds = index == 0
                    ? policy.path("maximumKmsKeyAgeSeconds").asLong()
                    : policy.path("maximumCaAgeSeconds").asLong();
            Duration activeAge = Duration.between(instant(rotation.path(
                    "activeGenerationActivatedAt").asText()), executionStartedAt);
            if (activeAge.isNegative()
                    || activeAge.compareTo(Duration.ofSeconds(maximumAgeSeconds)) > 0) {
                throw invalid("RG.MIRROR.CLIENT.REGIONAL_ACTIVE_KEY_AGE_REJECTED");
            }
            String component = index == 0 ? "EVIDENCE_KMS" : "MUTUAL_TLS";
            JsonNode observed = certification.path("componentObservations").get(
                    COMPONENTS.indexOf(component));
            if (observed.path("generation").asLong()
                    != rotation.path("activeGeneration").asLong()) {
                throw invalid("RG.MIRROR.CLIENT.REGIONAL_ROTATION_GENERATION_INVALID");
            }
        }
    }

    private static ObjectNode contractMaterial(JsonNode contract) {
        ObjectNode material = JSON.createObjectNode();
        for (String field : List.of("contractId", "revision", "scope", "region",
                "deployment", "requiredComponents", "rotationPolicy", "validFrom",
                "expiresAt", "owner")) {
            material.set(field, contract.path(field).deepCopy());
        }
        return material;
    }

    private static ObjectNode certificationMaterial(JsonNode certification) {
        ObjectNode material = JSON.createObjectNode();
        for (String field : List.of("certificationId", "revision", "contractRef", "scope",
                "region", "deployment", "observedAt", "validFrom", "expiresAt",
                "componentObservations", "rotationObservations",
                "externalBusinessWriteAttemptCount", "writeEscapeCount", "issuer",
                "proofRefs")) {
            material.set(field, certification.path(field).deepCopy());
        }
        return material;
    }

    private static boolean artifactEquals(
            JsonNode ref, String kind, String id, long revision, String fingerprint) {
        return kind.equals(ref.path("kind").asText())
                && id.equals(ref.path("id").asText())
                && revision == ref.path("revision").asLong()
                && fingerprint.equals(ref.path("fingerprint").asText());
    }

    private static boolean covered(JsonNode value, Instant start, Instant end) {
        if (start == null || end == null || end.isBefore(start)) {
            return false;
        }
        Instant validFrom = instant(value.path("validFrom").asText());
        Instant expiresAt = instant(value.path("expiresAt").asText());
        return !start.isBefore(validFrom) && end.isBefore(expiresAt);
    }

    private static Instant instant(String value) {
        try {
            return Instant.parse(value);
        } catch (RuntimeException malformed) {
            throw invalid("RG.MIRROR.CLIENT.REGIONAL_TIME_INVALID");
        }
    }

    private static JsonNode copy(JsonNode value, String code) {
        if (value == null || !value.isObject()) {
            throw invalid(code);
        }
        return value.deepCopy();
    }

    private static IllegalArgumentException invalid(String code) {
        return new IllegalArgumentException(code);
    }

    /**
     * Payload-free coordinates returned after complete verification.
     *
     * @param contractId exact contract id
     * @param contractRevision exact contract revision
     * @param contractFingerprint exact contract content address
     * @param certificationId exact certification id
     * @param certificationRevision exact certification revision
     * @param certificationFingerprint exact certification content address
     * @param isolationDecisionFingerprint exact v2 isolation decision content address
     * @param executionStartedAt verified execution start
     * @param executionCompletedAt verified execution completion
     */
    public record VerifiedCoordinates(
            String contractId,
            long contractRevision,
            String contractFingerprint,
            String certificationId,
            long certificationRevision,
            String certificationFingerprint,
            String isolationDecisionFingerprint,
            Instant executionStartedAt,
            Instant executionCompletedAt
    ) {
    }
}
