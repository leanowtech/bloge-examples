package com.leanowtech.bloge.gateway.capabilitystudio;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;

class CapabilityStudioDemoControllerTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final CapabilityStudioGoldenDemoPack pack = new CapabilityStudioGoldenDemoPackLoader().load(mapper);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:capability-studio-controller-" + System.nanoTime()
                + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        CapabilityStudioTutorialBranchRepository repository =
                new CapabilityStudioTutorialBranchRepository(jdbc);
        repository.init();
        CapabilityStudioTutorialBranchAuthority authority =
                new CapabilityStudioTutorialBranchAuthority(
                        repository, pack, mapper,
                        new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
        mvc = MockMvcBuilders
                .standaloneSetup(new CapabilityStudioDemoController(pack, authority))
                .build();
    }

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

    @Test
    void getsTheFrozenTutorialBranchProjection() throws Exception {
        mvc.perform(get("/api/capability-studio/tutorial-branch"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.branchId").value("tutorial-compensation-history-timeout"))
                .andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.fingerprint").value(
                        "sha256:cb87bd2157694f2a827acae1b107eff5d9bba48aef296c1be53a394aa934a070"))
                .andExpect(jsonPath("$.canonicalBaselineFingerprint").value(
                        "sha256:8abed67545bd784929162a5639bc91f587a50d195e0fcca9cab5f47b1cda9544"))
                .andExpect(jsonPath("$.behavior.dependencyId").value("api-compensation-history"))
                .andExpect(jsonPath("$.behavior.dependencyName").value("补偿历史查询"))
                .andExpect(jsonPath("$.behavior.condition").value("历史补偿查询超过超时阈值"))
                .andExpect(jsonPath("$.behavior.behavior").value("TIMEOUT"))
                .andExpect(jsonPath("$.behavior.durationMs").value(700));
    }

    @Test
    void updatesTheBranchWithARealContentFingerprintAndKeepsCanonicalBaseline() throws Exception {
        String response = mvc.perform(put("/api/capability-studio/tutorial-branch/behaviors/compensation-history")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "condition": "历史补偿查询超过 700ms 未返回",
                                  "behavior": "TIMEOUT",
                                  "durationMs": 1200,
                                  "expectedRevision": 1
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.branchId").value("tutorial-compensation-history-timeout"))
                .andExpect(jsonPath("$.revision").value(2))
                .andExpect(jsonPath("$.fingerprint").value(org.hamcrest.Matchers.matchesPattern(
                        "sha256:[0-9a-f]{64}")))
                .andExpect(jsonPath("$.canonicalBaselineFingerprint").value(
                        "sha256:8abed67545bd784929162a5639bc91f587a50d195e0fcca9cab5f47b1cda9544"))
                .andExpect(jsonPath("$.behavior.condition").value("历史补偿查询超过 700ms 未返回"))
                .andExpect(jsonPath("$.behavior.behavior").value("TIMEOUT"))
                .andExpect(jsonPath("$.behavior.durationMs").value(1200))
                .andReturn().getResponse().getContentAsString();

        assertThat(response).doesNotContain("payload", "mock", "fixture");
    }

    @Test
    void repeatedIdenticalContentIsIdempotent() throws Exception {
        String request = """
                {"condition":"历史补偿查询超过 700ms 未返回","behavior":"TIMEOUT","durationMs":1200,"expectedRevision":1}
                """;
        String first = mvc.perform(put("/api/capability-studio/tutorial-branch/behaviors/compensation-history")
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String fingerprint = mapper.readTree(first).path("fingerprint").asText();

        mvc.perform(put("/api/capability-studio/tutorial-branch/behaviors/compensation-history")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request.replace("\"expectedRevision\":1", "\"expectedRevision\":2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(2))
                .andExpect(jsonPath("$.fingerprint").value(fingerprint));
    }

    @Test
    void rejectsStaleRevisionWithoutLastWriteWins() throws Exception {
        mvc.perform(put("/api/capability-studio/tutorial-branch/behaviors/compensation-history")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{" +
                                "\"condition\":\"新条件\",\"behavior\":\"TIMEOUT\","
                                        + "\"durationMs\":900,\"expectedRevision\":1}"))
                .andExpect(status().isOk());

        mvc.perform(put("/api/capability-studio/tutorial-branch/behaviors/compensation-history")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{" +
                                "\"condition\":\"过期条件\",\"behavior\":\"TIMEOUT\","
                                        + "\"durationMs\":901,\"expectedRevision\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(
                        "RG.CAPABILITY_STUDIO.REVISION_CONFLICT"))
                .andExpect(jsonPath("$.whatHappened").isNotEmpty())
                .andExpect(jsonPath("$.impact").isNotEmpty())
                .andExpect(jsonPath("$.recoveryAction").isNotEmpty())
                .andExpect(jsonPath("$.field").value("expectedRevision"));

        mvc.perform(get("/api/capability-studio/tutorial-branch"))
                .andExpect(jsonPath("$.behavior.condition").value("新条件"))
                .andExpect(jsonPath("$.behavior.durationMs").value(900));
    }

    @Test
    void rejectsIllegalAndUnknownBusinessFields() throws Exception {
        mvc.perform(put("/api/capability-studio/tutorial-branch/behaviors/compensation-history")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{" +
                                "\"condition\":\"条件\",\"behavior\":\"RETURN\","
                                        + "\"durationMs\":900,\"expectedRevision\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(
                        "RG.CAPABILITY_STUDIO.REQUEST_INVALID"))
                .andExpect(jsonPath("$.field").value("behavior"));

        mvc.perform(put("/api/capability-studio/tutorial-branch/behaviors/compensation-history")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{" +
                                "\"condition\":\"条件\",\"behavior\":\"TIMEOUT\","
                                        + "\"durationMs\":900,\"expectedRevision\":1,"
                                        + "\"mock\":{\"raw\":true}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(
                        "RG.CAPABILITY_STUDIO.REQUEST_INVALID"))
                .andExpect(jsonPath("$.field").value("mock"));
    }

    @Test
    void rejectsOutOfRangeDurationAndOmitsAbsentErrorField() throws Exception {
        mvc.perform(put("/api/capability-studio/tutorial-branch/behaviors/compensation-history")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{" +
                                "\"condition\":\"条件\",\"behavior\":\"TIMEOUT\","
                                        + "\"durationMs\":99,\"expectedRevision\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(
                        "RG.CAPABILITY_STUDIO.REQUEST_INVALID"))
                .andExpect(jsonPath("$.field").value("durationMs"));

        String malformed = mvc.perform(put(
                        "/api/capability-studio/tutorial-branch/behaviors/compensation-history")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.whatHappened").isNotEmpty())
                .andExpect(jsonPath("$.impact").isNotEmpty())
                .andExpect(jsonPath("$.recoveryAction").isNotEmpty())
                .andExpect(jsonPath("$.field").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        assertThat(malformed).doesNotContain("\"field\":null", "payload", "fixture");
    }

    @Test
    void preflightIsIsolatedAndBindsCurrentRevisionAndFingerprint() throws Exception {
        String update = mvc.perform(put("/api/capability-studio/tutorial-branch/behaviors/compensation-history")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{" +
                                "\"condition\":\"达到业务超时阈值\",\"behavior\":\"TIMEOUT\","
                                        + "\"durationMs\":1000,\"expectedRevision\":1}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String fingerprint = mapper.readTree(update).path("fingerprint").asText();

        mvc.perform(post("/api/capability-studio/tutorial-branch/preflight"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("ISOLATED"))
                .andExpect(jsonPath("$.unresolvedDependencies").value(0))
                .andExpect(jsonPath("$.realExternalCallCount").value(0))
                .andExpect(jsonPath("$.fallbackToReal").value(false))
                .andExpect(jsonPath("$.branchId").value("tutorial-compensation-history-timeout"))
                .andExpect(jsonPath("$.revision").value(2))
                .andExpect(jsonPath("$.fingerprint").value(fingerprint));
    }
}
