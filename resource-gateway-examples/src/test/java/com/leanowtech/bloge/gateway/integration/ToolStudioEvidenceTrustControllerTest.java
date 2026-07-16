package com.leanowtech.bloge.gateway.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualGraphRunRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static com.leanowtech.bloge.gateway.integration.EvidenceKeySetTrustTestFixtures.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ToolStudioEvidenceTrustControllerTest {

    @Test
    void publishesAndReadsTrustChainThroughVersionedHttpRoutes() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        Authority authority = new Authority("security-a", keyPair());
        ToolStudioIntegrationService service = new ToolStudioIntegrationService(
                null, null, null, new InMemoryVisualGraphRunRepository(),
                new InMemoryGovernanceGateResultRepository(), mapper);
        service.configureEvidenceTrust(store(mapper, 1, List.of(authority)),
                new InMemoryEvidenceKeySetTrustPublicationRepository());
        String snapshot = service.evidenceKeySet().payload().snapshotFingerprint();
        EvidenceKeySetTrustPublication publication = publication(mapper, 1, "", 0,
                Instant.now().minusSeconds(1), List.of(active(snapshot)), List.of(authority));
        MockMvc mvc = mvc(service);

        mvc.perform(post("/api/integration/evidence-keys/trust-publications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(publication))
                        .header("Authorization", "Bearer trust-admin-token")
                        .header("X-Purpose", "EVIDENCE_TRUST_ADMIN")
                        .header("X-Correlation-Id", "trust-publish-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payloadKind")
                        .value("EVIDENCE_KEY_SET_TRUST_PUBLICATION"))
                .andExpect(jsonPath("$.payload.sequence").value(1))
                .andExpect(jsonPath("$.payload.publicationFingerprint")
                        .value(publication.publicationFingerprint()));

        mvc.perform(get("/api/integration/evidence-keys/trust-bundle")
                        .queryParam("afterSequence", "0")
                        .queryParam("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payloadKind").value("EVIDENCE_KEY_SET_TRUST_BUNDLE"))
                .andExpect(jsonPath("$.payload.throughSequence").value(1))
                .andExpect(jsonPath("$.payload.highWaterSequence").value(1))
                .andExpect(jsonPath("$.payload.hasMore").value(false))
                .andExpect(jsonPath("$.payload.publications.length()").value(1))
                .andExpect(jsonPath("$.payload.keySet.snapshotFingerprint").value(snapshot));
    }

    private static MockMvc mvc(ToolStudioIntegrationService service) {
        IntegrationWorkloadIdentity identity = new IntegrationWorkloadIdentity(
                "security-automation", "tenant-a", "security", "tool-studio", "prod", "",
                "WORKLOAD", "trust-publisher", "", Set.of("EVIDENCE_TRUST_ADMIN"),
                Instant.MAX, true);
        IntegrationRequestAuthenticator authenticator = new IntegrationRequestAuthenticator(
                new StaticBearerIntegrationIdentityResolver("trust-admin-token", identity, false),
                new RecordingIntegrationAccessAuditRepository());
        return MockMvcBuilders.standaloneSetup(
                        new ToolStudioIntegrationController(service, null, authenticator))
                .setControllerAdvice(new IntegrationProblemHandler())
                .build();
    }
}
