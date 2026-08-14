package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.format.DateTimeParseException;

/** Registry-free semantic verifier for Proposal implementation binding artifacts. */
public final class BusinessMirrorImplementationVerifier {
    private BusinessMirrorImplementationVerifier() {
    }

    /**
     * Verifies one strict payload-free binding command.
     *
     * @param request decoded binding command
     * @throws IllegalArgumentException when the command violates the packaged protocol
     */
    public static void verifyRequest(JsonNode request) {
        BusinessMirrorSchemaValidator.require(request,
                BusinessMirrorProtocol.IMPLEMENTATION_BINDING_REQUEST_SCHEMA_RESOURCE,
                "RG.BUSINESS_MIRROR.CLIENT.IMPLEMENTATION_BINDING_REQUEST_INVALID");
    }

    /**
     * Verifies content address, region admission, safety, and validity times.
     *
     * @param binding decoded immutable binding
     * @return verified payload-free binding identity
     * @throws IllegalArgumentException when semantic verification fails
     */
    public static VerifiedImplementationBinding verifyBinding(JsonNode binding) {
        BusinessMirrorSchemaValidator.require(binding,
                BusinessMirrorProtocol.IMPLEMENTATION_BINDING_SCHEMA_RESOURCE,
                "RG.BUSINESS_MIRROR.CLIENT.IMPLEMENTATION_BINDING_INVALID");
        ObjectNode material = ((ObjectNode) binding).deepCopy();
        String attached = material.path("fingerprint").asText();
        material.put("fingerprint", "");
        String expected = BusinessMirrorCanonical.fingerprint(material,
                "RG.BUSINESS_MIRROR.CLIENT.IMPLEMENTATION_BINDING_TOO_LARGE",
                "RG.BUSINESS_MIRROR.CLIENT.IMPLEMENTATION_BINDING_CANONICALIZATION_FAILED");
        Instant attestedAt = instant(binding.path("runtimeAttestedAt").asText());
        Instant createdAt = instant(binding.path("createdAt").asText());
        Instant expiresAt = instant(binding.path("expiresAt").asText());
        String region = binding.path("scope").path("region").asText();
        boolean regionAllowed = false;
        String previous = null;
        for (JsonNode value : binding.path("allowedRegions")) {
            String current = value.asText();
            if (previous != null && previous.compareTo(current) >= 0) {
                throw invalid("RG.BUSINESS_MIRROR.CLIENT.IMPLEMENTATION_BINDING_REGION_INVALID");
            }
            regionAllowed |= region.equals(current);
            previous = current;
        }
        if (!expected.equals(attached)) {
            throw invalid("RG.BUSINESS_MIRROR.CLIENT.IMPLEMENTATION_BINDING_FINGERPRINT_MISMATCH");
        }
        if (!regionAllowed || attestedAt.isAfter(createdAt) || !expiresAt.isAfter(createdAt)
                || !binding.path("readOnly").asBoolean()
                || !binding.path("stateless").asBoolean()) {
            throw invalid("RG.BUSINESS_MIRROR.CLIENT.IMPLEMENTATION_BINDING_SAFETY_INVALID");
        }
        return new VerifiedImplementationBinding(binding.path("bindingId").asText(),
                attached, binding.path("proposalDraftRef").path("id").asText(),
                binding.path("proposalDraftRef").path("revision").asLong(),
                binding.path("simulationEvidenceRef").path("fingerprint").asText(),
                binding.path("runtimePortRef").asText(),
                binding.path("implementationFingerprint").asText(), expiresAt);
    }

    /**
     * Verifies a durable binding and exact detached-attestation material closure.
     *
     * <p>Cryptographic signer trust remains deployment-owned; this method proves only that the
     * detached seal names the recomputed binding material.</p>
     *
     * @param stored decoded stored binding
     * @return verified payload-free binding identity
     * @throws IllegalArgumentException when Schema or identity closure fails
     */
    public static VerifiedImplementationBinding verifyStoredBinding(JsonNode stored) {
        BusinessMirrorSchemaValidator.require(stored,
                BusinessMirrorProtocol.STORED_IMPLEMENTATION_BINDING_SCHEMA_RESOURCE,
                "RG.BUSINESS_MIRROR.CLIENT.STORED_IMPLEMENTATION_BINDING_INVALID");
        VerifiedImplementationBinding verified = verifyBinding(stored.path("binding"));
        Instant signedAt = instant(stored.path("attestation").path("signedAt").asText());
        Instant createdAt = instant(stored.path("binding").path("createdAt").asText());
        Instant expiresAt = instant(stored.path("binding").path("expiresAt").asText());
        if (!stored.path("requestFingerprint").asText().matches("sha256:[a-f0-9]{64}")
                || !verified.bindingFingerprint().equals(
                stored.path("attestation").path("materialFingerprint").asText())
                || signedAt.isBefore(createdAt) || signedAt.isAfter(expiresAt)) {
            throw invalid("RG.BUSINESS_MIRROR.CLIENT.STORED_IMPLEMENTATION_BINDING_INCONSISTENT");
        }
        return verified;
    }

    private static Instant instant(String value) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException failure) {
            throw invalid("RG.BUSINESS_MIRROR.CLIENT.IMPLEMENTATION_BINDING_TIME_INVALID");
        }
    }

    private static IllegalArgumentException invalid(String code) {
        return new IllegalArgumentException(code);
    }

    /**
     * Verified immutable binding identity.
     *
     * @param bindingId stable binding id
     * @param bindingFingerprint verified content address
     * @param proposalId source Proposal id
     * @param proposalRevision exact Proposal revision
     * @param simulationEvidenceFingerprint exact prerequisite simulation evidence
     * @param runtimePortRef runtime-owned port identity
     * @param implementationFingerprint exact implementation generation
     * @param expiresAt hard binding expiry
     */
    public record VerifiedImplementationBinding(
            String bindingId,
            String bindingFingerprint,
            String proposalId,
            long proposalRevision,
            String simulationEvidenceFingerprint,
            String runtimePortRef,
            String implementationFingerprint,
            Instant expiresAt) {
    }
}
