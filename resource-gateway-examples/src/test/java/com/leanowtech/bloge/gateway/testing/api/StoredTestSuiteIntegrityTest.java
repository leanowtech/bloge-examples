package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.domain.TestSuite;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StoredTestSuiteIntegrityTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void canonicalSnapshotDetachesArbitraryMutableCaseInput() {
        MutableValue repositoryValue = new MutableValue("approved");
        TestSuite suite = suite("suite-mutable", 1, repositoryValue, Map.of());
        StoredTestSuite stored = stored(suite, ProtocolFingerprint.of(mapper, suite));

        StoredTestSuite snapshot = StoredTestSuiteIntegrity.verifiedSnapshot(mapper, stored);
        repositoryValue.status = "denied";

        assertThat(snapshot).isNotSameAs(stored);
        assertThat(snapshot.suite()).isNotSameAs(suite);
        assertThat(snapshot.suite().cases().getFirst().input())
                .isEqualTo(Map.of("status", "approved"));
        assertThat(StoredTestSuiteIntegrity.verifiedSnapshot(mapper, snapshot))
                .isNotSameAs(snapshot);
    }

    @Test
    void rejectsContentEnvelopeAndLookupScopeDriftWithoutEchoingPayload() {
        TestSuite original = suite("suite-a", 3, Map.of("value", "original"),
                Map.of("credential", "must-never-escape-71"));
        String fingerprint = ProtocolFingerprint.of(mapper, original);
        TestSuite changed = suite("suite-a", 3, Map.of("value", "changed"), Map.of());

        assertPayloadFreeFailure(stored(changed, fingerprint));
        assertPayloadFreeFailure(new StoredTestSuite("", "tenant-a", "test", "suite-b", 3,
                fingerprint, original, Instant.EPOCH, "runner"));
        assertPayloadFreeFailure(new StoredTestSuite("", "tenant-a", "test", "suite-a", 4,
                fingerprint, original, Instant.EPOCH, "runner"));
        assertPayloadFreeFailure(new StoredTestSuite("bloge.storedTestSuite.v0", "tenant-a",
                "test", "suite-a", 3, fingerprint, original, Instant.EPOCH, "runner"));
        assertThatThrownBy(() -> StoredTestSuiteIntegrity.verifiedSnapshot(mapper,
                stored(original, fingerprint), "tenant-b", "test", "suite-a", 3))
                .isInstanceOf(TestSuiteIntegrityException.class)
                .hasMessage("Stored test-suite integrity verification failed")
                .hasMessageNotContaining("tenant-b")
                .hasMessageNotContaining("suite-a");
    }

    @Test
    void createResultMayRetainFirstWriterProvenanceButNotSubstituteImmutableIdentity() {
        TestSuite suite = suite("suite-a", 3, Map.of("value", "approved"), Map.of());
        String fingerprint = ProtocolFingerprint.of(mapper, suite);
        StoredTestSuite expected = new StoredTestSuite("", "tenant-a", "test", "suite-a", 3,
                fingerprint, suite, Instant.parse("2026-07-20T08:00:00Z"), "retrying-runner");
        StoredTestSuite original = new StoredTestSuite("", "tenant-a", "test", "suite-a", 3,
                fingerprint, suite, Instant.parse("2026-07-19T08:00:00Z"), "first-runner");

        assertThat(StoredTestSuiteIntegrity.verifiedSnapshot(mapper, original, expected).createdBy())
                .isEqualTo("first-runner");

        StoredTestSuite substituted = new StoredTestSuite("", "tenant-a", "test", "suite-b", 3,
                ProtocolFingerprint.of(mapper, suite("suite-b", 3, Map.of(), Map.of())),
                suite("suite-b", 3, Map.of(), Map.of()), Instant.EPOCH, "first-runner");
        assertPayloadFreeFailure(substituted, expected);

        StoredTestSuite corruptExpected = new StoredTestSuite("", "tenant-a", "test", "suite-a",
                3, fingerprint, suite("suite-a", 3, Map.of("value", "denied"), Map.of()),
                expected.createdAt(), expected.createdBy());
        assertPayloadFreeFailure(original, corruptExpected);
    }

    @Test
    void rejectsIncompleteScopeAndNonCanonicalFingerprint() {
        TestSuite suite = suite("suite-a", 3, Map.of(), Map.of());

        assertPayloadFreeFailure(new StoredTestSuite("", "", "test", "suite-a", 3,
                "sha256:suite", suite, Instant.EPOCH, ""));
    }

    private void assertPayloadFreeFailure(StoredTestSuite stored) {
        assertThatThrownBy(() -> StoredTestSuiteIntegrity.verifiedSnapshot(mapper, stored))
                .isInstanceOf(TestSuiteIntegrityException.class)
                .hasMessage("Stored test-suite integrity verification failed")
                .hasMessageNotContaining("must-never-escape-71")
                .hasMessageNotContaining("suite-a")
                .hasMessageNotContaining("tenant-a");
    }

    private void assertPayloadFreeFailure(StoredTestSuite stored, StoredTestSuite expected) {
        assertThatThrownBy(() -> StoredTestSuiteIntegrity.verifiedSnapshot(mapper, stored, expected))
                .isInstanceOf(TestSuiteIntegrityException.class)
                .hasMessage("Stored test-suite integrity verification failed")
                .hasMessageNotContaining("suite-a")
                .hasMessageNotContaining("tenant-a");
    }

    private StoredTestSuite stored(TestSuite suite, String fingerprint) {
        return new StoredTestSuite("", "tenant-a", "test", suite.suiteId(), suite.revision(),
                fingerprint, suite, Instant.EPOCH, "runner");
    }

    private static TestSuite suite(String id, long revision, Object input,
                                   Map<String, Object> metadata) {
        return new TestSuite("", id, revision,
                new TestSuite.Target("GRAPH", "graph-a", "sha256:" + "a".repeat(64)),
                "INTERNAL", List.of(new TestSuite.TestCase("golden",
                TestSuite.CaseType.GOLDEN, input, new TestSuite.FixtureBundleRef(
                "fixture-a", 1, "sha256:" + "b".repeat(64)), List.of(), Map.of())),
                TestSuite.CoveragePolicy.defaults(), TestSuite.PromotionPolicy.defaults(), metadata);
    }

    private static final class MutableValue {
        public String status;

        private MutableValue(String status) {
            this.status = status;
        }
    }
}
