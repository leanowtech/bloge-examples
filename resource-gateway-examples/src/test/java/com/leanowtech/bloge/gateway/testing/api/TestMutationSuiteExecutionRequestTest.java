package com.leanowtech.bloge.gateway.testing.api;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestMutationSuiteExecutionRequestTest {

    @Test
    void defaultsVersionAndStrategyAndFreezesMetadata() {
        Map<String, Object> metadata = new LinkedHashMap<>(Map.of("pipeline", "nightly"));
        TestMutationSuiteExecutionRequest request = new TestMutationSuiteExecutionRequest(
                "", new TestSuiteExecutionRequest.SuiteRef(
                "mutations", 7, "sha256:" + "a".repeat(64)), " request-1 ", null, metadata);
        metadata.put("pipeline", "changed");

        assertThat(request.schemaVersion())
                .isEqualTo(TestMutationSuiteExecutionRequest.SCHEMA_VERSION);
        assertThat(request.clientRequestId()).isEqualTo("request-1");
        assertThat(request.strategy())
                .isEqualTo(TestMutationSuiteExecutionRequest.Strategy.COLLECT_ALL);
        assertThat(request.metadata()).containsExactlyEntriesOf(Map.of("pipeline", "nightly"));
        assertThatThrownBy(() -> request.metadata().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
