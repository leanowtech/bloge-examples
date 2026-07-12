package com.leanowtech.bloge.gateway.visual.runtime;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class VisualPayloadSanitizerTest {

    @Test
    void redactsSensitiveKeysAndSecretsEmbeddedInFailureText() {
        VisualNodeExecutionAttempt attempt = new VisualNodeExecutionAttempt(
                0, Map.of("authorization", "Bearer top-secret"), null, "FAILED", Instant.now(), 4,
                "RemoteFailure", "request failed: token=raw-token authorization: Bearer abc.def"
        );

        VisualPayloadSanitizer.Capture capture = VisualPayloadSanitizer.capture(
                Map.of("password", "plain", "nested", Map.of("apiKey", "key-1")), null, Map.of(),
                Map.of("fetch", List.of(attempt))
        );

        assertThat(capture.toString())
                .doesNotContain("top-secret", "raw-token", "abc.def", "key-1", "plain");
        assertThat(capture.nodeAttempts().get("fetch").getFirst().errorMessage())
                .contains("token=[REDACTED]")
                .contains("authorization: [REDACTED]");
        assertThat(capture.redaction().redactedPaths())
                .contains("/context/password", "/context/nested/apiKey",
                        "/nodeAttempts/fetch/0/input/authorization",
                        "/nodeAttempts/fetch/0/errorMessage");
    }
}
