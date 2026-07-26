package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemHandler;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.integration.ReadOnlyShadowSourceBindingController;
import com.leanowtech.bloge.gateway.integration.ReadOnlyShadowSourceBindingDecoder;
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
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ReadOnlyShadowSourceBindingControllerTest {
    @Test
    void authenticatesBeforeDecodeAndCandidateClosingPublication() {
        var service = mock(ReadOnlyShadowSourceBindingService.class);
        var authenticator = mock(IntegrationRequestAuthenticator.class);
        var decoder = mock(ReadOnlyShadowSourceBindingDecoder.class);
        var request =
                mock(ReadOnlyShadowSourceBindingRegistrationRequest.class);
        ReadOnlyShadowSourceBinding unsigned =
                ReadOnlyShadowJobTestFixtures.sourceBinding(
                        "source-pair", "candidate-run");
        ReadOnlyShadowSourceBinding signed = sign(unsigned);
        HttpHeaders headers = new HttpHeaders();
        byte[] body = "{}".getBytes(
                java.nio.charset.StandardCharsets.UTF_8);
        IntegrationRequestContext publisher =
                identity("MIRROR_SHADOW_SOURCE_ADMIN");
        when(authenticator.authenticate(
                headers,
                IntegrationOperation
                        .MIRROR_SHADOW_SOURCE_BINDING_PUBLISH))
                .thenReturn(publisher);
        when(decoder.decode(body, publisher)).thenReturn(request);
        when(request.toUnsignedBinding()).thenReturn(unsigned);
        when(service.publish(unsigned)).thenReturn(signed);
        var controller =
                new ReadOnlyShadowSourceBindingController(
                        service, authenticator, decoder);

        var envelope = controller.publish(body, headers);

        assertThat(envelope.payloadKind())
                .isEqualTo(
                        ReadOnlyShadowSourceBinding.ARTIFACT_KIND);
        var order = inOrder(authenticator, decoder, request, service);
        order.verify(authenticator).authenticate(
                headers,
                IntegrationOperation
                        .MIRROR_SHADOW_SOURCE_BINDING_PUBLISH);
        order.verify(decoder).decode(body, publisher);
        order.verify(request).toUnsignedBinding();
        order.verify(service).publish(unsigned);
        assertThat(IntegrationOperation
                .MIRROR_SHADOW_SOURCE_BINDING_PUBLISH
                .acceptedPurposes())
                .containsExactly("MIRROR_SHADOW_SOURCE_ADMIN");
    }

    @Test
    void malformedRegistrationFailsAfterAuthenticationBeforeService()
            throws Exception {
        var service = mock(ReadOnlyShadowSourceBindingService.class);
        var authenticator = mock(IntegrationRequestAuthenticator.class);
        when(authenticator.authenticate(
                any(HttpHeaders.class),
                eq(IntegrationOperation
                        .MIRROR_SHADOW_SOURCE_BINDING_PUBLISH)))
                .thenReturn(identity("MIRROR_SHADOW_SOURCE_ADMIN"));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                        new ReadOnlyShadowSourceBindingController(
                                service,
                                authenticator,
                                new ReadOnlyShadowSourceBindingDecoder(
                                        new ObjectMapper()
                                                .findAndRegisterModules())))
                .setControllerAdvice(new IntegrationProblemHandler())
                .build();

        mvc.perform(post("/api/mirror/shadow/source-bindings")
                        .header(
                                ReadOnlyShadowSourceBindingProtocol
                                        .REQUEST_HEADER,
                                ReadOnlyShadowSourceBindingProtocol
                                        .VERSION)
                        .contentType(APPLICATION_JSON)
                        .accept(
                                ReadOnlyShadowSourceBindingProtocol
                                        .MEDIA_TYPE)
                        .content("""
                                {"schemaVersion":"first","schemaVersion":"second"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(
                        "RG.MIRROR.SHADOW_SOURCE_BINDING_MALFORMED"));
        verifyNoInteractions(service);
    }

    @Test
    void exactReadsRequireProtocolNegotiationAndFingerprint()
            throws Exception {
        var service = mock(ReadOnlyShadowSourceBindingService.class);
        var authenticator = mock(IntegrationRequestAuthenticator.class);
        var decoder = mock(ReadOnlyShadowSourceBindingDecoder.class);
        ReadOnlyShadowSourceBinding signed = sign(
                ReadOnlyShadowJobTestFixtures.sourceBinding(
                        "source-pair", "candidate-run"));
        when(authenticator.authenticate(
                any(HttpHeaders.class),
                eq(IntegrationOperation
                        .MIRROR_SHADOW_SOURCE_BINDING_READ)))
                .thenReturn(identity("MIRROR_SHADOW"));
        when(service.resolve(
                signed.scope(),
                signed.artifactRef()))
                .thenReturn(signed);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                        new ReadOnlyShadowSourceBindingController(
                                service, authenticator, decoder))
                .setControllerAdvice(new IntegrationProblemHandler())
                .build();
        var request = get(
                "/api/mirror/shadow/source-bindings/{id}/revisions/{revision}",
                signed.bindingId(),
                signed.revision())
                .queryParam(
                        "fingerprint",
                        signed.bindingFingerprint())
                .accept(
                        ReadOnlyShadowSourceBindingProtocol.MEDIA_TYPE);

        mvc.perform(request)
                .andExpect(status().isNotFound());
        mvc.perform(request.header(
                        ReadOnlyShadowSourceBindingProtocol.REQUEST_HEADER,
                        ReadOnlyShadowSourceBindingProtocol.VERSION))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(
                        ReadOnlyShadowSourceBindingProtocol.MEDIA_TYPE))
                .andExpect(jsonPath("$.payload.bindingId")
                        .value(signed.bindingId()))
                .andExpect(jsonPath("$.payload.bindingFingerprint")
                        .value(signed.bindingFingerprint()));
    }

    private static ReadOnlyShadowSourceBinding sign(
            ReadOnlyShadowSourceBinding value) {
        return new ReadOnlyShadowSourceBindingIntegrity(
                new ObjectMapper().findAndRegisterModules(),
                InMemoryVisualEvidenceSigner.usingClock(
                        Clock.fixed(
                                ReadOnlyShadowJobTestFixtures.NOW,
                                ZoneOffset.UTC)),
                Clock.fixed(
                        ReadOnlyShadowJobTestFixtures.NOW,
                        ZoneOffset.UTC))
                .sign(value);
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
                "shadow-source-agent",
                "",
                purpose,
                "corr-shadow-source",
                Set.of(),
                "RESTRICTED",
                "");
    }
}
