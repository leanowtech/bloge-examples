package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.io.IOException;
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

    private static StoredFixtureBundle verify(ObjectMapper objectMapper, StoredFixtureBundle stored) {
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

    /**
     * Canonically detaches one repository-owned bundle and verifies the exact detached value.
     *
     * <p>The serialization round trip is intentional. A Java record and its collection wrappers
     * cannot detach an arbitrary mutable object embedded in an {@code Object} protocol field. The
     * returned envelope owns a fresh JSON value graph, so a repository cannot mutate the runtime
     * plan after verification through a retained alias.</p>
     *
     * @param objectMapper canonical protocol mapper
     * @param stored repository-owned envelope
     * @return independently owned and integrity-verified envelope
     * @throws FixtureBundleIntegrityException when canonicalization or verification fails
     */
    public static StoredFixtureBundle verifiedSnapshot(ObjectMapper objectMapper,
                                                       StoredFixtureBundle stored) {
        Objects.requireNonNull(objectMapper, "objectMapper");
        if (stored == null || stored.bundle() == null) {
            throw new FixtureBundleIntegrityException();
        }
        try {
            byte[] canonicalValue = objectMapper.writeValueAsBytes(stored.bundle());
            FixtureBundle bundle = objectMapper.readValue(canonicalValue, FixtureBundle.class);
            return verify(objectMapper, new StoredFixtureBundle(stored.schemaVersion(),
                    stored.tenantId(), stored.environmentId(), stored.fixtureBundleId(),
                    stored.revision(), stored.fingerprint(), bundle, stored.createdAt(),
                    stored.createdBy()));
        } catch (FixtureBundleIntegrityException invalid) {
            throw invalid;
        } catch (IOException | RuntimeException invalid) {
            throw new FixtureBundleIntegrityException(invalid);
        }
    }

    /**
     * Detaches and verifies a repository result against the exact lookup key.
     *
     * @param objectMapper canonical protocol mapper
     * @param stored repository-owned result
     * @param tenantId authorized tenant lookup key
     * @param environmentId authorized environment lookup key
     * @param fixtureBundleId requested fixture id
     * @param revision requested revision
     * @return independently owned result bound to the complete lookup key
     * @throws FixtureBundleIntegrityException when the result is corrupt or cross-boundary
     */
    public static StoredFixtureBundle verifiedSnapshot(ObjectMapper objectMapper,
                                                       StoredFixtureBundle stored,
                                                       String tenantId,
                                                       String environmentId,
                                                       String fixtureBundleId,
                                                       long revision) {
        StoredFixtureBundle snapshot = verifiedSnapshot(objectMapper, stored);
        if (!Objects.equals(tenantId, snapshot.tenantId())
                || !Objects.equals(environmentId, snapshot.environmentId())
                || !Objects.equals(fixtureBundleId, snapshot.fixtureBundleId())
                || revision != snapshot.revision()) {
            throw new FixtureBundleIntegrityException();
        }
        return snapshot;
    }

    /**
     * Detaches and verifies a create result against the submitted immutable identity.
     *
     * <p>Creation provenance is intentionally not compared. An idempotent create returns the
     * original registry timestamp and author, which legitimately differ from the retrying request.
     * The base integrity check still requires both values to be complete.</p>
     *
     * @param objectMapper canonical protocol mapper
     * @param stored repository-owned create result
     * @param expected canonical envelope submitted to the repository
     * @return independently owned result with the submitted immutable identity and content
     * @throws FixtureBundleIntegrityException when an immutable identity or content field changed
     */
    public static StoredFixtureBundle verifiedSnapshot(ObjectMapper objectMapper,
                                                       StoredFixtureBundle stored,
                                                       StoredFixtureBundle expected) {
        if (expected == null) {
            throw new FixtureBundleIntegrityException();
        }
        StoredFixtureBundle snapshot = verifiedSnapshot(objectMapper, stored,
                expected.tenantId(), expected.environmentId(), expected.fixtureBundleId(),
                expected.revision());
        if (!Objects.equals(expected.schemaVersion(), snapshot.schemaVersion())
                || !Objects.equals(expected.fingerprint(), snapshot.fingerprint())) {
            throw new FixtureBundleIntegrityException();
        }
        return snapshot;
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
