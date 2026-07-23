package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.CapabilityObservationController;
import com.leanowtech.bloge.gateway.integration.CapabilityObservationDecoder;
import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemHandler;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CapabilityObservationControllerTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void authenticatesBeforeDecodeAndReturnsAtomicReceipt() throws Exception {
        CapabilityObservationAdmissionService service =
                mock(CapabilityObservationAdmissionService.class);
        IntegrationRequestAuthenticator authenticator =
                mock(IntegrationRequestAuthenticator.class);
        CapabilityObservationDecoder decoder =
                mock(CapabilityObservationDecoder.class);
        CapabilityObservationEnvelope envelope = envelope("observation-controller");
        CapabilityObservationRepository.StoredObservation stored = stored(envelope);
        byte[] body = mapper.writeValueAsBytes(envelope);
        HttpHeaders headers = new HttpHeaders();
        IntegrationRequestContext identity =
                CapabilityObservationTestFixtures.identity("org-a");
        when(authenticator.authenticate(
                headers, IntegrationOperation.MIRROR_OBSERVATION_INGEST))
                .thenReturn(identity);
        when(decoder.decode(body, identity)).thenReturn(envelope);
        when(service.ingest(envelope, identity)).thenReturn(stored);

        var response = new CapabilityObservationController(
                service, authenticator, decoder).ingest(body, headers);

        assertThat(response.payloadKind())
                .isEqualTo(CapabilityObservationReceipt.ARTIFACT_KIND);
        assertThat(response.payloadSchemaVersion())
                .isEqualTo(CapabilityObservationReceipt.SCHEMA_VERSION);
        assertThat(response.payload())
                .isEqualTo(CapabilityObservationReceipt.from(stored));
        verify(authenticator).authenticate(
                headers, IntegrationOperation.MIRROR_OBSERVATION_INGEST);
    }

    @Test
    void duplicateInputIsRejectedAfterAuthenticationBeforeServiceInvocation()
            throws Exception {
        CapabilityObservationAdmissionService service =
                mock(CapabilityObservationAdmissionService.class);
        IntegrationRequestAuthenticator authenticator =
                mock(IntegrationRequestAuthenticator.class);
        when(authenticator.authenticate(
                any(HttpHeaders.class),
                eq(IntegrationOperation.MIRROR_OBSERVATION_INGEST)))
                .thenReturn(CapabilityObservationTestFixtures.identity("org-a"));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                        new CapabilityObservationController(
                                service,
                                authenticator,
                                new CapabilityObservationDecoder(mapper)))
                .setControllerAdvice(new IntegrationProblemHandler())
                .build();
        String json = mapper.writeValueAsString(envelope("observation-duplicate"));
        String duplicate = json.replaceFirst(
                "\\{", "{\"schemaVersion\":\"duplicate\",");

        mvc.perform(post("/api/mirror/observations")
                        .contentType(APPLICATION_JSON)
                        .content(duplicate.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code")
                        .value("RG.MIRROR.OBSERVATION_REQUEST_MALFORMED"));

        verify(authenticator).authenticate(
                any(HttpHeaders.class),
                eq(IntegrationOperation.MIRROR_OBSERVATION_INGEST));
        verifyNoInteractions(service);
    }

    @Test
    void operationAcceptsOnlyDedicatedCorpusIngestionPurpose() {
        assertThat(IntegrationOperation.MIRROR_OBSERVATION_INGEST.acceptedPurposes())
                .containsExactly("MIRROR_CORPUS_INGESTION");
        assertThat(IntegrationOperation.MIRROR_OBSERVATION_INGEST
                .accepts("MIRROR_REHEARSAL")).isFalse();
    }

    private CapabilityObservationEnvelope envelope(String observationId) {
        CapabilitySnapshot capability = CapabilityObservationTestFixtures.capability(
                mapper, CapabilityObservationTestFixtures.scope("org-a"));
        return CapabilityObservationTestFixtures.envelope(
                mapper,
                new InMemoryVisualEvidenceSigner(),
                capability,
                observationId);
    }

    private CapabilityObservationRepository.StoredObservation stored(
            CapabilityObservationEnvelope envelope) {
        Instant decidedAt = envelope.seal().signedAt().plusSeconds(1);
        CapabilityObservationAdmission admission =
                new CapabilityObservationAdmissionIntegrity(mapper).admitted(
                        envelope,
                        CapabilityObservationTestFixtures.ref(
                                "OBSERVATION_ADMISSION_POLICY",
                                "support-policy",
                                3,
                                'f'),
                        CapabilityObservationTestFixtures.ref(
                                "OBSERVATION_AUTHORITY_KEY",
                                envelope.seal().keyId(),
                                1,
                                'e'),
                        decidedAt,
                        decidedAt.plus(Duration.ofDays(10)));
        return new CapabilityObservationRepository.StoredObservation(
                envelope, admission);
    }
}
