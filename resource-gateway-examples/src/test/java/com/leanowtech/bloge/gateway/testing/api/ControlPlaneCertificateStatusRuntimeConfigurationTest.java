package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.persistence.TestRuntimeDatabase;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.actuate.health.Status;

import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ControlPlaneCertificateStatusRuntimeConfigurationTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void productCompositionCreatesIndependentStrictSourceDurableFloorAndClosedHealth()
            throws Exception {
        var tls = RecoveryFleetPublicationTlsFixture.Material.create(
                temporaryDirectory, "certificate-status-source");
        var objectMapper = new ObjectMapper().findAndRegisterModules();
        var properties = properties(objectMapper, tls);
        var configuration = new ControlPlaneCertificateStatusRuntimeConfiguration();

        try (var database = new TestRuntimeDatabase(new TestRuntimeDatabase.Settings(
                "jdbc:h2:mem:certificate-status-config-" + UUID.randomUUID()
                        + ";DB_CLOSE_DELAY=-1", "sa", "", 4))) {
            ControlPlaneCertificateStatusTrustStore trust =
                    configuration.controlPlaneCertificateStatusTrustStore(
                            objectMapper, properties);
            ControlPlaneCertificateStatusFloor floor =
                    configuration.controlPlaneCertificateStatusFloor(
                            database, objectMapper, trust, properties);
            ControlPlaneCertificateStatusSourceHeadFloor sourceHeadFloor =
                    configuration.controlPlaneCertificateStatusSourceHeadFloor(
                            database, objectMapper, trust, properties);
            ControlPlaneCertificateStatusTelemetry telemetry =
                    configuration.controlPlaneCertificateStatusTelemetry(
                            new SimpleMeterRegistry());
            ControlPlaneCertificateStatusAdmission admission =
                    configuration.controlPlaneCertificateStatusAdmission(telemetry);
            ControlPlaneCertificateStatusSource source =
                    configuration.controlPlaneCertificateStatusSource(objectMapper,
                            reference -> RecoveryFleetPublicationTlsFixture.password(),
                            properties);
            ControlPlaneCertificateStatusMonitor monitor =
                    configuration.controlPlaneCertificateStatusMonitor(
                            floor, sourceHeadFloor, source, admission, properties, telemetry);
            ControlPlaneCertificateStatusSloMonitor sloMonitor =
                    configuration.controlPlaneCertificateStatusSloMonitor(
                            monitor, admission, telemetry, properties);
            ControlPlaneCertificateStatusScheduler scheduler =
                    configuration.controlPlaneCertificateStatusScheduler(monitor, sloMonitor);
            ControlPlaneCertificateStatusHealth health =
                    configuration.controlPlaneCertificateStatusHealth(
                            monitor, source, trust, admission);

            assertThat(trust.descriptor().available()).isTrue();
            assertThat(floor.durable()).isTrue();
            assertThat(floor.snapshot().initialized()).isFalse();
            assertThat(sourceHeadFloor.durable()).isTrue();
            assertThat(sourceHeadFloor.snapshot().initialized()).isFalse();
            assertThat(source.descriptor()).satisfies(descriptor -> {
                assertThat(descriptor.available()).isTrue();
                assertThat(descriptor.privateTrustStore()).isTrue();
                assertThat(descriptor.serverSpkiPinned()).isTrue();
                assertThat(descriptor.mutualTls()).isTrue();
                assertThat(descriptor.certificateIdentityBound()).isTrue();
                assertThat(descriptor.strictProtocol()).isTrue();
            });
            assertThat(scheduler).isNotNull();
            assertThat(sloMonitor.descriptor().state()).isEqualTo(
                    ControlPlaneCertificateStatusSloMonitor.State.INITIALIZING);
            assertThat(health.health().getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.health().getDetails())
                    .containsEntry("runtimeStatus", "SOURCE_HEAD_UNAVAILABLE")
                    .containsEntry("productionReady", false);
        }
    }

    private static ControlPlaneCertificateStatusRuntimeProperties properties(
            ObjectMapper objectMapper,
            RecoveryFleetPublicationTlsFixture.Material tls) throws Exception {
        String issuer = PinnedMutualTlsRecoveryFleetPublicationTransport.spkiPin(
                tls.certificateAuthority());
        var transport = new RecoveryFleetPublicationTransportProperties(true, true,
                tls.trustStore().toString(), "test:trust",
                tls.clientKeyStore().toString(), "test:client",
                PinnedMutualTlsRecoveryFleetPublicationTransport.spkiPin(
                        tls.serverCertificate()), true,
                tls.clientCertificate().getSubjectX500Principal().getName(),
                tls.clientUriSan(), issuer, tls.serverUriSan(), issuer);
        return new ControlPlaneCertificateStatusRuntimeProperties(true, true,
                "rg-staging", "enterprise-ca", fingerprint('f'), 1,
                authorityKeysJson(objectMapper), 0L, fingerprint('0'),
                "https://certificate-status.example.test/publications",
                2_000L, 64 * 1024, 60L, 3_600L, 30_000L, 1_000L, 8,
                ControlPlaneCertificateStatusSloProperties.defaults(), transport);
    }

    private static String authorityKeysJson(ObjectMapper objectMapper) throws Exception {
        var keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var key = new LinkedHashMap<String, Object>();
        key.put("authorityId", "authority-a");
        key.put("keyId", "key-a");
        key.put("publicKeyBase64", Base64.getEncoder().encodeToString(
                keyPair.getPublic().getEncoded()));
        key.put("notBefore", Instant.now().minusSeconds(60).toString());
        key.put("expiresAt", Instant.now().plusSeconds(3_600).toString());
        key.put("enabled", true);
        key.put("revoked", false);
        return objectMapper.writeValueAsString(List.of(key));
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
