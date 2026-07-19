package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestSuiteStabilityTrendRequestTest {
    private static final Instant FROM = Instant.parse("2026-07-18T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-07-19T00:00:00Z");

    @Test
    void emitsExactSchemaValidatedRequestAndDefensiveJson() {
        TestSuiteStabilityTrendRequest request = request(3, 20, FROM, TO);

        ObjectNode first = (ObjectNode) request.toJson();
        first.put("minimumRuns", 99);

        assertThat(request.toJson().path("schemaVersion").asText())
                .isEqualTo(TestingProtocol.TEST_SUITE_STABILITY_TREND_ANALYSIS_REQUEST_V1);
        assertThat(request.toJson().path("minimumRuns").asInt()).isEqualTo(3);
        assertThat(request.requestFingerprint())
                .isEqualTo(EvidenceVerificationSupport.sha256(request.toJson()));
    }

    @Test
    void rejectsReversedOrEmptyTimeWindows() {
        assertThatThrownBy(() -> request(2, 10, TO, FROM))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> request(2, 10, FROM, FROM))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsInsufficientContradictoryOrUnboundedSourceBudgets() {
        assertThatThrownBy(() -> request(1, 10, FROM, TO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> request(10, 9, FROM, TO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> request(2, 101, FROM, TO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static TestSuiteStabilityTrendRequest request(
            int minimumRuns, int maximumRuns, Instant from, Instant to) {
        return new TestSuiteStabilityTrendRequest("orders-suite", 7,
                TestSuiteStabilityTrendTestFixtures.fingerprint('a'), from, to,
                minimumRuns, maximumRuns);
    }
}
