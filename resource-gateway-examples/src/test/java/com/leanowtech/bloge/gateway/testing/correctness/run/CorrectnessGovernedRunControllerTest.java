package com.leanowtech.bloge.gateway.testing.correctness.run;

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
import java.util.Set;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CorrectnessGovernedRunControllerTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void exposesAuthenticatedNoStoreGovernedRunWithoutRawClientKey() throws Exception {
        CorrectnessRunService service = mock(CorrectnessRunService.class);
        CorrectnessRunResponse response = mock(CorrectnessRunResponse.class);
        when(response.schemaVersion()).thenReturn(CorrectnessRunResponse.SCHEMA_VERSION);
        when(response.status()).thenReturn(CorrectnessRunResponse.Status.RUNNING);
        when(service.execute(any(), any())).thenReturn(response);

        mvc(service).perform(post("/api/visual/correctness-runs")
                        .headers(headers("TEST_EXECUTION"))
                        .contentType("application/json")
                        .content(mapper.writeValueAsBytes(request())))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(header().string(HttpHeaders.PRAGMA, "no-cache"))
                .andExpect(jsonPath("$.capabilities[0]").value("CORRECTNESS_RUN_V1"))
                .andExpect(jsonPath("$.data.schemaVersion")
                        .value(CorrectnessRunResponse.SCHEMA_VERSION))
                .andExpect(content().string(not(containsString("raw-client-key"))));
    }

    @Test
    void readsExactEvidenceForGovernancePurpose() throws Exception {
        CorrectnessRunService service = mock(CorrectnessRunService.class);
        StoredCorrectnessEvidenceCompanion stored = mock(
                StoredCorrectnessEvidenceCompanion.class);
        when(stored.schemaVersion()).thenReturn(
                StoredCorrectnessEvidenceCompanion.SCHEMA_VERSION);
        when(stored.companionFingerprint()).thenReturn(fp('d'));
        when(service.findEvidence(eq("suite-run-1"), any())).thenReturn(stored);

        mvc(service).perform(get(
                        "/api/visual/correctness-runs/suite-run-1/evidence-companion")
                        .headers(headers("GOVERNANCE_EVIDENCE_INGESTION")))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(jsonPath("$.capabilities[0]")
                        .value("CORRECTNESS_EVIDENCE_COMPANION_V1"))
                .andExpect(jsonPath("$.data.companionFingerprint").value(fp('d')));
    }

    @Test
    void mapsStableRunFailureWithoutLeakingInternalDetails() throws Exception {
        CorrectnessRunService service = mock(CorrectnessRunService.class);
        when(service.execute(any(), any())).thenThrow(new CorrectnessRunException(
                409, "RG.CORRECTNESS.PREFLIGHT_STALE",
                "The effective execution plan changed after review", false));

        mvc(service).perform(post("/api/visual/correctness-runs")
                        .headers(headers("TEST_EXECUTION"))
                        .contentType("application/json")
                        .content(mapper.writeValueAsBytes(request())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RG.CORRECTNESS.PREFLIGHT_STALE"))
                .andExpect(jsonPath("$.retryable").value(false));
    }

    private static CorrectnessRunRequest request() {
        return new CorrectnessRunRequest("",
                new CorrectnessRunRequest.PublicationRef("publication-1", 1, fp('a')),
                new CorrectnessRunRequest.Selection(
                        CorrectnessRunRequest.Selection.Mode.SELECTED,
                        List.of("case-1"), fp('b')),
                fp('c'), "raw-client-key", CorrectnessRunRequest.Strategy.COLLECT_ALL);
    }

    private static MockMvc mvc(CorrectnessRunService service) {
        IntegrationWorkloadIdentity identity = new IntegrationWorkloadIdentity(
                "studio", "tenant-a", "org-a", "project-a", "test", "sg",
                "USER", "author-a", "", Set.of(
                "TEST_EXECUTION", "GOVERNANCE_EVIDENCE_INGESTION"),
                Instant.MAX, true);
        IntegrationRequestAuthenticator authenticator = new IntegrationRequestAuthenticator(
                new StaticBearerIntegrationIdentityResolver("test-token", identity, false),
                new RecordingAudit());
        return MockMvcBuilders.standaloneSetup(
                        new CorrectnessGovernedRunController(service, authenticator))
                .setControllerAdvice(new IntegrationProblemHandler())
                .build();
    }

    private static HttpHeaders headers(String purpose) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("test-token");
        headers.set("X-Purpose", purpose);
        headers.set("X-Correlation-Id", "correctness-run-1");
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
