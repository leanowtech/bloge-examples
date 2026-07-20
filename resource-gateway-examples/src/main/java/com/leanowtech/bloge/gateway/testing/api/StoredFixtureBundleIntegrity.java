package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Canonical integrity boundary for immutable stored fixture revisions.
 *
 * <p>Database columns and serialized JSON are independent corruption domains. Every create and read
 * therefore binds the stored envelope back to the deserialized bundle and recomputes the canonical
 * content fingerprint. Callers must verify again at their trust transition so alternate repository
 * implementations cannot bypass the invariant.</p>
 */
public final class StoredFixtureBundleIntegrity {

    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    private StoredFixtureBundleIntegrity() {
    }

    /**
     * Verifies one complete stored fixture and returns the same immutable reference.
     *
     * @param objectMapper canonical protocol mapper
     * @param stored stored envelope and bundle content
     * @return {@code stored} after successful verification
     * @throws FixtureBundleIntegrityException when envelope, identity, revision, or content drift
     */
    public static StoredFixtureBundle verify(ObjectMapper objectMapper, StoredFixtureBundle stored) {
        Objects.requireNonNull(objectMapper, "objectMapper");
        if (stored == null || !StoredFixtureBundle.SCHEMA_VERSION.equals(stored.schemaVersion())
                || blank(stored.tenantId()) || blank(stored.environmentId())
                || blank(stored.fixtureBundleId()) || stored.revision() <= 0
                || stored.createdAt() == null || blank(stored.createdBy())
                || stored.bundle() == null || !validFingerprint(stored.fingerprint())) {
            throw new FixtureBundleIntegrityException();
        }
        FixtureBundle bundle = stored.bundle();
        if (!FixtureBundle.SCHEMA_VERSION.equals(bundle.schemaVersion())
                || !stored.fixtureBundleId().equals(bundle.fixtureBundleId())
                || stored.revision() != bundle.revision()) {
            throw new FixtureBundleIntegrityException();
        }
        String actual;
        try {
            actual = ProtocolFingerprint.of(objectMapper, bundle);
        } catch (RuntimeException invalid) {
            throw new FixtureBundleIntegrityException(invalid);
        }
        if (!same(stored.fingerprint(), actual)) {
            throw new FixtureBundleIntegrityException();
        }
        return stored;
    }

    private static boolean validFingerprint(String value) {
        return value != null && FINGERPRINT.matcher(value).matches();
    }

    private static boolean same(String expected, String actual) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                actual.getBytes(StandardCharsets.US_ASCII));
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
