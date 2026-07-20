package com.leanowtech.bloge.gateway.testing.api;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class VisualCanvasDemoScriptTest {

    private static final Path SCRIPT = Path.of("..", "scripts", "visual-canvas-demo.sh")
            .toAbsolutePath().normalize();

    @Test
    void scriptRemainsValidBash() throws Exception {
        Process process = new ProcessBuilder("bash", "-n", SCRIPT.toString())
                .redirectErrorStream(true)
                .start();

        assertThat(process.waitFor()).isZero();
        assertThat(process.getInputStream().readAllBytes()).isEmpty();
    }

    @Test
    void stagingTestSecretCohortFailsBeforeBuildWhenTrustChainIsPartial() throws Exception {
        ProcessBuilder builder = new ProcessBuilder(
                "bash", SCRIPT.toString(), "start", "--no-build")
                .redirectErrorStream(true);
        builder.environment().putAll(Map.ofEntries(
                Map.entry("BLOGE_VISUAL_CANVAS_PROFILE", "staging"),
                Map.entry("RG_TEST_WORKER_QUARANTINE_TOKEN_ACTIVE_KEY_ID", "token-a"),
                Map.entry("RG_TEST_WORKER_QUARANTINE_TOKEN_KEY_RING", "placeholder"),
                Map.entry("RG_TEST_WORKER_QUARANTINE_REQUEST_KEY_ACTIVE_KEY_ID", "request-a"),
                Map.entry("RG_TEST_WORKER_QUARANTINE_REQUEST_KEY_RING", "placeholder"),
                Map.entry("RG_TEST_WORKER_QUARANTINE_REQUEST_INDEX_WRITE_MODE", "KEYED_ONLY"),
                Map.entry("RG_RESOURCE_GATEWAY_INSTANCE_ID", "replica-a"),
                Map.entry("RG_RESOURCE_GATEWAY_ARTIFACT_FINGERPRINT",
                        "sha256:" + "a".repeat(64)),
                Map.entry("RG_TEST_WORKER_QUARANTINE_CHANGE_AUTH_TRUST_DOMAIN",
                        "governance.example"),
                Map.entry("RG_TEST_WORKER_QUARANTINE_CHANGE_AUTH_POLICY_FINGERPRINTS",
                        "sha256:" + "b".repeat(64)),
                Map.entry("RG_TEST_WORKER_QUARANTINE_CHANGE_AUTH_SIGNATURE_THRESHOLD", "1"),
                Map.entry("RG_TEST_WORKER_QUARANTINE_CHANGE_AUTH_AUTHORITY_KEYS_JSON", "[{}]"),
                Map.entry("RG_TEST_SECRET_AUTHORITY_COHORT_ENABLED", "true")));

        Process process = builder.start();
        assertThat(process.waitFor(Duration.ofSeconds(5))).isTrue();
        String output = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);

        assertThat(process.exitValue()).isEqualTo(1);
        assertThat(output).contains(
                "Staging test-secret cohort requires HTTP, dynamic JWKS, remote signed inventory, managed roots, and external anchoring.");
        assertThat(output).doesNotContain("Building Resource Gateway", "Starting visual canvas");
    }

    @Test
    void stagingRejectsStaticExternalNotaryKeysBeforeBuild() throws Exception {
        ProcessBuilder builder = new ProcessBuilder(
                "bash", SCRIPT.toString(), "start", "--no-build")
                .redirectErrorStream(true);
        builder.environment().putAll(Map.ofEntries(
                Map.entry("BLOGE_VISUAL_CANVAS_PROFILE", "staging"),
                Map.entry("RG_TEST_WORKER_QUARANTINE_TOKEN_ACTIVE_KEY_ID", "token-a"),
                Map.entry("RG_TEST_WORKER_QUARANTINE_TOKEN_KEY_RING", "placeholder"),
                Map.entry("RG_TEST_WORKER_QUARANTINE_REQUEST_KEY_ACTIVE_KEY_ID", "request-a"),
                Map.entry("RG_TEST_WORKER_QUARANTINE_REQUEST_KEY_RING", "placeholder"),
                Map.entry("RG_TEST_WORKER_QUARANTINE_REQUEST_INDEX_WRITE_MODE", "KEYED_ONLY"),
                Map.entry("RG_RESOURCE_GATEWAY_INSTANCE_ID", "replica-a"),
                Map.entry("RG_RESOURCE_GATEWAY_ARTIFACT_FINGERPRINT",
                        "sha256:" + "a".repeat(64)),
                Map.entry("RG_TEST_WORKER_QUARANTINE_CHANGE_AUTH_TRUST_DOMAIN",
                        "governance.example"),
                Map.entry("RG_TEST_WORKER_QUARANTINE_CHANGE_AUTH_POLICY_FINGERPRINTS",
                        "sha256:" + "b".repeat(64)),
                Map.entry("RG_TEST_WORKER_QUARANTINE_CHANGE_AUTH_SIGNATURE_THRESHOLD", "1"),
                Map.entry("RG_TEST_WORKER_QUARANTINE_CHANGE_AUTH_AUTHORITY_KEYS_JSON", "[{}]"),
                Map.entry("RG_TEST_SECRET_AUTHORITY_COHORT_ENABLED", "true"),
                Map.entry("RG_TEST_SECRET_AUTHORITY_HTTP_ENABLED", "true"),
                Map.entry("RG_TEST_SECRET_AUTHORITY_JWKS_ENABLED", "true"),
                Map.entry("RG_TEST_SECRET_AUTHORITY_COHORT_SIGNED_INVENTORY_ENABLED", "true"),
                Map.entry("RG_TEST_SECRET_AUTHORITY_COHORT_INVENTORY_REMOTE_ENABLED", "true"),
                Map.entry("RG_TEST_SECRET_AUTHORITY_COHORT_INVENTORY_TRUST_ROOTS_ENABLED", "true"),
                Map.entry("RG_TEST_SECRET_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_ENABLED", "true"),
                Map.entry("RG_TEST_SECRET_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_TRUST_DOMAIN",
                        "notary.example"),
                Map.entry("RG_TEST_SECRET_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_SET_ID",
                        "notary-set-a"),
                Map.entry("RG_TEST_SECRET_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_SIGNATURE_THRESHOLD",
                        "3"),
                Map.entry("RG_TEST_SECRET_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_MAXIMUM_FAULTS",
                        "1"),
                Map.entry("RG_TEST_SECRET_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_AUTHORITY_KEYS_JSON",
                        "[{}]"),
                Map.entry("RG_TEST_SECRET_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_ENDPOINTS_JSON",
                        "[{}]")));

        Process process = builder.start();
        assertThat(process.waitFor(Duration.ofSeconds(5))).isTrue();
        String output = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);

        assertThat(process.exitValue()).isEqualTo(1);
        assertThat(output).contains(
                "Managed test-secret external anchor forbids static notary keys.");
        assertThat(output).doesNotContain("Building Resource Gateway", "Starting visual canvas");
    }
}
