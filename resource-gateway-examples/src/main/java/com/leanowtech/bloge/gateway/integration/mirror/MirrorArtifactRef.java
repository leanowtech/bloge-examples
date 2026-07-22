package com.leanowtech.bloge.gateway.integration.mirror;

import java.util.regex.Pattern;

/**
 * Immutable reference to one exact revision of a mirror control-plane artifact.
 *
 * <p>The reference deliberately carries both a monotonic revision and a content fingerprint.
 * A consumer must reject a lookup that resolves the revision to different content; revision alone
 * is not a sufficient execution fence across independently upgraded registries.</p>
 *
 * @param kind stable artifact kind such as {@code CAPABILITY} or {@code STATE_MODEL}
 * @param id stable artifact identifier within its owning tenant
 * @param revision positive immutable revision
 * @param fingerprint canonical SHA-256 fingerprint of the referenced revision
 */
public record MirrorArtifactRef(
        String kind,
        String id,
        long revision,
        String fingerprint
) {
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    /**
     * Normalizes textual coordinates and rejects references that cannot freeze an exact artifact.
     */
    public MirrorArtifactRef {
        kind = required(kind, "kind").toUpperCase(java.util.Locale.ROOT);
        id = required(id, "id");
        if (revision < 1) {
            throw new IllegalArgumentException("revision must be positive");
        }
        fingerprint = required(fingerprint, "fingerprint");
        if (!FINGERPRINT.matcher(fingerprint).matches()) {
            throw new IllegalArgumentException("fingerprint must be a canonical SHA-256 value");
        }
    }

    private static String required(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }
}
