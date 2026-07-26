package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Fixed server-produced compatibility fixture for exact detached Shadow source resolution.
 *
 * <p>The fixture contains three distinct public keys and no private key or business payload. The
 * candidate evidence, source binding, and source-resolution attestation are independently
 * verified under their own authority roles, preventing producer and consumer implementations
 * from passing against separately maintained identity or signature algorithms.</p>
 *
 * @param expectedScope authenticated complete enterprise scope
 * @param expectedAttestationRef exact source-resolution artifact coordinates
 * @param expectedRequestId durable Shadow request identity
 * @param expectedExecutionId stable logical execution identity
 * @param expectedAdmissionFingerprint exact online-authority admission identity
 * @param candidateEvidenceBundle exact signed candidate evidence
 * @param sourceBinding exact signed detached source pair
 * @param attestation exact signed source-resolution proof
 * @param candidateEvidenceKey public candidate-evidence authority key
 * @param sourceBindingKey public source-binding authority key
 * @param sourceResolutionKey public source-resolution authority key
 * @param verificationTime frozen compatibility verification time
 */
public record ReadOnlyShadowSourceResolutionCompatibilityFixture(
        JsonNode expectedScope,
        JsonNode expectedAttestationRef,
        String expectedRequestId,
        String expectedExecutionId,
        String expectedAdmissionFingerprint,
        JsonNode candidateEvidenceBundle,
        JsonNode sourceBinding,
        JsonNode attestation,
        EvidenceVerificationKey candidateEvidenceKey,
        EvidenceVerificationKey sourceBindingKey,
        EvidenceVerificationKey sourceResolutionKey,
        Instant verificationTime
) {
    /** Fixed fixture envelope wire version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.readOnlyShadowSourceResolutionCompatibility.v1";

    /** Defensively copies payload-free JSON and validates all verification inputs. */
    public ReadOnlyShadowSourceResolutionCompatibilityFixture {
        expectedScope = object(expectedScope, "expectedScope");
        expectedAttestationRef = object(
                expectedAttestationRef,
                "expectedAttestationRef");
        expectedRequestId = text(
                expectedRequestId,
                "expectedRequestId");
        expectedExecutionId = text(
                expectedExecutionId,
                "expectedExecutionId");
        expectedAdmissionFingerprint = fingerprint(
                expectedAdmissionFingerprint,
                "expectedAdmissionFingerprint");
        candidateEvidenceBundle = object(
                candidateEvidenceBundle,
                "candidateEvidenceBundle");
        sourceBinding = object(
                sourceBinding,
                "sourceBinding");
        attestation = object(attestation, "attestation");
        candidateEvidenceKey = Objects.requireNonNull(
                candidateEvidenceKey,
                "candidateEvidenceKey");
        sourceBindingKey = Objects.requireNonNull(
                sourceBindingKey,
                "sourceBindingKey");
        sourceResolutionKey = Objects.requireNonNull(
                sourceResolutionKey,
                "sourceResolutionKey");
        verificationTime = Objects.requireNonNull(
                verificationTime,
                "verificationTime");
        if (candidateEvidenceKey.keyId().equals(
                sourceBindingKey.keyId())
                || candidateEvidenceKey.keyId().equals(
                sourceResolutionKey.keyId())
                || sourceBindingKey.keyId().equals(
                sourceResolutionKey.keyId())) {
            throw new IllegalArgumentException(
                    "Shadow source-resolution fixture authority roles must use distinct keys");
        }
    }

    /**
     * Parses one strict fixture envelope from the packaged public resource.
     *
     * @param value untrusted fixture JSON
     * @return defensively copied typed fixture
     */
    public static ReadOnlyShadowSourceResolutionCompatibilityFixture from(
            JsonNode value) {
        requireFields(
                value,
                Set.of(
                        "schemaVersion",
                        "verificationTime",
                        "verificationKeys",
                        "expected",
                        "candidateEvidenceBundle",
                        "sourceBinding",
                        "attestation"),
                "fixture");
        if (!SCHEMA_VERSION.equals(
                value.path("schemaVersion").asText())) {
            throw new IllegalArgumentException(
                    "Shadow source-resolution fixture schemaVersion is invalid");
        }
        JsonNode keys = value.path("verificationKeys");
        requireFields(
                keys,
                Set.of(
                        "candidateEvidence",
                        "sourceBinding",
                        "sourceResolution"),
                "verificationKeys");
        JsonNode expected = value.path("expected");
        requireFields(
                expected,
                Set.of(
                        "scope",
                        "attestationRef",
                        "requestId",
                        "executionId",
                        "admissionFingerprint"),
                "expected");
        return new ReadOnlyShadowSourceResolutionCompatibilityFixture(
                expected.path("scope"),
                expected.path("attestationRef"),
                expected.path("requestId").asText(),
                expected.path("executionId").asText(),
                expected.path(
                        "admissionFingerprint").asText(),
                value.path("candidateEvidenceBundle"),
                value.path("sourceBinding"),
                value.path("attestation"),
                key(keys.path("candidateEvidence")),
                key(keys.path("sourceBinding")),
                key(keys.path("sourceResolution")),
                Instant.parse(
                        value.path("verificationTime")
                                .asText()));
    }

    /**
     * Runs the standalone verifier over the complete three-authority fixture.
     *
     * @return bounded payload-free verification result
     */
    public ReadOnlyShadowSourceResolutionAttestationVerifier
    .VerificationResult verify() {
        ReadOnlyShadowSourceBindingVerifier.VerificationContext
                bindingContext =
                new ReadOnlyShadowSourceBindingVerifier
                        .VerificationContext(
                        expectedScope,
                        attestation.path("sourceBindingRef"),
                        candidateEvidenceBundle,
                        candidateEvidenceKey,
                        verificationTime);
        return new ReadOnlyShadowSourceResolutionAttestationVerifier()
                .verify(
                        attestation,
                        sourceResolutionKey,
                        new ReadOnlyShadowSourceResolutionAttestationVerifier
                                .VerificationContext(
                                expectedScope,
                                expectedAttestationRef,
                                expectedRequestId,
                                expectedExecutionId,
                                expectedAdmissionFingerprint,
                                sourceBinding,
                                sourceBindingKey,
                                bindingContext,
                                verificationTime));
    }

    /**
     * Returns the complete enterprise scope authenticated by the fixture.
     *
     * @return defensive copy of the authenticated scope
     */
    @Override
    public JsonNode expectedScope() {
        return expectedScope.deepCopy();
    }

    /**
     * Returns the exact artifact coordinates expected from source resolution.
     *
     * @return defensive copy of the exact expected source-resolution reference
     */
    @Override
    public JsonNode expectedAttestationRef() {
        return expectedAttestationRef.deepCopy();
    }

    /**
     * Returns the signed candidate evidence verified by its dedicated authority.
     *
     * @return defensive copy of the candidate evidence
     */
    @Override
    public JsonNode candidateEvidenceBundle() {
        return candidateEvidenceBundle.deepCopy();
    }

    /**
     * Returns the signed exact source pair verified by the source-binding authority.
     *
     * @return defensive copy of the signed source binding
     */
    @Override
    public JsonNode sourceBinding() {
        return sourceBinding.deepCopy();
    }

    /**
     * Returns the signed proof that closes source selection for the logical execution.
     *
     * @return defensive copy of the signed source-resolution proof
     */
    @Override
    public JsonNode attestation() {
        return attestation.deepCopy();
    }

    ReadOnlyShadowSourceResolutionCompatibilityFixture detachedCopy() {
        return new ReadOnlyShadowSourceResolutionCompatibilityFixture(
                expectedScope,
                expectedAttestationRef,
                expectedRequestId,
                expectedExecutionId,
                expectedAdmissionFingerprint,
                candidateEvidenceBundle,
                sourceBinding,
                attestation,
                candidateEvidenceKey,
                sourceBindingKey,
                sourceResolutionKey,
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
                value.path("schemaVersion").asText(),
                value.path("keyId").asText(),
                value.path("algorithm").asText(),
                value.path("encodedPublicKey").asText(),
                Instant.parse(value.path("createdAt").asText()),
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
        HashSet<String> actual = new HashSet<>();
        value.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) {
            throw new IllegalArgumentException(
                    field + " fields are invalid");
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

    private static String text(
            String value,
            String field) {
        String exact = value == null ? "" : value.trim();
        if (!exact.matches(
                "[A-Za-z0-9][A-Za-z0-9@._:/-]{0,511}")) {
            throw new IllegalArgumentException(
                    field + " is invalid");
        }
        return exact;
    }

    private static String fingerprint(
            String value,
            String field) {
        String exact = value == null ? "" : value.trim();
        if (!exact.matches("sha256:[a-f0-9]{64}")) {
            throw new IllegalArgumentException(
                    field + " is invalid");
        }
        return exact;
    }
}
