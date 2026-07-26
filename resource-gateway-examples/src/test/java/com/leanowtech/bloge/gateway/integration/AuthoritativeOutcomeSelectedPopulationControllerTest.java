package com.leanowtech.bloge.gateway.integration;

import com.leanowtech.bloge.gateway.integration.mirror.AuthoritativeOutcomeSelectedPopulationAdmission;
import com.leanowtech.bloge.gateway.integration.mirror.AuthoritativeOutcomeSelectedPopulationAdmissionRequest;
import com.leanowtech.bloge.gateway.integration.mirror.AuthoritativeOutcomeSelectedPopulationApplicationService;
import com.leanowtech.bloge.gateway.integration.mirror.AuthoritativeOutcomeSelectedPopulationAssessmentAdmission;
import com.leanowtech.bloge.gateway.integration.mirror.AuthoritativeOutcomeSelectedPopulationAssessmentRequest;
import com.leanowtech.bloge.gateway.integration.mirror.AuthoritativeOutcomeSelectedPopulationAssessmentSourcePage;
import com.leanowtech.bloge.gateway.integration.mirror.AuthoritativeOutcomeSelectedPopulationBundle;
import com.leanowtech.bloge.gateway.integration.mirror.AuthoritativeOutcomeSelectedPopulationCompletenessAssessment;
import com.leanowtech.bloge.gateway.integration.mirror.AuthoritativeOutcomeSelectedPopulationDisposition;
import com.leanowtech.bloge.gateway.integration.mirror.AuthoritativeOutcomeSelectedPopulationDispositionAdmission;
import com.leanowtech.bloge.gateway.integration.mirror.AuthoritativeOutcomeSelectedPopulationDispositionAdmissionRequest;
import com.leanowtech.bloge.gateway.integration.mirror.AuthoritativeOutcomeSelectedPopulationManifest;
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

class AuthoritativeOutcomeSelectedPopulationControllerTest {

    @Test
    void authenticatesBeforeEveryWriteDecodeAndUsesDedicatedReadOperation() {
        AuthoritativeOutcomeSelectedPopulationApplicationService service =
                mock(
                        AuthoritativeOutcomeSelectedPopulationApplicationService
                                .class);
        IntegrationRequestAuthenticator authenticator =
                mock(IntegrationRequestAuthenticator.class);
        AuthoritativeOutcomeSelectedPopulationRequestDecoder decoder =
                mock(
                        AuthoritativeOutcomeSelectedPopulationRequestDecoder
                                .class);
        IntegrationRequestContext identity =
                mock(IntegrationRequestContext.class);
        HttpHeaders headers = new HttpHeaders();
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);

        AuthoritativeOutcomeSelectedPopulationAdmissionRequest
                populationCommand =
                mock(
                        AuthoritativeOutcomeSelectedPopulationAdmissionRequest
                                .class);
        AuthoritativeOutcomeSelectedPopulationDispositionAdmissionRequest
                dispositionCommand =
                mock(
                        AuthoritativeOutcomeSelectedPopulationDispositionAdmissionRequest
                                .class);
        AuthoritativeOutcomeSelectedPopulationAssessmentRequest
                assessmentCommand =
                mock(
                        AuthoritativeOutcomeSelectedPopulationAssessmentRequest
                                .class);
        AuthoritativeOutcomeSelectedPopulationAdmission
                populationAdmission =
                mock(
                        AuthoritativeOutcomeSelectedPopulationAdmission
                                .class);
        AuthoritativeOutcomeSelectedPopulationBundle bundle =
                mock(
                        AuthoritativeOutcomeSelectedPopulationBundle
                                .class);
        AuthoritativeOutcomeSelectedPopulationManifest manifest =
                mock(
                        AuthoritativeOutcomeSelectedPopulationManifest
                                .class);
        AuthoritativeOutcomeSelectedPopulationDispositionAdmission
                dispositionAdmission =
                mock(
                        AuthoritativeOutcomeSelectedPopulationDispositionAdmission
                                .class);
        AuthoritativeOutcomeSelectedPopulationDisposition disposition =
                mock(
                        AuthoritativeOutcomeSelectedPopulationDisposition
                                .class);
        AuthoritativeOutcomeSelectedPopulationAssessmentAdmission
                assessmentAdmission =
                mock(
                        AuthoritativeOutcomeSelectedPopulationAssessmentAdmission
                                .class);
        AuthoritativeOutcomeSelectedPopulationCompletenessAssessment
                assessment =
                mock(
                        AuthoritativeOutcomeSelectedPopulationCompletenessAssessment
                                .class);
        AuthoritativeOutcomeSelectedPopulationAssessmentSourcePage
                sourcePage =
                mock(
                        AuthoritativeOutcomeSelectedPopulationAssessmentSourcePage
                                .class);

        when(authenticator.authenticate(
                headers,
                IntegrationOperation
                        .MIRROR_OUTCOME_POPULATION_INGEST))
                .thenReturn(identity);
        when(authenticator.authenticate(
                headers,
                IntegrationOperation
                        .MIRROR_OUTCOME_DISPOSITION_INGEST))
                .thenReturn(identity);
        when(authenticator.authenticate(
                headers,
                IntegrationOperation
                        .MIRROR_OUTCOME_POPULATION_ASSESS))
                .thenReturn(identity);
        when(authenticator.authenticate(
                headers,
                IntegrationOperation
                        .MIRROR_OUTCOME_POPULATION_READ))
                .thenReturn(identity);
        when(decoder.decodePopulation(body, identity))
                .thenReturn(populationCommand);
        when(decoder.decodeDisposition(body, identity))
                .thenReturn(dispositionCommand);
        when(decoder.decodeAssessment(body, identity))
                .thenReturn(assessmentCommand);

        when(service.ingestPopulation(
                populationCommand, identity))
                .thenReturn(populationAdmission);
        when(populationAdmission.population())
                .thenReturn(bundle);
        when(populationAdmission.schemaVersion())
                .thenReturn(
                        AuthoritativeOutcomeSelectedPopulationAdmission
                                .SCHEMA_VERSION);
        when(bundle.manifest()).thenReturn(manifest);
        when(bundle.schemaVersion()).thenReturn(
                AuthoritativeOutcomeSelectedPopulationBundle
                        .SCHEMA_VERSION);
        when(manifest.populationId())
                .thenReturn("refund-population");
        when(manifest.revision()).thenReturn(3L);

        when(service.ingestDisposition(
                "refund-population",
                dispositionCommand,
                identity))
                .thenReturn(dispositionAdmission);
        when(dispositionAdmission.disposition())
                .thenReturn(disposition);
        when(dispositionAdmission.schemaVersion())
                .thenReturn(
                        AuthoritativeOutcomeSelectedPopulationDispositionAdmission
                                .SCHEMA_VERSION);
        when(disposition.dispositionId())
                .thenReturn("deletion-1");
        when(disposition.revision()).thenReturn(2L);
        when(disposition.schemaVersion()).thenReturn(
                AuthoritativeOutcomeSelectedPopulationDisposition
                        .SCHEMA_VERSION);

        when(service.assess(
                "refund-population",
                assessmentCommand,
                identity))
                .thenReturn(assessmentAdmission);
        when(assessmentAdmission.assessment())
                .thenReturn(assessment);
        when(assessmentAdmission.schemaVersion())
                .thenReturn(
                        AuthoritativeOutcomeSelectedPopulationAssessmentAdmission
                                .SCHEMA_VERSION);
        when(assessment.assessmentId())
                .thenReturn("completeness-1");
        when(assessment.revision()).thenReturn(4L);
        when(assessment.schemaVersion()).thenReturn(
                AuthoritativeOutcomeSelectedPopulationCompletenessAssessment
                        .SCHEMA_VERSION);
        when(sourcePage.schemaVersion()).thenReturn(
                AuthoritativeOutcomeSelectedPopulationAssessmentSourcePage
                        .SCHEMA_VERSION);

        when(service.findPopulation(
                "refund-population", 3, identity))
                .thenReturn(bundle);
        when(service.findLatestPopulation(
                "refund-population", identity))
                .thenReturn(bundle);
        when(service.findDisposition(
                "refund-population",
                "deletion-1",
                2,
                identity))
                .thenReturn(disposition);
        when(service.findAssessment(
                "refund-population",
                "completeness-1",
                4,
                identity))
                .thenReturn(assessment);
        when(service.assessmentSources(
                "refund-population",
                "completeness-1",
                4,
                7,
                25,
                identity))
                .thenReturn(sourcePage);

        AuthoritativeOutcomeSelectedPopulationController controller =
                new
                        AuthoritativeOutcomeSelectedPopulationController(
                        service,
                        authenticator,
                        decoder);

        var populationResponse =
                controller.ingestPopulation(body, headers);
        var dispositionResponse =
                controller.ingestDisposition(
                        "refund-population", body, headers);
        var assessmentResponse =
                controller.assess(
                        "refund-population", body, headers);

        assertThat(populationResponse.getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(populationResponse.getHeaders().getLocation())
                .hasToString(
                        "/api/mirror/outcome-selected-populations/refund-population/revisions/3");
        assertThat(dispositionResponse.getHeaders().getLocation())
                .hasToString(
                        "/api/mirror/outcome-selected-populations/refund-population/dispositions/deletion-1/revisions/2");
        assertThat(assessmentResponse.getHeaders().getLocation())
                .hasToString(
                        "/api/mirror/outcome-selected-populations/refund-population/assessments/completeness-1/revisions/4");

        assertThat(controller.findPopulation(
                "refund-population", 3, headers).payload())
                .isSameAs(bundle);
        assertThat(controller.findLatestPopulation(
                "refund-population", headers).payload())
                .isSameAs(bundle);
        assertThat(controller.findDisposition(
                "refund-population",
                "deletion-1",
                2,
                headers).payload())
                .isSameAs(disposition);
        assertThat(controller.findAssessment(
                "refund-population",
                "completeness-1",
                4,
                headers).payload())
                .isSameAs(assessment);
        assertThat(controller.assessmentSources(
                "refund-population",
                "completeness-1",
                4,
                7,
                25,
                headers).payload())
                .isSameAs(sourcePage);

        InOrder populationOrder =
                inOrder(authenticator, decoder, service);
        populationOrder.verify(authenticator)
                .authenticate(
                        headers,
                        IntegrationOperation
                                .MIRROR_OUTCOME_POPULATION_INGEST);
        populationOrder.verify(decoder)
                .decodePopulation(body, identity);
        populationOrder.verify(service)
                .ingestPopulation(
                        populationCommand, identity);
        populationOrder.verify(authenticator)
                .authenticate(
                        headers,
                        IntegrationOperation
                                .MIRROR_OUTCOME_DISPOSITION_INGEST);
        populationOrder.verify(decoder)
                .decodeDisposition(body, identity);
        populationOrder.verify(service)
                .ingestDisposition(
                        "refund-population",
                        dispositionCommand,
                        identity);
        populationOrder.verify(authenticator)
                .authenticate(
                        headers,
                        IntegrationOperation
                                .MIRROR_OUTCOME_POPULATION_ASSESS);
        populationOrder.verify(decoder)
                .decodeAssessment(body, identity);
        populationOrder.verify(service)
                .assess(
                        "refund-population",
                        assessmentCommand,
                        identity);
    }

    @Test
    void authenticationFailureNeverDecodesLargeOrMalformedBodies() {
        AuthoritativeOutcomeSelectedPopulationApplicationService service =
                mock(
                        AuthoritativeOutcomeSelectedPopulationApplicationService
                                .class);
        IntegrationRequestAuthenticator authenticator =
                mock(IntegrationRequestAuthenticator.class);
        AuthoritativeOutcomeSelectedPopulationRequestDecoder decoder =
                mock(
                        AuthoritativeOutcomeSelectedPopulationRequestDecoder
                                .class);
        HttpHeaders headers = new HttpHeaders();
        byte[] body = "not-json".getBytes(StandardCharsets.UTF_8);
        RuntimeException rejected =
                new IllegalStateException("unauthorized");
        when(authenticator.authenticate(
                headers,
                IntegrationOperation
                        .MIRROR_OUTCOME_POPULATION_INGEST))
                .thenThrow(rejected);
        AuthoritativeOutcomeSelectedPopulationController controller =
                new
                        AuthoritativeOutcomeSelectedPopulationController(
                        service,
                        authenticator,
                        decoder);

        assertThatThrownBy(() ->
                controller.ingestPopulation(body, headers))
                .isSameAs(rejected);
        verifyNoInteractions(decoder, service);
    }

    @Test
    void operationsPreserveThreeRoleSeparationOfDuties() {
        assertThat(
                IntegrationOperation
                        .MIRROR_OUTCOME_POPULATION_INGEST
                        .acceptedPurposes())
                .containsExactly(
                        "MIRROR_OUTCOME_SELECTION");
        assertThat(
                IntegrationOperation
                        .MIRROR_OUTCOME_DISPOSITION_INGEST
                        .acceptedPurposes())
                .containsExactly(
                        "MIRROR_OUTCOME_DISPOSITION");
        assertThat(
                IntegrationOperation
                        .MIRROR_OUTCOME_POPULATION_ASSESS
                        .acceptedPurposes())
                .containsExactly(
                        "MIRROR_FIDELITY_GOVERNANCE");
        assertThat(
                IntegrationOperation
                        .MIRROR_OUTCOME_POPULATION_READ
                        .acceptedPurposes())
                .containsExactlyInAnyOrder(
                        "MIRROR_OUTCOME_SELECTION",
                        "MIRROR_OUTCOME_DISPOSITION",
                        "MIRROR_FIDELITY_GOVERNANCE",
                        "GOVERNANCE_EVIDENCE_INGESTION");
        assertThat(
                IntegrationOperation
                        .MIRROR_OUTCOME_POPULATION_INGEST
                        .accepts(
                                "MIRROR_OUTCOME_DISPOSITION"))
                .isFalse();
    }
}
