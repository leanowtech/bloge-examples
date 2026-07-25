package com.leanowtech.bloge.gateway.integration;

import com.leanowtech.bloge.gateway.integration.mirror.DomainFidelityInventory;
import com.leanowtech.bloge.gateway.integration.mirror.DomainFidelityInventoryRegistrationRequest;
import com.leanowtech.bloge.gateway.integration.mirror.DomainFidelityProfile;
import com.leanowtech.bloge.gateway.integration.mirror.DomainFidelityService;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.http.HttpHeaders;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DomainFidelityControllerTest {

    @Test
    void authenticatesBeforeDecodeAndReturnsVersionedArtifacts() {
        DomainFidelityService service =
                mock(DomainFidelityService.class);
        IntegrationRequestAuthenticator authenticator =
                mock(IntegrationRequestAuthenticator.class);
        DomainFidelityRequestDecoder decoder =
                mock(DomainFidelityRequestDecoder.class);
        IntegrationRequestContext identity =
                mock(IntegrationRequestContext.class);
        DomainFidelityInventoryRegistrationRequest command =
                mock(
                        DomainFidelityInventoryRegistrationRequest
                                .class);
        DomainFidelityInventory inventory =
                mock(DomainFidelityInventory.class);
        DomainFidelityProfile profile =
                mock(DomainFidelityProfile.class);
        HttpHeaders headers = new HttpHeaders();
        byte[] body =
                "{}".getBytes(StandardCharsets.UTF_8);
        String fingerprint =
                "sha256:" + "a".repeat(64);
        when(authenticator.authenticate(
                headers,
                IntegrationOperation
                        .MIRROR_FIDELITY_INVENTORY_WRITE))
                .thenReturn(identity);
        when(authenticator.authenticate(
                headers,
                IntegrationOperation
                        .MIRROR_FIDELITY_INVENTORY_READ))
                .thenReturn(identity);
        when(authenticator.authenticate(
                headers,
                IntegrationOperation
                        .MIRROR_FIDELITY_PROFILE_READ))
                .thenReturn(identity);
        when(decoder.decodeInventoryRegistration(
                body, identity)).thenReturn(command);
        when(service.registerInventory(command, identity))
                .thenReturn(inventory);
        when(service.findInventory(
                "refund-support", 1, identity))
                .thenReturn(inventory);
        when(service.findLatestInventory(
                "refund-support", identity))
                .thenReturn(inventory);
        when(service.findProfile(
                fingerprint, identity))
                .thenReturn(profile);
        when(service.findLatestProfile(
                "refund-domain", identity))
                .thenReturn(profile);
        when(inventory.schemaVersion())
                .thenReturn(
                        DomainFidelityInventory
                                .SCHEMA_VERSION);
        when(profile.schemaVersion())
                .thenReturn(
                        DomainFidelityProfile
                                .SCHEMA_VERSION);
        DomainFidelityController controller =
                new DomainFidelityController(
                        service, authenticator, decoder);

        assertThat(controller.registerInventory(
                body, headers).payload())
                .isSameAs(inventory);
        assertThat(controller.findInventory(
                "refund-support", 1, headers).payload())
                .isSameAs(inventory);
        assertThat(controller.findLatestInventory(
                "refund-support", headers).payload())
                .isSameAs(inventory);
        assertThat(controller.findProfile(
                fingerprint, headers).payload())
                .isSameAs(profile);
        assertThat(controller.findLatestProfile(
                "refund-domain", headers).payload())
                .isSameAs(profile);

        InOrder order =
                inOrder(authenticator, decoder, service);
        order.verify(authenticator).authenticate(
                headers,
                IntegrationOperation
                        .MIRROR_FIDELITY_INVENTORY_WRITE);
        order.verify(decoder)
                .decodeInventoryRegistration(
                        body, identity);
        order.verify(service)
                .registerInventory(
                        command, identity);
        verify(authenticator, times(2)).authenticate(
                headers,
                IntegrationOperation
                        .MIRROR_FIDELITY_PROFILE_READ);
    }

    @Test
    void authenticationFailureNeverParsesTheBody() {
        DomainFidelityService service =
                mock(DomainFidelityService.class);
        IntegrationRequestAuthenticator authenticator =
                mock(IntegrationRequestAuthenticator.class);
        DomainFidelityRequestDecoder decoder =
                mock(DomainFidelityRequestDecoder.class);
        HttpHeaders headers = new HttpHeaders();
        byte[] body =
                "not-json".getBytes(
                        StandardCharsets.UTF_8);
        RuntimeException rejected =
                new IllegalStateException("unauthorized");
        when(authenticator.authenticate(
                headers,
                IntegrationOperation
                        .MIRROR_FIDELITY_INVENTORY_WRITE))
                .thenThrow(rejected);
        DomainFidelityController controller =
                new DomainFidelityController(
                        service, authenticator, decoder);

        assertThatThrownBy(() ->
                controller.registerInventory(
                        body, headers))
                .isSameAs(rejected);
        verify(decoder, never())
                .decodeInventoryRegistration(
                        body, null);
        verify(service, never())
                .registerInventory(null, null);
    }

    @Test
    void operationsUseDedicatedGovernanceAndEvidencePurposes() {
        assertThat(
                IntegrationOperation
                        .MIRROR_FIDELITY_INVENTORY_WRITE
                        .acceptedPurposes())
                .containsExactly(
                        "MIRROR_FIDELITY_GOVERNANCE");
        assertThat(
                IntegrationOperation
                        .MIRROR_FIDELITY_PROFILE_READ
                        .acceptedPurposes())
                .containsExactlyInAnyOrder(
                        "MIRROR_FIDELITY_GOVERNANCE",
                        "GOVERNANCE_EVIDENCE_INGESTION");
        assertThat(
                IntegrationOperation
                        .MIRROR_FIDELITY_PROFILE_READ
                        .accepts("MIRROR_REHEARSAL"))
                .isFalse();
    }
}
