package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Objects;

/**
 * Dependency-light offline verifier for one detached read-only Shadow source binding.
 *
 * <p>The verifier does not link Resource Gateway server or Spring classes. It treats the binding,
 * expected job coordinates, and candidate evidence as hostile inputs; applies the packaged strict
 * schemas; recomputes the nested baseline and outer binding content addresses; independently
 * verifies the candidate evidence; closes every source, plan, capability, scope, request, and time
 * coordinate; applies local key policy; and verifies the domain-separated Ed25519 seal. Results
 * are payload-free and bounded for CI or governance logs.</p>
 */
public final class ReadOnlyShadowSourceBindingVerifier {
    /** Maximum canonical nested baseline or complete binding bytes. */
    public static final int MAXIMUM_BINDING_BYTES = 2 * 1024 * 1024;
    /** Maximum domain-separated seal material bytes. */
    public static final int MAXIMUM_ATTESTATION_BYTES = 16 * 1024;
    private static final Duration MAXIMUM_CLOCK_SKEW =
            Duration.ofMinutes(2);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String BASELINE_DOMAIN =
            "RESOURCE_GATEWAY_READ_ONLY_SHADOW_BASELINE_OBSERVATION_V1";
    private static final String BINDING_DOMAIN =
            "RESOURCE_GATEWAY_READ_ONLY_SHADOW_SOURCE_BINDING_V1";

    private final CandidateEvidenceVerifier candidateVerifier;

    /** Creates an independent verifier using the packaged mirror-evidence verifier. */
    public ReadOnlyShadowSourceBindingVerifier() {
        this(new MirrorEvidenceVerifier()::verify);
    }

    ReadOnlyShadowSourceBindingVerifier(
            CandidateEvidenceVerifier candidateVerifier) {
        this.candidateVerifier = Objects.requireNonNull(
                candidateVerifier, "candidateVerifier");
    }

    @FunctionalInterface
    interface CandidateEvidenceVerifier {
        MirrorEvidenceVerifier.VerificationResult verify(
                JsonNode bundle,
                EvidenceVerificationKey key);
    }

    /** Bounded source-binding verification outcome. */
    public enum Outcome {
        /** Every structure, closure, content-address, key, and signature check passed. */
        VERIFIED,
        /** Structure, semantics, content addressing, or signature is invalid. */
        INVALID,
        /** The exact job-bound source reference or enterprise scope does not match. */
        BINDING_MISMATCH,
        /** The independently verified candidate evidence does not close the binding. */
        CANDIDATE_INVALID,
        /** The exact source-binding verification key is unavailable. */
        KEY_UNAVAILABLE,
        /** Signature algorithm or key lifecycle policy rejected the binding. */
        POLICY_REJECTED,
        /** The binding is not active at the caller's trusted verification time. */
        WINDOW_REJECTED
    }

    /**
     * Exact consumer-side coordinates required for a complete verification.
     *
     * <p>The expected reference must be the v2 job request's {@code sourceBindingRef}. The
     * candidate bundle and key are verified independently rather than accepted as producer
     * labels.</p>
     *
     * @param expectedScope authenticated job scope
     * @param expectedSourceBindingRef exact job-bound source-binding reference
     * @param candidateEvidenceBundle exact referenced candidate evidence bundle
     * @param candidateEvidenceKey independently resolved candidate-evidence key
     * @param verificationTime trusted consumer clock
     */
    public record VerificationContext(
            JsonNode expectedScope,
            JsonNode expectedSourceBindingRef,
            JsonNode candidateEvidenceBundle,
            EvidenceVerificationKey candidateEvidenceKey,
            Instant verificationTime
    ) {
        /** Defensively copies untrusted JSON coordinates. */
        public VerificationContext {
            expectedScope = copyObject(
                    expectedScope, "expectedScope");
            expectedSourceBindingRef = copyObject(
                    expectedSourceBindingRef,
                    "expectedSourceBindingRef");
            candidateEvidenceBundle = copyObject(
                    candidateEvidenceBundle,
                    "candidateEvidenceBundle");
            verificationTime = Objects.requireNonNull(
                    verificationTime, "verificationTime");
        }

        /**
         * Returns the immutable-by-copy expected enterprise scope.
         *
         * @return defensive copy of the authenticated job scope
         */
        @Override
        public JsonNode expectedScope() {
            return expectedScope.deepCopy();
        }

        /**
         * Returns the immutable-by-copy exact source reference.
         *
         * @return defensive copy of the v2 job source-binding reference
         */
        @Override
        public JsonNode expectedSourceBindingRef() {
            return expectedSourceBindingRef.deepCopy();
        }

        /**
         * Returns the immutable-by-copy candidate evidence bundle.
         *
         * @return defensive copy independently passed to the evidence verifier
         */
        @Override
        public JsonNode candidateEvidenceBundle() {
            return candidateEvidenceBundle.deepCopy();
        }
    }

    /**
     * Payload-free result safe for build logs and correctness workbooks.
     *
     * @param outcome bounded verification outcome
     * @param reasonCode stable machine-readable reason
     * @param bindingId source-binding identity, or blank when unavailable
     * @param revision immutable binding revision, or zero when unavailable
     * @param bindingFingerprint complete binding content address, or blank
     * @param candidateRunId referenced candidate run identity, or blank
     * @param bindingKeyId source-binding authority key identity, or blank
     * @param candidateKeyId candidate evidence key identity, or blank
     */
    public record VerificationResult(
            Outcome outcome,
            String reasonCode,
            String bindingId,
            long revision,
            String bindingFingerprint,
            String candidateRunId,
            String bindingKeyId,
            String candidateKeyId
    ) {
        /** Normalizes one bounded payload-free result. */
        public VerificationResult {
            outcome = Objects.requireNonNull(
                    outcome, "outcome");
            reasonCode = bounded(reasonCode, 255);
            bindingId = boundedOptional(bindingId, 512);
            bindingFingerprint = fingerprintOptional(
                    bindingFingerprint);
            candidateRunId = boundedOptional(
                    candidateRunId, 512);
            bindingKeyId = boundedOptional(
                    bindingKeyId, 255);
            candidateKeyId = boundedOptional(
                    candidateKeyId, 255);
            if (!reasonCode.matches(
                    "[A-Z][A-Z0-9_.-]{0,254}")
                    || revision < 0) {
                throw new IllegalArgumentException(
                        "Shadow source-binding verification result is invalid");
            }
        }

        /**
         * Reports whether every independent source-pair check passed.
         *
         * @return true only for a fully verified exact binding and candidate
         */
        public boolean verified() {
            return outcome == Outcome.VERIFIED;
        }
    }

    /**
     * Independently verifies one source binding and its exact candidate evidence.
     *
     * @param binding untrusted decoded signed source binding
     * @param bindingKey independently resolved source-binding authority key
     * @param context exact consumer-side job and candidate coordinates
     * @return bounded payload-free verification result
     */
    public VerificationResult verify(
            JsonNode binding,
            EvidenceVerificationKey bindingKey,
            VerificationContext context) {
        Coordinates coordinates = Coordinates.from(
                binding, bindingKey, context);
        try {
            CapabilityMirrorSchemaValidator.require(
                    binding,
                    CapabilityMirrorProtocol
                            .READ_ONLY_SHADOW_SOURCE_BINDING_SCHEMA_RESOURCE,
                    "RG.MIRROR.CLIENT.SHADOW_SOURCE_BINDING_SCHEMA_INVALID");
        } catch (RuntimeException invalid) {
            return result(
                    Outcome.INVALID,
                    "SHADOW_SOURCE_BINDING_SCHEMA_INVALID",
                    coordinates);
        }
        if (context == null
                || !binding.path("scope").equals(
                context.expectedScope())
                || !matchesSourceReference(
                binding,
                context.expectedSourceBindingRef())) {
            return result(
                    Outcome.BINDING_MISMATCH,
                    "SHADOW_SOURCE_BINDING_EXPECTATION_MISMATCH",
                    coordinates);
        }

        Instant issuedAt;
        Instant validFrom;
        Instant expiresAt;
        try {
            issuedAt = instant(
                    binding.path("issuedAt"));
            validFrom = instant(
                    binding.path("validFrom"));
            expiresAt = instant(
                    binding.path("expiresAt"));
            Instant baselineObservedAt = instant(
                    binding.at("/baseline/observedAt"));
            if (issuedAt.isBefore(baselineObservedAt)
                    || issuedAt.isAfter(validFrom)
                    || !expiresAt.isAfter(validFrom)) {
                throw new VerificationFailure(
                        "SHADOW_SOURCE_BINDING_TIME_INVALID");
            }
            verifyFingerprints(binding);
        } catch (VerificationFailure invalid) {
            return result(
                    Outcome.INVALID,
                    invalid.reasonCode,
                    coordinates);
        } catch (RuntimeException invalid) {
            return result(
                    Outcome.INVALID,
                    "SHADOW_SOURCE_BINDING_MATERIAL_INVALID",
                    coordinates);
        }
        if (context.verificationTime().isBefore(validFrom)
                || !context.verificationTime().isBefore(
                expiresAt)) {
            return result(
                    Outcome.WINDOW_REJECTED,
                    "SHADOW_SOURCE_BINDING_OUTSIDE_VALIDITY_WINDOW",
                    coordinates);
        }

        VerificationResult candidateFailure =
                verifyCandidate(
                        binding, context, issuedAt, coordinates);
        if (candidateFailure != null) {
            return candidateFailure;
        }

        JsonNode seal = binding.path("bindingSeal");
        if (bindingKey == null) {
            return result(
                    Outcome.KEY_UNAVAILABLE,
                    "SHADOW_SOURCE_BINDING_KEY_UNAVAILABLE",
                    coordinates);
        }
        if (!bindingKey.keyId().equals(
                text(seal, "keyId"))) {
            return result(
                    Outcome.INVALID,
                    "SHADOW_SOURCE_BINDING_KEY_ID_MISMATCH",
                    coordinates);
        }
        if (!"Ed25519".equals(bindingKey.algorithm())
                || !bindingKey.algorithm().equals(
                text(seal, "algorithm"))) {
            return result(
                    Outcome.POLICY_REJECTED,
                    "SHADOW_SOURCE_BINDING_ALGORITHM_REJECTED",
                    coordinates);
        }
        Instant signedAt;
        try {
            signedAt = instant(seal.path("signedAt"));
        } catch (VerificationFailure invalid) {
            return result(
                    Outcome.INVALID,
                    "SHADOW_SOURCE_BINDING_SEAL_TIME_INVALID",
                    coordinates);
        }
        if (!bindingKey.verificationAllowed()
                || signedAt.isBefore(
                issuedAt.minus(MAXIMUM_CLOCK_SKEW))
                || signedAt.isAfter(
                context.verificationTime()
                        .plus(MAXIMUM_CLOCK_SKEW))
                || signedAt.isBefore(
                bindingKey.createdAt().minus(
                        EvidenceVerificationSupport
                                .KEY_CREATION_SKEW))) {
            return result(
                    Outcome.POLICY_REJECTED,
                    "SHADOW_SOURCE_BINDING_KEY_POLICY_REJECTED",
                    coordinates);
        }
        try {
            String materialFingerprint =
                    EvidenceVerificationSupport
                            .sha256Bounded(
                                    attestationMaterial(binding),
                                    MAXIMUM_ATTESTATION_BYTES);
            if (!materialFingerprint.equals(
                    text(seal, "materialFingerprint"))) {
                return result(
                        Outcome.INVALID,
                        "SHADOW_SOURCE_BINDING_ATTESTATION_MATERIAL_INVALID",
                        coordinates);
            }
            if (!EvidenceVerificationSupport.verifyEd25519(
                    materialFingerprint,
                    text(seal, "signature"),
                    bindingKey.encodedPublicKey())) {
                return result(
                        Outcome.INVALID,
                        "SHADOW_SOURCE_BINDING_SIGNATURE_INVALID",
                        coordinates);
            }
            return result(
                    Outcome.VERIFIED,
                    "VERIFIED",
                    coordinates);
        } catch (GeneralSecurityException
                 | RuntimeException invalid) {
            return result(
                    Outcome.INVALID,
                    "SHADOW_SOURCE_BINDING_SIGNATURE_MATERIAL_INVALID",
                    coordinates);
        }
    }

    private VerificationResult verifyCandidate(
            JsonNode binding,
            VerificationContext context,
            Instant issuedAt,
            Coordinates coordinates) {
        MirrorEvidenceVerifier.VerificationResult verified =
                candidateVerifier.verify(
                        context.candidateEvidenceBundle(),
                        context.candidateEvidenceKey());
        if (!verified.verified()) {
            return result(
                    Outcome.CANDIDATE_INVALID,
                    "SHADOW_SOURCE_BINDING_CANDIDATE_"
                            + verified.reasonCode(),
                    coordinates);
        }
        JsonNode candidate =
                context.candidateEvidenceBundle();
        JsonNode evidence = candidate.path("evidence");
        JsonNode reference =
                binding.path("candidateEvidenceRef");
        JsonNode plan =
                binding.path("candidatePlanRef");
        if (!"MIRROR_EVIDENCE_BUNDLE".equals(
                text(reference, "kind"))
                || reference.path("revision").asLong() != 1
                || !text(reference, "id").equals(
                text(evidence, "runId"))
                || !text(reference, "fingerprint").equals(
                text(candidate, "bundleFingerprint"))
                || !text(reference, "id").equals(
                verified.runId())
                || !text(reference, "fingerprint").equals(
                verified.bundleFingerprint())
                || !binding.path("scope").equals(
                evidence.path("scope"))
                || !binding.path("targetCapabilityRef").equals(
                evidence.path("rootCapability"))
                || !text(plan, "id").equals(
                text(evidence, "planId"))
                || !text(plan, "fingerprint").equals(
                text(evidence, "planFingerprint"))
                || !text(plan, "fingerprint").equals(
                verified.planFingerprint())
                || !text(binding, "requestContextFingerprint")
                .equals(text(
                        evidence,
                        "requestContextFingerprint"))) {
            return result(
                    Outcome.CANDIDATE_INVALID,
                    "SHADOW_SOURCE_BINDING_CANDIDATE_CLOSURE_INVALID",
                    coordinates);
        }
        try {
            if (instant(evidence.path("completedAt"))
                    .isAfter(issuedAt)) {
                throw new VerificationFailure(
                        "SHADOW_SOURCE_BINDING_CANDIDATE_TIME_INVALID");
            }
        } catch (VerificationFailure invalid) {
            return result(
                    Outcome.CANDIDATE_INVALID,
                    invalid.reasonCode,
                    coordinates);
        }
        return null;
    }

    private static boolean matchesSourceReference(
            JsonNode binding,
            JsonNode reference) {
        return reference != null
                && reference.isObject()
                && reference.size() == 4
                && "SHADOW_SOURCE_BINDING".equals(
                text(reference, "kind"))
                && text(binding, "bindingId").equals(
                text(reference, "id"))
                && binding.path("revision").asLong()
                == reference.path("revision").asLong()
                && text(binding, "bindingFingerprint")
                .equals(text(reference, "fingerprint"));
    }

    private static void verifyFingerprints(
            JsonNode binding) {
        String baselineFingerprint =
                EvidenceVerificationSupport.sha256Bounded(
                        baselineMaterial(binding),
                        MAXIMUM_BINDING_BYTES);
        if (!baselineFingerprint.equals(
                text(binding,
                        "baselineObservationFingerprint"))) {
            throw new VerificationFailure(
                    "SHADOW_SOURCE_BINDING_BASELINE_FINGERPRINT_INVALID");
        }
        ObjectNode material =
                copyObject(binding, "binding");
        material.put("bindingFingerprint", "");
        material.remove("bindingSeal");
        String bindingFingerprint =
                EvidenceVerificationSupport.sha256Bounded(
                        material, MAXIMUM_BINDING_BYTES);
        if (!bindingFingerprint.equals(
                text(binding, "bindingFingerprint"))) {
            throw new VerificationFailure(
                    "SHADOW_SOURCE_BINDING_FINGERPRINT_INVALID");
        }
    }

    private static ObjectNode baselineMaterial(
            JsonNode binding) {
        ObjectNode material = JSON.createObjectNode();
        material.put("domain", BASELINE_DOMAIN);
        material.put(
                "schemaVersion",
                text(binding, "schemaVersion"));
        material.put("bindingId",
                text(binding, "bindingId"));
        material.put("revision",
                binding.path("revision").asLong());
        material.set("scope",
                binding.path("scope").deepCopy());
        material.set("targetCapabilityRef",
                binding.path("targetCapabilityRef")
                        .deepCopy());
        material.set("baselineBindingRef",
                binding.path("baselineBindingRef")
                        .deepCopy());
        material.set("comparisonPolicyRef",
                binding.path("comparisonPolicyRef")
                        .deepCopy());
        material.put("requestContextFingerprint",
                text(binding,
                        "requestContextFingerprint"));
        material.set("baseline",
                binding.path("baseline").deepCopy());
        return material;
    }

    private static ObjectNode attestationMaterial(
            JsonNode binding) {
        ObjectNode material = JSON.createObjectNode();
        material.put("domain", BINDING_DOMAIN);
        material.put(
                "schemaVersion",
                text(binding, "schemaVersion"));
        material.put("bindingId",
                text(binding, "bindingId"));
        material.put("revision",
                binding.path("revision").asLong());
        material.set("scope",
                binding.path("scope").deepCopy());
        material.put("issuedAt",
                text(binding, "issuedAt"));
        material.put("bindingFingerprint",
                text(binding, "bindingFingerprint"));
        return material;
    }

    private static Instant instant(JsonNode value) {
        try {
            return Instant.parse(
                    value == null ? "" : value.asText());
        } catch (DateTimeParseException invalid) {
            throw new VerificationFailure(
                    "SHADOW_SOURCE_BINDING_TIME_INVALID");
        }
    }

    private static VerificationResult result(
            Outcome outcome,
            String reasonCode,
            Coordinates coordinates) {
        return new VerificationResult(
                outcome,
                reasonCode,
                coordinates.bindingId,
                coordinates.revision,
                coordinates.bindingFingerprint,
                coordinates.candidateRunId,
                coordinates.bindingKeyId,
                coordinates.candidateKeyId);
    }

    private static String text(
            JsonNode value, String field) {
        return value == null
                ? "" : value.path(field).asText("");
    }

    private static ObjectNode copyObject(
            JsonNode value, String field) {
        if (value == null || !value.isObject()) {
            throw new IllegalArgumentException(
                    field + " must be an object");
        }
        return ((ObjectNode) value).deepCopy();
    }

    private static String bounded(
            String value, int maximum) {
        String exact = value == null ? "" : value.trim();
        if (exact.isBlank() || exact.length() > maximum) {
            throw new IllegalArgumentException(
                    "bounded value is invalid");
        }
        return exact;
    }

    private static String boundedOptional(
            String value, int maximum) {
        String exact = value == null ? "" : value.trim();
        return exact.length() <= maximum ? exact : "";
    }

    private static String fingerprintOptional(
            String value) {
        String exact = boundedOptional(value, 71);
        return exact.matches("sha256:[a-f0-9]{64}")
                ? exact : "";
    }

    private record Coordinates(
            String bindingId,
            long revision,
            String bindingFingerprint,
            String candidateRunId,
            String bindingKeyId,
            String candidateKeyId
    ) {
        private static Coordinates from(
                JsonNode binding,
                EvidenceVerificationKey bindingKey,
                VerificationContext context) {
            JsonNode candidate =
                    context == null
                            ? null
                            : context.candidateEvidenceBundle();
            return new Coordinates(
                    text(binding, "bindingId"),
                    binding == null
                            ? 0
                            : Math.max(
                            0,
                            binding.path("revision")
                                    .asLong()),
                    text(binding,
                            "bindingFingerprint"),
                    candidate == null
                            ? ""
                            : candidate.at(
                            "/evidence/runId")
                            .asText(""),
                    bindingKey == null
                            ? ""
                            : bindingKey.keyId(),
                    context == null
                            || context.candidateEvidenceKey()
                            == null
                            ? ""
                            : context.candidateEvidenceKey()
                            .keyId());
        }
    }

    private static final class VerificationFailure
            extends RuntimeException {
        private final String reasonCode;

        private VerificationFailure(String reasonCode) {
            this.reasonCode = reasonCode;
        }
    }
}
