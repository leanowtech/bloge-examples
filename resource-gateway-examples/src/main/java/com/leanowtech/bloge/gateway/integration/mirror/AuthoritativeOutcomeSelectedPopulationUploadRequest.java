package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Immutable selection-authority intent for a resumable population upload.
 *
 * @param schemaVersion exact staged-upload protocol version
 * @param uploadId caller-stable idempotency identity
 * @param expectedPredecessorFingerprint blank for revision one, exact current root otherwise
 * @param manifest unsigned population root containing the complete chunk descriptor closure
 */
public record AuthoritativeOutcomeSelectedPopulationUploadRequest(
        String schemaVersion,
        String uploadId,
        String expectedPredecessorFingerprint,
        AuthoritativeOutcomeSelectedPopulationManifest manifest
) {
    /** Current staged-upload intent version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.authoritativeOutcomeSelectedPopulationUploadRequest.v1";
    /** Maximum canonical bytes used to address one upload intent. */
    public static final int MAXIMUM_CANONICAL_BYTES =
            AuthoritativeOutcomeSelectedPopulationManifest
                    .MAXIMUM_CANONICAL_BYTES + 16 * 1024;
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9@._:/-]{0,511}");
    private static final Pattern FINGERPRINT =
            Pattern.compile("sha256:[a-f0-9]{64}");

    /** Requires stable upload identity and exact population lineage. */
    public AuthoritativeOutcomeSelectedPopulationUploadRequest {
        schemaVersion = schemaVersion == null
                || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException(
                    "unsupported selected-population upload schemaVersion");
        }
        uploadId = uploadId == null
                ? "" : uploadId.trim();
        if (!IDENTIFIER.matcher(uploadId).matches()) {
            throw new IllegalArgumentException(
                    "uploadId must be a bounded identifier");
        }
        expectedPredecessorFingerprint =
                expectedPredecessorFingerprint == null
                        ? ""
                        : expectedPredecessorFingerprint.trim();
        if (!expectedPredecessorFingerprint.isBlank()
                && !FINGERPRINT.matcher(
                expectedPredecessorFingerprint).matches()) {
            throw new IllegalArgumentException(
                    "expectedPredecessorFingerprint is invalid");
        }
        manifest = Objects.requireNonNull(
                manifest, "manifest");
        if ((manifest.revision() == 1)
                != expectedPredecessorFingerprint.isBlank()) {
            throw new IllegalArgumentException(
                    "selected-population upload predecessor does not match revision");
        }
    }

    /**
     * Calculates a stable identity excluding Resource Gateway attestation fields.
     *
     * @param mapper canonical protocol mapper
     * @return domain-separated upload intent address
     */
    public String requestFingerprint(ObjectMapper mapper) {
        return ProtocolFingerprint.ofBounded(
                Objects.requireNonNull(mapper, "mapper"),
                new FingerprintMaterial(
                        schemaVersion,
                        uploadId,
                        expectedPredecessorFingerprint,
                        manifest.ingestionMaterialFingerprint(
                                mapper)),
                MAXIMUM_CANONICAL_BYTES);
    }

    private record FingerprintMaterial(
            String schemaVersion,
            String uploadId,
            String expectedPredecessorFingerprint,
            String manifestIngestionFingerprint
    ) {
    }
}
