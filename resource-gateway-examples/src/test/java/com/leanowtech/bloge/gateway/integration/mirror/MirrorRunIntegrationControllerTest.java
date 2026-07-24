package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemHandler;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.integration.MirrorExecutionRequestDecoder;
import com.leanowtech.bloge.gateway.integration.MirrorRunIntegrationController;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MirrorRunIntegrationControllerTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void authenticatesEachRouteWithItsDedicatedOperationAndReturnsVersionedEnvelopes() {
        MirrorRunIntegrationService service = mock(MirrorRunIntegrationService.class);
        IntegrationRequestAuthenticator authenticator = mock(IntegrationRequestAuthenticator.class);
        MirrorExecutionRequestDecoder decoder = mock(MirrorExecutionRequestDecoder.class);
        MirrorRunIntegrationController controller = new MirrorRunIntegrationController(
                service, authenticator, decoder);
        HttpHeaders headers = new HttpHeaders();
        IntegrationRequestContext identity = identity();
        byte[] json = "{}".getBytes(StandardCharsets.UTF_8);
        MirrorPlan plan = MirrorPersistenceTestFixtures.plan(mapper,
                MirrorPersistenceTestFixtures.scope("org-a"), "plan-1", '1');
        MirrorEvidenceBundle bundle =
                MirrorPersistenceTestFixtures.statefulEvidence(
                        mapper, new InMemoryVisualEvidenceSigner(),
                        plan, "run-1", '2');
        MirrorStateWorkbookSeed seed =
                MirrorStateWorkbookSeed.project(mapper, bundle);
        MirrorEvidenceBundle readWriteBundle =
                MirrorPersistenceTestFixtures.readWriteEvidence(
                        mapper, new InMemoryVisualEvidenceSigner(),
                        plan, "run-1", '3');
        MirrorStateTransitionWorkbookSeed transitionSeed =
                MirrorStateTransitionWorkbookSeed.project(
                        mapper, readWriteBundle);
        MirrorEvidenceBundle writeOutcomeBundle =
                MirrorPersistenceTestFixtures
                        .writeOutcomeEvidence(
                                mapper,
                                new InMemoryVisualEvidenceSigner(),
                                plan, "run-1", '4');
        MirrorStateWriteOutcomeWorkbookSeed
                writeOutcomeSeed =
                MirrorStateWriteOutcomeWorkbookSeed.project(
                        mapper, writeOutcomeBundle);
        MirrorRunSummary summary = MirrorRunSummary.from(bundle);
        MirrorExecutionRequest request = new MirrorExecutionRequest("", "request-1", "plan-1",
                plan.planFingerprint(), Map.of());
        when(authenticator.authenticate(headers, IntegrationOperation.MIRROR_EXECUTION_CREATE))
                .thenReturn(identity);
        when(authenticator.authenticate(headers, IntegrationOperation.MIRROR_RUN_READ))
                .thenReturn(identity);
        when(authenticator.authenticate(headers, IntegrationOperation.MIRROR_EVIDENCE_READ))
                .thenReturn(identity);
        when(decoder.decode(json, identity)).thenReturn(request);
        when(service.execute(request, identity)).thenReturn(summary);
        when(service.find("run-1", identity)).thenReturn(summary);
        when(service.evidence("run-1", identity)).thenReturn(bundle);
        when(service.stateWorkbookSeed("run-1", identity))
                .thenReturn(seed);
        when(service.stateTransitionWorkbookSeed(
                "run-1", identity)).thenReturn(transitionSeed);
        when(service.stateWriteOutcomeWorkbookSeed(
                "run-1", identity))
                .thenReturn(writeOutcomeSeed);

        var executed = controller.execute(json, headers);
        var found = controller.find("run-1", headers);
        var evidence = controller.evidence("run-1", headers);
        var workbook =
                controller.stateWorkbookSeed("run-1", headers);
        var transitionWorkbook =
                controller.stateTransitionWorkbookSeed(
                        "run-1", headers);
        var writeOutcomeWorkbook =
                controller.stateWriteOutcomeWorkbookSeed(
                        "run-1", headers);

        assertThat(executed.payloadKind()).isEqualTo("MIRROR_RUN_SUMMARY");
        assertThat(executed.payloadSchemaVersion()).isEqualTo(MirrorRunSummary.SCHEMA_VERSION);
        assertThat(found.payload()).isEqualTo(summary);
        assertThat(evidence.payloadKind()).isEqualTo("MIRROR_EVIDENCE_BUNDLE");
        assertThat(evidence.payloadSchemaVersion()).isEqualTo(
                MirrorEvidenceBundle.STATEFUL_SCHEMA_VERSION);
        assertThat(workbook.payloadKind())
                .isEqualTo("MIRROR_STATE_WORKBOOK_SEED");
        assertThat(workbook.payloadSchemaVersion())
                .isEqualTo(MirrorStateWorkbookSeed.SCHEMA_VERSION);
        assertThat(workbook.payload()).isEqualTo(seed);
        assertThat(transitionWorkbook.payloadKind())
                .isEqualTo(
                        "MIRROR_STATE_TRANSITION_WORKBOOK_SEED");
        assertThat(transitionWorkbook.payloadSchemaVersion())
                .isEqualTo(
                        MirrorStateTransitionWorkbookSeed
                                .SCHEMA_VERSION);
        assertThat(transitionWorkbook.payload())
                .isEqualTo(transitionSeed);
        assertThat(writeOutcomeWorkbook.payloadKind())
                .isEqualTo(
                        "MIRROR_STATE_WRITE_OUTCOME_WORKBOOK_SEED");
        assertThat(writeOutcomeWorkbook
                .payloadSchemaVersion())
                .isEqualTo(
                        MirrorStateWriteOutcomeWorkbookSeed
                                .SCHEMA_VERSION);
        assertThat(writeOutcomeWorkbook.payload())
                .isEqualTo(writeOutcomeSeed);
        verify(authenticator).authenticate(headers, IntegrationOperation.MIRROR_EXECUTION_CREATE);
        verify(authenticator).authenticate(headers, IntegrationOperation.MIRROR_RUN_READ);
        verify(authenticator, times(4)).authenticate(
                headers, IntegrationOperation.MIRROR_EVIDENCE_READ);
    }

    @Test
    void mapsRunFailuresThroughTheStableIntegrationProblemContract() throws Exception {
        MirrorRunIntegrationService service = mock(MirrorRunIntegrationService.class);
        IntegrationRequestAuthenticator authenticator = mock(IntegrationRequestAuthenticator.class);
        IntegrationRequestContext identity = identity();
        when(authenticator.authenticate(any(HttpHeaders.class),
                eq(IntegrationOperation.MIRROR_RUN_READ))).thenReturn(identity);
        when(service.find("missing-run", identity)).thenThrow(new IntegrationProblemException(
                IntegrationProblem.notFound("RG.MIRROR.RUN_NOT_FOUND",
                        "Mirror run was not found in the authorized scope.",
                        identity.correlationId(), Map.of())));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new MirrorRunIntegrationController(
                        service, authenticator, mock(MirrorExecutionRequestDecoder.class)))
                .setControllerAdvice(new IntegrationProblemHandler())
                .build();

        mvc.perform(get("/api/mirror/runs/missing-run"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RG.MIRROR.RUN_NOT_FOUND"))
                .andExpect(jsonPath("$.correlationId").value(identity.correlationId()));
    }

    @Test
    void rawExecutionTransportRejectsDuplicateKeysBeforeCallingTheService() throws Exception {
        MirrorRunIntegrationService service = mock(MirrorRunIntegrationService.class);
        IntegrationRequestAuthenticator authenticator = mock(IntegrationRequestAuthenticator.class);
        IntegrationRequestContext identity = identity();
        when(authenticator.authenticate(any(HttpHeaders.class),
                eq(IntegrationOperation.MIRROR_EXECUTION_CREATE))).thenReturn(identity);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new MirrorRunIntegrationController(
                        service, authenticator, new MirrorExecutionRequestDecoder(mapper)))
                .setControllerAdvice(new IntegrationProblemHandler())
                .build();
        String duplicated = """
                {
                  "schemaVersion":"resourceGateway.mirrorExecutionRequest.v1",
                  "requestId":"request-1",
                  "requestId":"request-2",
                  "planId":"plan-1",
                  "expectedPlanFingerprint":"%s",
                  "context":{}
                }
                """.formatted(MirrorPersistenceTestFixtures.fingerprint('a'));

        mvc.perform(post("/api/mirror/executions")
                        .contentType(APPLICATION_JSON)
                        .content(duplicated))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code")
                        .value("RG.MIRROR.EXECUTION_REQUEST_MALFORMED"));
        verifyNoInteractions(service);
    }

    private static IntegrationRequestContext identity() {
        return new IntegrationRequestContext("tenant-a", "org-a", "support", "test", "sg",
                "SERVICE", "mirror-client", "", "MIRROR_REHEARSAL", "corr-1",
                Set.of(), "CONFIDENTIAL", "");
    }
}
