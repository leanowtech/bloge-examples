package com.leanowtech.bloge.gateway.businessmirror.implementation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.businessmirror.domain.CapabilityImplementationBinding;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal;

import java.util.Objects;
import java.util.regex.Pattern;

/** Durable exact response for one server-attested implementation binding. */
public record StoredCapabilityImplementationBinding(
        String schemaVersion,
        String requestFingerprint,
        CapabilityImplementationBinding binding,
        VisualRunEvidenceSeal attestation
) {
    /** Current stored implementation-binding protocol. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.storedCapabilityImplementationBinding.v1";
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    /** Enforces one signed binding and exact request identity. */
    public StoredCapabilityImplementationBinding {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        requestFingerprint = requestFingerprint == null ? "" : requestFingerprint.trim();
        binding = Objects.requireNonNull(binding, "binding");
        attestation = Objects.requireNonNull(attestation, "attestation");
        if (!SCHEMA_VERSION.equals(schemaVersion)
                || !FINGERPRINT.matcher(requestFingerprint).matches()
                || !attestation.signed()
                || !binding.fingerprint().equals(attestation.materialFingerprint())
                || attestation.signedAt() == null
                || attestation.signedAt().isBefore(binding.createdAt())
                || attestation.signedAt().isAfter(binding.expiresAt())) {
            throw new IllegalArgumentException("stored implementation binding is inconsistent");
        }
    }

    /** Verifies the content address and detached binding attestation. */
    public void verify(ObjectMapper mapper, VisualEvidenceSigner signer) {
        binding.verify(mapper);
        if (!Objects.requireNonNull(signer, "signer")
                .verify(attestation, binding.fingerprint()).valid()) {
            throw new IllegalArgumentException("implementation binding attestation is invalid");
        }
    }
}
