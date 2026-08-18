package com.leanowtech.bloge.gateway.capabilitystudio;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CapabilityStudioScenarioQualityImpactControllerTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final CapabilityStudioGoldenDemoPack pack =
            new CapabilityStudioGoldenDemoPackLoader().load(mapper);

    @Test
    void exposesQualityImpactThroughTheDedicatedAuthorizedOperation() throws Exception {
        IntegrationRequestAuthenticator authenticator = mock(IntegrationRequestAuthenticator.class);
        when(authenticator.authenticate(any(), eq(IntegrationOperation.CAPABILITY_STUDIO_SCENARIO_QUALITY_READ)))
                .thenReturn(new IntegrationRequestContext(
                        "tenant-a", "org-a", "project-a", "test", "local", "WORKLOAD",
                        "quality-reader", "", "CAPABILITY_STUDIO_REHEARSAL", "corr-quality"));
        CapabilityStudioTutorialBranchAuthority authority = mock(CapabilityStudioTutorialBranchAuthority.class);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                        new CapabilityStudioDemoController(pack, authority, authenticator))
                .build();

        mvc.perform(get("/api/capability-studio/scenario-dataset/quality-impact")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer quality-token")
                        .header("X-Purpose", "CAPABILITY_STUDIO_REHEARSAL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value(
                        "resource-gateway.capability-studio.scenario-quality-impact.v1"))
                .andExpect(jsonPath("$.admission.status").value("BLOCKED"))
                .andExpect(jsonPath("$.admission.activeCaseCount").value(0))
                .andExpect(jsonPath("$.admission.draftCaseCount").value(9))
                .andExpect(jsonPath("$.quality.freshnessStatus").value("UNVERIFIED"))
                .andExpect(jsonPath("$.summary.impactedAssetCount").value(9))
                .andExpect(jsonPath("$.impactGraph.nodes.length()").value(37));

        verify(authenticator).authenticate(any(), eq(IntegrationOperation.CAPABILITY_STUDIO_SCENARIO_QUALITY_READ));
    }
}
