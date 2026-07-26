package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Objects;

/**
 * Dependency-light hostile-input verifier for one signed detached Shadow source-resolution proof.
 *
 * <p>The verifier does not link Resource Gateway server or Spring classes. It validates the
 * packaged strict schema, exact caller coordinates, deterministic identity, content address,
 * Ed25519 seal and key policy. It then independently verifies the exact signed source binding and
 * candidate evidence before recomputing the built-in payload-free policy reference and every
 * normalized baseline/candidate fact. Its result contains no business payload or free-form source
 * error.</p>
 */
public final class ReadOnlyShadowSourceResolutionAttestationVerifier {
    /** Maximum canonical source-resolution attestation bytes. */
    public static final int MAXIMUM_ATTESTATION_BYTES = 4 * 1024 * 1024;
    /** Maximum domain-separated signature material bytes. */
    public static final int MAXIMUM_SEAL_MATERIAL_BYTES = 16 * 1024;
    private static final int MAXIMUM_IDENTITY_BYTES = 64 * 1024;
    private static final int MAXIMUM_POLICY_BYTES = 64 * 1024;
    private static final Duration MAXIMUM_CLOCK_SKEW =
            Duration.ofMinutes(2);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ATTESTATION_KIND =
            "SHADOW_SOURCE_RESOLUTION_ATTESTATION";
    private static final String POLICY_KIND =
            "SHADOW_COMPARISON_POLICY";
    private static final String POLICY_ID =
            "payload-free-equality-v1";
    private static final String POLICY_FINGERPRINT =
            "sha256:66cb081470a0492453c5a35bbf7e9b2bb530abc2dbaaf86be8a564bec4c11f43";
    private static final String ATTESTATION_DOMAIN =
            "RESOURCE_GATEWAY_READ_ONLY_SHADOW_SOURCE_RESOLUTION_ATTESTATION_V1";
    private static final String IDENTITY_DOMAIN =
            "RESOURCE_GATEWAY_READ_ONLY_SHADOW_SOURCE_RESOLUTION_IDENTITY_V1";
    private static final String POLICY_DOMAIN =
            "RESOURCE_GATEWAY_PAYLOAD_FREE_EQUALITY_POLICY_V1";

    private final SourceBindingVerifier sourceBindingVerifier;

    /** Creates a verifier backed by the independent packaged source-binding verifier. */
    public ReadOnlyShadowSourceResolutionAttestationVerifier() {
        this(new ReadOnlyShadowSourceBindingVerifier()::verify);
    }

    ReadOnlyShadowSourceResolutionAttestationVerifier(
            SourceBindingVerifier sourceBindingVerifier) {
        this.sourceBindingVerifier = Objects.requireNonNull(
                sourceBindingVerifier, "sourceBindingVerifier");
    }

    @FunctionalInterface
    interface SourceBindingVerifier {
        ReadOnlyShadowSourceBindingVerifier.VerificationResult verify(
                JsonNode binding,
                EvidenceVerificationKey bindingKey,
                ReadOnlyShadowSourceBindingVerifier.VerificationContext
                        context);
    }

    /** Closed source-resolution verification result classes. */
    public enum Outcome {
        /** Structure, source closure, content addresses, key policy, and signature all passed. */
        VERIFIED,
        /** Schema, temporal semantics, content address, identity, or signature is invalid. */
        INVALID,
        /** Authenticated caller coordinates do not identify this exact proof. */
        EXPECTATION_MISMATCH,
        /** The nested exact source binding or its candidate evidence failed verification. */
        SOURCE_BINDING_INVALID,
        /** The source-resolution authority key is unavailable. */
        KEY_UNAVAILABLE,
        /** Signature algorithm or key lifecycle policy rejected the proof. */
        POLICY_REJECTED,
        /** The proof is from the future relative to the trusted consumer clock. */
        WINDOW_REJECTED
    }

    /**
     * Authenticated consumer coordinates and independently resolved nested source artifacts.
     *
     * <p>The source-binding context supplies the exact job-bound source reference, candidate
     * evidence, candidate key, and authenticated scope. This verifier replaces that context's
     * verification time with the signed source-resolution issue time, proving that the source
     * binding was valid when it was consumed rather than requiring it to remain current forever.</p>
     *
     * @param expectedScope authenticated enterprise scope
     * @param expectedAttestationRef exact comparison-bound source-resolution reference
     * @param expectedRequestId durable Shadow request identity
     * @param expectedExecutionId stable logical execution identity
     * @param expectedAdmissionFingerprint exact online-authority admission identity
     * @param sourceBinding exact signed source-binding document
     * @param sourceBindingKey independently resolved source-binding authority key
     * @param sourceBindingContext exact source-binding and candidate verification coordinates
     * @param verificationTime trusted consumer clock
     */
    public record VerificationContext(
            JsonNode expectedScope,
            JsonNode expectedAttestationRef,
            String expectedRequestId,
            String expectedExecutionId,
            String expectedAdmissionFingerprint,
            JsonNode sourceBinding,
            EvidenceVerificationKey sourceBindingKey,
            ReadOnlyShadowSourceBindingVerifier.VerificationContext
                    sourceBindingContext,
            Instant verificationTime
    ) {
        /** Defensively copies all untrusted JSON and validates bounded expected identities. */
        public VerificationContext {
            expectedScope = copyObject(
                    expectedScope, "expectedScope");
            expectedAttestationRef = copyObject(
                    expectedAttestationRef,
                    "expectedAttestationRef");
            expectedRequestId = bounded(
                    expectedRequestId, 512);
            expectedExecutionId = bounded(
                    expectedExecutionId, 512);
            expectedAdmissionFingerprint = fingerprint(
                    expectedAdmissionFingerprint);
            sourceBinding = copyObject(
                    sourceBinding, "sourceBinding");
            sourceBindingContext = Objects.requireNonNull(
                    sourceBindingContext,
                    "sourceBindingContext");
            verificationTime = Objects.requireNonNull(
                    verificationTime, "verificationTime");
        }

        /**
         * Returns a defensive copy of the authenticated scope.
         *
         * @return immutable-by-copy expected scope
         */
        @Override
        public JsonNode expectedScope() {
            return expectedScope.deepCopy();
        }

        /**
         * Returns a defensive copy of the exact expected attestation reference.
         *
         * @return immutable-by-copy exact source-resolution reference
         */
        @Override
        public JsonNode expectedAttestationRef() {
            return expectedAttestationRef.deepCopy();
        }

        /**
         * Returns a defensive copy of the signed source binding.
         *
         * @return immutable-by-copy nested source binding
         */
        @Override
        public JsonNode sourceBinding() {
            return sourceBinding.deepCopy();
        }
    }

    /**
     * Payload-free source-resolution verification result.
     *
     * @param outcome bounded verification outcome
     * @param reasonCode stable machine-readable reason
     * @param attestationId source-resolution identity, or blank
     * @param revision immutable revision, or zero
     * @param attestationFingerprint complete content address, or blank
     * @param requestId durable request identity, or blank
     * @param executionId stable execution identity, or blank
     * @param attestationKeyId source-resolution authority key identity, or blank
     * @param sourceBindingReason nested source-binding reason, or blank
     */
    public record VerificationResult(
            Outcome outcome,
            String reasonCode,
            String attestationId,
            long revision,
            String attestationFingerprint,
            String requestId,
            String executionId,
            String attestationKeyId,
            String sourceBindingReason
    ) {
        /** Normalizes one bounded result suitable for CI and governance logs. */
        public VerificationResult {
            outcome = Objects.requireNonNull(
                    outcome, "outcome");
            reasonCode = reason(reasonCode);
            attestationId = boundedOptional(
                    attestationId, 512);
            attestationFingerprint = fingerprintOptional(
                    attestationFingerprint);
            requestId = boundedOptional(requestId, 512);
            executionId = boundedOptional(
                    executionId, 512);
            attestationKeyId = boundedOptional(
                    attestationKeyId, 255);
            sourceBindingReason = reasonOptional(
                    sourceBindingReason);
            if (revision < 0) {
                throw new IllegalArgumentException(
                        "source-resolution verification revision is invalid");
            }
        }

        /**
         * Reports whether every independent closure check passed.
         *
         * @return true only for a completely verified source-resolution proof
         */
        public boolean verified() {
            return outcome == Outcome.VERIFIED;
        }
    }

    /**
     * Independently verifies one exact signed source-resolution attestation.
     *
     * @param attestation untrusted decoded source-resolution proof
     * @param attestationKey independently resolved source-resolution authority key
     * @param context authenticated caller coordinates and nested source artifacts
     * @return bounded payload-free verification result
     */
    public VerificationResult verify(
            JsonNode attestation,
            EvidenceVerificationKey attestationKey,
            VerificationContext context) {
        Coordinates coordinates =
                Coordinates.from(
                        attestation, attestationKey);
        try {
            CapabilityMirrorSchemaValidator.require(
                    attestation,
                    CapabilityMirrorProtocol
                            .READ_ONLY_SHADOW_SOURCE_RESOLUTION_ATTESTATION_SCHEMA_RESOURCE,
                    "RG.MIRROR.CLIENT.SHADOW_SOURCE_RESOLUTION_SCHEMA_INVALID");
        } catch (RuntimeException invalid) {
            return result(
                    Outcome.INVALID,
                    "SHADOW_SOURCE_RESOLUTION_SCHEMA_INVALID",
                    coordinates,
                    "");
        }
        if (!matchesExpectations(
                attestation, context)) {
            return result(
                    Outcome.EXPECTATION_MISMATCH,
                    "SHADOW_SOURCE_RESOLUTION_EXPECTATION_MISMATCH",
                    coordinates,
                    "");
        }

        Instant admittedAt;
        Instant confirmedAt;
        Instant issuedAt;
        try {
            admittedAt = instant(
                    attestation.path("admittedAt"));
            confirmedAt = instant(
                    attestation.path("confirmedAt"));
            issuedAt = instant(
                    attestation.path("issuedAt"));
            requireTemporalClosure(
                    attestation,
                    admittedAt,
                    confirmedAt,
                    issuedAt);
            verifyIdentityAndFingerprint(
                    attestation);
        } catch (VerificationFailure invalid) {
            return result(
                    Outcome.INVALID,
                    invalid.reasonCode,
                    coordinates,
                    "");
        } catch (RuntimeException invalid) {
            return result(
                    Outcome.INVALID,
                    "SHADOW_SOURCE_RESOLUTION_MATERIAL_INVALID",
                    coordinates,
                    "");
        }
        if (issuedAt.isAfter(
                context.verificationTime()
                        .plus(MAXIMUM_CLOCK_SKEW))) {
            return result(
                    Outcome.WINDOW_REJECTED,
                    "SHADOW_SOURCE_RESOLUTION_FROM_FUTURE",
                    coordinates,
                    "");
        }

        ReadOnlyShadowSourceBindingVerifier.VerificationResult
                bindingResult =
                verifySourceBinding(
                        context, issuedAt);
        if (!bindingResult.verified()) {
            return result(
                    Outcome.SOURCE_BINDING_INVALID,
                    "SHADOW_SOURCE_RESOLUTION_SOURCE_BINDING_INVALID",
                    coordinates,
                    bindingResult.reasonCode());
        }
        try {
            verifySourceClosure(
                    attestation,
                    context.sourceBinding(),
                    context.sourceBindingContext()
                            .candidateEvidenceBundle());
        } catch (VerificationFailure invalid) {
            return result(
                    Outcome.INVALID,
                    invalid.reasonCode,
                    coordinates,
                    bindingResult.reasonCode());
        } catch (RuntimeException invalid) {
            return result(
                    Outcome.INVALID,
                    "SHADOW_SOURCE_RESOLUTION_SOURCE_CLOSURE_INVALID",
                    coordinates,
                    bindingResult.reasonCode());
        }

        JsonNode seal =
                attestation.path("attestationSeal");
        if (attestationKey == null) {
            return result(
                    Outcome.KEY_UNAVAILABLE,
                    "SHADOW_SOURCE_RESOLUTION_KEY_UNAVAILABLE",
                    coordinates,
                    bindingResult.reasonCode());
        }
        if (!attestationKey.keyId().equals(
                text(seal, "keyId"))) {
            return result(
                    Outcome.INVALID,
                    "SHADOW_SOURCE_RESOLUTION_KEY_ID_MISMATCH",
                    coordinates,
                    bindingResult.reasonCode());
        }
        if (!"Ed25519".equals(
                attestationKey.algorithm())
                || !attestationKey.algorithm().equals(
                text(seal, "algorithm"))) {
            return result(
                    Outcome.POLICY_REJECTED,
                    "SHADOW_SOURCE_RESOLUTION_ALGORITHM_REJECTED",
                    coordinates,
                    bindingResult.reasonCode());
        }
        Instant signedAt;
        try {
            signedAt = instant(
                    seal.path("signedAt"));
        } catch (VerificationFailure invalid) {
            return result(
                    Outcome.INVALID,
                    "SHADOW_SOURCE_RESOLUTION_SEAL_TIME_INVALID",
                    coordinates,
                    bindingResult.reasonCode());
        }
        if (!attestationKey.verificationAllowed()
                || signedAt.isBefore(
                issuedAt.minus(MAXIMUM_CLOCK_SKEW))
                || signedAt.isAfter(
                context.verificationTime()
                        .plus(MAXIMUM_CLOCK_SKEW))
                || signedAt.isBefore(
                attestationKey.createdAt().minus(
                        EvidenceVerificationSupport
                                .KEY_CREATION_SKEW))) {
            return result(
                    Outcome.POLICY_REJECTED,
                    "SHADOW_SOURCE_RESOLUTION_KEY_POLICY_REJECTED",
                    coordinates,
                    bindingResult.reasonCode());
        }
        try {
            String materialFingerprint =
                    EvidenceVerificationSupport
                            .sha256Bounded(
                                    attestationMaterial(
                                            attestation),
                                    MAXIMUM_SEAL_MATERIAL_BYTES);
            if (!materialFingerprint.equals(
                    text(seal,
                            "materialFingerprint"))) {
                return result(
                        Outcome.INVALID,
                        "SHADOW_SOURCE_RESOLUTION_SEAL_MATERIAL_INVALID",
                        coordinates,
                        bindingResult.reasonCode());
            }
            if (!EvidenceVerificationSupport
                    .verifyEd25519(
                            materialFingerprint,
                            text(seal, "signature"),
                            attestationKey
                                    .encodedPublicKey())) {
                return result(
                        Outcome.INVALID,
                        "SHADOW_SOURCE_RESOLUTION_SIGNATURE_INVALID",
                        coordinates,
                        bindingResult.reasonCode());
            }
            return result(
                    Outcome.VERIFIED,
                    "VERIFIED",
                    coordinates,
                    bindingResult.reasonCode());
        } catch (GeneralSecurityException
                 | RuntimeException invalid) {
            return result(
                    Outcome.INVALID,
                    "SHADOW_SOURCE_RESOLUTION_SIGNATURE_MATERIAL_INVALID",
                    coordinates,
                    bindingResult.reasonCode());
        }
    }

    private ReadOnlyShadowSourceBindingVerifier.VerificationResult
    verifySourceBinding(
            VerificationContext context,
            Instant sourceResolutionIssuedAt) {
        var supplied =
                context.sourceBindingContext();
        var exact =
                new ReadOnlyShadowSourceBindingVerifier
                        .VerificationContext(
                        supplied.expectedScope(),
                        supplied.expectedSourceBindingRef(),
                        supplied.candidateEvidenceBundle(),
                        supplied.candidateEvidenceKey(),
                        sourceResolutionIssuedAt);
        return sourceBindingVerifier.verify(
                context.sourceBinding(),
                context.sourceBindingKey(),
                exact);
    }

    private static boolean matchesExpectations(
            JsonNode attestation,
            VerificationContext context) {
        return context != null
                && attestation.path("scope")
                .equals(context.expectedScope())
                && text(attestation, "requestId")
                .equals(context.expectedRequestId())
                && text(attestation, "executionId")
                .equals(context.expectedExecutionId())
                && text(attestation,
                "admissionFingerprint")
                .equals(
                        context.expectedAdmissionFingerprint())
                && matchesReference(
                attestation,
                context.expectedAttestationRef(),
                "attestationId",
                "attestationFingerprint",
                ATTESTATION_KIND)
                && referencesEqual(
                attestation.path("sourceBindingRef"),
                        context.sourceBindingContext()
                                .expectedSourceBindingRef());
    }

    private static void requireTemporalClosure(
            JsonNode attestation,
            Instant admittedAt,
            Instant confirmedAt,
            Instant issuedAt) {
        Instant baselineSource =
                instant(attestation.at(
                        "/baseline/sourceCompletedAt"));
        Instant baselineResolved =
                instant(attestation.at(
                        "/baseline/resolvedAt"));
        Instant candidateSource =
                instant(attestation.at(
                        "/candidate/sourceCompletedAt"));
        Instant candidateResolved =
                instant(attestation.at(
                        "/candidate/resolvedAt"));
        if (confirmedAt.isBefore(admittedAt)
                || issuedAt.isBefore(confirmedAt)
                || baselineResolved.isBefore(admittedAt)
                || candidateResolved.isBefore(admittedAt)
                || confirmedAt.isBefore(baselineResolved)
                || confirmedAt.isBefore(candidateResolved)
                || baselineResolved.isBefore(baselineSource)
                || candidateResolved.isBefore(candidateSource)) {
            throw new VerificationFailure(
                    "SHADOW_SOURCE_RESOLUTION_TIME_INVALID");
        }
    }

    private static void verifyIdentityAndFingerprint(
            JsonNode attestation) {
        ObjectNode identity =
                JSON.createObjectNode();
        identity.put("domain", IDENTITY_DOMAIN);
        identity.put(
                "executionId",
                text(attestation, "executionId"));
        identity.put(
                "admissionFingerprint",
                text(attestation,
                        "admissionFingerprint"));
        identity.put(
                "confirmedAt",
                text(attestation, "confirmedAt"));
        identity.set(
                "baselineRef",
                attestation.at(
                        "/baseline/artifactRef")
                        .deepCopy());
        identity.put(
                "baselineResolvedAt",
                attestation.at(
                        "/baseline/resolvedAt")
                        .asText());
        identity.set(
                "candidateRef",
                attestation.at(
                        "/candidate/artifactRef")
                        .deepCopy());
        identity.put(
                "candidateResolvedAt",
                attestation.at(
                        "/candidate/resolvedAt")
                        .asText());
        String identityFingerprint =
                EvidenceVerificationSupport
                        .sha256Bounded(
                                identity,
                                MAXIMUM_IDENTITY_BYTES);
        String expectedId =
                "source-resolution-"
                        + identityFingerprint.substring(
                        "sha256:".length());
        if (!expectedId.equals(
                text(attestation,
                        "attestationId"))) {
            throw new VerificationFailure(
                    "SHADOW_SOURCE_RESOLUTION_IDENTITY_INVALID");
        }

        ObjectNode material =
                copyObject(attestation, "attestation");
        material.put(
                "attestationFingerprint", "");
        material.remove("attestationSeal");
        String fingerprint =
                EvidenceVerificationSupport
                        .sha256Bounded(
                                material,
                                MAXIMUM_ATTESTATION_BYTES);
        if (!fingerprint.equals(
                text(attestation,
                        "attestationFingerprint"))) {
            throw new VerificationFailure(
                    "SHADOW_SOURCE_RESOLUTION_FINGERPRINT_INVALID");
        }
    }

    private static void verifySourceClosure(
            JsonNode attestation,
            JsonNode binding,
            JsonNode candidateBundle) {
        if (!attestation.path("scope")
                .equals(binding.path("scope"))) {
            throw new VerificationFailure(
                    "SHADOW_SOURCE_RESOLUTION_BINDING_SCOPE_INVALID");
        }
        if (!referencesEqual(
                attestation.path("sourceBindingRef"),
                sourceBindingRef(binding))) {
            throw new VerificationFailure(
                    "SHADOW_SOURCE_RESOLUTION_BINDING_REFERENCE_INVALID");
        }
        if (!attestation.path(
                "requestContextFingerprint")
                .equals(binding.path(
                        "requestContextFingerprint"))) {
            throw new VerificationFailure(
                    "SHADOW_SOURCE_RESOLUTION_REQUEST_CONTEXT_INVALID");
        }
        if (!referencesEqual(
                attestation.path("comparisonPolicyRef"),
                binding.path("comparisonPolicyRef"))) {
            throw new VerificationFailure(
                    "SHADOW_SOURCE_RESOLUTION_BINDING_POLICY_INVALID");
        }
        if (!referencesEqual(
                attestation.path("comparisonPolicyRef"),
                builtInPolicyRef())) {
            throw new VerificationFailure(
                    "SHADOW_SOURCE_RESOLUTION_BUILT_IN_POLICY_INVALID");
        }
        JsonNode baseline =
                attestation.path("baseline");
        JsonNode baselineSource =
                binding.path("baseline");
        if (!referencesEqual(
                baseline.path("artifactRef"),
                baselineRef(binding))
                || !baseline.path(
                "semanticResultFingerprint")
                .equals(baselineSource.path(
                        "semanticResultFingerprint"))
                || !baseline.path(
                "normalizedFactFingerprints")
                .equals(baselineSource.path(
                        "normalizedFactFingerprints"))
                || !baseline.path("sourceCompletedAt")
                .equals(baselineSource.path(
                        "observedAt"))
                || !baseline.path("evidenceClass")
                .equals(baselineSource.path(
                        "evidenceClass"))
                || !baseline.path("evidenceComplete")
                .equals(baselineSource.path(
                        "evidenceComplete"))
                || !baseline.path(
                "writeCredentialExposed")
                .equals(baselineSource.path(
                        "writeCredentialExposed"))
                || !baseline.path("writeAttemptCount")
                .equals(baselineSource.path(
                        "writeAttemptCount"))) {
            throw new VerificationFailure(
                    "SHADOW_SOURCE_RESOLUTION_BASELINE_CLOSURE_INVALID");
        }

        JsonNode candidate =
                attestation.path("candidate");
        JsonNode evidence =
                candidateBundle.path("evidence");
        boolean complete =
                !"EVIDENCE_INCOMPLETE".equals(
                        text(evidence, "status"))
                        && !"CONTROL_PLAN_UNAVAILABLE".equals(
                        text(evidence, "status"));
        if (!referencesEqual(
                candidate.path("artifactRef"),
                binding.path("candidateEvidenceRef"))
                || !candidate.path(
                "semanticResultFingerprint")
                .equals(evidence.path(
                        "semanticResultFingerprint"))
                || !candidate.path(
                "normalizedFactFingerprints")
                .equals(candidateFacts(evidence))
                || !candidate.path("sourceCompletedAt")
                .equals(evidence.path("completedAt"))
                || !candidate.path("evidenceClass")
                .equals(evidence.path(
                        "evidenceClass"))
                || candidate.path("evidenceComplete")
                .asBoolean() != complete
                || candidate.path(
                "writeCredentialExposed")
                .asBoolean(true)
                || candidate.path("writeAttemptCount")
                .asLong(-1) != 0) {
            throw new VerificationFailure(
                    "SHADOW_SOURCE_RESOLUTION_CANDIDATE_CLOSURE_INVALID");
        }
    }

    private static ObjectNode candidateFacts(
            JsonNode evidence) {
        ObjectNode facts =
                JSON.createObjectNode();
        facts.put(
                "BEHAVIOR",
                text(evidence,
                        "semanticResultFingerprint"));
        facts.put(
                "CONTRACT",
                text(evidence,
                        "capabilityClosureFingerprint"));
        ObjectNode effect =
                JSON.createObjectNode();
        effect.set(
                "externalBindings",
                evidence.path("externalBindings")
                        .deepCopy());
        effect.set(
                "resolutions",
                evidence.path("resolutions")
                        .deepCopy());
        facts.put(
                "EFFECT",
                EvidenceVerificationSupport
                        .sha256Bounded(
                                effect,
                                MirrorEvidenceVerifier
                                        .MAXIMUM_EVIDENCE_BYTES));
        JsonNode state =
                evidence.path("stateEvidence");
        if (state.isObject()) {
            facts.put(
                    "STATE_TRANSITION",
                    text(state,
                            "stateEvidenceFingerprint"));
        }
        return facts;
    }

    private static ObjectNode builtInPolicyRef() {
        ObjectNode policy =
                JSON.createObjectNode();
        policy.put("domain", POLICY_DOMAIN);
        ArrayNode rules =
                policy.putArray(
                        "normalizationRules");
        rules.add(
                "BEHAVIOR=semanticResultFingerprint");
        rules.add(
                "CONTRACT=capabilityClosureFingerprint");
        rules.add(
                "EFFECT=externalBindings+resolutions");
        rules.add(
                "STATE_TRANSITION=stateEvidenceFingerprint");
        ObjectNode types =
                policy.putObject("mismatchTypes");
        types.put("BEHAVIOR", "OUTPUT_VALUE");
        types.put("CONTRACT", "OUTPUT_SCHEMA");
        types.put("EFFECT", "EFFECT");
        types.put("STATE_TRANSITION", "STATE");
        ObjectNode reference =
                JSON.createObjectNode();
        reference.put("kind", POLICY_KIND);
        reference.put("id", POLICY_ID);
        reference.put("revision", 1);
        reference.put(
                "fingerprint",
                verifiedPolicyFingerprint(policy));
        return reference;
    }

    private static String verifiedPolicyFingerprint(
            ObjectNode policy) {
        String calculated =
                EvidenceVerificationSupport
                        .sha256Bounded(
                                policy,
                                MAXIMUM_POLICY_BYTES);
        if (!POLICY_FINGERPRINT.equals(calculated)) {
            throw new IllegalStateException(
                    "built-in Shadow comparison policy protocol drift");
        }
        return POLICY_FINGERPRINT;
    }

    private static ObjectNode sourceBindingRef(
            JsonNode binding) {
        ObjectNode reference =
                JSON.createObjectNode();
        reference.put(
                "kind", "SHADOW_SOURCE_BINDING");
        reference.put(
                "id", text(binding, "bindingId"));
        reference.put(
                "revision",
                binding.path("revision").asLong());
        reference.put(
                "fingerprint",
                text(binding,
                        "bindingFingerprint"));
        return reference;
    }

    private static ObjectNode baselineRef(
            JsonNode binding) {
        ObjectNode reference =
                JSON.createObjectNode();
        reference.put(
                "kind",
                "SHADOW_BASELINE_OBSERVATION");
        reference.put(
                "id",
                text(binding, "bindingId")
                        + ":baseline");
        reference.put(
                "revision",
                binding.path("revision").asLong());
        reference.put(
                "fingerprint",
                text(binding,
                        "baselineObservationFingerprint"));
        return reference;
    }

    private static ObjectNode attestationMaterial(
            JsonNode attestation) {
        ObjectNode material =
                JSON.createObjectNode();
        material.put(
                "domain", ATTESTATION_DOMAIN);
        material.put(
                "schemaVersion",
                text(attestation,
                        "schemaVersion"));
        material.put(
                "attestationId",
                text(attestation,
                        "attestationId"));
        material.put(
                "revision",
                attestation.path("revision")
                        .asLong());
        material.set(
                "scope",
                attestation.path("scope")
                        .deepCopy());
        material.put(
                "issuedAt",
                text(attestation, "issuedAt"));
        material.put(
                "attestationFingerprint",
                text(attestation,
                        "attestationFingerprint"));
        return material;
    }

    private static boolean matchesReference(
            JsonNode artifact,
            JsonNode reference,
            String idField,
            String fingerprintField,
            String kind) {
        return reference != null
                && reference.isObject()
                && reference.size() == 4
                && kind.equals(text(
                reference, "kind"))
                && text(artifact, idField).equals(
                text(reference, "id"))
                && artifact.path("revision")
                .asLong()
                == reference.path("revision")
                .asLong()
                && text(artifact,
                fingerprintField).equals(
                text(reference, "fingerprint"));
    }

    private static boolean referencesEqual(
            JsonNode left,
            JsonNode right) {
        return left != null
                && right != null
                && left.isObject()
                && right.isObject()
                && text(left, "kind").equals(
                text(right, "kind"))
                && text(left, "id").equals(
                text(right, "id"))
                && left.path("revision").isIntegralNumber()
                && right.path("revision").isIntegralNumber()
                && left.path("revision").asLong()
                == right.path("revision").asLong()
                && text(left, "fingerprint").equals(
                text(right, "fingerprint"));
    }

    private static Instant instant(
            JsonNode value) {
        try {
            return Instant.parse(
                    value == null
                            ? "" : value.asText());
        } catch (DateTimeParseException invalid) {
            throw new VerificationFailure(
                    "SHADOW_SOURCE_RESOLUTION_TIME_INVALID");
        }
    }

    private static VerificationResult result(
            Outcome outcome,
            String reasonCode,
            Coordinates coordinates,
            String sourceBindingReason) {
        return new VerificationResult(
                outcome,
                reasonCode,
                coordinates.attestationId,
                coordinates.revision,
                coordinates.attestationFingerprint,
                coordinates.requestId,
                coordinates.executionId,
                coordinates.attestationKeyId,
                sourceBindingReason);
    }

    private static String text(
            JsonNode value,
            String field) {
        return value == null
                ? "" : value.path(field)
                .asText("");
    }

    private static ObjectNode copyObject(
            JsonNode value,
            String field) {
        if (value == null || !value.isObject()) {
            throw new IllegalArgumentException(
                    field + " must be an object");
        }
        return ((ObjectNode) value)
                .deepCopy();
    }

    private static String bounded(
            String value,
            int maximum) {
        String exact =
                value == null ? "" : value.trim();
        if (exact.isBlank()
                || exact.length() > maximum) {
            throw new IllegalArgumentException(
                    "bounded value is invalid");
        }
        return exact;
    }

    private static String boundedOptional(
            String value,
            int maximum) {
        String exact =
                value == null ? "" : value.trim();
        return exact.length() <= maximum
                ? exact : "";
    }

    private static String fingerprint(
            String value) {
        String exact =
                value == null ? "" : value.trim();
        if (!exact.matches(
                "sha256:[a-f0-9]{64}")) {
            throw new IllegalArgumentException(
                    "fingerprint is invalid");
        }
        return exact;
    }

    private static String fingerprintOptional(
            String value) {
        String exact =
                boundedOptional(value, 71);
        return exact.matches(
                "sha256:[a-f0-9]{64}")
                ? exact : "";
    }

    private static String reason(
            String value) {
        String exact = bounded(
                value, 255);
        if (!exact.matches(
                "[A-Z][A-Z0-9_.-]{0,254}")) {
            throw new IllegalArgumentException(
                    "reason code is invalid");
        }
        return exact;
    }

    private static String reasonOptional(
            String value) {
        String exact =
                boundedOptional(value, 255);
        return exact.isBlank()
                || exact.matches(
                "[A-Z][A-Z0-9_.-]{0,254}")
                ? exact : "";
    }

    private record Coordinates(
            String attestationId,
            long revision,
            String attestationFingerprint,
            String requestId,
            String executionId,
            String attestationKeyId
    ) {
        private static Coordinates from(
                JsonNode attestation,
                EvidenceVerificationKey key) {
            return new Coordinates(
                    text(attestation,
                            "attestationId"),
                    attestation == null
                            ? 0
                            : Math.max(
                            0,
                            attestation.path(
                                    "revision")
                                    .asLong()),
                    text(attestation,
                            "attestationFingerprint"),
                    text(attestation,
                            "requestId"),
                    text(attestation,
                            "executionId"),
                    key == null
                            ? ""
                            : key.keyId());
        }
    }

    private static final class VerificationFailure
            extends RuntimeException {
        private final String reasonCode;

        private VerificationFailure(
                String reasonCode) {
            this.reasonCode = reasonCode;
        }
    }
}
