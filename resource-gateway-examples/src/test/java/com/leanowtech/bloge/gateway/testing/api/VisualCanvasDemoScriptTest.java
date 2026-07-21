package com.leanowtech.bloge.gateway.testing.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class VisualCanvasDemoScriptTest {

    private static final Path SCRIPT = Path.of("..", "scripts", "visual-canvas-demo.sh")
            .toAbsolutePath().normalize();

    @TempDir
    private Path temporaryDirectory;

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
                Map.entry("RG_TEST_SECRET_AUTHORITY_COHORT_SCOPE_ID", "secret-fleet"),
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

    @Test
    void stagingRejectsManagedNotaryTrustWithoutPinnedBootstrapRootGenesis()
            throws Exception {
        ProcessBuilder builder = new ProcessBuilder(
                "bash", SCRIPT.toString(), "start", "--no-build")
                .redirectErrorStream(true);
        builder.environment().putAll(stagingBase());
        builder.environment().putAll(Map.ofEntries(
                Map.entry("RG_TEST_SECRET_AUTHORITY_COHORT_ENABLED", "true"),
                Map.entry("RG_TEST_SECRET_AUTHORITY_HTTP_ENABLED", "true"),
                Map.entry("RG_TEST_SECRET_AUTHORITY_JWKS_ENABLED", "true"),
                Map.entry("RG_TEST_SECRET_AUTHORITY_COHORT_SCOPE_ID", "secret-fleet"),
                Map.entry("RG_TEST_SECRET_AUTHORITY_COHORT_SIGNED_INVENTORY_ENABLED", "true"),
                Map.entry("RG_TEST_SECRET_AUTHORITY_COHORT_INVENTORY_REMOTE_ENABLED", "true"),
                Map.entry("RG_TEST_SECRET_AUTHORITY_COHORT_INVENTORY_TRUST_ROOTS_ENABLED", "true"),
                Map.entry("RG_TEST_SECRET_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_ENABLED", "true"),
                Map.entry("RG_TEST_SECRET_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_TRUST_DOMAIN",
                        "secret-notary.example"),
                Map.entry("RG_TEST_SECRET_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_SET_ID",
                        "secret-notary-set"),
                Map.entry("RG_TEST_SECRET_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_SIGNATURE_THRESHOLD",
                        "3"),
                Map.entry("RG_TEST_SECRET_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_MAXIMUM_FAULTS",
                        "1"),
                Map.entry("RG_TEST_SECRET_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_AUTHORITY_KEYS_JSON",
                        "[]"),
                Map.entry("RG_TEST_SECRET_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_ENDPOINTS_JSON",
                        "[{}]"),
                Map.entry("RG_TEST_SECRET_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_MANAGED_TRUST_ENABLED",
                        "true"),
                Map.entry("RG_TEST_SECRET_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_TRUST_URI",
                        "https://notary-trust.example/current"),
                Map.entry("RG_TEST_SECRET_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_TRUST_ROOT_SET_ID",
                        "secret-notary-roots"),
                Map.entry("RG_TEST_SECRET_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_BOOTSTRAP_TRUST_DOMAIN",
                        "secret-notary-root.example"),
                Map.entry("RG_TEST_SECRET_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_TRUST_POLICY_FINGERPRINTS",
                        "sha256:" + "c".repeat(64)),
                Map.entry("RG_TEST_SECRET_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_BOOTSTRAP_THRESHOLD",
                        "0"),
                Map.entry("RG_TEST_SECRET_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_BOOTSTRAP_KEYS_JSON",
                        "[]"),
                Map.entry("RG_TEST_SECRET_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_BOOTSTRAP_ROOTS_ENABLED",
                        "true"),
                Map.entry("RG_TEST_SECRET_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_BOOTSTRAP_ROOT_POLICY_FINGERPRINTS",
                        "sha256:" + "d".repeat(64)),
                Map.entry("RG_TEST_SECRET_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_BOOTSTRAP_ROOT_BUNDLE_URI",
                        "https://root-chain.example/current")));

        Process process = builder.start();
        assertThat(process.waitFor(Duration.ofSeconds(5))).isTrue();
        String output = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);

        assertThat(process.exitValue()).isEqualTo(1);
        assertThat(output).contains(
                "requires notary trust plus a pinned genesis and root bundle");
        assertThat(output).doesNotContain("Building Resource Gateway", "Starting visual canvas");
    }

    @Test
    void stagingRecoveryFleetRejectsManagedRootDowngradeBeforeBuild() throws Exception {
        ProcessBuilder builder = new ProcessBuilder(
                "bash", SCRIPT.toString(), "start", "--no-build")
                .redirectErrorStream(true);
        builder.environment().putAll(stagingBase());
        builder.environment().put("RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_ENABLED", "true");

        Process process = builder.start();
        assertThat(process.waitFor(Duration.ofSeconds(5))).isTrue();
        String output = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);

        assertThat(process.exitValue()).isEqualTo(1);
        assertThat(output).contains(
                "Staging recovery fleet requires dynamic witnessed inventory and managed dual trust roots.");
        assertThat(output).doesNotContain("Building Resource Gateway", "Starting visual canvas");
    }

    @Test
    void stagingRecoveryFleetRejectsPublicationTransportDowngradeBeforeBuild()
            throws Exception {
        ProcessBuilder builder = new ProcessBuilder(
                "bash", SCRIPT.toString(), "start", "--no-build")
                .redirectErrorStream(true);
        builder.environment().putAll(stagingBase());
        builder.environment().putAll(Map.ofEntries(
                Map.entry("RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_ENABLED", "true"),
                Map.entry("RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_ENABLED",
                        "true"),
                Map.entry("RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_REQUIRED",
                        "true"),
                Map.entry("RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_TRUST_ROOTS_ENABLED",
                        "true"),
                Map.entry("RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_TRUST_ROOTS_REQUIRED",
                        "true")));

        Process process = builder.start();
        assertThat(process.waitFor(Duration.ofSeconds(5))).isTrue();
        String output = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);

        assertThat(process.exitValue()).isEqualTo(1);
        assertThat(output).contains(
                "Staging recovery-fleet inventory source requires pinned mutual TLS.");
        assertThat(output).doesNotContain("Building Resource Gateway", "Starting visual canvas");
    }

    @Test
    void stagingRecoveryFleetRejectsSharedPublicationClientIdentityBeforeBuild()
            throws Exception {
        Path sharedClient = Files.createFile(temporaryDirectory.resolve("shared-client.p12"));
        ProcessBuilder builder = new ProcessBuilder(
                "bash", SCRIPT.toString(), "start", "--no-build")
                .redirectErrorStream(true);
        builder.environment().putAll(stagingBase());
        builder.environment().putAll(Map.ofEntries(
                Map.entry("RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_ENABLED", "true"),
                Map.entry("RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_ENABLED",
                        "true"),
                Map.entry("RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_REQUIRED",
                        "true"),
                Map.entry("RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_TRUST_ROOTS_ENABLED",
                        "true"),
                Map.entry("RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_TRUST_ROOTS_REQUIRED",
                        "true"),
                Map.entry("RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_TRANSPORT_ENABLED",
                        "true"),
                Map.entry("RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_TRANSPORT_REQUIRED",
                        "true"),
                Map.entry("RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_TRANSPORT_CLIENT_KEY_STORE_PATH",
                        sharedClient.toString()),
                Map.entry("RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_TRANSPORT_CLIENT_KEY_STORE_PASSWORD_REF",
                        "env:RG_RECOVERY_SHARED_CLIENT_PASSWORD"),
                Map.entry("RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_TRANSPORT_SERVER_SPKI_PINS",
                        "sha256:" + "c".repeat(64)),
                Map.entry("RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_TRUST_ROOT_TRANSPORT_ENABLED",
                        "true"),
                Map.entry("RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_TRUST_ROOT_TRANSPORT_REQUIRED",
                        "true"),
                Map.entry("RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_TRUST_ROOT_TRANSPORT_CLIENT_KEY_STORE_PATH",
                        sharedClient.toString()),
                Map.entry("RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_TRUST_ROOT_TRANSPORT_CLIENT_KEY_STORE_PASSWORD_REF",
                        "env:RG_RECOVERY_SHARED_CLIENT_PASSWORD"),
                Map.entry("RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_TRUST_ROOT_TRANSPORT_SERVER_SPKI_PINS",
                        "sha256:" + "d".repeat(64)),
                Map.entry("RG_RECOVERY_SHARED_CLIENT_PASSWORD", "test-only-secret")));

        Process process = builder.start();
        assertThat(process.waitFor(Duration.ofSeconds(5))).isTrue();
        String output = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);

        assertThat(process.exitValue()).isEqualTo(1);
        assertThat(output).contains(
                "Recovery-fleet inventory and trust-root sources require independent client identities.");
        assertThat(output).doesNotContain("test-only-secret", "Building Resource Gateway",
                "Starting visual canvas");
    }

    private static Map<String, String> stagingBase() {
        return Map.ofEntries(
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
                Map.entry("RG_TEST_WORKER_QUARANTINE_CHANGE_AUTH_AUTHORITY_KEYS_JSON", "[{}]"));
    }
}
