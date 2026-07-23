package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.security.GeneralSecurityException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Dependency-light offline verifier for one signed, payload-free mirror run evidence bundle.
 *
 * <p>The verifier trusts neither producer labels nor a valid detached signature by itself. It
 * validates the strict packaged schemas, re-derives deterministic ordering, proves that every
 * executed external attempt has exactly one matching resolution, verifies nested and aggregate
 * fingerprints, applies verification-key policy, and finally verifies the domain-separated
 * Ed25519 signature. It has no Spring or Resource Gateway server dependency.</p>
 */
public final class MirrorEvidenceVerifier {
    /** Maximum canonical bytes admitted for one nested resolution. */
    public static final int MAXIMUM_RESOLUTION_BYTES = 20 * 1024 * 1024;
    /** Maximum canonical bytes admitted for one run-evidence value. */
    public static final int MAXIMUM_EVIDENCE_BYTES = 64 * 1024 * 1024;
    /** Maximum canonical bytes admitted for one portable bundle. */
    public static final int MAXIMUM_BUNDLE_BYTES = 72 * 1024 * 1024;
    private static final int MAXIMUM_SIGNATURE_MATERIAL_BYTES = 8 * 1024;
    private static final String SIGNATURE_DOMAIN_V1 = "RESOURCE_GATEWAY_MIRROR_EVIDENCE_V1";
    private static final String SIGNATURE_DOMAIN_V2 = "RESOURCE_GATEWAY_MIRROR_EVIDENCE_V2";
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Creates a stateless offline mirror evidence verifier. */
    public MirrorEvidenceVerifier() {
    }

    /** Bounded offline verification outcome. */
    public enum Outcome {
        /** Structure, closure, fingerprints, key policy, and signature all passed. */
        VERIFIED,
        /** Evidence structure, semantics, closure, fingerprint, or signature is invalid. */
        INVALID,
        /** The attestation's public verification key is unavailable. */
        KEY_UNAVAILABLE,
        /** Key lifecycle or signature algorithm policy rejects the material. */
        POLICY_REJECTED
    }

    /**
     * Payload-free verification result suitable for correctness workbooks and CI logs.
     *
     * @param outcome bounded verification outcome
     * @param reasonCode stable machine-readable reason
     * @param runId mirror run identity, or blank when unavailable
     * @param planFingerprint sealed plan identity, or blank when unavailable
     * @param bundleFingerprint attached bundle identity, or blank when unavailable
     * @param evidenceFingerprint attached evidence identity, or blank when unavailable
     * @param keyId attestation key identity, or blank when unavailable
     */
    public record VerificationResult(
            Outcome outcome,
            String reasonCode,
            String runId,
            String planFingerprint,
            String bundleFingerprint,
            String evidenceFingerprint,
            String keyId
    ) {
        /** Normalizes and validates log-safe result fields. */
        public VerificationResult {
            reasonCode = normalize(reasonCode);
            runId = normalize(runId);
            planFingerprint = normalize(planFingerprint);
            bundleFingerprint = normalize(bundleFingerprint);
            evidenceFingerprint = normalize(evidenceFingerprint);
            keyId = normalize(keyId);
            if (outcome == null || !reasonCode.matches("[A-Z][A-Z0-9_.-]{0,254}")) {
                throw new IllegalArgumentException("Mirror verification result is invalid");
            }
        }

        /**
         * Reports whether every independent verification step passed.
         *
         * @return true only for a fully verified bundle
         */
        public boolean verified() {
            return outcome == Outcome.VERIFIED;
        }
    }

    /**
     * Independently verifies one decoded mirror evidence bundle with its resolved public key.
     *
     * <p>The method never returns business payload values or producer diagnostics. A malformed
     * input produces a bounded invalid result rather than propagating parser or cryptographic
     * exception text.</p>
     *
     * @param bundle decoded supported mirror evidence bundle version
     * @param key public key resolved by the attestation key id; may be {@code null}
     * @return payload-free verification result
     */
    public VerificationResult verify(JsonNode bundle, EvidenceVerificationKey key) {
        Coordinates coordinates = Coordinates.from(bundle);
        try {
            CapabilityMirrorSchemaValidator.require(bundle,
                    bundleSchema(bundle),
                    "RG.MIRROR.CLIENT.EVIDENCE_SCHEMA_INVALID");
        } catch (RuntimeException invalid) {
            return result(Outcome.INVALID, "MIRROR_EVIDENCE_SCHEMA_INVALID", coordinates);
        }

        JsonNode evidence = bundle.path("evidence");
        JsonNode attestation = bundle.path("attestation");
        try {
            verifySemantics(evidence, attestation);
            String actualEvidenceFingerprint = EvidenceVerificationSupport.sha256Bounded(
                    evidence, MAXIMUM_EVIDENCE_BYTES);
            if (!actualEvidenceFingerprint.equals(attestation.path("evidenceFingerprint").asText())) {
                return result(Outcome.INVALID, "MIRROR_EVIDENCE_FINGERPRINT_INVALID", coordinates);
            }
            String actualBundleFingerprint = EvidenceVerificationSupport.sha256Bounded(
                    bundleMaterial(bundle), MAXIMUM_BUNDLE_BYTES);
            if (!actualBundleFingerprint.equals(bundle.path("bundleFingerprint").asText())) {
                return result(Outcome.INVALID, "MIRROR_BUNDLE_FINGERPRINT_INVALID", coordinates);
            }
        } catch (VerificationFailure failure) {
            return result(Outcome.INVALID, failure.reasonCode, coordinates);
        } catch (RuntimeException failure) {
            return result(Outcome.INVALID, "MIRROR_EVIDENCE_MATERIAL_INVALID", coordinates);
        }

        if (key == null) {
            return result(Outcome.KEY_UNAVAILABLE, "MIRROR_VERIFICATION_KEY_UNAVAILABLE",
                    coordinates);
        }
        if (!key.keyId().equals(attestation.path("keyId").asText())) {
            return result(Outcome.INVALID, "MIRROR_VERIFICATION_KEY_ID_MISMATCH", coordinates);
        }
        if (!"Ed25519".equals(key.algorithm())
                || !key.algorithm().equals(attestation.path("algorithm").asText())) {
            return result(Outcome.POLICY_REJECTED, "MIRROR_SIGNATURE_ALGORITHM_REJECTED",
                    coordinates);
        }
        Instant signedAt;
        try {
            signedAt = Instant.parse(attestation.path("signedAt").asText());
        } catch (DateTimeParseException failure) {
            return result(Outcome.INVALID, "MIRROR_ATTESTATION_TIME_INVALID", coordinates);
        }
        if (!key.verificationAllowed()
                || signedAt.isBefore(key.createdAt().minus(
                EvidenceVerificationSupport.KEY_CREATION_SKEW))) {
            return result(Outcome.POLICY_REJECTED, "MIRROR_VERIFICATION_KEY_POLICY_REJECTED",
                    coordinates);
        }
        try {
            String materialFingerprint = EvidenceVerificationSupport.sha256Bounded(
                    signatureMaterial(attestation), MAXIMUM_SIGNATURE_MATERIAL_BYTES);
            if (!EvidenceVerificationSupport.verifyEd25519(materialFingerprint,
                    attestation.path("signature").asText(), key.encodedPublicKey())) {
                return result(Outcome.INVALID, "MIRROR_ATTESTATION_SIGNATURE_INVALID",
                        coordinates);
            }
            return result(Outcome.VERIFIED, "VERIFIED", coordinates);
        } catch (RuntimeException | GeneralSecurityException failure) {
            return result(Outcome.INVALID, "MIRROR_ATTESTATION_MATERIAL_INVALID", coordinates);
        }
    }

    private static void verifySemantics(JsonNode evidence, JsonNode attestation) {
        if (!evidence.path("runId").asText().equals(attestation.path("runId").asText())
                || !evidence.path("planFingerprint").asText()
                .equals(attestation.path("planFingerprint").asText())) {
            fail("MIRROR_ATTESTATION_IDENTITY_MISMATCH");
        }
        Instant startedAt = instant(evidence.path("startedAt"), "MIRROR_RUN_TIME_INVALID");
        Instant completedAt = instant(evidence.path("completedAt"), "MIRROR_RUN_TIME_INVALID");
        Instant signedAt = instant(attestation.path("signedAt"), "MIRROR_ATTESTATION_TIME_INVALID");
        if (completedAt.isBefore(startedAt) || signedAt.isBefore(completedAt)) {
            fail("MIRROR_ATTESTATION_TIME_INVALID");
        }

        JsonNode bindings = evidence.path("externalBindings");
        requireOrdered(bindings, MirrorEvidenceVerifier::compareBinding,
                MirrorEvidenceVerifier::bindingCoordinate, "MIRROR_EXTERNAL_BINDING_ORDER_INVALID");
        Map<String, JsonNode> bindingBySite = new HashMap<>();
        bindings.forEach(binding -> bindingBySite.put(
                binding.path("invocationSiteId").asText(), binding));

        JsonNode nodes = evidence.path("nodeTraces");
        requireOrdered(nodes, MirrorEvidenceVerifier::compareNode,
                MirrorEvidenceVerifier::nodeCoordinate, "MIRROR_NODE_TRACE_ORDER_INVALID");
        Map<String, JsonNode> expectedAttempts = new HashMap<>();
        for (JsonNode node : nodes) {
            JsonNode attempts = node.path("attempts");
            requireOrdered(attempts, Comparator.comparingInt(value -> value.path("attempt").asInt()),
                    value -> Integer.toString(value.path("attempt").asInt()),
                    "MIRROR_ATTEMPT_TRACE_ORDER_INVALID");
            JsonNode binding = bindingBySite.get(node.path("invocationSiteId").asText());
            if (binding == null) {
                continue;
            }
            if (!binding.path("graphPath").asText().equals(node.path("graphPath").asText())) {
                fail("MIRROR_EXTERNAL_ATTEMPT_BINDING_MISMATCH");
            }
            for (JsonNode attempt : attempts) {
                String coordinate = attemptCoordinate(node, attempt);
                if (expectedAttempts.put(coordinate, attempt) != null) {
                    fail("MIRROR_EXTERNAL_ATTEMPT_DUPLICATE");
                }
            }
        }

        JsonNode edges = evidence.path("edgeTraces");
        requireOrdered(edges, MirrorEvidenceVerifier::compareEdge,
                MirrorEvidenceVerifier::edgeCoordinate, "MIRROR_EDGE_TRACE_ORDER_INVALID");

        JsonNode resolutions = evidence.path("resolutions");
        requireOrdered(resolutions, MirrorEvidenceVerifier::compareResolution,
                MirrorEvidenceVerifier::resolutionCoordinate,
                "MIRROR_RESOLUTION_ORDER_INVALID");
        Set<String> actualResolutionCoordinates = new HashSet<>();
        for (JsonNode resolution : resolutions) {
            verifyResolution(evidence, resolution, bindingBySite, expectedAttempts);
            actualResolutionCoordinates.add(resolutionCoordinate(resolution));
        }
        if (!actualResolutionCoordinates.equals(expectedAttempts.keySet())) {
            fail("MIRROR_RESOLUTION_CLOSURE_INCOMPLETE");
        }

        requireStringOrder(evidence.path("limitations"), "MIRROR_LIMITATION_ORDER_INVALID");
        JsonNode isolation = evidence.path("isolation");
        requireStringOrder(isolation.path("interceptorTypes"),
                "MIRROR_ISOLATION_ORDER_INVALID");
        requireStringOrder(isolation.path("listenerTypes"),
                "MIRROR_ISOLATION_ORDER_INVALID");
        requireStringOrder(isolation.path("limitations"),
                "MIRROR_ISOLATION_ORDER_INVALID");
        verifyDeploymentTrust(evidence, attestation, isolation, startedAt, completedAt,
                signedAt);
    }

    private static void verifyDeploymentTrust(
            JsonNode evidence,
            JsonNode attestation,
            JsonNode isolation,
            Instant startedAt,
            Instant completedAt,
            Instant signedAt) {
        boolean current = CapabilityMirrorProtocol.MIRROR_RUN_EVIDENCE_V2.equals(
                evidence.path("schemaVersion").asText());
        JsonNode binding = isolation.path("deploymentTrustBinding");
        if (!current) {
            if (!binding.isMissingNode()) {
                fail("MIRROR_DEPLOYMENT_TRUST_VERSION_INVALID");
            }
            return;
        }
        if (!isolation.path("deploymentEgressEnforced").asBoolean()) {
            if (!binding.isMissingNode()) {
                fail("MIRROR_DEPLOYMENT_TRUST_UNEXPECTED");
            }
            return;
        }
        JsonNode admitted = binding.path("admittedSnapshotRef");
        JsonNode committed = binding.path("committedSnapshotRef");
        Instant admittedAt = instant(binding.path("admittedAt"),
                "MIRROR_DEPLOYMENT_TRUST_TIME_INVALID");
        Instant confirmedAt = instant(binding.path("confirmedAt"),
                "MIRROR_DEPLOYMENT_TRUST_TIME_INVALID");
        if (!isolation.path("deploymentIsolationRef").equals(binding.path("attestationRef"))
                || binding.path("decisionRef").path("revision").asLong()
                != binding.path("statusRef").path("revision").asLong()
                || !admitted.path("id").asText().equals(committed.path("id").asText())
                || committed.path("revision").asLong() < admitted.path("revision").asLong()
                || admittedAt.isAfter(startedAt)
                || confirmedAt.isBefore(completedAt)
                || signedAt.isBefore(confirmedAt)) {
            fail("MIRROR_DEPLOYMENT_TRUST_BINDING_INVALID");
        }
    }

    private static void verifyResolution(
            JsonNode evidence,
            JsonNode resolution,
            Map<String, JsonNode> bindingBySite,
            Map<String, JsonNode> expectedAttempts) {
        if (!evidence.path("runId").asText().equals(resolution.path("runId").asText())
                || !evidence.path("planFingerprint").asText()
                .equals(resolution.path("planFingerprint").asText())) {
            fail("MIRROR_RESOLUTION_RUN_PLAN_MISMATCH");
        }
        String visibility = resolution.path("payloadVisibility").asText();
        if (!("HASH_ONLY".equals(visibility) || "NONE".equals(visibility))
                || resolution.path("outputIncluded").asBoolean()
                || !resolution.path("output").isNull()) {
            fail("MIRROR_RESOLUTION_PAYLOAD_POLICY_INVALID");
        }
        JsonNode binding = bindingBySite.get(resolution.path("invocationSiteId").asText());
        if (binding == null
                || !binding.path("graphPath").asText().equals(resolution.path("graphPath").asText())
                || !binding.path("capabilityRef").equals(resolution.path("capabilityRef"))) {
            fail("MIRROR_RESOLUTION_BINDING_MISMATCH");
        }
        String coordinate = resolutionCoordinate(resolution);
        JsonNode attempt = expectedAttempts.get(coordinate);
        if (attempt == null) {
            fail("MIRROR_RESOLUTION_WITHOUT_ATTEMPT");
        }
        if (!resolution.path("requestFingerprint").asText()
                .equals(attempt.path("inputFingerprint").asText())) {
            fail("MIRROR_RESOLUTION_REQUEST_FINGERPRINT_MISMATCH");
        }
        String outputFingerprint = resolution.path("outputFingerprint").asText();
        if (!outputFingerprint.isBlank()
                && !outputFingerprint.equals(attempt.path("outputFingerprint").asText())) {
            fail("MIRROR_RESOLUTION_OUTPUT_FINGERPRINT_MISMATCH");
        }
        requireArtifactRefOrder(resolution.path("matchedArtifactRefs"));
        requireStringOrder(resolution.path("matchedRuleRefs"),
                "MIRROR_RESOLUTION_PROVENANCE_ORDER_INVALID");
        requireStringOrder(resolution.path("limitations"),
                "MIRROR_RESOLUTION_LIMITATION_ORDER_INVALID");

        ObjectNode material = ((ObjectNode) resolution).deepCopy();
        String attached = material.path("resolutionFingerprint").asText();
        material.put("resolutionFingerprint", "");
        String expected = EvidenceVerificationSupport.sha256Bounded(
                material, MAXIMUM_RESOLUTION_BYTES);
        if (!expected.equals(attached)) {
            fail("MIRROR_RESOLUTION_FINGERPRINT_INVALID");
        }
    }

    private static void requireArtifactRefOrder(JsonNode values) {
        requireOrdered(values, MirrorEvidenceVerifier::compareArtifactRef,
                value -> value.path("kind").asText() + '\0' + value.path("id").asText()
                        + '\0' + value.path("revision").asLong() + '\0'
                        + value.path("fingerprint").asText(),
                "MIRROR_RESOLUTION_PROVENANCE_ORDER_INVALID");
    }

    private static void requireStringOrder(JsonNode values, String reason) {
        requireOrdered(values, Comparator.comparing(JsonNode::asText), JsonNode::asText, reason);
    }

    private static void requireOrdered(
            JsonNode values,
            Comparator<JsonNode> comparator,
            java.util.function.Function<JsonNode, String> coordinate,
            String reason) {
        JsonNode previous = null;
        Set<String> coordinates = new HashSet<>();
        for (JsonNode value : values) {
            if (previous != null && comparator.compare(previous, value) > 0) {
                fail(reason);
            }
            if (!coordinates.add(coordinate.apply(value))) {
                fail(reason);
            }
            previous = value;
        }
    }

    private static int compareBinding(JsonNode left, JsonNode right) {
        return Comparator.comparing((JsonNode value) -> value.path("invocationSiteId").asText())
                .thenComparing(value -> value.path("dependencyNodeId").asText())
                .thenComparing(value -> value.path("capabilityRef").path("id").asText())
                .compare(left, right);
    }

    private static int compareNode(JsonNode left, JsonNode right) {
        return Comparator.comparing((JsonNode value) -> value.path("invocationSiteId").asText())
                .thenComparing(value -> value.path("graphPath").asText())
                .thenComparing(value -> value.path("correlationKey").asText())
                .thenComparingInt(value -> value.path("graphOccurrence").asInt())
                .thenComparingInt(value -> value.path("occurrence").asInt())
                .thenComparing(value -> value.path("nodeId").asText())
                .compare(left, right);
    }

    private static int compareEdge(JsonNode left, JsonNode right) {
        return Comparator.comparing((JsonNode value) -> value.path("graphPath").asText())
                .thenComparing(value -> value.path("correlationKey").asText())
                .thenComparingInt(value -> value.path("graphOccurrence").asInt())
                .thenComparing(value -> value.path("edgeId").asText())
                .compare(left, right);
    }

    private static int compareResolution(JsonNode left, JsonNode right) {
        return Comparator.comparing((JsonNode value) -> value.path("invocationSiteId").asText())
                .thenComparing(value -> value.path("correlationKey").asText())
                .thenComparingInt(value -> value.path("occurrence").asInt())
                .thenComparingInt(value -> value.path("attempt").asInt())
                .compare(left, right);
    }

    private static int compareArtifactRef(JsonNode left, JsonNode right) {
        return Comparator.comparing((JsonNode value) -> value.path("kind").asText())
                .thenComparing(value -> value.path("id").asText())
                .thenComparingLong(value -> value.path("revision").asLong())
                .thenComparing(value -> value.path("fingerprint").asText())
                .compare(left, right);
    }

    private static String bindingCoordinate(JsonNode binding) {
        return binding.path("invocationSiteId").asText();
    }

    private static String nodeCoordinate(JsonNode node) {
        return node.path("invocationSiteId").asText() + '\0'
                + node.path("graphPath").asText() + '\0'
                + node.path("correlationKey").asText() + '\0'
                + node.path("graphOccurrence").asInt() + '\0'
                + node.path("occurrence").asInt();
    }

    private static String edgeCoordinate(JsonNode edge) {
        return edge.path("graphPath").asText() + '\0'
                + edge.path("correlationKey").asText() + '\0'
                + edge.path("graphOccurrence").asInt() + '\0'
                + edge.path("edgeId").asText();
    }

    private static String attemptCoordinate(JsonNode node, JsonNode attempt) {
        return node.path("invocationSiteId").asText() + '\0'
                + node.path("correlationKey").asText() + '\0'
                + node.path("occurrence").asInt() + '\0'
                + attempt.path("attempt").asInt();
    }

    private static String resolutionCoordinate(JsonNode resolution) {
        return resolution.path("invocationSiteId").asText() + '\0'
                + resolution.path("correlationKey").asText() + '\0'
                + resolution.path("occurrence").asInt() + '\0'
                + resolution.path("attempt").asInt();
    }

    private static ObjectNode signatureMaterial(JsonNode attestation) {
        ObjectNode material = JSON.createObjectNode();
        String version = attestation.path("schemaVersion").asText();
        material.put("domain", CapabilityMirrorProtocol.MIRROR_EVIDENCE_ATTESTATION_V1
                .equals(version) ? SIGNATURE_DOMAIN_V1 : SIGNATURE_DOMAIN_V2);
        material.put("schemaVersion", version);
        material.put("runId", attestation.path("runId").asText());
        material.put("planFingerprint", attestation.path("planFingerprint").asText());
        material.put("evidenceFingerprint", attestation.path("evidenceFingerprint").asText());
        material.put("signedAt", attestation.path("signedAt").asText());
        return material;
    }

    private static String bundleSchema(JsonNode bundle) {
        String version = bundle == null ? "" : bundle.path("schemaVersion").asText();
        if (CapabilityMirrorProtocol.MIRROR_EVIDENCE_BUNDLE_V1.equals(version)) {
            return CapabilityMirrorProtocol.MIRROR_EVIDENCE_BUNDLE_SCHEMA_RESOURCE;
        }
        if (CapabilityMirrorProtocol.MIRROR_EVIDENCE_BUNDLE_V2.equals(version)) {
            return CapabilityMirrorProtocol.MIRROR_EVIDENCE_BUNDLE_V2_SCHEMA_RESOURCE;
        }
        return "";
    }

    private static ObjectNode bundleMaterial(JsonNode bundle) {
        ObjectNode material = JSON.createObjectNode();
        material.set("schemaVersion", bundle.path("schemaVersion"));
        material.set("payloadPolicy", bundle.path("payloadPolicy"));
        material.set("attestation", bundle.path("attestation"));
        material.set("evidence", bundle.path("evidence"));
        return material;
    }

    private static Instant instant(JsonNode value, String reason) {
        try {
            return Instant.parse(value.asText());
        } catch (DateTimeParseException failure) {
            fail(reason);
            return Instant.EPOCH;
        }
    }

    private static VerificationResult result(
            Outcome outcome, String reason, Coordinates coordinates) {
        return new VerificationResult(outcome, reason, coordinates.runId,
                coordinates.planFingerprint, coordinates.bundleFingerprint,
                coordinates.evidenceFingerprint, coordinates.keyId);
    }

    private static void fail(String reason) {
        throw new VerificationFailure(reason);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private record Coordinates(
            String runId,
            String planFingerprint,
            String bundleFingerprint,
            String evidenceFingerprint,
            String keyId
    ) {
        private static Coordinates from(JsonNode bundle) {
            if (bundle == null || !bundle.isObject()) {
                return new Coordinates("", "", "", "", "");
            }
            JsonNode evidence = bundle.path("evidence");
            JsonNode attestation = bundle.path("attestation");
            return new Coordinates(evidence.path("runId").asText(),
                    evidence.path("planFingerprint").asText(),
                    bundle.path("bundleFingerprint").asText(),
                    attestation.path("evidenceFingerprint").asText(),
                    attestation.path("keyId").asText());
        }
    }

    private static final class VerificationFailure extends RuntimeException {
        private final String reasonCode;

        private VerificationFailure(String reasonCode) {
            super(null, null, false, false);
            this.reasonCode = reasonCode;
        }
    }
}
