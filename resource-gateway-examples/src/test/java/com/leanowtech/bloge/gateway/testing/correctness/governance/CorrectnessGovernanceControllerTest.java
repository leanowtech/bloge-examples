package com.leanowtech.bloge.gateway.testing.correctness.governance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationAccessAuditRecord;
import com.leanowtech.bloge.gateway.integration.IntegrationAccessAuditRepository;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemHandler;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationWorkloadIdentity;
import com.leanowtech.bloge.gateway.integration.StaticBearerIntegrationIdentityResolver;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CorrectnessGovernanceControllerTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void writesCalibrationProposalWithDedicatedPurposeAndNoStoreResponse() throws Exception {
        CorrectnessGovernanceService service = mock(CorrectnessGovernanceService.class);
        StoredOutcomeCalibrationProposal stored = mock(StoredOutcomeCalibrationProposal.class);
        when(stored.schemaVersion()).thenReturn(StoredOutcomeCalibrationProposal.SCHEMA_VERSION);
        when(stored.proposalFingerprint()).thenReturn(fp('1'));
        when(service.propose(any(), any())).thenReturn(stored);

        mvc(service).perform(post("/api/visual/correctness-outcome-calibration-proposals")
                        .headers(headers("CORRECTNESS_WRITE"))
                        .contentType("application/json")
                        .content(mapper.writeValueAsBytes(Map.of(
                                "proposalId", "proposal-1",
                                "suiteRunId", "suite-run-1",
                                "evidenceCompanionFingerprint", fp('2'),
                                "mismatchKind", "EXPECTED_OUTCOME_DIFFERED",
                                "reasonCode", "OUTCOME_MISMATCH",
                                "businessRationale", "Reviewed business truth changed.",
                                "proposedRegressionTitle", "Preserve reviewed outcome"))))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(header().string(HttpHeaders.PRAGMA, "no-cache"))
                .andExpect(jsonPath("$.capabilities[0]")
                        .value("OUTCOME_CALIBRATION_PROPOSAL_V1"))
                .andExpect(jsonPath("$.data.proposalFingerprint").value(fp('1')));
    }

    @Test
    void readsGovernanceFeedbackWithDedicatedPurposeAndNoStoreResponse() throws Exception {
        CorrectnessGovernanceService service = mock(CorrectnessGovernanceService.class);
        StoredCorrectnessGovernanceFeedback stored =
                mock(StoredCorrectnessGovernanceFeedback.class);
        when(stored.schemaVersion()).thenReturn(
                StoredCorrectnessGovernanceFeedback.SCHEMA_VERSION);
        when(stored.feedbackFingerprint()).thenReturn(fp('3'));
        when(service.latestFeedback(eq("publication-1"), any())).thenReturn(stored);

        mvc(service).perform(get(
                        "/api/visual/correctness-publications/publication-1/governance-feedback")
                        .headers(headers("CORRECTNESS_READ")))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(jsonPath("$.capabilities[0]")
                        .value("CORRECTNESS_GOVERNANCE_FEEDBACK_V1"))
                .andExpect(jsonPath("$.data.feedbackFingerprint").value(fp('3')));
    }

    @Test
    void rejectsPurposeSubstitutionBeforeCallingTheGovernanceService() throws Exception {
        CorrectnessGovernanceService service = mock(CorrectnessGovernanceService.class);

        mvc(service).perform(post(
                        "/api/integration/correctness-publications/publication-1/governance-feedback")
                        .headers(headers("CORRECTNESS_READ"))
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("RG.INTEGRATION.PURPOSE_FORBIDDEN"));

        verifyNoInteractions(service);
    }

    @Test
    void mapsStableGovernanceFailuresWithoutExposingInternalCauseTypes() throws Exception {
        CorrectnessGovernanceService service = mock(CorrectnessGovernanceService.class);
        when(service.latestFeedback(eq("publication-1"), any())).thenThrow(
                new CorrectnessGovernanceException(
                        404, "RG.CORRECTNESS.GOVERNANCE_FEEDBACK_NOT_FOUND",
                        "No governance feedback exists for the exact Publication", false));

        mvc(service).perform(get(
                        "/api/visual/correctness-publications/publication-1/governance-feedback")
                        .headers(headers("CORRECTNESS_READ")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code")
                        .value("RG.CORRECTNESS.GOVERNANCE_FEEDBACK_NOT_FOUND"))
                .andExpect(jsonPath("$.retryable").value(false));
    }

    private static MockMvc mvc(CorrectnessGovernanceService service) {
        IntegrationWorkloadIdentity identity = new IntegrationWorkloadIdentity(
                "correctness-studio", "tenant-a", "org-a", "project-a", "test", "sg",
                "USER", "author-a", "", Set.of(
                "CORRECTNESS_READ", "CORRECTNESS_WRITE", "GOVERNANCE_GATE_FEEDBACK"),
                Instant.MAX, true);
        IntegrationRequestAuthenticator authenticator = new IntegrationRequestAuthenticator(
                new StaticBearerIntegrationIdentityResolver("test-token", identity, false),
                new RecordingAudit());
        return MockMvcBuilders.standaloneSetup(
                        new CorrectnessGovernanceController(service, authenticator))
                .setControllerAdvice(new IntegrationProblemHandler())
                .build();
    }

    private static HttpHeaders headers(String purpose) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("test-token");
        headers.set("X-Purpose", purpose);
        headers.set("X-Correlation-Id", "correctness-governance-1");
        return headers;
    }

    private static String fp(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }

    private static final class RecordingAudit implements IntegrationAccessAuditRepository {
        @Override
        public IntegrationAccessAuditRecord append(IntegrationAccessAuditRecord record) {
            return record;
        }

        @Override
        public List<IntegrationAccessAuditRecord> recent(int limit) {
            return List.of();
        }
    }
}
