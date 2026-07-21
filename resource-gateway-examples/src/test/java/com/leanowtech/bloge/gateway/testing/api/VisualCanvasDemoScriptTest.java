package com.leanowtech.bloge.gateway.testing.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
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
    void stagingRejectsPartialCertificateRotationBeforeBuild() throws Exception {
        ProcessBuilder builder = new ProcessBuilder(
                "bash", SCRIPT.toString(), "start", "--no-build")
                .redirectErrorStream(true);
        builder.environment().putAll(stagingBase());
        builder.environment().put(
                "RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_ENABLED", "true");

        Process process = builder.start();
        assertThat(process.waitFor(Duration.ofSeconds(5))).isTrue();
        String output = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);

        assertThat(process.exitValue()).isEqualTo(1);
        assertThat(output).contains(
                "Signed certificate rotation requires complete scope, trust, generation, and material configuration.");
        assertThat(output).doesNotContain("Building Resource Gateway", "Starting visual canvas");
    }

    @Test
    void convergenceCanBeEnabledWithoutRequiredModeButStillRequiresExternalFleetProof()
            throws Exception {
        ProcessBuilder builder = new ProcessBuilder(
                "bash", SCRIPT.toString(), "start", "--no-build")
                .redirectErrorStream(true);
        builder.environment().putAll(stagingBase());
        builder.environment().putAll(Map.ofEntries(
                Map.entry("RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_ENABLED", "true"),
                Map.entry("RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_REQUIRED", "true"),
                Map.entry("RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_SCOPE_ID",
                        "rg-staging"),
                Map.entry("RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_TRUST_DOMAIN",
                        "enterprise-pki"),
                Map.entry("RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_ACCEPTED_POLICIES",
                        "sha256:" + "c".repeat(64)),
                Map.entry("RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_SIGNATURE_THRESHOLD", "1"),
                Map.entry("RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_AUTHORITY_KEYS_JSON", "[{}]"),
                Map.entry("RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_INITIAL_GENERATIONS_JSON",
                        "{\"test.secret.notary.transport\":1}"),
                Map.entry("RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_MATERIAL_CATALOG_JSON",
                        "[{}]"),
                Map.entry("RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_CONVERGENCE_ENABLED", "true"),
                Map.entry("RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_FLEET_ID", "fleet-a"),
                Map.entry("RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_INSTANCE_ID", "replica-a"),
                Map.entry("RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_STARTUP_ID",
                        "6f5fe859-b55d-4bd2-b5bf-5cc54356e03d"),
                Map.entry("RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_ARTIFACT_FINGERPRINT",
                        "sha256:" + "d".repeat(64)),
                Map.entry("RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_EXPECTED_INSTANCE_IDS",
                        "replica-a,replica-b"),
                Map.entry("RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_REQUIRED_STAGED_REPLICAS",
                        "2"),
                Map.entry("RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_INVENTORY_SOURCE_TYPE",
                        "DEPLOYMENT_SIGNED")));

        Process process = builder.start();
        assertThat(process.waitFor(Duration.ofSeconds(5))).isTrue();
        String output = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);

        assertThat(process.exitValue()).isEqualTo(1);
        assertThat(output).contains(
                "External certificate rotation inventory attestation is incomplete.");
        assertThat(output).doesNotContain("required=true", "Building Resource Gateway",
                "Starting visual canvas");
    }

    @Test
    void stagingRejectsEventDeliveryWithoutRequiredRotationAndConvergenceBeforeBuild()
            throws Exception {
        ProcessBuilder builder = new ProcessBuilder(
                "bash", SCRIPT.toString(), "start", "--no-build")
                .redirectErrorStream(true);
        builder.environment().putAll(stagingBase());
        builder.environment().put(
                "RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_EVENT_SOURCE_ENABLED", "true");

        Process process = builder.start();
        assertThat(process.waitFor(Duration.ofSeconds(5))).isTrue();
        String output = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);

        assertThat(process.exitValue()).isEqualTo(1);
        assertThat(output).contains(
                "Certificate rotation event delivery requires required signed rotation and all-replica convergence.");
        assertThat(output).doesNotContain("Building Resource Gateway", "Starting visual canvas");
    }

    @Test
    void stagingRejectsEventSourceLoopbackEscapeBeforeTransportLoading() throws Exception {
        ProcessBuilder builder = new ProcessBuilder(
                "bash", SCRIPT.toString(), "start", "--no-build")
                .redirectErrorStream(true);
        Map<String, String> environment = new HashMap<>(stagingBase());
        putRequiredCertificateRotation(environment);
        environment.put("RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_EVENT_SOURCE_ENABLED",
                "true");
        environment.put("RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_EVENT_SOURCE_REQUIRED",
                "true");
        environment.put("RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_EVENT_SOURCE_ENDPOINT_URI",
                "http://127.0.0.1:18080/events");
        environment.put(
                "RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_EVENT_SOURCE_BASELINE_PAGE_FINGERPRINT",
                "sha256:" + "e".repeat(64));
        environment.put(
                "RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_EVENT_SOURCE_ALLOW_INSECURE_LOOPBACK",
                "true");
        builder.environment().putAll(environment);

        Process process = builder.start();
        assertThat(process.waitFor(Duration.ofSeconds(5))).isTrue();
        String output = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);

        assertThat(process.exitValue()).isEqualTo(1);
        assertThat(output).contains(
                "Staging certificate rotation event source requires HTTPS and an exact page-chain baseline.");
        assertThat(output).doesNotContain("Building Resource Gateway", "Starting visual canvas");
    }

    @Test
    void stagingRejectsEventSourcePrivateTrustDowngradeBeforeBuild() throws Exception {
        ProcessBuilder builder = new ProcessBuilder(
                "bash", SCRIPT.toString(), "start", "--no-build")
                .redirectErrorStream(true);
        Map<String, String> environment = new HashMap<>(stagingBase());
        putRequiredCertificateRotation(environment);
        environment.put("RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_EVENT_SOURCE_ENABLED",
                "true");
        environment.put("RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_EVENT_SOURCE_REQUIRED",
                "true");
        environment.put("RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_EVENT_SOURCE_ENDPOINT_URI",
                "https://ca.example.test/v1/rotation-events");
        environment.put(
                "RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_EVENT_SOURCE_BASELINE_PAGE_FINGERPRINT",
                "sha256:" + "e".repeat(64));
        builder.environment().putAll(environment);

        Process process = builder.start();
        assertThat(process.waitFor(Duration.ofSeconds(5))).isTrue();
        String output = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);

        assertThat(process.exitValue()).isEqualTo(1);
        assertThat(output).contains(
                "Certificate rotation event source requires a private trust store.");
        assertThat(output).doesNotContain("Building Resource Gateway", "Starting visual canvas");
    }

    @Test
    void stagingRejectsCertificateStatusWithoutRequiredRotationBeforeBuild()
            throws Exception {
        ProcessBuilder builder = new ProcessBuilder(
                "bash", SCRIPT.toString(), "start", "--no-build")
                .redirectErrorStream(true);
        builder.environment().putAll(stagingBase());
        builder.environment().put(
                "RG_TEST_CONTROL_PLANE_CERTIFICATE_STATUS_ENABLED", "true");

        Process process = builder.start();
        assertThat(process.waitFor(Duration.ofSeconds(5))).isTrue();
        String output = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);

        assertThat(process.exitValue()).isEqualTo(1);
        assertThat(output).contains(
                "Certificate status admission requires required signed rotation with the exact deployment scope.");
        assertThat(output).doesNotContain("Building Resource Gateway", "Starting visual canvas");
    }

    @Test
    void stagingRejectsCertificateStatusScopeDriftBeforeTrustLoading() throws Exception {
        ProcessBuilder builder = new ProcessBuilder(
                "bash", SCRIPT.toString(), "start", "--no-build")
                .redirectErrorStream(true);
        Map<String, String> environment = new HashMap<>(stagingBase());
        putRequiredCertificateRotation(environment);
        environment.put("RG_TEST_CONTROL_PLANE_CERTIFICATE_STATUS_ENABLED", "true");
        environment.put("RG_TEST_CONTROL_PLANE_CERTIFICATE_STATUS_REQUIRED", "true");
        environment.put("RG_TEST_CONTROL_PLANE_CERTIFICATE_STATUS_SCOPE_ID", "other-scope");
        builder.environment().putAll(environment);

        Process process = builder.start();
        assertThat(process.waitFor(Duration.ofSeconds(5))).isTrue();
        String output = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);

        assertThat(process.exitValue()).isEqualTo(1);
        assertThat(output).contains(
                "Certificate status admission requires required signed rotation with the exact deployment scope.");
        assertThat(output).doesNotContain("Building Resource Gateway", "Starting visual canvas");
    }

    @Test
    void stagingRejectsCertificateStatusPrivateTrustDowngradeBeforeBuild()
            throws Exception {
        ProcessBuilder builder = new ProcessBuilder(
                "bash", SCRIPT.toString(), "start", "--no-build")
                .redirectErrorStream(true);
        Map<String, String> environment = new HashMap<>(stagingBase());
        putRequiredCertificateRotation(environment);
        environment.putAll(Map.ofEntries(
                Map.entry("RG_TEST_CONTROL_PLANE_CERTIFICATE_STATUS_ENABLED", "true"),
                Map.entry("RG_TEST_CONTROL_PLANE_CERTIFICATE_STATUS_REQUIRED", "true"),
                Map.entry("RG_TEST_CONTROL_PLANE_CERTIFICATE_STATUS_SCOPE_ID", "rg-staging"),
                Map.entry("RG_TEST_CONTROL_PLANE_CERTIFICATE_STATUS_TRUST_DOMAIN",
                        "enterprise-ca-status"),
                Map.entry("RG_TEST_CONTROL_PLANE_CERTIFICATE_STATUS_ACCEPTED_POLICIES",
                        "sha256:" + "9".repeat(64)),
                Map.entry("RG_TEST_CONTROL_PLANE_CERTIFICATE_STATUS_SIGNATURE_THRESHOLD", "1"),
                Map.entry("RG_TEST_CONTROL_PLANE_CERTIFICATE_STATUS_AUTHORITY_KEYS_JSON", "[{}]"),
                Map.entry("RG_TEST_CONTROL_PLANE_CERTIFICATE_STATUS_BASELINE_SEQUENCE", "0"),
                Map.entry("RG_TEST_CONTROL_PLANE_CERTIFICATE_STATUS_BASELINE_PUBLICATION_FINGERPRINT",
                        "sha256:" + "8".repeat(64)),
                Map.entry("RG_TEST_CONTROL_PLANE_CERTIFICATE_STATUS_ENDPOINT_URI",
                        "https://ca.example.test/v1/certificate-status")));
        builder.environment().putAll(environment);

        Process process = builder.start();
        assertThat(process.waitFor(Duration.ofSeconds(5))).isTrue();
        String output = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);

        assertThat(process.exitValue()).isEqualTo(1);
        assertThat(output).contains(
                "Certificate status source requires a private trust store.");
        assertThat(output).doesNotContain("Building Resource Gateway", "Starting visual canvas");
    }

    @Test
    void stagingRejectsIncoherentCertificateStatusSloBeforeBuild() throws Exception {
        ProcessBuilder builder = new ProcessBuilder(
                "bash", SCRIPT.toString(), "start", "--no-build")
                .redirectErrorStream(true);
        Map<String, String> environment = new HashMap<>(stagingBase());
        putRequiredCertificateRotation(environment);
        environment.putAll(Map.ofEntries(
                Map.entry("RG_TEST_CONTROL_PLANE_CERTIFICATE_STATUS_ENABLED", "true"),
                Map.entry("RG_TEST_CONTROL_PLANE_CERTIFICATE_STATUS_REQUIRED", "true"),
                Map.entry("RG_TEST_CONTROL_PLANE_CERTIFICATE_STATUS_SCOPE_ID", "rg-staging"),
                Map.entry("RG_TEST_CONTROL_PLANE_CERTIFICATE_STATUS_TRUST_DOMAIN",
                        "enterprise-ca-status"),
                Map.entry("RG_TEST_CONTROL_PLANE_CERTIFICATE_STATUS_ACCEPTED_POLICIES",
                        "sha256:" + "9".repeat(64)),
                Map.entry("RG_TEST_CONTROL_PLANE_CERTIFICATE_STATUS_SIGNATURE_THRESHOLD", "1"),
                Map.entry("RG_TEST_CONTROL_PLANE_CERTIFICATE_STATUS_AUTHORITY_KEYS_JSON", "[{}]"),
                Map.entry("RG_TEST_CONTROL_PLANE_CERTIFICATE_STATUS_BASELINE_SEQUENCE", "0"),
                Map.entry("RG_TEST_CONTROL_PLANE_CERTIFICATE_STATUS_BASELINE_PUBLICATION_FINGERPRINT",
                        "sha256:" + "8".repeat(64)),
                Map.entry("RG_TEST_CONTROL_PLANE_CERTIFICATE_STATUS_ENDPOINT_URI",
                        "https://ca.example.test/v1/certificate-status"),
                Map.entry("RG_TEST_CONTROL_PLANE_CERTIFICATE_STATUS_SLO_STARTUP_GRACE_SECONDS",
                        "5")));
        builder.environment().putAll(environment);

        Process process = builder.start();
        assertThat(process.waitFor(Duration.ofSeconds(5))).isTrue();
        String output = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);

        assertThat(process.exitValue()).isEqualTo(1);
        assertThat(output).contains("Certificate status SLO bounds are invalid.");
        assertThat(output).doesNotContain("Building Resource Gateway", "Starting visual canvas");
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
    void stagingRejectsExternalAnchorTransportDowngradeBeforeBuild() throws Exception {
        ProcessBuilder builder = new ProcessBuilder(
                "bash", SCRIPT.toString(), "start", "--no-build")
                .redirectErrorStream(true);
        Map<String, String> environment = new HashMap<>(stagingBase());
        environment.putAll(stagingTestSecretExternalAnchor());
        builder.environment().putAll(environment);

        Process process = builder.start();
        assertThat(process.waitFor(Duration.ofSeconds(5))).isTrue();
        String output = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);

        assertThat(process.exitValue()).isEqualTo(1);
        assertThat(output).contains(
                "Staging test-secret external notary requires pinned mutual TLS.");
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
    void stagingBootstrapRootPublisherRejectsTransportDowngradeBeforeBuild()
            throws Exception {
        ProcessBuilder builder = new ProcessBuilder(
                "bash", SCRIPT.toString(), "start", "--no-build")
                .redirectErrorStream(true);
        builder.environment().putAll(stagingBase());
        builder.environment().putAll(Map.of(
                "RG_TEST_BOOTSTRAP_ROOT_PUBLICATION_ENABLED", "true",
                "RG_TEST_BOOTSTRAP_ROOT_PUBLICATION_ENDPOINT",
                "https://publisher.example/publications"));

        Process process = builder.start();
        assertThat(process.waitFor(Duration.ofSeconds(5))).isTrue();
        String output = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);

        assertThat(process.exitValue()).isEqualTo(1);
        assertThat(output).contains(
                "Staging bootstrap-root publisher requires pinned mutual TLS.");
        assertThat(output).doesNotContain("Building Resource Gateway",
                "Starting visual canvas");
    }

    @Test
    void stagingBootstrapRootPublisherRejectsMissingCertificateIdentityBeforeBuild()
            throws Exception {
        Path client = Files.createFile(temporaryDirectory.resolve("publisher-client.p12"));
        ProcessBuilder builder = new ProcessBuilder(
                "bash", SCRIPT.toString(), "start", "--no-build")
                .redirectErrorStream(true);
        builder.environment().putAll(stagingBase());
        builder.environment().putAll(Map.ofEntries(
                Map.entry("RG_TEST_BOOTSTRAP_ROOT_PUBLICATION_ENABLED", "true"),
                Map.entry("RG_TEST_BOOTSTRAP_ROOT_PUBLICATION_ENDPOINT",
                        "https://publisher.example/publications"),
                Map.entry("RG_TEST_BOOTSTRAP_ROOT_PUBLICATION_TRANSPORT_ENABLED", "true"),
                Map.entry("RG_TEST_BOOTSTRAP_ROOT_PUBLICATION_TRANSPORT_REQUIRED", "true"),
                Map.entry("RG_TEST_BOOTSTRAP_ROOT_PUBLICATION_TRANSPORT_CLIENT_KEY_STORE_PATH",
                        client.toString()),
                Map.entry("RG_TEST_BOOTSTRAP_ROOT_PUBLICATION_TRANSPORT_CLIENT_KEY_STORE_PASSWORD_REF",
                        "env:RG_PUBLISHER_CLIENT_PASSWORD"),
                Map.entry("RG_TEST_BOOTSTRAP_ROOT_PUBLICATION_TRANSPORT_SERVER_SPKI_PINS",
                        "sha256:" + "c".repeat(64)),
                Map.entry("RG_PUBLISHER_CLIENT_PASSWORD", "test-only-secret")));

        Process process = builder.start();
        assertThat(process.waitFor(Duration.ofSeconds(5))).isTrue();
        String output = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);

        assertThat(process.exitValue()).isEqualTo(1);
        assertThat(output).contains(
                "publisher transport requires exact client and server certificate workload identities.");
        assertThat(output).doesNotContain("test-only-secret", "Building Resource Gateway",
                "Starting visual canvas");
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
                "Staging inventory source requires pinned mutual TLS.");
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
        putCertificateIdentity(builder.environment(),
                "RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_TRANSPORT",
                "inventory", 'e');
        putCertificateIdentity(builder.environment(),
                "RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_TRUST_ROOT_TRANSPORT",
                "trust-root", 'f');

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

    private static void putRequiredCertificateRotation(Map<String, String> environment) {
        environment.put("RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_ENABLED", "true");
        environment.put("RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_REQUIRED", "true");
        environment.put("RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_SCOPE_ID", "rg-staging");
        environment.put("RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_TRUST_DOMAIN",
                "enterprise-pki");
        environment.put("RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_ACCEPTED_POLICIES",
                "sha256:" + "c".repeat(64));
        environment.put("RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_SIGNATURE_THRESHOLD", "1");
        environment.put("RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_AUTHORITY_KEYS_JSON", "[{}]");
        environment.put("RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_INITIAL_GENERATIONS_JSON",
                "{\"test.secret.notary.transport\":1}");
        environment.put("RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_MATERIAL_CATALOG_JSON",
                "[{}]");
        environment.put("RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_CONVERGENCE_ENABLED",
                "true");
        environment.put("RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_CONVERGENCE_REQUIRED",
                "true");
        environment.put("RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_FLEET_ID", "fleet-a");
        environment.put("RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_INSTANCE_ID", "replica-a");
        environment.put("RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_STARTUP_ID",
                "6f5fe859-b55d-4bd2-b5bf-5cc54356e03d");
        environment.put("RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_ARTIFACT_FINGERPRINT",
                "sha256:" + "d".repeat(64));
        environment.put("RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_EXPECTED_INSTANCE_IDS",
                "replica-a");
        environment.put("RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_REQUIRED_STAGED_REPLICAS",
                "1");
    }

    private static void putCertificateIdentity(
            Map<String, String> environment,
            String prefix,
            String workload,
            char issuerPin) {
        environment.put(prefix + "_EXPECTED_CLIENT_SUBJECT_DN",
                "CN=" + workload + "-client,O=Resource Gateway");
        environment.put(prefix + "_EXPECTED_CLIENT_URI_SAN",
                "spiffe://resource-gateway.example/client/" + workload);
        environment.put(prefix + "_CLIENT_ISSUER_SPKI_PINS",
                "sha256:" + String.valueOf(issuerPin).repeat(64));
        environment.put(prefix + "_EXPECTED_SERVER_URI_SAN",
                "spiffe://resource-gateway.example/server/" + workload);
        environment.put(prefix + "_SERVER_ISSUER_SPKI_PINS",
                "sha256:" + String.valueOf(issuerPin).repeat(64));
    }

    private static Map<String, String> stagingTestSecretExternalAnchor() {
        String fingerprint = "sha256:" + "c".repeat(64);
        return Map.ofEntries(
                Map.entry("RG_TEST_SECRET_AUTHORITY_COHORT_ENABLED", "true"),
                Map.entry("RG_TEST_SECRET_AUTHORITY_COHORT_SCOPE_ID", "secret-fleet"),
                Map.entry("RG_TEST_SECRET_AUTHORITY_HTTP_ENABLED", "true"),
                Map.entry("RG_TEST_SECRET_AUTHORITY_JWKS_ENABLED", "true"),
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
                Map.entry("RG_TEST_SECRET_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_MANAGED_TRUST_REQUIRED",
                        "true"),
                Map.entry("RG_TEST_SECRET_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_TRUST_URI",
                        "https://notary-trust.example/current"),
                Map.entry("RG_TEST_SECRET_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_TRUST_ROOT_SET_ID",
                        "secret-notary-roots"),
                Map.entry("RG_TEST_SECRET_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_BOOTSTRAP_TRUST_DOMAIN",
                        "secret-root.example"),
                Map.entry("RG_TEST_SECRET_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_TRUST_POLICY_FINGERPRINTS",
                        fingerprint),
                Map.entry("RG_TEST_SECRET_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_BOOTSTRAP_THRESHOLD",
                        "0"),
                Map.entry("RG_TEST_SECRET_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_BOOTSTRAP_KEYS_JSON",
                        "[]"),
                Map.entry("RG_TEST_SECRET_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_BOOTSTRAP_ROOTS_ENABLED",
                        "true"),
                Map.entry("RG_TEST_SECRET_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_BOOTSTRAP_ROOTS_REQUIRED",
                        "true"),
                Map.entry("RG_TEST_SECRET_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_BOOTSTRAP_ROOT_GENESIS_JSON",
                        "{}"),
                Map.entry("RG_TEST_SECRET_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_BOOTSTRAP_ROOT_POLICY_FINGERPRINTS",
                        fingerprint),
                Map.entry("RG_TEST_SECRET_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_BOOTSTRAP_ROOT_BUNDLE_URI",
                        "https://root-chain.example/current"));
    }
}
