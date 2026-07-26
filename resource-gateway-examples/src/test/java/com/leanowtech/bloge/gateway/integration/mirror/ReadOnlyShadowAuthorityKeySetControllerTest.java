package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemHandler;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.integration.ReadOnlyShadowAuthorityKeySetController;
import com.leanowtech.bloge.gateway.integration.ReadOnlyShadowAuthorityKeySetDecoder;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
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

class ReadOnlyShadowAuthorityKeySetControllerTest {
    @Test
    void authenticatesBeforeDecodeAndUsesDedicatedOperations() {
        var service = mock(ReadOnlyShadowAuthorityKeySetService.class);
        var authenticator = mock(IntegrationRequestAuthenticator.class);
        var decoder = mock(ReadOnlyShadowAuthorityKeySetDecoder.class);
        var publication = mock(ReadOnlyShadowAuthorityKeySetPublication.class);
        var controller = new ReadOnlyShadowAuthorityKeySetController(
                service, authenticator, decoder);
        HttpHeaders headers = new HttpHeaders();
        byte[] body = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        IntegrationRequestContext publisher = identity("MIRROR_TRUST_ADMIN");
        when(authenticator.authenticate(
                headers, IntegrationOperation.MIRROR_SHADOW_AUTHORITY_KEY_SET_PUBLISH))
                .thenReturn(publisher);
        when(decoder.decode(body, publisher)).thenReturn(publication);
        when(service.publish(publication)).thenReturn(publication);

        var envelope = controller.publish(body, headers);

        assertThat(envelope.payloadKind())
                .isEqualTo(ReadOnlyShadowAuthorityKeySetPublication.ARTIFACT_KIND);
        var order = inOrder(authenticator, decoder, service);
        order.verify(authenticator).authenticate(
                headers, IntegrationOperation.MIRROR_SHADOW_AUTHORITY_KEY_SET_PUBLISH);
        order.verify(decoder).decode(body, publisher);
        order.verify(service).publish(publication);
        assertThat(IntegrationOperation.MIRROR_SHADOW_AUTHORITY_KEY_SET_PUBLISH
                .acceptedPurposes()).containsExactly("MIRROR_TRUST_ADMIN");
        assertThat(IntegrationOperation.MIRROR_SHADOW_AUTHORITY_KEY_SET_READ
                .acceptedPurposes())
                .containsExactlyInAnyOrder("MIRROR_TRUST_DISTRIBUTION", "MIRROR_SHADOW");
    }

    @Test
    void duplicateJsonKeysFailAfterAuthenticationAndBeforeService() throws Exception {
        var service = mock(ReadOnlyShadowAuthorityKeySetService.class);
        var authenticator = mock(IntegrationRequestAuthenticator.class);
        when(authenticator.authenticate(any(HttpHeaders.class),
                eq(IntegrationOperation.MIRROR_SHADOW_AUTHORITY_KEY_SET_PUBLISH)))
                .thenReturn(identity("MIRROR_TRUST_ADMIN"));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                        new ReadOnlyShadowAuthorityKeySetController(
                                service, authenticator,
                                new ReadOnlyShadowAuthorityKeySetDecoder(
                                        new ObjectMapper().findAndRegisterModules())))
                .setControllerAdvice(new IntegrationProblemHandler())
                .build();

        mvc.perform(post("/api/mirror/trust/read-only-shadow/authority-key-sets")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"schemaVersion":"first","schemaVersion":"second"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code")
                        .value("RG.MIRROR.SHADOW_AUTHORITY_KEY_SET_MALFORMED"));
        verifyNoInteractions(service);
    }

    @Test
    void cursorPagesRequireExactProtocolNegotiationAndReturnVendorJson() throws Exception {
        var service = mock(ReadOnlyShadowAuthorityKeySetService.class);
        var authenticator = mock(IntegrationRequestAuthenticator.class);
        var decoder = mock(ReadOnlyShadowAuthorityKeySetDecoder.class);
        var page = new ReadOnlyShadowAuthorityKeySetPage(
                "", Instant.parse("2026-07-26T10:30:00Z"), scope(),
                ReadOnlyShadowAuthorityIntegrity.PublicationKind.SAMPLING_GRANT,
                "data-governance:shadow", "shadow-sampling-keys:staging",
                0, "", 0, 0, "", null, false, List.of());
        when(authenticator.authenticate(any(HttpHeaders.class),
                eq(IntegrationOperation.MIRROR_SHADOW_AUTHORITY_KEY_SET_READ)))
                .thenReturn(identity("MIRROR_TRUST_DISTRIBUTION"));
        when(service.page(eq(scope()),
                eq(ReadOnlyShadowAuthorityIntegrity.PublicationKind.SAMPLING_GRANT),
                eq("data-governance:shadow"), eq(0L), eq(""), eq(64)))
                .thenReturn(page);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                        new ReadOnlyShadowAuthorityKeySetController(
                                service, authenticator, decoder))
                .setControllerAdvice(new IntegrationProblemHandler())
                .build();
        var request = get(
                "/api/mirror/trust/read-only-shadow/authority-key-sets/pages")
                .queryParam("publicationKind", "SAMPLING_GRANT")
                .queryParam("issuer", "data-governance:shadow")
                .accept(ReadOnlyShadowAuthorityTrustDistributionProtocol.MEDIA_TYPE);

        mvc.perform(request).andExpect(status().isNotFound());
        mvc.perform(request.header(
                        ReadOnlyShadowAuthorityTrustDistributionProtocol.REQUEST_HEADER,
                        ReadOnlyShadowAuthorityTrustDistributionProtocol.VERSION))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(
                        ReadOnlyShadowAuthorityTrustDistributionProtocol.MEDIA_TYPE))
                .andExpect(jsonPath("$.payload.schemaVersion")
                        .value(ReadOnlyShadowAuthorityKeySetPage.SCHEMA_VERSION))
                .andExpect(jsonPath("$.payload.highWaterGeneration").value(0));
    }

    private static IntegrationRequestContext identity(String purpose) {
        return new IntegrationRequestContext(
                "tenant-a", "org-a", "project-a", "staging", "ap-southeast-1",
                "SERVICE", "shadow-trust-agent", "", purpose,
                "corr-shadow-trust", Set.of(), "RESTRICTED", "");
    }

    private static CapabilitySnapshot.Scope scope() {
        return new CapabilitySnapshot.Scope(
                "tenant-a", "org-a", "project-a", "staging", "ap-southeast-1");
    }
}
