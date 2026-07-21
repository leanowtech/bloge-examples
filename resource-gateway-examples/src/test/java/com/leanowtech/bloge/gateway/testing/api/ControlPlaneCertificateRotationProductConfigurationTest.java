package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ControlPlaneCertificateRotationProductConfigurationTest {

    private static final String TARGET =
            ControlPlaneCertificateRotationTargets.BOOTSTRAP_ROOT_PUBLISHER;
    private static final String POLICY = "sha256:" + "a".repeat(64);

    @TempDir
    Path temporaryDirectory;

    @Test
    void settingsFingerprintCoversBytesAndPolicyButNotFilesystemLocation() throws Exception {
        var material = RecoveryFleetPublicationTlsFixture.Material.create(
                temporaryDirectory, "fingerprint");
        var mapper = new ObjectMapper().findAndRegisterModules();
        var fingerprinter = new ControlPlaneCertificateSettingsFingerprint(mapper);
        var original = transport(material).pinnedSettings();
        Path copiedTrust = temporaryDirectory.resolve("copied-trust.p12");
        Path copiedClient = temporaryDirectory.resolve("copied-client.p12");
        Files.copy(material.trustStore(), copiedTrust);
        Files.copy(material.clientKeyStore(), copiedClient);
        var moved = new PinnedMutualTlsRecoveryFleetPublicationTransport.Settings(
                copiedTrust, "different:trust-reference", copiedClient,
                "different:client-reference", original.serverSpkiPins(),
                original.certificateIdentityPolicy());

        assertThat(fingerprinter.fingerprint(moved))
                .isEqualTo(fingerprinter.fingerprint(original));

        byte[] changed = Files.readAllBytes(copiedClient);
        changed[changed.length - 1] ^= 1;
        Files.write(copiedClient, changed);
        assertThat(fingerprinter.fingerprint(moved))
                .isNotEqualTo(fingerprinter.fingerprint(original));
    }

    @Test
    void strictCatalogResolvesOnlyExactOpaqueTargetMaterialPairs() throws Exception {
        var material = RecoveryFleetPublicationTlsFixture.Material.create(
                temporaryDirectory, "catalog");
        var mapper = new ObjectMapper().findAndRegisterModules();
        var fingerprinter = new ControlPlaneCertificateSettingsFingerprint(mapper);
        String json = mapper.writeValueAsString(List.of(catalogEntry(material, "candidate-b")));
        var source = ConfiguredControlPlaneCertificateRotationMaterialSource.fromJson(
                mapper, fingerprinter, json);

        var resolved = source.resolve(TARGET, 2, "candidate-b");

        assertThat(source.materialCount()).isEqualTo(1);
        assertThat(resolved.settingsFingerprint())
                .isEqualTo(fingerprinter.fingerprint(transport(material).pinnedSettings()));
        assertThatThrownBy(() -> source.resolve(TARGET, 2, "missing"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Control-plane certificate rotation material is unavailable");
        assertThatThrownBy(() ->
                ConfiguredControlPlaneCertificateRotationMaterialSource.fromJson(
                        mapper, fingerprinter, json + "{}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("catalog is invalid");

        Map<String, String> unsafe = catalogEntry(material, "candidate-b");
        unsafe.put("materialId", "vault://secret/path");
        assertThatThrownBy(() ->
                ConfiguredControlPlaneCertificateRotationMaterialSource.fromJson(
                        mapper, fingerprinter,
                        mapper.writeValueAsString(List.of(unsafe))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("catalog is invalid");
    }

    @Test
    void runtimePropertiesRejectDisabledResidualUnknownDuplicateAndInvalidGenerations()
            throws Exception {
        var mapper = new ObjectMapper().findAndRegisterModules();
        var properties = properties("{\"" + TARGET + "\":7}");
        var explicit = properties("{\"" + TARGET
                + "\":{\"generation\":7,\"materialId\":\"baseline-a\"}}");

        assertThat(properties.initialGenerations(mapper)).containsEntry(TARGET, 7L);
        assertThat(explicit.initialTargets(mapper).get(TARGET)).satisfies(initial -> {
            assertThat(initial.generation()).isEqualTo(7);
            assertThat(initial.materialId()).isEqualTo("baseline-a");
        });
        assertThat(ControlPlaneCertificateRotationRuntimeProperties.disabled().enabled())
                .isFalse();
        assertThatThrownBy(() -> new ControlPlaneCertificateRotationRuntimeProperties(
                false, false, "residual", "", "", 0, "[]", 300L, 86_400L,
                "{}", "[]")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties("{\"unknown.target\":1}")
                .initialGenerations(mapper)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("generation inventory is invalid");
        assertThatThrownBy(() -> properties("{\"" + TARGET + "\":0}")
                .initialGenerations(mapper)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties("{\"" + TARGET + "\":1,\""
                + TARGET + "\":2}").initialGenerations(mapper))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties("{\"" + TARGET + "\":1}{}")
                .initialGenerations(mapper)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties("{\"" + TARGET
                + "\":{\"generation\":1,\"materialId\":\"vault://bad\"}}")
                .initialTargets(mapper)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties("{\"" + TARGET
                + "\":{\"generation\":1,\"materialId\":\"baseline\",\"extra\":1}}")
                .initialTargets(mapper)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void productTargetVocabularyContainsExactlyTwelveUniqueTransportPrefixes() {
        assertThat(ControlPlaneCertificateRotationTargets.values())
                .hasSize(12).doesNotHaveDuplicates()
                .allMatch(target -> target.endsWith(".transport"));
        assertThat(ControlPlaneCertificateRotationTargets.values())
                .contains(TARGET,
                        ControlPlaneCertificateRotationTargets.RECOVERY_FLEET_INVENTORY,
                        ControlPlaneCertificateRotationTargets.RECOVERY_FLEET_BOOTSTRAP_ROOTS);
    }

    @Test
    void convergencePropertiesRejectPartialLocalAndUnfencedFleetPolicies() {
        String startup = UUID.randomUUID().toString();
        var local = convergence(true, "replica-a", startup, "replica-a", 1,
                "ALL_REPLICAS", "LOCAL_CONFIGURED", 0, "", "", "");

        assertThat(local.policy("rg-staging").expectedInstanceIds())
                .containsExactly("replica-a");
        assertThatThrownBy(() -> convergence(false, "replica-a", startup,
                "replica-a", 0, "ALL_REPLICAS", "LOCAL_CONFIGURED", 0,
                "", "", "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> convergence(true, "replica-a", startup,
                "replica-a,replica-b", 2, "ALL_REPLICAS", "LOCAL_CONFIGURED", 0,
                "", "", "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> convergence(true, "replica-a", startup,
                "replica-a,replica-b,replica-c", 2, "FENCED_QUORUM",
                "SIGNED_INVENTORY", 7, fingerprint('b'), fingerprint('c'),
                Instant.now().plusSeconds(300).toString()))
                .isInstanceOf(IllegalArgumentException.class);

        var external = convergence(true, "replica-a", startup,
                "replica-a,replica-b", 2, "ALL_REPLICAS", "SIGNED_INVENTORY", 7,
                fingerprint('b'), fingerprint('c'),
                Instant.now().plusSeconds(300).toString());
        assertThat(external.policy("rg-staging").inventoryAttestation()
                .externallyAttested()).isTrue();
        assertThat(ControlPlaneCertificateRotationConvergenceProperties.disabled().enabled())
                .isFalse();
    }

    private static ControlPlaneCertificateRotationRuntimeProperties properties(
            String generations) {
        return new ControlPlaneCertificateRotationRuntimeProperties(true, true,
                "rg-staging", "enterprise-pki", POLICY, 1, "[{}]",
                300L, 86_400L, generations, "[{}]");
    }

    private static Map<String, String> catalogEntry(
            RecoveryFleetPublicationTlsFixture.Material material,
            String materialId) {
        RecoveryFleetPublicationTransportProperties transport = transport(material);
        LinkedHashMap<String, String> entry = new LinkedHashMap<>();
        entry.put("targetId", TARGET);
        entry.put("materialId", materialId);
        entry.put("trustStorePath", material.trustStore().toString());
        entry.put("trustStorePasswordRef", "test:trust");
        entry.put("clientKeyStorePath", material.clientKeyStore().toString());
        entry.put("clientKeyStorePasswordRef", "test:client");
        entry.put("serverSpkiPins", transport.serverSpkiPins());
        entry.put("expectedClientSubjectDn", transport.expectedClientSubjectDn());
        entry.put("expectedClientUriSan", transport.expectedClientUriSan());
        entry.put("clientIssuerSpkiPins", transport.clientIssuerSpkiPins());
        entry.put("expectedServerUriSan", transport.expectedServerUriSan());
        entry.put("serverIssuerSpkiPins", transport.serverIssuerSpkiPins());
        return entry;
    }

    private static RecoveryFleetPublicationTransportProperties transport(
            RecoveryFleetPublicationTlsFixture.Material material) {
        String issuer = PinnedMutualTlsRecoveryFleetPublicationTransport.spkiPin(
                material.certificateAuthority());
        return new RecoveryFleetPublicationTransportProperties(true, true,
                material.trustStore().toString(), "test:trust",
                material.clientKeyStore().toString(), "test:client",
                PinnedMutualTlsRecoveryFleetPublicationTransport.spkiPin(
                        material.serverCertificate()), true,
                material.clientCertificate().getSubjectX500Principal().getName(),
                material.clientUriSan(), issuer, material.serverUriSan(), issuer);
    }

    private static ControlPlaneCertificateRotationConvergenceProperties convergence(
            boolean enabled,
            String instanceId,
            String startupId,
            String instances,
            int required,
            String mode,
            String inventoryType,
            long revision,
            String inventoryFingerprint,
            String inventoryPolicy,
            String inventoryExpiry) {
        return new ControlPlaneCertificateRotationConvergenceProperties(
                enabled, enabled, "fleet-2026-07", instanceId, startupId,
                fingerprint('d'), instances, "convergence-v1", mode, required,
                5L, 15L, 3_600L, inventoryType, revision, inventoryFingerprint,
                inventoryPolicy, inventoryExpiry);
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
