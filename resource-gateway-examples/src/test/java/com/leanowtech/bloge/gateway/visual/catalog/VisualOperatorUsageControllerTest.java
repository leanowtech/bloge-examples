package com.leanowtech.bloge.gateway.visual.catalog;

import com.leanowtech.bloge.gateway.visual.draft.InMemoryGraphDraftRepository;
import com.leanowtech.bloge.gateway.visual.publication.InMemoryVisualGraphPublicationRepository;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests for the public visual operator usage API.
 */
class VisualOperatorUsageControllerTest {

    @Test
    void preservesResourceOperatorRefPathSegmentWithDots() throws Exception {
        OperatorUsageIndex usageIndex = new OperatorUsageIndex(
                new InMemoryGraphDraftRepository(),
                new InMemoryVisualGraphPublicationRepository(),
                VisualCatalogTestSupport.catalogWithLoanApplicantResource()
        );
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new VisualOperatorUsageController(usageIndex)).build();

        mockMvc.perform(get("/api/visual/operators/resource:loan-applicant-service.getProfile/usage"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value("bloge.visualOperatorUsage.v1"))
                .andExpect(jsonPath("$.operatorRef").value("resource:loan-applicant-service.getProfile"))
                .andExpect(jsonPath("$.currentFingerprint").isNotEmpty())
                .andExpect(jsonPath("$.drafts").isArray())
                .andExpect(jsonPath("$.publications").isArray());
    }
}
