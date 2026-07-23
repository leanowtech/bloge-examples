package com.leanowtech.bloge.gateway.integration.mirror;

import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemHandler;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.integration.MirrorDeploymentIsolationAuthorityPublicationController;
import com.leanowtech.bloge.gateway.integration.MirrorDeploymentIsolationAuthorityPublicationDecoder;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MirrorDeploymentIsolationAuthorityPublicationControllerTest {
    private final MirrorDeploymentIsolationAuthorityPublicationTestFixtures fixtures =
            new MirrorDeploymentIsolationAuthorityPublicationTestFixtures();

    @Test
    void authenticatesEveryRouteBeforeDelegatingAndReturnsVersionedEnvelopes() throws Exception {
        var service = mock(MirrorDeploymentIsolationAuthorityPublicationService.class);
        var authenticator = mock(IntegrationRequestAuthenticator.class);
        var decoder = mock(MirrorDeploymentIsolationAuthorityPublicationDecoder.class);
        var controller = new MirrorDeploymentIsolationAuthorityPublicationController(
                service, authenticator, decoder);
        var publication = fixtures.publication(1, "");
        byte[] body = fixtures.mapper.writeValueAsBytes(publication);
        HttpHeaders headers = new HttpHeaders();
        IntegrationRequestContext publisher = identity("MIRROR_TRUST_ADMIN");
        IntegrationRequestContext reader = identity("MIRROR_TRUST_DISTRIBUTION");
        when(authenticator.authenticate(headers,
                IntegrationOperation.MIRROR_ISOLATION_AUTHORITY_PUBLISH)).thenReturn(publisher);
        when(authenticator.authenticate(headers,
                IntegrationOperation.MIRROR_ISOLATION_AUTHORITY_READ)).thenReturn(reader);
        when(decoder.decode(body, publisher)).thenReturn(publication);
        when(service.publish(publication, publisher)).thenReturn(publication);
        when(service.latest(MirrorDeploymentIsolationAuthorityPublicationTestFixtures
                        .DEPLOYMENT_SCOPE_ID,
                MirrorDeploymentIsolationAuthorityPublicationTestFixtures.KEY_SET_ID, reader))
                .thenReturn(publication);
        when(service.current(MirrorDeploymentIsolationAuthorityPublicationTestFixtures
                        .DEPLOYMENT_SCOPE_ID,
                MirrorDeploymentIsolationAuthorityPublicationTestFixtures.KEY_SET_ID, 1,
                publication.publicationFingerprint(), reader)).thenReturn(publication);

        var published = controller.publish(body, headers);
        var latest = controller.latest(
                MirrorDeploymentIsolationAuthorityPublicationTestFixtures.KEY_SET_ID,
                MirrorDeploymentIsolationAuthorityPublicationTestFixtures.DEPLOYMENT_SCOPE_ID,
                headers);
        var current = controller.current(
                MirrorDeploymentIsolationAuthorityPublicationTestFixtures.KEY_SET_ID, 1,
                MirrorDeploymentIsolationAuthorityPublicationTestFixtures.DEPLOYMENT_SCOPE_ID,
                publication.publicationFingerprint(), headers);

        assertThat(published.payloadKind()).isEqualTo(
                MirrorDeploymentIsolationAuthorityKeySetPublication.ARTIFACT_KIND);
        assertThat(published.payloadSchemaVersion()).isEqualTo(
                MirrorDeploymentIsolationAuthorityKeySetPublication.SCHEMA_VERSION);
        assertThat(latest.payload()).isEqualTo(publication);
        assertThat(current.payload()).isEqualTo(publication);
        verify(authenticator).authenticate(headers,
                IntegrationOperation.MIRROR_ISOLATION_AUTHORITY_PUBLISH);
        verify(authenticator, org.mockito.Mockito.times(2)).authenticate(headers,
                IntegrationOperation.MIRROR_ISOLATION_AUTHORITY_READ);
    }

    @Test
    void strictTransportRejectsDuplicateKeysBeforeCallingTheService() throws Exception {
        var service = mock(MirrorDeploymentIsolationAuthorityPublicationService.class);
        var authenticator = mock(IntegrationRequestAuthenticator.class);
        IntegrationRequestContext identity = identity("MIRROR_TRUST_ADMIN");
        when(authenticator.authenticate(any(HttpHeaders.class),
                eq(IntegrationOperation.MIRROR_ISOLATION_AUTHORITY_PUBLISH)))
                .thenReturn(identity);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                        new MirrorDeploymentIsolationAuthorityPublicationController(
                                service, authenticator,
                                new MirrorDeploymentIsolationAuthorityPublicationDecoder(
                                        fixtures.mapper)))
                .setControllerAdvice(new IntegrationProblemHandler())
                .build();
        String json = fixtures.mapper.writeValueAsString(fixtures.publication(1, ""));
        String duplicate = json.replaceFirst("\\{", "{\"schemaVersion\":\"duplicate\",");

        mvc.perform(post("/api/mirror/trust/deployment-isolation/authority-key-sets")
                        .contentType(APPLICATION_JSON)
                        .content(duplicate.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code")
                        .value("RG.MIRROR.AUTHORITY_PUBLICATION_MALFORMED"));
        verifyNoInteractions(service);
    }

    @Test
    void separatesAdministrativePublicationFromDistributionAndRehearsalReads() {
        assertThat(IntegrationOperation.MIRROR_ISOLATION_AUTHORITY_PUBLISH
                .acceptedPurposes()).containsExactly("MIRROR_TRUST_ADMIN");
        assertThat(IntegrationOperation.MIRROR_ISOLATION_AUTHORITY_READ
                .acceptedPurposes())
                .containsExactlyInAnyOrder("MIRROR_TRUST_DISTRIBUTION", "MIRROR_REHEARSAL");
        assertThat(IntegrationOperation.MIRROR_ISOLATION_AUTHORITY_PUBLISH
                .accepts("MIRROR_REHEARSAL")).isFalse();
        assertThat(IntegrationOperation.MIRROR_ISOLATION_AUTHORITY_READ
                .accepts("MIRROR_TRUST_ADMIN")).isFalse();
    }

    @Test
    void currentReadsRequireExactAgentProtocolAndReturnVendorJson() throws Exception {
        var service = mock(MirrorDeploymentIsolationAuthorityPublicationService.class);
        var authenticator = mock(IntegrationRequestAuthenticator.class);
        var publication = fixtures.publication(1, "");
        when(authenticator.authenticate(any(HttpHeaders.class),
                eq(IntegrationOperation.MIRROR_ISOLATION_AUTHORITY_READ)))
                .thenReturn(identity("MIRROR_TRUST_DISTRIBUTION"));
        when(service.latest(eq(MirrorDeploymentIsolationAuthorityPublicationTestFixtures
                        .DEPLOYMENT_SCOPE_ID),
                eq(MirrorDeploymentIsolationAuthorityPublicationTestFixtures.KEY_SET_ID),
                any(IntegrationRequestContext.class))).thenReturn(publication);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                        new MirrorDeploymentIsolationAuthorityPublicationController(
                                service, authenticator,
                                new MirrorDeploymentIsolationAuthorityPublicationDecoder(
                                        fixtures.mapper)))
                .setControllerAdvice(new IntegrationProblemHandler())
                .build();

        var request = get(
                "/api/mirror/trust/deployment-isolation/authority-key-sets/{id}/latest",
                MirrorDeploymentIsolationAuthorityPublicationTestFixtures.KEY_SET_ID)
                .queryParam("deploymentScopeId",
                        MirrorDeploymentIsolationAuthorityPublicationTestFixtures
                                .DEPLOYMENT_SCOPE_ID)
                .accept(MirrorDeploymentIsolationTrustDistributionProtocol.MEDIA_TYPE);
        mvc.perform(request).andExpect(status().isNotFound());
        mvc.perform(request.header(
                        MirrorDeploymentIsolationTrustDistributionProtocol.REQUEST_HEADER,
                        MirrorDeploymentIsolationTrustDistributionProtocol.VERSION))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(
                        MirrorDeploymentIsolationTrustDistributionProtocol.MEDIA_TYPE))
                .andExpect(jsonPath("$.payload.publicationFingerprint")
                        .value(publication.publicationFingerprint()));
    }

    private static IntegrationRequestContext identity(String purpose) {
        return new IntegrationRequestContext("tenant-a", "org-a", "project-a", "staging",
                "ap-southeast-1", "SERVICE", "trust-agent", "", purpose,
                "corr-controller", Set.of(), "RESTRICTED", "");
    }
}
