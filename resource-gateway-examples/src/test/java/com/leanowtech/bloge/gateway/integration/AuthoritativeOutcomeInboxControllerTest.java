package com.leanowtech.bloge.gateway.integration;

import com.leanowtech.bloge.gateway.integration.mirror.AuthoritativeOutcomeInboxAdmission;
import com.leanowtech.bloge.gateway.integration.mirror.AuthoritativeOutcomeInboxEntry;
import com.leanowtech.bloge.gateway.integration.mirror.AuthoritativeOutcomeInboxLifecyclePage;
import com.leanowtech.bloge.gateway.integration.mirror.AuthoritativeOutcomeInboxService;
import com.leanowtech.bloge.gateway.integration.mirror.AuthoritativeOutcomeObservation;
import com.leanowtech.bloge.gateway.integration.mirror.AuthoritativeOutcomeObservationAdmissionRequest;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AuthoritativeOutcomeInboxControllerTest {

    @Test
    void authenticatesBeforeDecodeAndUsesDedicatedReadOperations() {
        AuthoritativeOutcomeInboxService service =
                mock(AuthoritativeOutcomeInboxService.class);
        IntegrationRequestAuthenticator authenticator =
                mock(IntegrationRequestAuthenticator.class);
        AuthoritativeOutcomeObservationRequestDecoder decoder =
                mock(
                        AuthoritativeOutcomeObservationRequestDecoder
                                .class);
        IntegrationRequestContext identity =
                mock(IntegrationRequestContext.class);
        AuthoritativeOutcomeObservationAdmissionRequest command =
                mock(
                        AuthoritativeOutcomeObservationAdmissionRequest
                                .class);
        AuthoritativeOutcomeInboxAdmission admission =
                mock(AuthoritativeOutcomeInboxAdmission.class);
        AuthoritativeOutcomeObservation observation =
                mock(AuthoritativeOutcomeObservation.class);
        AuthoritativeOutcomeInboxEntry entry =
                mock(AuthoritativeOutcomeInboxEntry.class);
        AuthoritativeOutcomeInboxLifecyclePage page =
                mock(AuthoritativeOutcomeInboxLifecyclePage.class);
        HttpHeaders headers = new HttpHeaders();
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        when(authenticator.authenticate(
                headers,
                IntegrationOperation
                        .MIRROR_OUTCOME_OBSERVATION_INGEST))
                .thenReturn(identity);
        when(authenticator.authenticate(
                headers,
                IntegrationOperation
                        .MIRROR_OUTCOME_OBSERVATION_READ))
                .thenReturn(identity);
        when(authenticator.authenticate(
                headers,
                IntegrationOperation
                        .MIRROR_OUTCOME_LIFECYCLE_READ))
                .thenReturn(identity);
        when(decoder.decode(body, identity))
                .thenReturn(command);
        when(service.ingest(command, identity))
                .thenReturn(admission);
        when(admission.observation()).thenReturn(observation);
        when(admission.schemaVersion()).thenReturn(
                AuthoritativeOutcomeInboxAdmission.SCHEMA_VERSION);
        when(observation.observationId()).thenReturn("outcome-1");
        when(observation.revision()).thenReturn(3L);
        when(observation.schemaVersion()).thenReturn(
                AuthoritativeOutcomeObservation.SCHEMA_VERSION);
        when(service.findObservation(
                "outcome-1", 3, identity))
                .thenReturn(observation);
        when(service.findLatestObservation(
                "outcome-1", identity))
                .thenReturn(observation);
        when(service.findEntry("outcome-1", identity))
                .thenReturn(entry);
        when(entry.schemaVersion()).thenReturn(
                AuthoritativeOutcomeInboxEntry.SCHEMA_VERSION);
        when(service.lifecycle(
                "outcome-1", 7, 25, identity))
                .thenReturn(page);
        when(page.schemaVersion()).thenReturn(
                AuthoritativeOutcomeInboxLifecyclePage.SCHEMA_VERSION);
        AuthoritativeOutcomeInboxController controller =
                new AuthoritativeOutcomeInboxController(
                        service,
                        authenticator,
                        decoder);

        var ingested = controller.ingest(body, headers);
        assertThat(ingested.getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(ingested.getHeaders().getLocation())
                .hasToString(
                        "/api/mirror/outcome-observations/outcome-1/revisions/3");
        assertThat(ingested.getBody()).isNotNull();
        assertThat(ingested.getBody().payload())
                .isSameAs(admission);
        assertThat(controller.findObservation(
                "outcome-1", 3, headers).payload())
                .isSameAs(observation);
        assertThat(controller.findLatestObservation(
                "outcome-1", headers).payload())
                .isSameAs(observation);
        assertThat(controller.findEntry(
                "outcome-1", headers).payload())
                .isSameAs(entry);
        assertThat(controller.lifecycle(
                "outcome-1", 7, 25, headers).payload())
                .isSameAs(page);

        InOrder order = inOrder(authenticator, decoder, service);
        order.verify(authenticator).authenticate(
                headers,
                IntegrationOperation
                        .MIRROR_OUTCOME_OBSERVATION_INGEST);
        order.verify(decoder).decode(body, identity);
        order.verify(service).ingest(command, identity);
    }

    @Test
    void authenticationFailureNeverParsesTheBody() {
        AuthoritativeOutcomeInboxService service =
                mock(AuthoritativeOutcomeInboxService.class);
        IntegrationRequestAuthenticator authenticator =
                mock(IntegrationRequestAuthenticator.class);
        AuthoritativeOutcomeObservationRequestDecoder decoder =
                mock(
                        AuthoritativeOutcomeObservationRequestDecoder
                                .class);
        HttpHeaders headers = new HttpHeaders();
        byte[] body = "not-json".getBytes(StandardCharsets.UTF_8);
        RuntimeException rejected =
                new IllegalStateException("unauthorized");
        when(authenticator.authenticate(
                headers,
                IntegrationOperation
                        .MIRROR_OUTCOME_OBSERVATION_INGEST))
                .thenThrow(rejected);
        AuthoritativeOutcomeInboxController controller =
                new AuthoritativeOutcomeInboxController(
                        service,
                        authenticator,
                        decoder);

        assertThatThrownBy(() ->
                controller.ingest(body, headers))
                .isSameAs(rejected);
        verifyNoInteractions(decoder, service);
    }

    @Test
    void operationsSeparateConnectorAndGovernancePurposes() {
        assertThat(
                IntegrationOperation
                        .MIRROR_OUTCOME_OBSERVATION_INGEST
                        .acceptedPurposes())
                .containsExactly("MIRROR_OUTCOME_INGESTION");
        assertThat(
                IntegrationOperation
                        .MIRROR_OUTCOME_OBSERVATION_READ
                        .acceptedPurposes())
                .containsExactlyInAnyOrder(
                        "MIRROR_OUTCOME_INGESTION",
                        "MIRROR_FIDELITY_GOVERNANCE",
                        "GOVERNANCE_EVIDENCE_INGESTION");
        assertThat(
                IntegrationOperation
                        .MIRROR_OUTCOME_OBSERVATION_INGEST
                        .accepts(
                                "GOVERNANCE_EVIDENCE_INGESTION"))
                .isFalse();
    }
}
