package com.leanowtech.bloge.gateway.testing.correctness.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationAccessAuditRecord;
import com.leanowtech.bloge.gateway.integration.IntegrationAccessAuditRepository;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemHandler;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationWorkloadIdentity;
import com.leanowtech.bloge.gateway.integration.StaticBearerIntegrationIdentityResolver;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactTargetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.TargetKind;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CorrectnessRunControllerTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void exposesAuthenticatedNoStorePayloadFreePreflight() throws Exception {
        CorrectnessPreflightFacade facade = mock(CorrectnessPreflightFacade.class);
        CorrectnessPreflightReport report = report();
        when(facade.preflight(any(), any())).thenReturn(report);

        mvc(facade).perform(post("/api/visual/correctness-runs:preflight")
                        .headers(headers())
                        .contentType("application/json")
                        .content(mapper.writeValueAsBytes(new CorrectnessPreflightRequest(
                                "", report.publicationRef(), report.selection()))))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(jsonPath("$.protocolVersion").value("bloge.correctnessApi.v1"))
                .andExpect(jsonPath("$.capabilities[0]").value("CORRECTNESS_PREFLIGHT_V1"))
                .andExpect(jsonPath("$.data.preflightFingerprint")
                        .value(report.preflightFingerprint()))
                .andExpect(jsonPath("$.data.riskSummary.realCount").value(0))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        containsString("fixture-value"))));
    }

    @Test
    void mapsStablePreflightConflictWithoutLeakingDetails() throws Exception {
        CorrectnessPreflightFacade facade = mock(CorrectnessPreflightFacade.class);
        when(facade.preflight(any(), any())).thenThrow(new CorrectnessRunException(
                409, "RG.CORRECTNESS.SELECTION_FINGERPRINT_CONFLICT",
                "Case selection changed after it was reviewed", false));
        CorrectnessPreflightReport report = report();

        mvc(facade).perform(post("/api/visual/correctness-runs:preflight")
                        .headers(headers())
                        .contentType("application/json")
                        .content(mapper.writeValueAsBytes(new CorrectnessPreflightRequest(
                                "", report.publicationRef(), report.selection()))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(
                        "RG.CORRECTNESS.SELECTION_FINGERPRINT_CONFLICT"))
                .andExpect(jsonPath("$.retryable").value(false));
    }

    private static CorrectnessPreflightReport report() {
        CorrectnessRunRequest.PublicationRef publication =
                new CorrectnessRunRequest.PublicationRef("publication-1", 1, fp('a'));
        CorrectnessRunRequest.Selection selection = new CorrectnessRunRequest.Selection(
                CorrectnessRunRequest.Selection.Mode.SELECTED,
                List.of("case-1"), fp('b'));
        return new CorrectnessPreflightReport("", publication,
                new ExactTargetRef(TargetKind.GRAPH, "graph-1", 1, fp('c')),
                new ExactAssetRef("TEST_SUITE", "suite-1", 1, fp('d')),
                selection, CorrectnessPreflightReport.ProofLevel.STRUCTURAL,
                List.of(), new CorrectnessPreflightReport.RiskSummary(
                        0, 0, 0, 0, 0, 0, 0, 0, 0, false, List.of()),
                List.of(), fp('e'));
    }

    private static MockMvc mvc(CorrectnessPreflightFacade facade) {
        IntegrationWorkloadIdentity identity = new IntegrationWorkloadIdentity(
                "studio", "tenant-a", "org-a", "project-a", "test", "sg",
                "USER", "author-a", "", Set.of("TEST_EXECUTION"), Instant.MAX, true);
        IntegrationRequestAuthenticator authenticator = new IntegrationRequestAuthenticator(
                new StaticBearerIntegrationIdentityResolver("test-token", identity, false),
                new RecordingAudit());
        return MockMvcBuilders.standaloneSetup(
                        new CorrectnessRunController(facade, authenticator))
                .setControllerAdvice(new IntegrationProblemHandler())
                .build();
    }

    private static HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("test-token");
        headers.set("X-Purpose", "TEST_EXECUTION");
        headers.set("X-Correlation-Id", "correctness-preflight-1");
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
