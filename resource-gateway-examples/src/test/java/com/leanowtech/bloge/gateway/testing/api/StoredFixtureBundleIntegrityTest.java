package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StoredFixtureBundleIntegrityTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void acceptsOnlyCanonicalContentBoundToItsEnvelopeIdentity() {
        FixtureBundle bundle = bundle("fixture-a", 3, Map.of("owner", "quality"));
        StoredFixtureBundle stored = stored(bundle, ProtocolFingerprint.of(mapper, bundle));

        assertThat(StoredFixtureBundleIntegrity.verify(mapper, stored)).isSameAs(stored);
    }

    @Test
    void rejectsContentEnvelopeAndProtocolDriftWithoutEchoingPayload() {
        FixtureBundle original = bundle("fixture-a", 3,
                Map.of("credential", "must-never-escape-93"));
        String fingerprint = ProtocolFingerprint.of(mapper, original);
        FixtureBundle changed = bundle("fixture-a", 3,
                Map.of("credential", "tampered-value-51"));

        assertPayloadFreeFailure(stored(changed, fingerprint));
        assertPayloadFreeFailure(new StoredFixtureBundle("", "tenant-a", "test",
                "fixture-b", 3, fingerprint, original, Instant.EPOCH, "runner"));
        assertPayloadFreeFailure(new StoredFixtureBundle("", "tenant-a", "test",
                "fixture-a", 4, fingerprint, original, Instant.EPOCH, "runner"));
        assertPayloadFreeFailure(new StoredFixtureBundle("bloge.storedFixtureBundle.v0",
                "tenant-a", "test", "fixture-a", 3, fingerprint, original,
                Instant.EPOCH, "runner"));
    }

    @Test
    void rejectsIncompleteScopeAndNonCanonicalFingerprint() {
        FixtureBundle bundle = bundle("fixture-a", 3, Map.of());

        assertPayloadFreeFailure(new StoredFixtureBundle("", "", "test", "fixture-a", 3,
                "sha256:fixture", bundle, Instant.EPOCH, ""));
    }

    private void assertPayloadFreeFailure(StoredFixtureBundle stored) {
        assertThatThrownBy(() -> StoredFixtureBundleIntegrity.verify(mapper, stored))
                .isInstanceOf(FixtureBundleIntegrityException.class)
                .hasMessage("Stored fixture integrity verification failed")
                .hasMessageNotContaining("must-never-escape-93")
                .hasMessageNotContaining("tampered-value-51")
                .hasMessageNotContaining("fixture-a")
                .hasMessageNotContaining("tenant-a");
    }

    private StoredFixtureBundle stored(FixtureBundle bundle, String fingerprint) {
        return new StoredFixtureBundle("", "tenant-a", "test", bundle.fixtureBundleId(),
                bundle.revision(), fingerprint, bundle, Instant.EPOCH, "runner");
    }

    private static FixtureBundle bundle(String id, long revision, Map<String, Object> metadata) {
        return new FixtureBundle("", id, revision, "sha256:" + "a".repeat(64),
                "INTERNAL", null, null, List.of(), List.of(), metadata);
    }
}
