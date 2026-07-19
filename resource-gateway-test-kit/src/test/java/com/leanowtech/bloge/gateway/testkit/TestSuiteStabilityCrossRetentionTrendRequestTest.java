package com.leanowtech.bloge.gateway.testkit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestSuiteStabilityCrossRetentionTrendRequestTest {
    @Test
    void serializesStrictFirstPageAndHeadPinnedContinuation() {
        TestSuiteStabilityCrossRetentionTrendRequest first =
                TestSuiteStabilityCrossRetentionTrendRequest.firstPage(
                        "orders-suite", 7,
                        TestSuiteStabilityCrossRetentionTrendTestFixtures.fingerprint('a'),
                        4, 20);
        TestSuiteStabilityCrossRetentionTrendRequest next = first.continueAfter(
                20, TestSuiteStabilityCrossRetentionTrendTestFixtures.fingerprint('b'));

        assertThat(first.toJson().path("expectedHeadFingerprint").asText()).isEmpty();
        assertThat(next.toJson().path("afterSequence").asLong()).isEqualTo(20);
        assertThat(next.toJson().path("expectedHeadFingerprint").asText())
                .isEqualTo(TestSuiteStabilityCrossRetentionTrendTestFixtures.fingerprint('b'));
        assertThat(first.requestFingerprint()).startsWith("sha256:").hasSize(71);
    }

    @Test
    void rejectsPinnedFirstPageUnpinnedContinuationAndUnboundedCounts() {
        String suiteFingerprint =
                TestSuiteStabilityCrossRetentionTrendTestFixtures.fingerprint('a');
        String headFingerprint =
                TestSuiteStabilityCrossRetentionTrendTestFixtures.fingerprint('b');

        assertThatThrownBy(() -> new TestSuiteStabilityCrossRetentionTrendRequest(
                "orders-suite", 7, suiteFingerprint, 0, 2, 10, headFingerprint))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TestSuiteStabilityCrossRetentionTrendRequest(
                "orders-suite", 7, suiteFingerprint, 1, 2, 10, ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TestSuiteStabilityCrossRetentionTrendRequest.firstPage(
                "orders-suite", 7, suiteFingerprint, 2, 101))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
