package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;
import com.leanowtech.bloge.gateway.testing.domain.FixtureRule;
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

        StoredFixtureBundle snapshot = StoredFixtureBundleIntegrity.verifiedSnapshot(mapper, stored);

        assertThat(snapshot).isNotSameAs(stored);
        assertThat(snapshot.bundle()).isEqualTo(stored.bundle());
    }

    @Test
    void verifiedSnapshotDetachesMutableRepositoryOwnedValuesBeforeUse() {
        MutableValue repositoryValue = new MutableValue("approved");
        FixtureRule rule = new FixtureRule("", "mutable", FixtureRule.Selector.node("subject"),
                FixtureRule.Behavior.returning(repositoryValue), FixtureRule.Consumption.once(),
                FixtureRule.SchemaCheck.strict());
        FixtureBundle bundle = new FixtureBundle("", "fixture-mutable", 1,
                "sha256:" + "a".repeat(64), "INTERNAL", null, null,
                List.of(rule), List.of(), Map.of());
        StoredFixtureBundle stored = stored(bundle, ProtocolFingerprint.of(mapper, bundle));

        StoredFixtureBundle snapshot = StoredFixtureBundleIntegrity.verifiedSnapshot(mapper, stored);
        repositoryValue.status = "denied";

        assertThat(snapshot).isNotSameAs(stored);
        assertThat(snapshot.bundle()).isNotSameAs(bundle);
        assertThat(snapshot.bundle().rules().getFirst().behavior().value())
                .isEqualTo(Map.of("status", "approved"));
        assertThat(StoredFixtureBundleIntegrity.verifiedSnapshot(mapper, snapshot))
                .isNotSameAs(snapshot);
        assertThatThrownBy(() -> StoredFixtureBundleIntegrity.verifiedSnapshot(mapper, stored,
                "tenant-b", "test", "fixture-mutable", 1))
                .isInstanceOf(FixtureBundleIntegrityException.class)
                .hasMessage("Stored fixture integrity verification failed")
                .hasMessageNotContaining("tenant-b")
                .hasMessageNotContaining("fixture-mutable");
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

    @Test
    void enterpriseSnapshotRequiresEveryScopeDimensionAndNeverPromotesLegacy() {
        FixtureBundle bundle = bundle("fixture-a", 3, Map.of());
        String fingerprint = ProtocolFingerprint.of(mapper, bundle);
        TestingArtifactScope scope = new TestingArtifactScope(
                "tenant-a", "org-a", "project-a", "test", "sg");
        StoredFixtureBundle scoped = new StoredFixtureBundle(
                "", scope.tenantId(), scope.organizationId(), scope.projectId(),
                scope.environmentId(), scope.region(), bundle.fixtureBundleId(),
                bundle.revision(), fingerprint, bundle, Instant.EPOCH, "runner");

        assertThat(StoredFixtureBundleIntegrity.verifiedSnapshot(
                mapper, scoped, scope, "fixture-a", 3)).isEqualTo(scoped);
        assertThatThrownBy(() -> StoredFixtureBundleIntegrity.verifiedSnapshot(
                mapper, scoped,
                new TestingArtifactScope(
                        "tenant-a", "org-a", "project-b", "test", "sg"),
                "fixture-a", 3))
                .isInstanceOf(FixtureBundleIntegrityException.class);
        assertThatThrownBy(() -> StoredFixtureBundleIntegrity.verifiedSnapshot(
                mapper, stored(bundle, fingerprint), scope, "fixture-a", 3))
                .isInstanceOf(FixtureBundleIntegrityException.class);
    }

    private void assertPayloadFreeFailure(StoredFixtureBundle stored) {
        assertThatThrownBy(() -> StoredFixtureBundleIntegrity.verifiedSnapshot(mapper, stored))
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

    private static final class MutableValue {
        public String status;

        private MutableValue(String status) {
            this.status = status;
        }
    }
}
