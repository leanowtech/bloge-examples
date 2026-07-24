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
    /** Maximum canonical bytes admitted for one nested state-evidence value. */
    public static final int MAXIMUM_STATE_EVIDENCE_BYTES = 32 * 1024 * 1024;
    /** Maximum canonical bytes admitted for one portable bundle. */
    public static final int MAXIMUM_BUNDLE_BYTES = 72 * 1024 * 1024;
    private static final int MAXIMUM_SIGNATURE_MATERIAL_BYTES = 8 * 1024;
    private static final String SIGNATURE_DOMAIN_V1 = "RESOURCE_GATEWAY_MIRROR_EVIDENCE_V1";
    private static final String SIGNATURE_DOMAIN_V2 = "RESOURCE_GATEWAY_MIRROR_EVIDENCE_V2";
    private static final String SIGNATURE_DOMAIN_V3 = "RESOURCE_GATEWAY_MIRROR_EVIDENCE_V3";
    private static final String SIGNATURE_DOMAIN_V4 = "RESOURCE_GATEWAY_MIRROR_EVIDENCE_V4";
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Creates an offline verifier for stateless and Session-backed mirror evidence. */
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
        Map<String, JsonNode> resolutionByCoordinate =
                new HashMap<>();
        for (JsonNode resolution : resolutions) {
            verifyResolution(evidence, resolution, bindingBySite, expectedAttempts);
            String coordinate = resolutionCoordinate(resolution);
            actualResolutionCoordinates.add(coordinate);
            resolutionByCoordinate.put(coordinate, resolution);
        }
        if (!actualResolutionCoordinates.equals(expectedAttempts.keySet())) {
            fail("MIRROR_RESOLUTION_CLOSURE_INCOMPLETE");
        }
        verifyStateEvidence(
                evidence, bindingBySite, expectedAttempts,
                resolutionByCoordinate);

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
        String version = evidence.path("schemaVersion").asText();
        boolean current =
                CapabilityMirrorProtocol.MIRROR_RUN_EVIDENCE_V2.equals(
                        version)
                        || CapabilityMirrorProtocol.MIRROR_RUN_EVIDENCE_V3
                        .equals(version)
                        || CapabilityMirrorProtocol.MIRROR_RUN_EVIDENCE_V4
                        .equals(version);
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

    private static void verifyStateEvidence(
            JsonNode evidence,
            Map<String, JsonNode> bindingBySite,
            Map<String, JsonNode> expectedAttempts,
            Map<String, JsonNode> resolutionByCoordinate) {
        boolean readOnly =
                CapabilityMirrorProtocol.MIRROR_RUN_EVIDENCE_V3.equals(
                        evidence.path("schemaVersion").asText());
        boolean readWrite =
                CapabilityMirrorProtocol.MIRROR_RUN_EVIDENCE_V4.equals(
                        evidence.path("schemaVersion").asText());
        JsonNode state = evidence.path("stateEvidence");
        if (readWrite) {
            verifyStateTransitionEvidence(
                    evidence, state, bindingBySite,
                    expectedAttempts, resolutionByCoordinate);
            return;
        }
        if (!readOnly) {
            if (!state.isMissingNode()) {
                fail("MIRROR_STATE_EVIDENCE_VERSION_INVALID");
            }
            return;
        }
        if (!evidence.path("runId").asText()
                .equals(state.path("runId").asText())
                || !evidence.path("planFingerprint").asText()
                .equals(state.path("planFingerprint").asText())
                || state.path("sessionStateRef").path("revision").asLong()
                != state.path("stateRevision").asLong() + 1) {
            fail("MIRROR_STATE_EVIDENCE_IDENTITY_INVALID");
        }
        ObjectNode material = ((ObjectNode) state).deepCopy();
        String attached =
                material.path("stateEvidenceFingerprint").asText();
        material.put("stateEvidenceFingerprint", "");
        if (!EvidenceVerificationSupport.sha256Bounded(
                material, MAXIMUM_STATE_EVIDENCE_BYTES)
                .equals(attached)) {
            fail("MIRROR_STATE_EVIDENCE_FINGERPRINT_INVALID");
        }

        JsonNode stateBindings = state.path("statefulBindings");
        requireOrdered(
                stateBindings,
                MirrorEvidenceVerifier::compareStateBinding,
                value -> value.path("invocationSiteId").asText(),
                "MIRROR_STATE_BINDING_ORDER_INVALID");
        Map<String, JsonNode> stateBindingBySite =
                new HashMap<>();
        for (JsonNode stateBinding : stateBindings) {
            String site =
                    stateBinding.path("invocationSiteId").asText();
            JsonNode binding = bindingBySite.get(site);
            if (binding == null
                    || !binding.path("graphPath").equals(
                    stateBinding.path("graphPath"))
                    || !binding.path("capabilityRef").equals(
                    stateBinding.path("capabilityRef"))
                    || stateBindingBySite.put(site, stateBinding)
                    != null) {
                fail("MIRROR_STATE_BINDING_CLOSURE_INVALID");
            }
        }

        Map<String, JsonNode> expectedStateAttempts =
                new HashMap<>();
        expectedAttempts.forEach((coordinate, attempt) -> {
            String site = coordinate.substring(
                    0, coordinate.indexOf('\0'));
            if (stateBindingBySite.containsKey(site)) {
                expectedStateAttempts.put(coordinate, attempt);
            }
        });
        JsonNode accesses = state.path("accesses");
        requireOrdered(
                accesses,
                MirrorEvidenceVerifier::compareStateAccess,
                MirrorEvidenceVerifier::stateAccessCoordinate,
                "MIRROR_STATE_ACCESS_ORDER_INVALID");
        Map<String, JsonNode> accessByCoordinate =
                new HashMap<>();
        for (JsonNode access : accesses) {
            String coordinate = stateAccessCoordinate(access);
            JsonNode stateBinding = stateBindingBySite.get(
                    access.path("invocationSiteId").asText());
            JsonNode attempt = expectedStateAttempts.get(coordinate);
            JsonNode resolution =
                    resolutionByCoordinate.get(coordinate);
            if (stateBinding == null || attempt == null
                    || resolution == null
                    || !stateBinding.path("graphPath").equals(
                    access.path("graphPath"))
                    || !stateBinding.path("capabilityRef").equals(
                    access.path("capabilityRef"))
                    || !stateBinding.path("stateReadSpecRef").equals(
                    access.path("stateReadSpecRef"))
                    || !access.path("requestFingerprint").equals(
                    attempt.path("inputFingerprint"))
                    || !access.path("requestFingerprint").equals(
                    resolution.path("requestFingerprint"))
                    || accessByCoordinate.put(coordinate, access)
                    != null) {
                fail("MIRROR_STATE_ACCESS_CLOSURE_INVALID");
            }
            verifyStateOutcome(state, access, attempt, resolution);
        }
        if (!accessByCoordinate.keySet().equals(
                expectedStateAttempts.keySet())) {
            fail("MIRROR_STATE_ACCESS_CLOSURE_INCOMPLETE");
        }
        requireStringOrder(
                state.path("limitations"),
                "MIRROR_STATE_LIMITATION_ORDER_INVALID");
    }

    private static void verifyStateTransitionEvidence(
            JsonNode evidence,
            JsonNode state,
            Map<String, JsonNode> bindingBySite,
            Map<String, JsonNode> expectedAttempts,
            Map<String, JsonNode> resolutionByCoordinate) {
        long initialRevision =
                state.path("stateRevision").asLong();
        long finalRevision =
                state.path("finalStateRevision").asLong();
        if (!evidence.path("runId").asText()
                .equals(state.path("runId").asText())
                || !evidence.path("planFingerprint").asText()
                .equals(state.path("planFingerprint").asText())
                || state.path("sessionStateRef").path("revision")
                .asLong() != initialRevision + 1
                || state.path("finalSessionStateRef")
                .path("revision").asLong()
                != finalRevision + 1
                || finalRevision < initialRevision
                || !state.path("sessionStateRef").path("id")
                .equals(state.path("finalSessionStateRef")
                        .path("id"))) {
            fail("MIRROR_STATE_TRANSITION_IDENTITY_INVALID");
        }
        ObjectNode material =
                ((ObjectNode) state).deepCopy();
        String attached = material.path(
                "stateEvidenceFingerprint").asText();
        material.put("stateEvidenceFingerprint", "");
        if (!EvidenceVerificationSupport.sha256Bounded(
                material, MAXIMUM_STATE_EVIDENCE_BYTES)
                .equals(attached)) {
            fail("MIRROR_STATE_EVIDENCE_FINGERPRINT_INVALID");
        }

        JsonNode stateBindings =
                state.path("statefulBindings");
        requireOrdered(
                stateBindings,
                MirrorEvidenceVerifier::compareStateBinding,
                value -> value.path(
                        "invocationSiteId").asText(),
                "MIRROR_STATE_BINDING_ORDER_INVALID");
        Map<String, JsonNode> stateBindingBySite =
                new HashMap<>();
        for (JsonNode stateBinding : stateBindings) {
            String site = stateBinding.path(
                    "invocationSiteId").asText();
            JsonNode binding = bindingBySite.get(site);
            String interaction =
                    stateBinding.path("interaction").asText();
            boolean exactSpec =
                    "READ".equals(interaction)
                            ? stateBinding.hasNonNull(
                            "stateReadSpecRef")
                            && !stateBinding.hasNonNull(
                            "writeEffectRef")
                            : "WRITE".equals(interaction)
                            && stateBinding.hasNonNull(
                            "writeEffectRef")
                            && !stateBinding.hasNonNull(
                            "stateReadSpecRef");
            if (binding == null || !exactSpec
                    || !binding.path("graphPath").equals(
                    stateBinding.path("graphPath"))
                    || !binding.path("capabilityRef").equals(
                    stateBinding.path("capabilityRef"))
                    || stateBindingBySite.put(
                    site, stateBinding) != null) {
                fail("MIRROR_STATE_BINDING_CLOSURE_INVALID");
            }
        }
        Map<String, JsonNode> expectedStateAttempts =
                new HashMap<>();
        expectedAttempts.forEach((coordinate, attempt) -> {
            String site = coordinate.substring(
                    0, coordinate.indexOf('\0'));
            if (stateBindingBySite.containsKey(site)) {
                expectedStateAttempts.put(
                        coordinate, attempt);
            }
        });

        Set<String> actualInteractions =
                new HashSet<>();
        JsonNode accesses = state.path("accesses");
        requireOrdered(
                accesses,
                MirrorEvidenceVerifier::compareStateAccess,
                MirrorEvidenceVerifier
                        ::stateAccessCoordinate,
                "MIRROR_STATE_ACCESS_ORDER_INVALID");
        for (JsonNode access : accesses) {
            String coordinate =
                    stateAccessCoordinate(access);
            JsonNode binding = stateBindingBySite.get(
                    access.path("invocationSiteId")
                            .asText());
            JsonNode attempt =
                    expectedStateAttempts.get(coordinate);
            JsonNode resolution =
                    resolutionByCoordinate.get(coordinate);
            long observed = access.path(
                    "observedStateRevision").asLong();
            if (binding == null
                    || !"READ".equals(
                    binding.path("interaction").asText())
                    || attempt == null || resolution == null
                    || !binding.path("graphPath").equals(
                    access.path("graphPath"))
                    || !binding.path("capabilityRef").equals(
                    access.path("capabilityRef"))
                    || !binding.path("stateReadSpecRef").equals(
                    access.path("stateReadSpecRef"))
                    || observed < initialRevision
                    || observed > finalRevision
                    || access.path("observedStateRef")
                    .path("revision").asLong()
                    != observed + 1
                    || !access.path("observedStateRef")
                    .path("id").equals(
                            state.path("sessionStateRef")
                                    .path("id"))
                    || !access.path("requestFingerprint")
                    .equals(attempt.path(
                            "inputFingerprint"))
                    || !access.path("requestFingerprint")
                    .equals(resolution.path(
                            "requestFingerprint"))
                    || !actualInteractions.add(
                    coordinate)) {
                fail("MIRROR_STATE_ACCESS_CLOSURE_INVALID");
            }
            verifyTransitionStateOutcome(
                    state, access, attempt, resolution);
        }

        JsonNode transitions = state.path("transitions");
        requireOrdered(
                transitions,
                MirrorEvidenceVerifier::compareStateAccess,
                MirrorEvidenceVerifier
                        ::stateAccessCoordinate,
                "MIRROR_STATE_TRANSITION_ORDER_INVALID");
        Map<Long, JsonNode> committedByRevision =
                new java.util.TreeMap<>();
        for (JsonNode transition : transitions) {
            String coordinate =
                    stateAccessCoordinate(transition);
            JsonNode binding = stateBindingBySite.get(
                    transition.path("invocationSiteId")
                            .asText());
            JsonNode attempt =
                    expectedStateAttempts.get(coordinate);
            JsonNode resolution =
                    resolutionByCoordinate.get(coordinate);
            long before =
                    transition.path("revisionBefore")
                            .asLong();
            long after =
                    transition.path("revisionAfter")
                            .asLong();
            boolean replayed =
                    transition.path("replayed").asBoolean();
            if (binding == null
                    || !"WRITE".equals(
                    binding.path("interaction").asText())
                    || attempt == null || resolution == null
                    || !binding.path("graphPath").equals(
                    transition.path("graphPath"))
                    || !binding.path("capabilityRef").equals(
                    transition.path("capabilityRef"))
                    || !binding.path("writeEffectRef").equals(
                    transition.path("writeEffectRef"))
                    || before < initialRevision
                    || after > finalRevision
                    || transition.path("initialStateRef")
                    .path("revision").asLong()
                    != before + 1
                    || transition.path("finalStateRef")
                    .path("revision").asLong()
                    != after + 1
                    || !transition.path(
                    "requestFingerprint").equals(
                    attempt.path("inputFingerprint"))
                    || !transition.path(
                    "requestFingerprint").equals(
                    resolution.path(
                            "requestFingerprint"))
                    || !transition.path(
                    "responseFingerprint").equals(
                    attempt.path("outputFingerprint"))
                    || !transition.path(
                    "responseFingerprint").equals(
                    resolution.path(
                            "outputFingerprint"))
                    || !"SESSION_STATE".equals(
                    resolution.path("source").asText())
                    || !"RESOLVED".equals(
                    resolution.path("status").asText())
                    || !containsArtifact(
                    resolution.path("matchedArtifactRefs"),
                    transition.path("finalStateRef"))
                    || !containsArtifact(
                    resolution.path("matchedArtifactRefs"),
                    state.path("stateModelRef"))
                    || !containsArtifact(
                    resolution.path("matchedArtifactRefs"),
                    transition.path("writeEffectRef"))
                    || !containsText(
                    resolution.path("matchedRuleRefs"),
                    "transaction-receipt:"
                            + transition.path(
                            "receiptFingerprint").asText())
                    || !actualInteractions.add(
                    coordinate)) {
                fail("MIRROR_STATE_TRANSITION_CLOSURE_INVALID");
            }
            if (replayed) {
                if (before != after
                        || !transition.path(
                        "initialStateRef").equals(
                        transition.path(
                                "finalStateRef"))
                        || !transition.path(
                        "initialWorldFingerprint").equals(
                        transition.path(
                                "finalWorldFingerprint"))) {
                    fail("MIRROR_STATE_REPLAY_PROGRESSION_INVALID");
                }
            } else {
                if (after != before + 1
                        || !transition.path(
                        "resultingWorldFingerprint").equals(
                        transition.path(
                                "finalWorldFingerprint"))
                        || committedByRevision.put(
                        after, transition) != null) {
                    fail("MIRROR_STATE_COMMIT_PROGRESSION_INVALID");
                }
            }
            verifyTransitionEvents(
                    transition, replayed, after);
        }
        if (!actualInteractions.equals(
                expectedStateAttempts.keySet())) {
            fail("MIRROR_STATE_INTERACTION_CLOSURE_INCOMPLETE");
        }
        long expectedRevision = initialRevision;
        JsonNode previous =
                state.path("sessionStateRef");
        for (JsonNode transition
                : committedByRevision.values()) {
            if (transition.path("revisionBefore")
                    .asLong() != expectedRevision
                    || !transition.path(
                    "initialStateRef").equals(previous)) {
                fail("MIRROR_STATE_COMMIT_CHAIN_INVALID");
            }
            expectedRevision = transition.path(
                    "revisionAfter").asLong();
            previous = transition.path(
                    "finalStateRef");
        }
        if (expectedRevision != finalRevision
                || !previous.equals(
                state.path("finalSessionStateRef"))) {
            fail("MIRROR_STATE_FINAL_HEAD_INVALID");
        }
        requireStringOrder(
                state.path("limitations"),
                "MIRROR_STATE_LIMITATION_ORDER_INVALID");
    }

    private static void verifyTransitionStateOutcome(
            JsonNode state,
            JsonNode access,
            JsonNode attempt,
            JsonNode resolution) {
        String outcome = access.path("outcome").asText();
        String source =
                resolution.path("source").asText();
        if ("ABSENT".equals(outcome)) {
            if ("SESSION_STATE".equals(source)) {
                fail("MIRROR_STATE_ABSENT_RESOLUTION_INVALID");
            }
            return;
        }
        if (!"SESSION_STATE".equals(source)
                || !containsArtifact(
                resolution.path("matchedArtifactRefs"),
                access.path("observedStateRef"))
                || !containsArtifact(
                resolution.path("matchedArtifactRefs"),
                state.path("stateModelRef"))
                || !containsArtifact(
                resolution.path("matchedArtifactRefs"),
                access.path("stateReadSpecRef"))) {
            fail("MIRROR_STATE_READ_PROVENANCE_INVALID");
        }
        if ("LIVE_ENTITY".equals(outcome)) {
            if (!access.path(
            "projectedOutputFingerprint").equals(
                    attempt.path("outputFingerprint"))
                    || !access.path(
            "projectedOutputFingerprint").equals(
                    resolution.path(
                            "outputFingerprint"))) {
                fail("MIRROR_STATE_LIVE_RESOLUTION_INVALID");
            }
        } else if ("TOMBSTONED".equals(outcome)) {
            if (!access.path("errorCode").equals(
                    resolution.path("error")
                            .path("code"))) {
                fail("MIRROR_STATE_TOMBSTONE_RESOLUTION_INVALID");
            }
        } else {
            fail("MIRROR_STATE_OUTCOME_INVALID");
        }
    }

    private static void verifyTransitionEvents(
            JsonNode transition,
            boolean replayed,
            long revisionAfter) {
        Set<String> eventIds = new HashSet<>();
        for (JsonNode event
                : transition.path("events")) {
            if (!eventIds.add(event.path(
                    "eventIdFingerprint").asText())
                    || !replayed
                    && event.path("stateRevision").asLong()
                    != revisionAfter) {
                fail("MIRROR_STATE_EVENT_CLOSURE_INVALID");
            }
        }
    }

    private static void verifyStateOutcome(
            JsonNode state,
            JsonNode access,
            JsonNode attempt,
            JsonNode resolution) {
        String outcome = access.path("outcome").asText();
        String source = resolution.path("source").asText();
        switch (outcome) {
            case "LIVE_ENTITY" -> {
                if (!"SESSION_STATE".equals(source)
                        || !access.path(
                        "projectedOutputFingerprint").equals(
                        attempt.path("outputFingerprint"))
                        || !access.path(
                        "projectedOutputFingerprint").equals(
                        resolution.path("outputFingerprint"))
                        || !containsArtifact(
                        resolution.path("matchedArtifactRefs"),
                        state.path("sessionStateRef"))
                        || !containsArtifact(
                        resolution.path("matchedArtifactRefs"),
                        state.path("stateModelRef"))
                        || !containsArtifact(
                        resolution.path("matchedArtifactRefs"),
                        access.path("stateReadSpecRef"))) {
                    fail("MIRROR_STATE_LIVE_RESOLUTION_INVALID");
                }
            }
            case "TOMBSTONED" -> {
                if (!"SESSION_STATE".equals(source)
                        || !"RG.MIRROR.STATE.ENTITY_TOMBSTONED"
                        .equals(access.path("errorCode").asText())
                        || !access.path("errorCode").equals(
                        resolution.path("error").path("code"))
                        || !containsArtifact(
                        resolution.path("matchedArtifactRefs"),
                        state.path("sessionStateRef"))
                        || !containsArtifact(
                        resolution.path("matchedArtifactRefs"),
                        state.path("stateModelRef"))
                        || !containsArtifact(
                        resolution.path("matchedArtifactRefs"),
                        access.path("stateReadSpecRef"))) {
                    fail("MIRROR_STATE_TOMBSTONE_RESOLUTION_INVALID");
                }
            }
            case "ABSENT" -> {
                if ("SESSION_STATE".equals(source)) {
                    fail("MIRROR_STATE_ABSENT_RESOLUTION_INVALID");
                }
            }
            default -> fail("MIRROR_STATE_OUTCOME_INVALID");
        }
    }

    private static boolean containsArtifact(
            JsonNode values, JsonNode expected) {
        for (JsonNode value : values) {
            if (value.equals(expected)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsText(
            JsonNode values, String expected) {
        for (JsonNode value : values) {
            if (expected.equals(value.asText())) {
                return true;
            }
        }
        return false;
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

    private static int compareStateBinding(
            JsonNode left, JsonNode right) {
        return Comparator.comparing(
                (JsonNode value) -> value.path(
                        "invocationSiteId").asText())
                .thenComparing(value ->
                        value.path("graphPath").asText())
                .thenComparing(value ->
                        value.path("capabilityRef").path("id").asText())
                .thenComparing(value ->
                        value.path("stateReadSpecRef").path("id").asText())
                .compare(left, right);
    }

    private static int compareStateAccess(
            JsonNode left, JsonNode right) {
        return Comparator.comparing(
                (JsonNode value) -> value.path(
                        "invocationSiteId").asText())
                .thenComparing(value ->
                        value.path("correlationKey").asText())
                .thenComparingInt(value ->
                        value.path("occurrence").asInt())
                .thenComparingInt(value ->
                        value.path("attempt").asInt())
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

    private static String stateAccessCoordinate(
            JsonNode access) {
        return access.path("invocationSiteId").asText() + '\0'
                + access.path("correlationKey").asText() + '\0'
                + access.path("occurrence").asInt() + '\0'
                + access.path("attempt").asInt();
    }

    private static ObjectNode signatureMaterial(JsonNode attestation) {
        ObjectNode material = JSON.createObjectNode();
        String version = attestation.path("schemaVersion").asText();
        String domain = switch (version) {
            case CapabilityMirrorProtocol.MIRROR_EVIDENCE_ATTESTATION_V1 ->
                    SIGNATURE_DOMAIN_V1;
            case CapabilityMirrorProtocol.MIRROR_EVIDENCE_ATTESTATION_V2 ->
                    SIGNATURE_DOMAIN_V2;
            case CapabilityMirrorProtocol.MIRROR_EVIDENCE_ATTESTATION_V3 ->
                    SIGNATURE_DOMAIN_V3;
            case CapabilityMirrorProtocol.MIRROR_EVIDENCE_ATTESTATION_V4 ->
                    SIGNATURE_DOMAIN_V4;
            default -> throw new IllegalArgumentException(
                    "unsupported mirror evidence attestation version");
        };
        material.put("domain", domain);
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
        if (CapabilityMirrorProtocol.MIRROR_EVIDENCE_BUNDLE_V3.equals(version)) {
            return CapabilityMirrorProtocol.MIRROR_EVIDENCE_BUNDLE_V3_SCHEMA_RESOURCE;
        }
        if (CapabilityMirrorProtocol.MIRROR_EVIDENCE_BUNDLE_V4.equals(version)) {
            return CapabilityMirrorProtocol.MIRROR_EVIDENCE_BUNDLE_V4_SCHEMA_RESOURCE;
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
