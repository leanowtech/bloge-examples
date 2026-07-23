package com.leanowtech.bloge.gateway.integration.mirror;

import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemHandler;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.integration.MirrorDeploymentIsolationAttestationController;
import com.leanowtech.bloge.gateway.integration.MirrorDeploymentIsolationAttestationDecoder;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MirrorDeploymentIsolationAttestationControllerTest {
    private final MirrorDeploymentIsolationAttestationRepositoryTestFixtures fixtures =
            new MirrorDeploymentIsolationAttestationRepositoryTestFixtures();

    @Test
    void authenticatesAllRoutesBeforeDecodeAndReturnsAtomicBundleEnvelopes() throws Exception {
        var service = mock(MirrorDeploymentIsolationAttestationService.class);
        var authenticator = mock(IntegrationRequestAuthenticator.class);
        var decoder = mock(MirrorDeploymentIsolationAttestationDecoder.class);
        var controller = new MirrorDeploymentIsolationAttestationController(
                service, authenticator, decoder);
        var bundle = fixtures.bundle(7);
        var request = revocation(bundle);
        byte[] attestationBody = fixtures.mapper.writeValueAsBytes(bundle.attestation());
        byte[] revocationBody = fixtures.mapper.writeValueAsBytes(request);
        HttpHeaders headers = new HttpHeaders();
        IntegrationRequestContext admin = identity("MIRROR_TRUST_ADMIN");
        IntegrationRequestContext reader = identity("MIRROR_TRUST_DISTRIBUTION");
        when(authenticator.authenticate(headers,
                IntegrationOperation.MIRROR_ISOLATION_ATTESTATION_ADMIN)).thenReturn(admin);
        when(authenticator.authenticate(headers,
                IntegrationOperation.MIRROR_ISOLATION_ATTESTATION_READ)).thenReturn(reader);
        when(decoder.decodeAttestation(attestationBody, admin))
                .thenReturn(bundle.attestation());
        when(decoder.decodeRevocation(revocationBody, admin)).thenReturn(request);
        when(service.ingest("deployment:staging", fixtures.KEY_SET_ID,
                bundle.attestation(), admin)).thenReturn(bundle);
        when(service.current("deployment:staging", fixtures.KEY_SET_ID,
                fixtures.ATTESTATION_ID, reader)).thenReturn(bundle);
        when(service.current(eq("deployment:staging"), eq(fixtures.KEY_SET_ID),
                eq(fixtures.ATTESTATION_ID), any(), eq(reader))).thenReturn(bundle);
        when(service.revoke("deployment:staging", fixtures.KEY_SET_ID,
                fixtures.ATTESTATION_ID, request, admin)).thenReturn(bundle);

        var ingested = controller.ingest(attestationBody, "deployment:staging",
                fixtures.KEY_SET_ID, headers);
        var current = controller.current(fixtures.ATTESTATION_ID, "deployment:staging",
                fixtures.KEY_SET_ID, headers);
        var exact = controller.currentExact(fixtures.ATTESTATION_ID, 7,
                "deployment:staging", fixtures.KEY_SET_ID,
                bundle.attestation().attestationFingerprint(), 1,
                bundle.status().statusFingerprint(), headers);
        var revoked = controller.revoke(fixtures.ATTESTATION_ID, revocationBody,
                "deployment:staging", fixtures.KEY_SET_ID, headers);

        assertThat(ingested.payloadKind())
                .isEqualTo(MirrorDeploymentIsolationAttestationBundle.ARTIFACT_KIND);
        assertThat(ingested.payloadSchemaVersion())
                .isEqualTo(MirrorDeploymentIsolationAttestationBundle.SCHEMA_VERSION);
        assertThat(current.payload()).isEqualTo(bundle);
        assertThat(exact.payload()).isEqualTo(bundle);
        assertThat(revoked.payload()).isEqualTo(bundle);
        verify(authenticator, times(2)).authenticate(headers,
                IntegrationOperation.MIRROR_ISOLATION_ATTESTATION_ADMIN);
        verify(authenticator, times(2)).authenticate(headers,
                IntegrationOperation.MIRROR_ISOLATION_ATTESTATION_READ);
    }

    @Test
    void duplicateInputIsRejectedAfterAuthenticationAndBeforeServiceInvocation() throws Exception {
        var service = mock(MirrorDeploymentIsolationAttestationService.class);
        var authenticator = mock(IntegrationRequestAuthenticator.class);
        IntegrationRequestContext identity = identity("MIRROR_TRUST_ADMIN");
        when(authenticator.authenticate(any(HttpHeaders.class),
                eq(IntegrationOperation.MIRROR_ISOLATION_ATTESTATION_ADMIN)))
                .thenReturn(identity);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                        new MirrorDeploymentIsolationAttestationController(
                                service, authenticator,
                                new MirrorDeploymentIsolationAttestationDecoder(fixtures.mapper)))
                .setControllerAdvice(new IntegrationProblemHandler())
                .build();
        String json = fixtures.mapper.writeValueAsString(fixtures.bundle(7).attestation());
        String duplicate = json.replaceFirst("\\{", "{\"schemaVersion\":\"duplicate\",");

        mvc.perform(post("/api/mirror/trust/deployment-isolation/attestations")
                        .queryParam("deploymentScopeId", "deployment:staging")
                        .queryParam("keySetId", fixtures.KEY_SET_ID)
                        .contentType(APPLICATION_JSON)
                        .content(duplicate.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code")
                        .value("RG.MIRROR.ISOLATION_ATTESTATION_REQUEST_MALFORMED"));
        verify(authenticator).authenticate(any(HttpHeaders.class),
                eq(IntegrationOperation.MIRROR_ISOLATION_ATTESTATION_ADMIN));
        verifyNoInteractions(service);
    }

    @Test
    void separatesAdministrativeMutationFromDistributionAndRehearsalReads() {
        assertThat(IntegrationOperation.MIRROR_ISOLATION_ATTESTATION_ADMIN.acceptedPurposes())
                .containsExactly("MIRROR_TRUST_ADMIN");
        assertThat(IntegrationOperation.MIRROR_ISOLATION_ATTESTATION_READ.acceptedPurposes())
                .containsExactlyInAnyOrder("MIRROR_TRUST_DISTRIBUTION", "MIRROR_REHEARSAL");
        assertThat(IntegrationOperation.MIRROR_ISOLATION_ATTESTATION_ADMIN
                .accepts("MIRROR_REHEARSAL")).isFalse();
        assertThat(IntegrationOperation.MIRROR_ISOLATION_ATTESTATION_READ
                .accepts("MIRROR_TRUST_ADMIN")).isFalse();
    }

    private MirrorDeploymentIsolationAttestationRevocationRequest revocation(
            MirrorDeploymentIsolationAttestationBundle bundle) {
        return new MirrorDeploymentIsolationAttestationRevocationRequest("", 7,
                bundle.attestation().attestationFingerprint(), 1,
                bundle.status().statusFingerprint(),
                MirrorDeploymentIsolationAttestationStatusPublication.Reason.OPERATOR_REVOKED);
    }

    private static IntegrationRequestContext identity(String purpose) {
        return new IntegrationRequestContext("tenant-a", "org-a", "project-a", "staging",
                "ap-southeast-1", "SERVICE", "trust-agent", "", purpose,
                "corr-controller", Set.of(), "RESTRICTED", "");
    }
}
