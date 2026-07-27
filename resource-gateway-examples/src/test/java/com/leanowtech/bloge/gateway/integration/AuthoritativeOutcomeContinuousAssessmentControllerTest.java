package com.leanowtech.bloge.gateway.integration;

import com.leanowtech.bloge.gateway.integration.mirror.AuthoritativeOutcomeContinuousAssessmentAdmission;
import com.leanowtech.bloge.gateway.integration.mirror.AuthoritativeOutcomeContinuousAssessmentLifecyclePage;
import com.leanowtech.bloge.gateway.integration.mirror.AuthoritativeOutcomeContinuousAssessmentProjection;
import com.leanowtech.bloge.gateway.integration.mirror.AuthoritativeOutcomeContinuousAssessmentRequest;
import com.leanowtech.bloge.gateway.integration.mirror.AuthoritativeOutcomeContinuousAssessmentService;
import com.leanowtech.bloge.gateway.integration.mirror.AuthoritativeOutcomeContinuousAssessmentStatus;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthoritativeOutcomeContinuousAssessmentControllerTest {
    @Test
    void authenticatesBeforeDecodeAndUsesDedicatedReadOperation() {
        AuthoritativeOutcomeContinuousAssessmentService service =
                mock(AuthoritativeOutcomeContinuousAssessmentService
                        .class);
        IntegrationRequestAuthenticator authenticator =
                mock(IntegrationRequestAuthenticator.class);
        AuthoritativeOutcomeSelectedPopulationRequestDecoder decoder =
                mock(
                        AuthoritativeOutcomeSelectedPopulationRequestDecoder
                                .class);
        IntegrationRequestContext identity =
                mock(IntegrationRequestContext.class);
        AuthoritativeOutcomeContinuousAssessmentRequest command =
                mock(
                        AuthoritativeOutcomeContinuousAssessmentRequest
                                .class);
        AuthoritativeOutcomeContinuousAssessmentAdmission admission =
                mock(
                        AuthoritativeOutcomeContinuousAssessmentAdmission
                                .class);
        AuthoritativeOutcomeContinuousAssessmentStatus status =
                mock(
                        AuthoritativeOutcomeContinuousAssessmentStatus
                                .class);
        AuthoritativeOutcomeContinuousAssessmentProjection projection =
                mock(
                        AuthoritativeOutcomeContinuousAssessmentProjection
                                .class);
        AuthoritativeOutcomeContinuousAssessmentLifecyclePage lifecycle =
                mock(
                        AuthoritativeOutcomeContinuousAssessmentLifecyclePage
                                .class);
        HttpHeaders headers = new HttpHeaders();
        byte[] body = "{}".getBytes(
                StandardCharsets.UTF_8);

        when(authenticator.authenticate(
                headers,
                IntegrationOperation
                        .MIRROR_OUTCOME_CONTINUOUS_ASSESSMENT_REGISTER))
                .thenReturn(identity);
        when(authenticator.authenticate(
                headers,
                IntegrationOperation
                        .MIRROR_OUTCOME_CONTINUOUS_ASSESSMENT_READ))
                .thenReturn(identity);
        when(authenticator.authenticate(
                headers,
                IntegrationOperation
                        .MIRROR_OUTCOME_CONTINUOUS_ASSESSMENT_LIFECYCLE_READ))
                .thenReturn(identity);
        when(decoder.decodeContinuousAssessment(
                body, identity)).thenReturn(command);
        when(service.register(
                command, identity)).thenReturn(admission);
        when(admission.status()).thenReturn(status);
        when(admission.schemaVersion()).thenReturn(
                AuthoritativeOutcomeContinuousAssessmentAdmission
                        .SCHEMA_VERSION);
        when(status.projection()).thenReturn(projection);
        when(projection.projectionId()).thenReturn(
                "refund-completeness");
        when(service.find(
                "refund-completeness",
                identity)).thenReturn(status);
        when(status.schemaVersion()).thenReturn(
                AuthoritativeOutcomeContinuousAssessmentStatus
                        .SCHEMA_VERSION);
        when(service.lifecycle(
                "refund-completeness",
                1,
                25,
                identity)).thenReturn(lifecycle);
        when(lifecycle.schemaVersion()).thenReturn(
                AuthoritativeOutcomeContinuousAssessmentLifecyclePage
                        .SCHEMA_VERSION);

        AuthoritativeOutcomeContinuousAssessmentController controller =
                new AuthoritativeOutcomeContinuousAssessmentController(
                        service,
                        authenticator,
                        decoder);
        var created = controller.register(
                body, headers);
        var found = controller.find(
                "refund-completeness",
                headers);
        var history = controller.lifecycle(
                "refund-completeness",
                1,
                25,
                headers);

        assertThat(created.getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(created.getHeaders()
                .getLocation().toString())
                .isEqualTo(
                        "/api/mirror/outcome-continuous-assessments/refund-completeness");
        assertThat(found.payload()).isSameAs(status);
        assertThat(history.payload()).isSameAs(lifecycle);
        InOrder order = inOrder(
                authenticator, decoder, service);
        order.verify(authenticator).authenticate(
                headers,
                IntegrationOperation
                        .MIRROR_OUTCOME_CONTINUOUS_ASSESSMENT_REGISTER);
        order.verify(decoder).decodeContinuousAssessment(
                body, identity);
        order.verify(service).register(
                command, identity);
        order.verify(authenticator).authenticate(
                headers,
                IntegrationOperation
                        .MIRROR_OUTCOME_CONTINUOUS_ASSESSMENT_READ);
        order.verify(service).find(
                "refund-completeness",
                identity);
        order.verify(authenticator).authenticate(
                headers,
                IntegrationOperation
                        .MIRROR_OUTCOME_CONTINUOUS_ASSESSMENT_LIFECYCLE_READ);
        order.verify(service).lifecycle(
                "refund-completeness",
                1,
                25,
                identity);
    }
}
