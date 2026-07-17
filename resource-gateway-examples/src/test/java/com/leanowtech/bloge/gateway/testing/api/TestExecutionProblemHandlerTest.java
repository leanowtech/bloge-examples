package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TestExecutionProblemHandlerTest {

    private final TestExecutionProblemHandler handler = new TestExecutionProblemHandler();

    @Test
    void emitsBoundedRetryAfterForAdmissionBackpressure() {
        var response = handler.handle(new IntegrationProblemException(
                IntegrationProblem.tooManyRequests(
                        "RG.TEST.ADMISSION_QUOTA_EXCEEDED", "capacity exhausted",
                        "correlation-a", Map.of("retryAfterSeconds", 99_999L))));

        assertThat(response.getStatusCode().value()).isEqualTo(429);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("3600");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().retryable()).isTrue();
    }

    @Test
    void neverReflectsMalformedRetryMetadataIntoAnHttpHeader() {
        var response = handler.handle(new IntegrationProblemException(
                IntegrationProblem.tooManyRequests(
                        "RG.TEST.ADMISSION_QUOTA_EXCEEDED", "capacity exhausted",
                        "correlation-a", Map.of("retryAfterSeconds", "operator-a"))));

        assertThat(response.getStatusCode().value()).isEqualTo(429);
        assertThat(response.getHeaders()).doesNotContainKey(HttpHeaders.RETRY_AFTER);
    }
}
