package com.leanowtech.bloge.gateway.businessmirror.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.ResourceGatewayApplication;
import com.leanowtech.bloge.gateway.businessmirror.transport.PackageCompilationController;
import com.leanowtech.bloge.gateway.businessmirror.compilation.PackageCompilationFactRepository;
import com.leanowtech.bloge.gateway.businessmirror.compilation.PackageCompilationReceiptRepository;
import com.leanowtech.bloge.gateway.businessmirror.application.PackageCompilationService;
import com.leanowtech.bloge.gateway.businessmirror.migration.LegacyGraphPackageController;
import com.leanowtech.bloge.gateway.businessmirror.migration.LegacyGraphPackageProjector;

import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@SpringBootTest(
        classes = ResourceGatewayApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "gateway.seed-descriptors=true",
                "gateway.integration.identity.tenant-id=ride-hailing",
                "gateway.integration.identity.organization-id=customer-service",
                "gateway.integration.identity.project-id=cancellation",
                "gateway.integration.identity.environment-id=test",
                "gateway.integration.identity.region=sg",
                "gateway.integration.identity.actor-id=alice",
                "gateway.integration.identity.allowed-purposes=BUSINESS_MIRROR_AUTHORING",
                "spring.datasource.url=jdbc:h2:mem:business-mirror-package-wiring;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false"
        })
class BusinessMirrorPackageSpringWiringTest {
    @Autowired
    private ApplicationContext context;

    @Autowired
    private DomainCapabilityPackageAuthoringService service;

    @Autowired
    private PackageCompilationService compilationService;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void assemblesAuthenticatedApiWithATransactionalApplicationProxy() {
        assertThat(context.getBean(DomainCapabilityPackageController.class)).isNotNull();
        assertThat(context.getBean(DomainCapabilityPackageDraftRepository.class)).isNotNull();
        assertThat(context.getBean(DomainCapabilityPackageSaveReceiptRepository.class)).isNotNull();
        assertThat(context.getBean(PackageCompilationController.class)).isNotNull();
        assertThat(context.getBean(PackageCompilationFactRepository.class)).isNotNull();
        assertThat(context.getBean(PackageCompilationReceiptRepository.class)).isNotNull();
        assertThat(context.getBean(LegacyGraphPackageController.class)).isNotNull();
        assertThat(context.getBean(CapabilityProposalController.class)).isNotNull();
        assertThat(context.getBean(CapabilityProposalDraftRepository.class)).isNotNull();
        assertThat(context.getBean(CapabilityProposalSaveReceiptRepository.class)).isNotNull();
        assertThat(context.getBean(LegacyGraphPackageProjector.class).ready()).isTrue();
        assertThat(AopUtils.isAopProxy(service)).isTrue();
        assertThat(AopUtils.isAopProxy(compilationService)).isTrue();
    }

    @Test
    void persistsAndExactlyReplaysSimulationOnlyProposalThroughAuthenticatedHttp() throws Exception {
        String body = objectMapper.writeValueAsString(BusinessMirrorAuthoringFixtures.proposal(
                "trip-attribution-http-e2e", 0,
                "Rehearse cancellation attribution without the Trip Platform"));
        String key = "bm-proposal-http-e2e:create:v1";

        String firstBody = mockMvc.perform(post("/api/business-mirror/proposals")
                        .header("Authorization", "Bearer bloge-aneke-demo-token")
                        .header("X-Purpose", "BUSINESS_MIRROR_AUTHORING")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "false"))
                .andExpect(header().exists("ETag"))
                .andExpect(jsonPath("$.result.draft.revision").value(1))
                .andExpect(jsonPath("$.result.draft.proposalId")
                        .value("trip-attribution-http-e2e"))
                .andExpect(jsonPath("$.result.draft.simulationRuntimeBinding.kind")
                        .value("SIMULATION_ONLY"))
                .andExpect(jsonPath("$.result.draft.simulationRuntimeBinding.networkEgressAllowed")
                        .value(false))
                .andReturn().getResponse().getContentAsString();

        String replayBody = mockMvc.perform(post("/api/business-mirror/proposals")
                        .header("Authorization", "Bearer bloge-aneke-demo-token")
                        .header("X-Purpose", "BUSINESS_MIRROR_AUTHORING")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(replayBody)).isEqualTo(objectMapper.readTree(firstBody));

        mockMvc.perform(get("/api/business-mirror/proposals?limit=25")
                        .header("Authorization", "Bearer bloge-aneke-demo-token")
                        .header("X-Purpose", "BUSINESS_MIRROR_AUTHORING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].draft.proposalId").value(
                        org.hamcrest.Matchers.hasItem("trip-attribution-http-e2e")));
    }

    @Test
    void authenticatesPersistsExactlyReplaysAndListsThroughTheHttpBoundary() throws Exception {
        String body = objectMapper.writeValueAsString(BusinessMirrorAuthoringFixtures.draft(
                "cancellation-fee-http-e2e", 0, "Appeal eligibility depends on cancellation facts"));
        String key = "bm-package-http-e2e:create:v1";

        String firstBody = mockMvc.perform(post("/api/business-mirror/packages")
                        .header("Authorization", "Bearer bloge-aneke-demo-token")
                        .header("X-Purpose", "BUSINESS_MIRROR_AUTHORING")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "false"))
                .andExpect(header().exists("ETag"))
                .andExpect(jsonPath("$.result.draft.revision").value(1))
                .andExpect(jsonPath("$.result.draft.packageId").value("cancellation-fee-http-e2e"))
                .andReturn().getResponse().getContentAsString();

        String replayBody = mockMvc.perform(post("/api/business-mirror/packages")
                        .header("Authorization", "Bearer bloge-aneke-demo-token")
                        .header("X-Purpose", "BUSINESS_MIRROR_AUTHORING")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andReturn().getResponse().getContentAsString();

        JsonNode first = objectMapper.readTree(firstBody);
        assertThat(objectMapper.readTree(replayBody)).isEqualTo(first);

        mockMvc.perform(get("/api/business-mirror/packages?limit=25")
                        .header("Authorization", "Bearer bloge-aneke-demo-token")
                        .header("X-Purpose", "BUSINESS_MIRROR_AUTHORING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].draft.packageId").value(
                        org.hamcrest.Matchers.hasItem("cancellation-fee-http-e2e")));

        String compileBody = mockMvc.perform(post(
                        "/api/business-mirror/packages/cancellation-fee-http-e2e/compile?sourceRevision=1")
                        .header("Authorization", "Bearer bloge-aneke-demo-token")
                        .header("X-Purpose", "BUSINESS_MIRROR_AUTHORING")
                        .header("Idempotency-Key", "bm-package-http-e2e:compile:v1"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "false"))
                .andExpect(header().string("Compilation-Status", "BLOCKED"))
                .andExpect(header().exists("ETag"))
                .andExpect(jsonPath("$.compilationRevision").value(1))
                .andExpect(jsonPath("$.readiness.status").value("BLOCKED"))
                .andExpect(jsonPath("$.snapshot").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        String compileReplay = mockMvc.perform(post(
                        "/api/business-mirror/packages/cancellation-fee-http-e2e/compile?sourceRevision=1")
                        .header("Authorization", "Bearer bloge-aneke-demo-token")
                        .header("X-Purpose", "BUSINESS_MIRROR_AUTHORING")
                        .header("Idempotency-Key", "bm-package-http-e2e:compile:v1"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(compileReplay)).isEqualTo(objectMapper.readTree(compileBody));
        String authorityGeneration = objectMapper.readTree(compileBody)
                .path("authorityGeneration").asText();
        assertThat(authorityGeneration).startsWith("composite-authority-v1:").hasSize(87);

        mockMvc.perform(get(
                        "/api/business-mirror/packages/cancellation-fee-http-e2e/compilations/1")
                .header("Authorization", "Bearer bloge-aneke-demo-token")
                .header("X-Purpose", "BUSINESS_MIRROR_AUTHORING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authorityGeneration").value(authorityGeneration))
                .andExpect(jsonPath("$.readiness.status").value("BLOCKED"));
    }

    @Test
    void previewsAndIdempotentlyImportsEveryLegacyGraphWithoutInferringBusinessSemantics()
            throws Exception {
        mockMvc.perform(get("/api/business-mirror/legacy-graphs")
                        .header("Authorization", "Bearer bloge-aneke-demo-token")
                        .header("X-Purpose", "BUSINESS_MIRROR_AUTHORING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion")
                        .value("resourceGateway.legacyGraphPackageProjectionCatalog.v1"))
                .andExpect(jsonPath("$.items.length()").value(7))
                .andExpect(jsonPath("$.items[0].graphName").value("aiEnrichedSearch"))
                .andExpect(jsonPath("$.items[6].graphName").value("userDashboard"))
                .andExpect(jsonPath("$.items[0].status").value("BLOCKED"));

        String previewBody = mockMvc.perform(get(
                        "/api/business-mirror/legacy-graphs/loanDecisionPolicy")
                        .header("Authorization", "Bearer bloge-aneke-demo-token")
                        .header("X-Purpose", "BUSINESS_MIRROR_AUTHORING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.migrationMode").value("LEGACY_IMPORTED"))
                .andExpect(jsonPath("$.projectionFingerprint").value(
                        org.hamcrest.Matchers.matchesPattern("sha256:[a-f0-9]{64}")))
                .andExpect(jsonPath("$.packageDraft.packageId")
                        .value("legacy:loanDecisionPolicy"))
                .andExpect(jsonPath("$.packageDraft.revision").value(0))
                .andExpect(jsonPath("$.packageDraft.businessDefinition.domainId").value(""))
                .andExpect(jsonPath("$.packageDraft.businessDefinition.accountableOwner").value(""))
                .andExpect(jsonPath("$.packageDraft.provenance.sourceType").value("INFERRED"))
                .andExpect(jsonPath("$.packageDraft.provenance.approvedBy").value(""))
                .andExpect(jsonPath("$.discoveredTestSuiteRefs.length()").value(1))
                .andReturn().getResponse().getContentAsString();
        JsonNode preview = objectMapper.readTree(previewBody);
        JsonNode fixedFixture = objectMapper.readTree(java.nio.file.Files.readString(
                java.nio.file.Path.of("../docs/schemas/resource-gateway-business-mirror/"
                        + "loan-decision-legacy-graph-projection-v1.fixture.json")));
        assertThat(preview).isEqualTo(fixedFixture);
        assertThat(preview.path("gaps")).extracting(value -> value.path("code").asText())
                .contains("BUSINESS_DOMAIN_MISSING", "SCENARIO_INVENTORY_MISSING",
                        "SCENARIO_PACK_MISSING", "GRAPH_CONTRACT_OWNER_CONFIRMATION_MISSING",
                        "MIRROR_PLAN_MISSING", "LEGACY_PROJECTION_OWNER_APPROVAL_MISSING",
                        "DISCOVERED_TEST_SUITE_REQUIRES_SCENARIO_GOVERNANCE");

        mockMvc.perform(get("/api/business-mirror/legacy-graphs/not-installed")
                        .header("Authorization", "Bearer bloge-aneke-demo-token")
                        .header("X-Purpose", "BUSINESS_MIRROR_AUTHORING"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code")
                        .value("RG.BUSINESS_MIRROR.LEGACY_GRAPH_NOT_FOUND"));

        String key = "legacy-loan-decision-import:v1";
        String importedBody = mockMvc.perform(post(
                        "/api/business-mirror/legacy-graphs/loanDecisionPolicy/packages")
                        .header("Authorization", "Bearer bloge-aneke-demo-token")
                        .header("X-Purpose", "BUSINESS_MIRROR_AUTHORING")
                        .header("Idempotency-Key", key))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "false"))
                .andExpect(header().string("Legacy-Projection-Fingerprint",
                        preview.path("projectionFingerprint").asText()))
                .andExpect(header().string("Location",
                        "/api/business-mirror/packages/legacy:loanDecisionPolicy"))
                .andExpect(jsonPath("$.result.draft.revision").value(1))
                .andExpect(jsonPath("$.result.draft.packageId")
                        .value("legacy:loanDecisionPolicy"))
                .andReturn().getResponse().getContentAsString();

        String replayBody = mockMvc.perform(post(
                        "/api/business-mirror/legacy-graphs/loanDecisionPolicy/packages")
                        .header("Authorization", "Bearer bloge-aneke-demo-token")
                        .header("X-Purpose", "BUSINESS_MIRROR_AUTHORING")
                        .header("Idempotency-Key", key))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(replayBody))
                .isEqualTo(objectMapper.readTree(importedBody));

        mockMvc.perform(post(
                        "/api/business-mirror/packages/legacy:loanDecisionPolicy/compile"
                                + "?sourceRevision=1")
                        .header("Authorization", "Bearer bloge-aneke-demo-token")
                        .header("X-Purpose", "BUSINESS_MIRROR_AUTHORING")
                        .header("Idempotency-Key", "legacy-loan-decision-compile:v1"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Compilation-Status", "BLOCKED"))
                .andExpect(jsonPath("$.authorityGeneration").value(
                        org.hamcrest.Matchers.startsWith("composite-authority-v1:")))
                .andExpect(jsonPath("$.readiness.status").value("BLOCKED"));
    }
}
