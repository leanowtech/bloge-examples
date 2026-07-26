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
 * Standalone hostile-input verifier for one online paired-source Shadow proof.
 *
 * <p>The verifier links neither Resource Gateway server nor Spring classes. It independently
 * verifies the exact regional baseline command and signed observation, the exact candidate
 * command and signed Mirror evidence bundle, command-to-command pairing, built-in normalization,
 * zero-write closure, fresh exact-read times, deterministic proof identity, complete content
 * address, authority-key policy, and the v2 Ed25519 seal. No business payload, endpoint,
 * credential, private key, or producer diagnostic enters its result.</p>
 */
public final class OnlineReadOnlyShadowSourceResolutionAttestationVerifier {
    /** Maximum canonical source-resolution proof bytes. */
    public static final int MAXIMUM_ATTESTATION_BYTES =
            4 * 1024 * 1024;
    /** Maximum canonical online baseline command bytes. */
    public static final int MAXIMUM_BASELINE_COMMAND_BYTES =
            128 * 1024;
    /** Maximum canonical online candidate command bytes. */
    public static final int MAXIMUM_CANDIDATE_COMMAND_BYTES =
            160 * 1024;
    /** Maximum deterministic identity bytes. */
    public static final int MAXIMUM_IDENTITY_BYTES =
            96 * 1024;
    /** Maximum authority-signature material bytes. */
    public static final int MAXIMUM_SEAL_MATERIAL_BYTES =
            16 * 1024;

    private static final Duration MAXIMUM_CLOCK_SKEW =
            Duration.ofMinutes(2);
    private static final ObjectMapper JSON =
            new ObjectMapper();
    private static final String ATTESTATION_KIND =
            "SHADOW_SOURCE_RESOLUTION_ATTESTATION";
    private static final String BASELINE_KIND =
            "SHADOW_BASELINE_OBSERVATION";
    private static final String CANDIDATE_KIND =
            "MIRROR_EVIDENCE_BUNDLE";
    private static final String POLICY_KIND =
            "SHADOW_COMPARISON_POLICY";
    private static final String POLICY_ID =
            "payload-free-equality-v1";
    private static final String POLICY_FINGERPRINT =
            "sha256:66cb081470a0492453c5a35bbf7e9b2bb530abc2dbaaf86be8a564bec4c11f43";
    private static final String IDENTITY_DOMAIN =
            "RESOURCE_GATEWAY_READ_ONLY_SHADOW_SOURCE_RESOLUTION_IDENTITY_V2";
    private static final String ATTESTATION_DOMAIN =
            "RESOURCE_GATEWAY_READ_ONLY_SHADOW_SOURCE_RESOLUTION_ATTESTATION_V2";
    private static final String POLICY_DOMAIN =
            "RESOURCE_GATEWAY_PAYLOAD_FREE_EQUALITY_POLICY_V1";

    private final OnlineReadOnlyShadowBaselineObservationVerifier
            baselineVerifier;
    private final MirrorEvidenceVerifier evidenceVerifier;

    /** Creates a verifier composed from the independent packaged source verifiers. */
    public OnlineReadOnlyShadowSourceResolutionAttestationVerifier() {
        this(new OnlineReadOnlyShadowBaselineObservationVerifier(),
                new MirrorEvidenceVerifier());
    }

    OnlineReadOnlyShadowSourceResolutionAttestationVerifier(
            OnlineReadOnlyShadowBaselineObservationVerifier
                    baselineVerifier,
            MirrorEvidenceVerifier evidenceVerifier) {
        this.baselineVerifier = Objects.requireNonNull(
                baselineVerifier, "baselineVerifier");
        this.evidenceVerifier = Objects.requireNonNull(
                evidenceVerifier, "evidenceVerifier");
    }

    /** Closed online paired-source verification outcomes. */
    public enum Outcome {
        /** Every source, pairing, content, time, key, and signature check passed. */
        VERIFIED,
        /** Structure, semantics, content address, identity, or signature is invalid. */
        INVALID,
        /** Authenticated caller coordinates do not identify this exact proof. */
        EXPECTATION_MISMATCH,
        /** The independently signed baseline observation did not verify. */
        BASELINE_INVALID,
        /** The independently signed candidate Mirror evidence did not verify. */
        CANDIDATE_INVALID,
        /** The source-resolution authority key is unavailable. */
        KEY_UNAVAILABLE,
        /** Signature algorithm or key lifecycle policy rejected the proof. */
        POLICY_REJECTED,
        /** The proof is from the future relative to the trusted consumer clock. */
        WINDOW_REJECTED
    }

    /**
     * Authenticated coordinates and independently obtained artifacts for one online source pair.
     *
     * @param expectedScope authenticated enterprise scope
     * @param expectedAttestationRef exact comparison-bound proof reference
     * @param expectedRequestId durable Shadow request identity
     * @param expectedExecutionId stable logical execution identity
     * @param expectedAdmissionFingerprint exact online-authority admission identity
     * @param baselineCommand exact payload-free regional baseline command
     * @param baselineObservation exact independently signed regional observation
     * @param baselineKey independently resolved regional observation public key
     * @param candidateCommand exact payload-free candidate command
     * @param candidateEvidenceBundle exact independently signed Mirror evidence bundle
     * @param candidateEvidenceKey independently resolved Mirror evidence public key
     * @param verificationTime trusted consumer clock
     */
    public record VerificationContext(
            JsonNode expectedScope,
            JsonNode expectedAttestationRef,
            String expectedRequestId,
            String expectedExecutionId,
            String expectedAdmissionFingerprint,
            JsonNode baselineCommand,
            JsonNode baselineObservation,
            EvidenceVerificationKey baselineKey,
            JsonNode candidateCommand,
            JsonNode candidateEvidenceBundle,
            EvidenceVerificationKey candidateEvidenceKey,
            Instant verificationTime
    ) {
        /** Defensively copies all untrusted JSON and validates bounded caller coordinates. */
        public VerificationContext {
            expectedScope = object(
                    expectedScope, "expectedScope");
            expectedAttestationRef = object(
                    expectedAttestationRef,
                    "expectedAttestationRef");
            expectedRequestId = bounded(
                    expectedRequestId, 512);
            expectedExecutionId = bounded(
                    expectedExecutionId, 512);
            expectedAdmissionFingerprint = fingerprint(
                    expectedAdmissionFingerprint);
            baselineCommand = object(
                    baselineCommand, "baselineCommand");
            baselineObservation = object(
                    baselineObservation,
                    "baselineObservation");
            candidateCommand = object(
                    candidateCommand, "candidateCommand");
            candidateEvidenceBundle = object(
                    candidateEvidenceBundle,
                    "candidateEvidenceBundle");
            verificationTime = Objects.requireNonNull(
                    verificationTime, "verificationTime");
        }

        /**
         * Returns an independently owned expected enterprise scope.
         *
         * @return defensive copy of the expected scope
         */
        @Override
        public JsonNode expectedScope() {
            return expectedScope.deepCopy();
        }

        /**
         * Returns an independently owned exact proof reference.
         *
         * @return defensive copy of the expected proof reference
         */
        @Override
        public JsonNode expectedAttestationRef() {
            return expectedAttestationRef.deepCopy();
        }

        /**
         * Returns an independently owned regional baseline command.
         *
         * @return defensive copy of the exact baseline command
         */
        @Override
        public JsonNode baselineCommand() {
            return baselineCommand.deepCopy();
        }

        /**
         * Returns an independently owned signed regional observation.
         *
         * @return defensive copy of the signed baseline observation
         */
        @Override
        public JsonNode baselineObservation() {
            return baselineObservation.deepCopy();
        }

        /**
         * Returns an independently owned same-input candidate command.
         *
         * @return defensive copy of the exact candidate command
         */
        @Override
        public JsonNode candidateCommand() {
            return candidateCommand.deepCopy();
        }

        /**
         * Returns an independently owned signed candidate evidence bundle.
         *
         * @return defensive copy of the signed candidate evidence bundle
         */
        @Override
        public JsonNode candidateEvidenceBundle() {
            return candidateEvidenceBundle.deepCopy();
        }
    }

    /**
     * Bounded payload-free result suitable for CI, governance admission, and audit logs.
     *
     * @param outcome closed verification outcome
     * @param reasonCode stable machine-readable reason
     * @param attestationId deterministic proof identity, or blank
     * @param revision immutable proof revision, or zero
     * @param attestationFingerprint complete proof content address, or blank
     * @param requestId durable request identity, or blank
     * @param executionId stable execution identity, or blank
     * @param keyId source-resolution authority key identity, or blank
     * @param baselineReason nested baseline verifier reason, or blank
     * @param candidateReason nested candidate evidence verifier reason, or blank
     * @param zeroWrite whether all independently verified sources prove zero-write execution
     */
    public record VerificationResult(
            Outcome outcome,
            String reasonCode,
            String attestationId,
            long revision,
            String attestationFingerprint,
            String requestId,
            String executionId,
            String keyId,
            String baselineReason,
            String candidateReason,
            boolean zeroWrite
    ) {
        /** Normalizes one log-safe result without retaining untrusted diagnostics. */
        public VerificationResult {
            outcome = Objects.requireNonNull(
                    outcome, "outcome");
            reasonCode = reason(reasonCode);
            attestationId = boundedOptional(
                    attestationId, 512);
            attestationFingerprint =
                    fingerprintOptional(
                            attestationFingerprint);
            requestId = boundedOptional(requestId, 512);
            executionId = boundedOptional(
                    executionId, 512);
            keyId = boundedOptional(keyId, 255);
            baselineReason = reasonOptional(
                    baselineReason);
            candidateReason = reasonOptional(
                    candidateReason);
            if (revision < 0) {
                throw new IllegalArgumentException(
                        "online source-resolution revision is invalid");
            }
        }

        /**
         * Reports whether the complete independent source and proof closure passed.
         *
         * @return true only for a fully verified zero-write proof
         */
        public boolean verified() {
            return outcome == Outcome.VERIFIED
                    && zeroWrite;
        }
    }

    /**
     * Independently verifies one online v2 source-resolution proof.
     *
     * @param attestation untrusted decoded v2 source-resolution proof
     * @param attestationKey independently resolved proof-authority public key
     * @param context authenticated commands, source artifacts, keys, and caller coordinates
     * @return bounded payload-free verification result
     */
    public VerificationResult verify(
            JsonNode attestation,
            EvidenceVerificationKey attestationKey,
            VerificationContext context) {
        Coordinates coordinates =
                Coordinates.from(
                        attestation, attestationKey);
        if (context == null) {
            return result(
                    Outcome.EXPECTATION_MISMATCH,
                    "ONLINE_SOURCE_RESOLUTION_CONTEXT_UNAVAILABLE",
                    coordinates, "", "", false);
        }
        try {
            CapabilityMirrorSchemaValidator.require(
                    attestation,
                    CapabilityMirrorProtocol
                            .READ_ONLY_SHADOW_SOURCE_RESOLUTION_ATTESTATION_V2_SCHEMA_RESOURCE,
                    "RG.MIRROR.CLIENT.ONLINE_SOURCE_RESOLUTION_SCHEMA_INVALID");
            CapabilityMirrorSchemaValidator.require(
                    context.candidateCommand(),
                    CapabilityMirrorProtocol
                            .ONLINE_READ_ONLY_SHADOW_CANDIDATE_COMMAND_SCHEMA_RESOURCE,
                    "RG.MIRROR.CLIENT.ONLINE_CANDIDATE_COMMAND_SCHEMA_INVALID");
        } catch (RuntimeException invalid) {
            return result(
                    Outcome.INVALID,
                    "ONLINE_SOURCE_RESOLUTION_SCHEMA_INVALID",
                    coordinates, "", "", false);
        }

        Instant issuedAt;
        String baselineCommandFingerprint;
        String candidateCommandFingerprint;
        try {
            issuedAt = instant(
                    attestation.path("issuedAt"));
            requireTemporalClosure(attestation);
            baselineCommandFingerprint =
                    EvidenceVerificationSupport
                            .sha256Bounded(
                                    context.baselineCommand(),
                                    MAXIMUM_BASELINE_COMMAND_BYTES);
            candidateCommandFingerprint =
                    EvidenceVerificationSupport
                            .sha256Bounded(
                                    context.candidateCommand(),
                                    MAXIMUM_CANDIDATE_COMMAND_BYTES);
            if (!matchesExpectations(
                    attestation, context,
                    baselineCommandFingerprint,
                    candidateCommandFingerprint)) {
                return result(
                        Outcome.EXPECTATION_MISMATCH,
                        "ONLINE_SOURCE_RESOLUTION_EXPECTATION_MISMATCH",
                        coordinates, "", "", false);
            }
            String pairReason =
                    commandPairReason(
                            attestation, context);
            if (!pairReason.isEmpty()) {
                return result(
                        Outcome.EXPECTATION_MISMATCH,
                        pairReason,
                        coordinates, "", "", false);
            }
        } catch (VerificationFailure invalid) {
            return result(
                    Outcome.INVALID,
                    invalid.reasonCode,
                    coordinates, "", "", false);
        } catch (RuntimeException invalid) {
            return result(
                    Outcome.INVALID,
                    "ONLINE_SOURCE_RESOLUTION_MATERIAL_INVALID",
                    coordinates, "", "", false);
        }
        if (issuedAt.isAfter(
                context.verificationTime()
                        .plus(MAXIMUM_CLOCK_SKEW))) {
            return result(
                    Outcome.WINDOW_REJECTED,
                    "ONLINE_SOURCE_RESOLUTION_FROM_FUTURE",
                    coordinates, "", "", false);
        }

        var baselineResult =
                baselineVerifier.verify(
                        context.baselineObservation(),
                        context.baselineKey(),
                        new OnlineReadOnlyShadowBaselineObservationVerifier
                                .VerificationContext(
                                context.baselineCommand(),
                                attestation.at(
                                        "/baseline/artifactRef"),
                                issuedAt));
        if (!baselineResult.verified()) {
            return result(
                    Outcome.BASELINE_INVALID,
                    "ONLINE_SOURCE_RESOLUTION_BASELINE_INVALID",
                    coordinates,
                    baselineResult.reasonCode(),
                    "",
                    false);
        }

        MirrorEvidenceVerifier.VerificationResult
                candidateResult =
                evidenceVerifier.verify(
                        context.candidateEvidenceBundle(),
                        context.candidateEvidenceKey());
        if (!candidateResult.verified()) {
            return result(
                    Outcome.CANDIDATE_INVALID,
                    "ONLINE_SOURCE_RESOLUTION_CANDIDATE_INVALID",
                    coordinates,
                    baselineResult.reasonCode(),
                    candidateResult.reasonCode(),
                    false);
        }

        try {
            verifyCandidateCommandClosure(
                    context,
                    candidateCommandFingerprint);
            verifySourceClosure(
                    attestation,
                    context.baselineObservation(),
                    context.candidateEvidenceBundle());
            verifyIdentityAndFingerprint(
                    attestation);
        } catch (VerificationFailure invalid) {
            return result(
                    Outcome.INVALID,
                    invalid.reasonCode,
                    coordinates,
                    baselineResult.reasonCode(),
                    candidateResult.reasonCode(),
                    false);
        } catch (RuntimeException invalid) {
            return result(
                    Outcome.INVALID,
                    "ONLINE_SOURCE_RESOLUTION_CLOSURE_INVALID",
                    coordinates,
                    baselineResult.reasonCode(),
                    candidateResult.reasonCode(),
                    false);
        }

        JsonNode seal =
                attestation.path("attestationSeal");
        if (attestationKey == null) {
            return result(
                    Outcome.KEY_UNAVAILABLE,
                    "ONLINE_SOURCE_RESOLUTION_KEY_UNAVAILABLE",
                    coordinates,
                    baselineResult.reasonCode(),
                    candidateResult.reasonCode(),
                    false);
        }
        if (!attestationKey.keyId().equals(
                text(seal, "keyId"))) {
            return result(
                    Outcome.POLICY_REJECTED,
                    "ONLINE_SOURCE_RESOLUTION_KEY_IDENTITY_MISMATCH",
                    coordinates,
                    baselineResult.reasonCode(),
                    candidateResult.reasonCode(),
                    false);
        }
        if (!"Ed25519".equals(
                attestationKey.algorithm())
                || !attestationKey.algorithm().equals(
                text(seal, "algorithm"))) {
            return result(
                    Outcome.POLICY_REJECTED,
                    "ONLINE_SOURCE_RESOLUTION_ALGORITHM_REJECTED",
                    coordinates,
                    baselineResult.reasonCode(),
                    candidateResult.reasonCode(),
                    false);
        }

        Instant signedAt;
        try {
            signedAt = instant(
                    seal.path("signedAt"));
        } catch (VerificationFailure invalid) {
            return result(
                    Outcome.INVALID,
                    "ONLINE_SOURCE_RESOLUTION_SEAL_TIME_INVALID",
                    coordinates,
                    baselineResult.reasonCode(),
                    candidateResult.reasonCode(),
                    false);
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
                    "ONLINE_SOURCE_RESOLUTION_KEY_POLICY_REJECTED",
                    coordinates,
                    baselineResult.reasonCode(),
                    candidateResult.reasonCode(),
                    false);
        }
        try {
            String materialFingerprint =
                    EvidenceVerificationSupport
                            .sha256Bounded(
                                    signatureMaterial(
                                            attestation),
                                    MAXIMUM_SEAL_MATERIAL_BYTES);
            if (!materialFingerprint.equals(
                    text(seal,
                            "materialFingerprint"))) {
                return result(
                        Outcome.INVALID,
                        "ONLINE_SOURCE_RESOLUTION_SEAL_MATERIAL_INVALID",
                        coordinates,
                        baselineResult.reasonCode(),
                        candidateResult.reasonCode(),
                        false);
            }
            if (!EvidenceVerificationSupport
                    .verifyEd25519(
                            materialFingerprint,
                            text(seal, "signature"),
                            attestationKey
                                    .encodedPublicKey())) {
                return result(
                        Outcome.INVALID,
                        "ONLINE_SOURCE_RESOLUTION_SIGNATURE_INVALID",
                        coordinates,
                        baselineResult.reasonCode(),
                        candidateResult.reasonCode(),
                        false);
            }
            return result(
                    Outcome.VERIFIED,
                    "VERIFIED",
                    coordinates,
                    baselineResult.reasonCode(),
                    candidateResult.reasonCode(),
                    true);
        } catch (GeneralSecurityException
                 | RuntimeException invalid) {
            return result(
                    Outcome.INVALID,
                    "ONLINE_SOURCE_RESOLUTION_SIGNATURE_MATERIAL_INVALID",
                    coordinates,
                    baselineResult.reasonCode(),
                    candidateResult.reasonCode(),
                    false);
        }
    }

    private static boolean matchesExpectations(
            JsonNode attestation,
            VerificationContext context,
            String baselineCommandFingerprint,
            String candidateCommandFingerprint) {
        return attestation.path("scope")
                .equals(context.expectedScope())
                && text(attestation, "requestId")
                .equals(context.expectedRequestId())
                && text(attestation, "executionId")
                .equals(context.expectedExecutionId())
                && text(attestation,
                "admissionFingerprint")
                .equals(
                        context.expectedAdmissionFingerprint())
                && text(attestation,
                "baselineCommandFingerprint")
                .equals(baselineCommandFingerprint)
                && text(attestation,
                "candidateCommandFingerprint")
                .equals(candidateCommandFingerprint)
                && matchesReference(
                attestation,
                context.expectedAttestationRef(),
                "attestationId",
                "attestationFingerprint",
                ATTESTATION_KIND);
    }

    private static String commandPairReason(
            JsonNode attestation,
            VerificationContext context) {
        JsonNode baseline =
                context.baselineCommand();
        JsonNode candidate =
                context.candidateCommand();
        String[] common = {
                "executionId",
                "requestId",
                "scope",
                "inventoryRef",
                "unitId",
                "scenarioCaseRef",
                "targetCapabilityRef",
                "comparisonPolicyRef",
                "accessGrant",
                "admissionFingerprint",
                "admittedAt",
                "deadlineAt"
        };
        for (String field : common) {
            if (!baseline.path(field)
                    .equals(candidate.path(field))) {
                return "ONLINE_SOURCE_RESOLUTION_COMMAND_PAIR_"
                        + field.replaceAll(
                        "([a-z])([A-Z])",
                        "$1_$2").toUpperCase()
                        + "_MISMATCH";
            }
        }
        JsonNode observation =
                context.baselineObservation();
        if (!baseline.path("scope")
                .equals(attestation.path("scope"))
                || !text(baseline, "requestId")
                .equals(text(attestation,
                        "requestId"))
                || !text(baseline, "executionId")
                .equals(text(attestation,
                        "executionId"))
                || !baseline.path("comparisonPolicyRef")
                .equals(attestation.path(
                        "comparisonPolicyRef"))
                || !text(baseline,
                "admissionFingerprint")
                .equals(text(attestation,
                        "admissionFingerprint"))
                || !baseline.path("admittedAt")
                .equals(attestation.path("admittedAt"))) {
            return "ONLINE_SOURCE_RESOLUTION_PROOF_COMMAND_MISMATCH";
        }
        if (!candidate.path(
                "baselineObservationRef")
                .equals(attestation.at(
                        "/baseline/artifactRef"))
                || !referencesEqual(
                candidate.path(
                        "baselineObservationRef"),
                observationRef(observation))) {
            return "ONLINE_SOURCE_RESOLUTION_BASELINE_REFERENCE_MISMATCH";
        }
        if (!candidate.path(
                "payloadVaultReceiptRef")
                .equals(observation.path(
                        "payloadVaultReceiptRef"))) {
            return "ONLINE_SOURCE_RESOLUTION_VAULT_RECEIPT_MISMATCH";
        }
        if (!candidate.path(
                "requestContextFingerprint")
                .equals(observation.path(
                        "requestContextFingerprint"))
                || !candidate.path(
                "requestContextFingerprint")
                .equals(attestation.path(
                        "requestContextFingerprint"))) {
            return "ONLINE_SOURCE_RESOLUTION_REQUEST_CONTEXT_MISMATCH";
        }
        return "";
    }

    private static void verifyCandidateCommandClosure(
            VerificationContext context,
            String candidateCommandFingerprint) {
        JsonNode command =
                context.candidateCommand();
        JsonNode baseline =
                context.baselineObservation();
        JsonNode bundle =
                context.candidateEvidenceBundle();
        JsonNode evidence =
                bundle.path("evidence");
        Instant baselineCompleted =
                instant(baseline.path(
                        "completedAt"));
        Instant started =
                instant(evidence.path("startedAt"));
        Instant completed =
                instant(evidence.path("completedAt"));
        Instant signed =
                instant(bundle.at(
                        "/attestation/signedAt"));
        Instant deadline =
                instant(command.path("deadlineAt"));
        if (!text(evidence, "requestId")
                .equals(candidateCommandFingerprint)
                || !evidence.path("scope")
                .equals(command.path("scope"))
                || !text(evidence, "planId")
                .equals(command.at(
                        "/candidatePlanRef/id")
                        .asText())
                || !text(evidence,
                "planFingerprint")
                .equals(command.at(
                        "/candidatePlanRef/fingerprint")
                        .asText())
                || !evidence.path("rootCapability")
                .equals(command.path(
                        "targetCapabilityRef"))
                || !evidence.path(
                "requestContextFingerprint")
                .equals(command.path(
                        "requestContextFingerprint"))
                || started.isBefore(baselineCompleted)
                || completed.isBefore(started)
                || completed.isAfter(deadline)
                || signed.isAfter(deadline)) {
            throw new VerificationFailure(
                    "ONLINE_SOURCE_RESOLUTION_CANDIDATE_COMMAND_INVALID");
        }
    }

    private static void requireTemporalClosure(
            JsonNode attestation) {
        Instant admitted =
                instant(attestation.path("admittedAt"));
        Instant confirmed =
                instant(attestation.path("confirmedAt"));
        Instant issued =
                instant(attestation.path("issuedAt"));
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
        if (confirmed.isBefore(admitted)
                || baselineResolved.isBefore(confirmed)
                || candidateResolved.isBefore(confirmed)
                || baselineResolved.isBefore(
                baselineSource)
                || candidateResolved.isBefore(
                candidateSource)
                || issued.isBefore(baselineResolved)
                || issued.isBefore(candidateResolved)) {
            throw new VerificationFailure(
                    "ONLINE_SOURCE_RESOLUTION_TIME_INVALID");
        }
    }

    private static void verifySourceClosure(
            JsonNode attestation,
            JsonNode baselineObservation,
            JsonNode candidateBundle) {
        if (!referencesEqual(
                attestation.at(
                        "/baseline/artifactRef"),
                observationRef(
                        baselineObservation))
                || !attestation.path("scope")
                .equals(baselineObservation.path(
                        "scope"))
                || !attestation.path(
                "requestContextFingerprint")
                .equals(baselineObservation.path(
                        "requestContextFingerprint"))
                || !attestation.path(
                "comparisonPolicyRef")
                .equals(baselineObservation.path(
                        "comparisonPolicyRef"))
                || !referencesEqual(
                attestation.path(
                        "comparisonPolicyRef"),
                builtInPolicyRef())) {
            throw new VerificationFailure(
                    "ONLINE_SOURCE_RESOLUTION_BASELINE_COORDINATES_INVALID");
        }
        JsonNode baseline =
                attestation.path("baseline");
        if (!baseline.path(
                "semanticResultFingerprint")
                .equals(baselineObservation.path(
                        "semanticResultFingerprint"))
                || !baseline.path(
                "normalizedFactFingerprints")
                .equals(baselineObservation.path(
                        "normalizedFactFingerprints"))
                || !baseline.path(
                "sourceCompletedAt")
                .equals(baselineObservation.path(
                        "completedAt"))
                || !baseline.path("evidenceClass")
                .equals(baselineObservation.path(
                        "evidenceClass"))
                || !baseline.path("evidenceComplete")
                .equals(baselineObservation.path(
                        "evidenceComplete"))
                || baseline.path(
                "writeCredentialExposed")
                .asBoolean(true)
                || baseline.path(
                "writeAttemptCount")
                .asLong(-1) != 0) {
            throw new VerificationFailure(
                    "ONLINE_SOURCE_RESOLUTION_BASELINE_CLOSURE_INVALID");
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
                evidenceBundleRef(
                        candidateBundle))
                || !candidate.path(
                "semanticResultFingerprint")
                .equals(evidence.path(
                        "semanticResultFingerprint"))
                || !candidate.path(
                "normalizedFactFingerprints")
                .equals(candidateFacts(evidence))
                || !candidate.path(
                "sourceCompletedAt")
                .equals(evidence.path("completedAt"))
                || !candidate.path("evidenceClass")
                .equals(evidence.path(
                        "evidenceClass"))
                || candidate.path("evidenceComplete")
                .asBoolean() != complete
                || candidate.path(
                "writeCredentialExposed")
                .asBoolean(true)
                || candidate.path(
                "writeAttemptCount")
                .asLong(-1) != 0) {
            throw new VerificationFailure(
                    "ONLINE_SOURCE_RESOLUTION_CANDIDATE_CLOSURE_INVALID");
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
        identity.put(
                "baselineCommandFingerprint",
                text(attestation,
                        "baselineCommandFingerprint"));
        identity.put(
                "candidateCommandFingerprint",
                text(attestation,
                        "candidateCommandFingerprint"));
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
                    "ONLINE_SOURCE_RESOLUTION_IDENTITY_INVALID");
        }

        ObjectNode material =
                object(attestation, "attestation");
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
                    "ONLINE_SOURCE_RESOLUTION_FINGERPRINT_INVALID");
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
        String calculated =
                EvidenceVerificationSupport
                        .sha256Bounded(
                                policy, 64 * 1024);
        if (!POLICY_FINGERPRINT.equals(
                calculated)) {
            throw new IllegalStateException(
                    "built-in Shadow policy protocol drift");
        }
        ObjectNode reference =
                JSON.createObjectNode();
        reference.put("kind", POLICY_KIND);
        reference.put("id", POLICY_ID);
        reference.put("revision", 1);
        reference.put(
                "fingerprint",
                POLICY_FINGERPRINT);
        return reference;
    }

    private static ObjectNode observationRef(
            JsonNode observation) {
        ObjectNode reference =
                JSON.createObjectNode();
        reference.put("kind", BASELINE_KIND);
        reference.put(
                "id",
                text(observation,
                        "observationId"));
        reference.put(
                "revision",
                observation.path("revision")
                        .asLong());
        reference.put(
                "fingerprint",
                text(observation,
                        "observationFingerprint"));
        return reference;
    }

    private static ObjectNode evidenceBundleRef(
            JsonNode bundle) {
        ObjectNode reference =
                JSON.createObjectNode();
        reference.put("kind", CANDIDATE_KIND);
        reference.put(
                "id",
                bundle.at("/evidence/runId")
                        .asText());
        reference.put("revision", 1);
        reference.put(
                "fingerprint",
                text(bundle,
                        "bundleFingerprint"));
        return reference;
    }

    private static ObjectNode signatureMaterial(
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
                && left.path("revision")
                .isIntegralNumber()
                && right.path("revision")
                .isIntegralNumber()
                && left.path("revision")
                .asLong()
                == right.path("revision")
                .asLong()
                && text(left, "fingerprint")
                .equals(text(right,
                        "fingerprint"));
    }

    private static Instant instant(
            JsonNode value) {
        try {
            return Instant.parse(
                    value == null
                            ? "" : value.asText());
        } catch (DateTimeParseException invalid) {
            throw new VerificationFailure(
                    "ONLINE_SOURCE_RESOLUTION_TIME_INVALID");
        }
    }

    private static VerificationResult result(
            Outcome outcome,
            String reasonCode,
            Coordinates coordinates,
            String baselineReason,
            String candidateReason,
            boolean zeroWrite) {
        return new VerificationResult(
                outcome,
                reasonCode,
                coordinates.attestationId,
                coordinates.revision,
                coordinates.attestationFingerprint,
                coordinates.requestId,
                coordinates.executionId,
                coordinates.keyId,
                baselineReason,
                candidateReason,
                zeroWrite);
    }

    private static String text(
            JsonNode value,
            String field) {
        return value == null
                ? "" : value.path(field)
                .asText("");
    }

    private static ObjectNode object(
            JsonNode value,
            String field) {
        if (value == null || !value.isObject()) {
            throw new IllegalArgumentException(
                    field + " must be an object");
        }
        return ((ObjectNode) value).deepCopy();
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
            String keyId
    ) {
        private static Coordinates from(
                JsonNode attestation,
                EvidenceVerificationKey key) {
            return new Coordinates(
                    text(attestation,
                            "attestationId"),
                    attestation == null
                            ? 0 : Math.max(
                            0,
                            attestation.path(
                                    "revision").asLong()),
                    text(attestation,
                            "attestationFingerprint"),
                    text(attestation,
                            "requestId"),
                    text(attestation,
                            "executionId"),
                    key == null
                            ? "" : key.keyId());
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
