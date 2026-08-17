package com.leanowtech.bloge.gateway.capabilitystudio;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;

class CapabilityStudioDemoControllerTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final CapabilityStudioGoldenDemoPack pack = new CapabilityStudioGoldenDemoPackLoader().load(mapper);
    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new CapabilityStudioDemoController(pack))
            .build();

    @Test
    void projectsBusinessContractSummariesAndScenarioMetadataWithoutMaterialPayload() throws Exception {
        String response = mvc.perform(get("/api/capability-studio/demo-pack"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cardinality.api").value(4))
                .andExpect(jsonPath("$.cardinality.feature").value(1))
                .andExpect(jsonPath("$.cardinality.tool").value(1))
                .andExpect(jsonPath("$.cardinality.scenarios").value(9))
                .andExpect(jsonPath("$.displayName").value("取消费用争议处理"))
                .andExpect(jsonPath("$.acceptanceStatus").value("NO_GO"))
                .andExpect(jsonPath("$.apiCapabilities[0].contract.inputs[0].label").value("订单 ID"))
                .andExpect(jsonPath("$.apiCapabilities[0].contract.errors[0].retryable").value(false))
                .andExpect(jsonPath("$.scenarios[0].source.displayName").isNotEmpty())
                .andExpect(jsonPath("$.scenarios[0].oracle.summary").isNotEmpty())
                .andExpect(jsonPath("$.scenarios[0].lifecycle").value("ACTIVE"))
                .andExpect(jsonPath("$.scenarios[0].applicableContractCount").value(1))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(response).doesNotContain("payload", "fixture", "secret", "customerName", "phoneNumber");
    }

    @Test
    void exposesNoGoBaselineAndAllGatesStartNotRun() throws Exception {
        String response = mvc.perform(get("/api/capability-studio/acceptance-baseline"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NO_GO"))
                .andExpect(jsonPath("$.cardinality.scenarios").value(9))
                .andExpect(jsonPath("$.gates").isArray())
                .andExpect(jsonPath("$.gates.length()").value(10))
                .andExpect(jsonPath("$.gates[0].id").value("GP-01"))
                .andExpect(jsonPath("$.gates[0].status").value("NOT_RUN"))
                .andExpect(jsonPath("$.gates[9].id").value("GP-10"))
                .andExpect(jsonPath("$.gates[9].status").value("NOT_RUN"))
                .andExpect(jsonPath("$.isolationIntent.realExternalCallCount").doesNotExist())
                .andExpect(jsonPath("$.isolationIntent.evidenceStatus").value("NOT_RUN"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(response).doesNotContain("payload", "fixture", "secret");
    }
}
