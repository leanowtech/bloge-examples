package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Fixed server-produced public-only fixture for one online paired-source proof.
 *
 * <p>The fixture carries two payload-free commands, two independently signed source artifacts,
 * the signed v2 source-resolution proof, exact expected coordinates, and three public keys. It
 * contains no private key, endpoint, credential, business request, or business response.</p>
 *
 * @param expectedScope authenticated enterprise scope
 * @param expectedAttestationRef exact proof reference
 * @param expectedRequestId durable Shadow request identity
 * @param expectedExecutionId stable logical execution identity
 * @param expectedAdmissionFingerprint exact online-authority admission identity
 * @param baselineCommand exact payload-free regional baseline command
 * @param baselineObservation exact signed regional observation
 * @param baselineKey public regional observation key
 * @param candidateCommand exact payload-free candidate command
 * @param candidateEvidenceBundle exact signed candidate Mirror evidence
 * @param candidateEvidenceKey public candidate evidence key
 * @param attestation exact signed online paired-source proof
 * @param attestationKey public source-resolution authority key
 * @param verificationTime frozen compatibility verification time
 */
public record OnlineReadOnlyShadowSourceResolutionCompatibilityFixture(
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
        JsonNode attestation,
        EvidenceVerificationKey attestationKey,
        Instant verificationTime
) {
    /** Fixed online paired-source compatibility fixture envelope version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.onlineReadOnlyShadowSourceResolutionCompatibility.v1";

    /** Defensively copies public protocol JSON and validates complete verifier coordinates. */
    public OnlineReadOnlyShadowSourceResolutionCompatibilityFixture {
        expectedScope = object(
                expectedScope, "expectedScope");
        expectedAttestationRef = object(
                expectedAttestationRef,
                "expectedAttestationRef");
        expectedRequestId = required(
                expectedRequestId,
                "expectedRequestId");
        expectedExecutionId = required(
                expectedExecutionId,
                "expectedExecutionId");
        expectedAdmissionFingerprint = fingerprint(
                expectedAdmissionFingerprint);
        baselineCommand = object(
                baselineCommand, "baselineCommand");
        baselineObservation = object(
                baselineObservation,
                "baselineObservation");
        baselineKey = Objects.requireNonNull(
                baselineKey, "baselineKey");
        candidateCommand = object(
                candidateCommand, "candidateCommand");
        candidateEvidenceBundle = object(
                candidateEvidenceBundle,
                "candidateEvidenceBundle");
        candidateEvidenceKey =
                Objects.requireNonNull(
                        candidateEvidenceKey,
                        "candidateEvidenceKey");
        attestation = object(
                attestation, "attestation");
        attestationKey = Objects.requireNonNull(
                attestationKey, "attestationKey");
        verificationTime = Objects.requireNonNull(
                verificationTime, "verificationTime");
    }

    /**
     * Parses one exact public-only compatibility fixture envelope.
     *
     * @param value untrusted fixture JSON
     * @return defensively copied typed fixture
     */
    public static
    OnlineReadOnlyShadowSourceResolutionCompatibilityFixture
    from(JsonNode value) {
        requireFields(
                value,
                Set.of(
                        "schemaVersion",
                        "verificationTime",
                        "verificationKeys",
                        "expected",
                        "baselineCommand",
                        "baselineObservation",
                        "candidateCommand",
                        "candidateEvidenceBundle",
                        "attestation"),
                "fixture");
        if (!SCHEMA_VERSION.equals(
                value.path("schemaVersion")
                        .asText())) {
            throw new IllegalArgumentException(
                    "Online source-resolution fixture schemaVersion is invalid");
        }
        JsonNode keys =
                value.path("verificationKeys");
        requireFields(
                keys,
                Set.of(
                        "baselineObservation",
                        "candidateEvidence",
                        "sourceResolution"),
                "verificationKeys");
        JsonNode expected =
                value.path("expected");
        requireFields(
                expected,
                Set.of(
                        "scope",
                        "attestationRef",
                        "requestId",
                        "executionId",
                        "admissionFingerprint"),
                "expected");
        return new OnlineReadOnlyShadowSourceResolutionCompatibilityFixture(
                expected.path("scope"),
                expected.path("attestationRef"),
                expected.path("requestId").asText(),
                expected.path("executionId")
                        .asText(),
                expected.path(
                        "admissionFingerprint")
                        .asText(),
                value.path("baselineCommand"),
                value.path("baselineObservation"),
                key(keys.path(
                        "baselineObservation")),
                value.path("candidateCommand"),
                value.path(
                        "candidateEvidenceBundle"),
                key(keys.path(
                        "candidateEvidence")),
                value.path("attestation"),
                key(keys.path(
                        "sourceResolution")),
                instant(
                        value.path("verificationTime"),
                        "verificationTime"));
    }

    /**
     * Runs the standalone verifier over every artifact and all three authority signatures.
     *
     * @return bounded payload-free online paired-source verification result
     */
    public
    OnlineReadOnlyShadowSourceResolutionAttestationVerifier
            .VerificationResult verify() {
        return new OnlineReadOnlyShadowSourceResolutionAttestationVerifier()
                .verify(
                        attestation,
                        attestationKey,
                        new OnlineReadOnlyShadowSourceResolutionAttestationVerifier
                                .VerificationContext(
                                expectedScope,
                                expectedAttestationRef,
                                expectedRequestId,
                                expectedExecutionId,
                                expectedAdmissionFingerprint,
                                baselineCommand,
                                baselineObservation,
                                baselineKey,
                                candidateCommand,
                                candidateEvidenceBundle,
                                candidateEvidenceKey,
                                verificationTime));
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
     * @return defensive copy of the signed regional observation
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
     * @return defensive copy of the signed candidate evidence
     */
    @Override
    public JsonNode candidateEvidenceBundle() {
        return candidateEvidenceBundle.deepCopy();
    }

    /**
     * Returns an independently owned signed v2 paired-source proof.
     *
     * @return defensive copy of the signed v2 proof
     */
    @Override
    public JsonNode attestation() {
        return attestation.deepCopy();
    }

    OnlineReadOnlyShadowSourceResolutionCompatibilityFixture
    detachedCopy() {
        return new OnlineReadOnlyShadowSourceResolutionCompatibilityFixture(
                expectedScope,
                expectedAttestationRef,
                expectedRequestId,
                expectedExecutionId,
                expectedAdmissionFingerprint,
                baselineCommand,
                baselineObservation,
                baselineKey,
                candidateCommand,
                candidateEvidenceBundle,
                candidateEvidenceKey,
                attestation,
                attestationKey,
                verificationTime);
    }

    private static EvidenceVerificationKey key(
            JsonNode value) {
        requireFields(
                value,
                Set.of(
                        "schemaVersion",
                        "keyId",
                        "algorithm",
                        "encodedPublicKey",
                        "createdAt",
                        "state",
                        "provider"),
                "verificationKey");
        return new EvidenceVerificationKey(
                value.path("schemaVersion")
                        .asText(),
                value.path("keyId").asText(),
                value.path("algorithm").asText(),
                value.path("encodedPublicKey")
                        .asText(),
                instant(
                        value.path("createdAt"),
                        "verificationKey.createdAt"),
                value.path("state").asText(),
                value.path("provider").asText());
    }

    private static void requireFields(
            JsonNode value,
            Set<String> expected,
            String field) {
        if (value == null || !value.isObject()) {
            throw new IllegalArgumentException(
                    field + " must be an object");
        }
        HashSet<String> actual =
                new HashSet<>();
        value.fieldNames()
                .forEachRemaining(actual::add);
        if (!actual.equals(expected)) {
            throw new IllegalArgumentException(
                    field + " fields are invalid");
        }
    }

    private static Instant instant(
            JsonNode value,
            String field) {
        try {
            Instant exact = Instant.parse(
                    value.asText());
            if (Instant.EPOCH.equals(exact)
                    || !exact.toString().equals(
                    value.asText())) {
                throw new IllegalArgumentException(
                        field + " is invalid");
            }
            return exact;
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException(
                    field + " is invalid",
                    invalid);
        }
    }

    private static JsonNode object(
            JsonNode value,
            String field) {
        if (value == null || !value.isObject()) {
            throw new IllegalArgumentException(
                    field + " must be an object");
        }
        return value.deepCopy();
    }

    private static String required(
            String value,
            String field) {
        String exact =
                value == null ? "" : value.trim();
        if (exact.isBlank()
                || exact.length() > 512) {
            throw new IllegalArgumentException(
                    field + " is invalid");
        }
        return exact;
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
}
