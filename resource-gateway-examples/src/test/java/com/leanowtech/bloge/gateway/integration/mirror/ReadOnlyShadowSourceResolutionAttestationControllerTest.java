package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemHandler;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.integration.ReadOnlyShadowSourceResolutionAttestationController;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ReadOnlyShadowSourceResolutionAttestationControllerTest {
    @Test
    void authenticatesBeforeExactAttestationLookup() {
        var service =
                mock(ReadOnlyShadowSourceResolutionAttestationService.class);
        var authenticator =
                mock(IntegrationRequestAuthenticator.class);
        ReadOnlyShadowSourceResolutionAttestation signed =
                signed();
        HttpHeaders headers = new HttpHeaders();
        IntegrationRequestContext identity =
                identity("GOVERNANCE_EVIDENCE_INGESTION");
        when(authenticator.authenticate(
                headers,
                IntegrationOperation
                        .MIRROR_SHADOW_SOURCE_RESOLUTION_READ))
                .thenReturn(identity);
        when(service.resolve(
                signed.scope(),
                signed.artifactRef()))
                .thenReturn(signed);
        var controller =
                new ReadOnlyShadowSourceResolutionAttestationController(
                        service,
                        authenticator);

        var envelope = controller.find(
                signed.attestationId(),
                signed.revision(),
                signed.attestationFingerprint(),
                headers);

        assertThat(envelope.payloadKind())
                .isEqualTo(
                        ReadOnlyShadowSourceResolutionAttestation
                                .ARTIFACT_KIND);
        var order = inOrder(authenticator, service);
        order.verify(authenticator).authenticate(
                headers,
                IntegrationOperation
                        .MIRROR_SHADOW_SOURCE_RESOLUTION_READ);
        order.verify(service).resolve(
                signed.scope(),
                signed.artifactRef());
        assertThat(IntegrationOperation
                .MIRROR_SHADOW_SOURCE_RESOLUTION_READ
                .acceptedPurposes())
                .containsExactlyInAnyOrder(
                        "MIRROR_SHADOW",
                        "GOVERNANCE_EVIDENCE_INGESTION");
    }

    @Test
    void exactReadRequiresProtocolAndFingerprintAfterAuthentication()
            throws Exception {
        var service =
                mock(ReadOnlyShadowSourceResolutionAttestationService.class);
        var authenticator =
                mock(IntegrationRequestAuthenticator.class);
        ReadOnlyShadowSourceResolutionAttestation signed =
                signed();
        when(authenticator.authenticate(
                any(HttpHeaders.class),
                eq(IntegrationOperation
                        .MIRROR_SHADOW_SOURCE_RESOLUTION_READ)))
                .thenReturn(identity("MIRROR_SHADOW"));
        when(service.resolve(
                signed.scope(),
                signed.artifactRef()))
                .thenReturn(signed);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                        new ReadOnlyShadowSourceResolutionAttestationController(
                                service,
                                authenticator))
                .setControllerAdvice(
                        new IntegrationProblemHandler())
                .build();
        var request = get(
                "/api/mirror/shadow/source-resolutions/{id}/revisions/{revision}",
                signed.attestationId(),
                signed.revision())
                .queryParam(
                        "fingerprint",
                        signed.attestationFingerprint())
                .accept(
                        ReadOnlyShadowSourceResolutionAttestationProtocol
                                .MEDIA_TYPE);

        mvc.perform(request)
                .andExpect(status().isNotFound());
        verifyNoInteractions(service);

        mvc.perform(request.header(
                        ReadOnlyShadowSourceResolutionAttestationProtocol
                                .REQUEST_HEADER,
                        ReadOnlyShadowSourceResolutionAttestationProtocol
                                .VERSION))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(
                        ReadOnlyShadowSourceResolutionAttestationProtocol
                                .MEDIA_TYPE))
                .andExpect(jsonPath("$.payload.attestationId")
                        .value(signed.attestationId()))
                .andExpect(jsonPath(
                        "$.payload.attestationFingerprint")
                        .value(
                                signed.attestationFingerprint()));
    }

    @Test
    void malformedReferenceFailsAfterAuthenticationBeforeLookup()
            throws Exception {
        var service =
                mock(ReadOnlyShadowSourceResolutionAttestationService.class);
        var authenticator =
                mock(IntegrationRequestAuthenticator.class);
        when(authenticator.authenticate(
                any(HttpHeaders.class),
                eq(IntegrationOperation
                        .MIRROR_SHADOW_SOURCE_RESOLUTION_READ)))
                .thenReturn(identity("MIRROR_SHADOW"));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                        new ReadOnlyShadowSourceResolutionAttestationController(
                                service,
                                authenticator))
                .setControllerAdvice(
                        new IntegrationProblemHandler())
                .build();

        mvc.perform(get(
                        "/api/mirror/shadow/source-resolutions/id/revisions/1")
                        .queryParam("fingerprint", "not-a-fingerprint")
                        .header(
                                ReadOnlyShadowSourceResolutionAttestationProtocol
                                        .REQUEST_HEADER,
                                ReadOnlyShadowSourceResolutionAttestationProtocol
                                        .VERSION)
                        .accept(
                                ReadOnlyShadowSourceResolutionAttestationProtocol
                                        .MEDIA_TYPE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(
                        "RG.MIRROR.SHADOW_SOURCE_RESOLUTION_REFERENCE_INVALID"));
        verifyNoInteractions(service);
    }

    private static ReadOnlyShadowSourceResolutionAttestation signed() {
        ObjectMapper mapper =
                new ObjectMapper().findAndRegisterModules();
        var policy =
                new PayloadFreeEqualityReadOnlyShadowPolicy(
                        mapper);
        Clock clock = Clock.fixed(
                ReadOnlyShadowSourceResolutionTestFixtures
                        .NOW.plusSeconds(4),
                ZoneOffset.UTC);
        return new ReadOnlyShadowSourceResolutionAttestationIntegrity(
                mapper,
                InMemoryVisualEvidenceSigner.usingClock(clock),
                clock)
                .sign(
                        ReadOnlyShadowSourceResolutionTestFixtures
                                .unsigned(policy.reference()));
    }

    private static IntegrationRequestContext identity(
            String purpose) {
        CapabilitySnapshot.Scope scope =
                ReadOnlyShadowJobTestFixtures.scope("support");
        return new IntegrationRequestContext(
                scope.tenantId(),
                scope.organizationId(),
                scope.projectId(),
                scope.environmentId(),
                scope.region(),
                "SERVICE",
                "shadow-source-reader",
                "",
                purpose,
                "corr-shadow-source-resolution",
                Set.of(),
                "RESTRICTED",
                "");
    }
}
