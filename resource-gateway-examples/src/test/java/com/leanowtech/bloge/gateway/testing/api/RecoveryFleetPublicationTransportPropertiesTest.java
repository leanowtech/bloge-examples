package com.leanowtech.bloge.gateway.testing.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecoveryFleetPublicationTransportPropertiesTest {

    private static final String PIN = "sha256:" + "a".repeat(64);

    @TempDir
    Path temporaryDirectory;

    @Test
    void disabledPolicyCreatesOnlyTheExplicitCompatibilityAdapter() {
        RecoveryFleetPublicationTransportProperties properties =
                RecoveryFleetPublicationTransportProperties.disabled();

        RecoveryFleetPublicationTransport transport = properties.create(null);

        assertThat(properties.configured()).isFalse();
        assertThat(transport).isInstanceOf(SystemTrustRecoveryFleetPublicationTransport.class);
        assertThat(transport.descriptor()).satisfies(descriptor -> {
            assertThat(descriptor.systemTrustStore()).isTrue();
            assertThat(descriptor.serverSpkiPinned()).isFalse();
            assertThat(descriptor.mutualTls()).isFalse();
        });
    }

    @Test
    void requiredDisabledAndEveryPartialOrResidualShapeFailClosed() {
        assertThatThrownBy(() -> properties(false, true, "", "", "", "", ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(false, false, "", "", "/client.p12",
                "env:CLIENT_PASSWORD", PIN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(true, true, "/trust.p12", "", "/client.p12",
                "env:CLIENT_PASSWORD", PIN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(true, true, "", "", "",
                "env:CLIENT_PASSWORD", PIN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(true, true, "", "", "/client.p12",
                "env:CLIENT_PASSWORD", ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sourceIdentityIsolationUsesBothKeystoreAndCredentialReference() {
        RecoveryFleetPublicationTransportProperties first = properties(
                true, true, "", "", "/identity-a.p12", "env:IDENTITY_A", PIN);
        RecoveryFleetPublicationTransportProperties same = properties(
                true, true, "", "", "/identity-a.p12", "env:IDENTITY_A", PIN);
        RecoveryFleetPublicationTransportProperties differentStore = properties(
                true, true, "", "", "/identity-b.p12", "env:IDENTITY_A", PIN);
        RecoveryFleetPublicationTransportProperties differentReference = properties(
                true, true, "", "", "/identity-a.p12", "env:IDENTITY_B", PIN);

        assertThat(first.configured()).isTrue();
        assertThat(first.sharesClientIdentityWith(same)).isTrue();
        assertThat(first.sharesClientIdentityWith(differentStore)).isFalse();
        assertThat(first.sharesClientIdentityWith(differentReference)).isFalse();
        assertThat(first.sharesClientIdentityWith(
                RecoveryFleetPublicationTransportProperties.disabled())).isFalse();
    }

    @Test
    void duplicatePinsAreRejectedBeforeCredentialResolution() throws Exception {
        Path client = Files.createFile(temporaryDirectory.resolve("client.p12"));
        RecoveryFleetPublicationTransportProperties properties = properties(
                true, true, "", "", client.toString(), "env:CLIENT_PASSWORD",
                PIN + "," + PIN);

        assertThatThrownBy(() -> properties.create(reference -> {
            throw new AssertionError("credentials must not be resolved");
        })).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Recovery-fleet publication transport configuration is invalid");
    }

    private static RecoveryFleetPublicationTransportProperties properties(
            Boolean enabled,
            Boolean required,
            String trustStorePath,
            String trustStorePasswordRef,
            String clientKeyStorePath,
            String clientKeyStorePasswordRef,
            String pins) {
        return new RecoveryFleetPublicationTransportProperties(enabled, required,
                trustStorePath, trustStorePasswordRef, clientKeyStorePath,
                clientKeyStorePasswordRef, pins);
    }
}
