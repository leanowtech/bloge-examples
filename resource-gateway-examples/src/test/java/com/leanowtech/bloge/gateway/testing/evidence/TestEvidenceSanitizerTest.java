package com.leanowtech.bloge.gateway.testing.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.leanowtech.bloge.gateway.testing.domain.TestRunEvidence;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TestEvidenceSanitizerTest {

    private final ObjectMapper mapper = JsonMapper.builder()
            .addModule(new JavaTimeModule()).build();
    private final TestEvidenceSanitizer sanitizer = new TestEvidenceSanitizer(mapper);

    @Test
    void recomputesSemanticIdentityAfterRedactionWithoutCreatingASecretOracle() throws Exception {
        TestRunEvidence first = sanitizer.sanitize(evidence("first-secret"));
        TestRunEvidence second = sanitizer.sanitize(evidence("second-secret"));

        assertThat(first.semanticResultFingerprint())
                .isEqualTo(second.semanticResultFingerprint());
        assertThat(TestSemanticResultFingerprint.matches(mapper, first)).isTrue();
        assertThat(TestSemanticResultFingerprint.matches(mapper, second)).isTrue();
        assertThat(mapper.writeValueAsString(List.of(first, second)))
                .doesNotContain("first-secret", "second-secret")
                .contains("[REDACTED]");
    }

    private static TestRunEvidence evidence(String secret) {
        Object payload = Map.of("customerId", "C-1", "password", secret);
        TestRunEvidence.NodeTrace node = new TestRunEvidence.NodeTrace(
                "lookup", "operator.lookup", "SUCCESS", "REAL", payload, payload, "", 5,
                "/root/lookup#PRIMARY", "/root", "", 1, 1,
                List.of(new TestRunEvidence.AttemptTrace(
                        1, "SUCCESS", "REAL", payload, payload, "", 5)));
        return TestSemanticResultFingerprint.attach(JsonMapper.builder()
                        .addModule(new JavaTimeModule()).build(),
                new TestRunEvidence(TestRunEvidence.SCHEMA_VERSION, "run-secret-redaction",
                        TestRunEvidence.Status.PASSED, TestRunEvidence.EvidenceClass.CERTIFIABLE,
                        "GRAPH_CONTRACT_TEST", "sha256:" + "1".repeat(64),
                        "sha256:" + "2".repeat(64), "sha256:" + "3".repeat(64),
                        Instant.EPOCH, Instant.EPOCH.plusSeconds(1), List.of(node), List.of(),
                        List.of(), List.of(), List.of(), Map.of()));
    }
}
