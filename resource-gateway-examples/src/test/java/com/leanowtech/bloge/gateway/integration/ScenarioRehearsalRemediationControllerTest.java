package com.leanowtech.bloge.gateway.integration;

import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalRemediationApproval;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalRemediationApprovalCommand;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalRemediationComparison;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalRemediationLineage;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalRemediationPlan;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalRemediationPreviewRequest;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalRemediationReceipt;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalRemediationRepository;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalRemediationService;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalRemediationSubmitCommand;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.http.HttpHeaders;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScenarioRehearsalRemediationControllerTest {

    @Test
    void authenticatesBeforeStrictDecodeAndReturnsVersionedFacts() {
        ScenarioRehearsalRemediationService service =
                mock(ScenarioRehearsalRemediationService.class);
        IntegrationRequestAuthenticator authenticator =
                mock(IntegrationRequestAuthenticator.class);
        ScenarioArtifactRequestDecoder decoder =
                mock(ScenarioArtifactRequestDecoder.class);
        IntegrationRequestContext identity =
                mock(IntegrationRequestContext.class);
        ScenarioRehearsalRemediationPreviewRequest preview =
                mock(ScenarioRehearsalRemediationPreviewRequest.class);
        ScenarioRehearsalRemediationApprovalCommand approval =
                mock(ScenarioRehearsalRemediationApprovalCommand.class);
        ScenarioRehearsalRemediationSubmitCommand submit =
                mock(ScenarioRehearsalRemediationSubmitCommand.class);
        ScenarioRehearsalRemediationPlan plan =
                mock(ScenarioRehearsalRemediationPlan.class);
        ScenarioRehearsalRemediationLineage lineage =
                mock(ScenarioRehearsalRemediationLineage.class);
        ScenarioRehearsalRemediationComparison comparison =
                mock(
                        ScenarioRehearsalRemediationComparison
                                .class);
        ScenarioRehearsalRemediationApproval approvalFact =
                mock(ScenarioRehearsalRemediationApproval.class);
        ScenarioRehearsalRemediationReceipt receipt =
                mock(ScenarioRehearsalRemediationReceipt.class);
        HttpHeaders headers = new HttpHeaders();
        byte[] raw = "{}".getBytes(StandardCharsets.UTF_8);
        String remediationId =
                "scenario-remediation-" + "a".repeat(64);

        when(authenticator.authenticate(
                headers,
                IntegrationOperation
                        .MIRROR_REHEARSAL_REMEDIATION_PREVIEW))
                .thenReturn(identity);
        when(authenticator.authenticate(
                headers,
                IntegrationOperation
                        .MIRROR_REHEARSAL_REMEDIATION_READ))
                .thenReturn(identity);
        when(authenticator.authenticate(
                headers,
                IntegrationOperation
                        .MIRROR_REHEARSAL_REMEDIATION_COMPARISON_READ))
                .thenReturn(identity);
        when(authenticator.authenticate(
                headers,
                IntegrationOperation
                        .MIRROR_REHEARSAL_REMEDIATION_APPROVE))
                .thenReturn(identity);
        when(authenticator.authenticate(
                headers,
                IntegrationOperation
                        .MIRROR_REHEARSAL_REMEDIATION_SUBMIT))
                .thenReturn(identity);
        when(decoder.decodeRemediationPreviewRequest(
                raw, identity)).thenReturn(preview);
        when(decoder.decodeRemediationApprovalCommand(
                raw, identity)).thenReturn(approval);
        when(decoder.decodeRemediationSubmitCommand(
                raw, identity)).thenReturn(submit);
        when(service.preview(
                "job-a", preview, identity))
                .thenReturn(
                        new ScenarioRehearsalRemediationRepository
                                .PreviewResult(plan, false));
        when(service.find(
                remediationId, identity))
                .thenReturn(Optional.of(lineage));
        when(service.compare(
                remediationId, identity))
                .thenReturn(comparison);
        when(service.approve(
                remediationId, approval, identity))
                .thenReturn(
                        new ScenarioRehearsalRemediationRepository
                                .ApprovalResult(
                                approvalFact, false));
        when(service.submit(
                remediationId, submit, identity))
                .thenReturn(
                        new ScenarioRehearsalRemediationRepository
                                .SubmissionResult(
                                receipt, false));
        when(plan.schemaVersion()).thenReturn(
                ScenarioRehearsalRemediationPlan
                        .SCHEMA_VERSION);
        when(lineage.schemaVersion()).thenReturn(
                ScenarioRehearsalRemediationLineage
                        .SCHEMA_VERSION);
        when(comparison.schemaVersion()).thenReturn(
                ScenarioRehearsalRemediationComparison
                        .SCHEMA_VERSION);
        when(approvalFact.schemaVersion()).thenReturn(
                ScenarioRehearsalRemediationApproval
                        .SCHEMA_VERSION);
        when(receipt.schemaVersion()).thenReturn(
                ScenarioRehearsalRemediationReceipt
                        .SCHEMA_VERSION);
        ScenarioRehearsalRemediationController controller =
                new ScenarioRehearsalRemediationController(
                        service, authenticator, decoder);

        assertThat(controller.preview(
                "job-a", raw, headers))
                .satisfies(envelope -> {
                    assertThat(envelope.payloadKind())
                            .isEqualTo(
                                    "SCENARIO_REHEARSAL_REMEDIATION_PLAN");
                    assertThat(envelope.payload())
                            .isSameAs(plan);
                });
        assertThat(controller.find(
                remediationId, headers).payload())
                .isSameAs(lineage);
        assertThat(controller.compare(
                remediationId, headers).payload())
                .isSameAs(comparison);
        assertThat(controller.approve(
                remediationId, raw, headers).payload())
                .isSameAs(approvalFact);
        assertThat(controller.submit(
                remediationId, raw, headers).payload())
                .isSameAs(receipt);

        InOrder previewOrder =
                inOrder(authenticator, decoder, service);
        previewOrder.verify(authenticator).authenticate(
                headers,
                IntegrationOperation
                        .MIRROR_REHEARSAL_REMEDIATION_PREVIEW);
        previewOrder.verify(decoder)
                .decodeRemediationPreviewRequest(
                        raw, identity);
        previewOrder.verify(service).preview(
                "job-a", preview, identity);
        verify(authenticator).authenticate(
                headers,
                IntegrationOperation
                        .MIRROR_REHEARSAL_REMEDIATION_COMPARISON_READ);
        verify(service).compare(
                remediationId, identity);
    }

    @Test
    void authenticationFailureNeverParsesTheBody() {
        ScenarioRehearsalRemediationService service =
                mock(ScenarioRehearsalRemediationService.class);
        IntegrationRequestAuthenticator authenticator =
                mock(IntegrationRequestAuthenticator.class);
        ScenarioArtifactRequestDecoder decoder =
                mock(ScenarioArtifactRequestDecoder.class);
        HttpHeaders headers = new HttpHeaders();
        byte[] raw = "not-json".getBytes(StandardCharsets.UTF_8);
        RuntimeException rejected =
                new IllegalStateException("unauthorized");
        when(authenticator.authenticate(
                headers,
                IntegrationOperation
                        .MIRROR_REHEARSAL_REMEDIATION_PREVIEW))
                .thenThrow(rejected);
        ScenarioRehearsalRemediationController controller =
                new ScenarioRehearsalRemediationController(
                        service, authenticator, decoder);

        assertThatThrownBy(() -> controller.preview(
                "job-a", raw, headers))
                .isSameAs(rejected);
        verify(decoder, never())
                .decodeRemediationPreviewRequest(
                        raw, null);
        verify(service, never()).preview(
                "job-a", null, null);
    }
}
