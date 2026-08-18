package com.leanowtech.bloge.gateway.capabilitystudio;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationAccessAuditRecord;
import com.leanowtech.bloge.gateway.integration.IntegrationAccessAuditRepository;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemHandler;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationWorkloadIdentity;
import com.leanowtech.bloge.gateway.integration.StaticBearerIntegrationIdentityResolver;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CapabilityStudioDemoControllerTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final CapabilityStudioGoldenDemoPack pack = new CapabilityStudioGoldenDemoPackLoader().load(mapper);
    private CapabilityStudioTutorialBranchAuthority authority;
    private MockMvc mvc;
    private MockMvc publicMvc;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:capability-studio-controller-" + System.nanoTime()
                + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        CapabilityStudioTutorialBranchRepository repository =
                new CapabilityStudioTutorialBranchRepository(jdbc);
        repository.init();
        authority = new CapabilityStudioTutorialBranchAuthority(
                        repository, pack, mapper,
                        new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
        mvc = MockMvcBuilders
                .standaloneSetup(new CapabilityStudioDemoController(
                        pack, authority, authenticator("confidential-token", "CONFIDENTIAL")))
                .setControllerAdvice(new IntegrationProblemHandler())
                .build();
        publicMvc = MockMvcBuilders
                .standaloneSetup(new CapabilityStudioDemoController(
                        pack, authority, authenticator("public-token", "PUBLIC")))
                .setControllerAdvice(new IntegrationProblemHandler())
                .build();
    }

    @Test
    void sharedIntegrationProblemAdviceIncludesCapabilityStudioController() {
        RestControllerAdvice advice = IntegrationProblemHandler.class
                .getAnnotation(RestControllerAdvice.class);

        assertThat(advice).isNotNull();
        assertThat(advice.assignableTypes()).contains(CapabilityStudioDemoController.class);
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
    void postsPayloadFreeGovernedBaselineProjection() throws Exception {
        CapabilityStudioGovernedBaselineService baseline =
                mock(CapabilityStudioGovernedBaselineService.class);
        when(baseline.run()).thenReturn(passedGovernedBaseline());
        MockMvc governedMvc = MockMvcBuilders
                .standaloneSetup(new CapabilityStudioDemoController(
                        pack, authority, baseline,
                        authenticator("confidential-token", "CONFIDENTIAL")))
                .setControllerAdvice(new IntegrationProblemHandler())
                .build();

        String response = governedMvc.perform(post("/api/capability-studio/governed-baseline"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value(
                        "resource-gateway.capability-studio.governed-baseline.v3"))
                .andExpect(jsonPath("$.evidenceKind").value("DEVELOPMENT_TEST_OWNED"))
                .andExpect(jsonPath("$.evidenceClass").value("CERTIFIABLE"))
                .andExpect(jsonPath("$.verificationLevel").value("DEVELOPMENT_VERIFIED"))
                .andExpect(jsonPath("$.status").value("PASSED"))
                .andExpect(jsonPath("$.caseCount").value(9))
                .andExpect(jsonPath("$.roundCount").value(3))
                .andExpect(jsonPath("$.suiteRunCount").value(3))
                .andExpect(jsonPath("$.childRunCount").value(27))
                .andExpect(jsonPath("$.oraclePassCount").value(9))
                .andExpect(jsonPath("$.businessCheckCount").value(27))
                .andExpect(jsonPath("$.businessCheckPassCount").value(27))
                .andExpect(jsonPath("$.realExternalCallCount").value(0))
                .andExpect(jsonPath("$.rounds", hasSize(3)))
                .andExpect(jsonPath("$.cases", hasSize(9)))
                .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("payload", "input", "output", "secret");
    }

    @Test
    void authenticatesBeforePassingTheExactCallerContextToEvidenceService() throws Exception {
        CapabilityStudioGovernedRunEvidenceService evidence =
                mock(CapabilityStudioGovernedRunEvidenceService.class);
        IntegrationRequestAuthenticator authenticator = mock(IntegrationRequestAuthenticator.class);
        com.leanowtech.bloge.gateway.integration.IntegrationRequestContext caller =
                new com.leanowtech.bloge.gateway.integration.IntegrationRequestContext(
                        "tenant", "org", "project", "test", "local", "WORKLOAD", "caller",
                        "", "CAPABILITY_STUDIO_REHEARSAL", "corr", Set.of(), "PUBLIC", "");
        when(authenticator.authenticate(any(), eq(IntegrationOperation.CAPABILITY_STUDIO_EVIDENCE_READ)))
                .thenReturn(caller);
        when(evidence.read("run-1", "case-1", caller)).thenReturn(emptyEvidenceProjection());

        evidenceMvc(evidence, authenticator).perform(get(
                        "/api/capability-studio/governed-runs/run-1/evidence")
                .queryParam("expectedCaseId", "case-1")
                .header(HttpHeaders.AUTHORIZATION, "Bearer ignored")
                .header("X-Purpose", "CAPABILITY_STUDIO_REHEARSAL"))
                .andExpect(status().isOk());

        verify(authenticator).authenticate(any(), eq(IntegrationOperation.CAPABILITY_STUDIO_EVIDENCE_READ));
        verify(evidence).read("run-1", "case-1", caller);
    }

    @Test
    void missingOrInvalidAuthAndPurposeAreRejectedBeforeEvidenceService() throws Exception {
        CapabilityStudioGovernedRunEvidenceService evidence =
                mock(CapabilityStudioGovernedRunEvidenceService.class);

        mvcForEvidence(evidence, authenticator("confidential-token", "CONFIDENTIAL"))
                .perform(get("/api/capability-studio/governed-runs/run-1/evidence")
                        .header("X-Purpose", "CAPABILITY_STUDIO_REHEARSAL"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(evidence);

        mvcForEvidence(evidence, authenticator("confidential-token", "CONFIDENTIAL"))
                .perform(get("/api/capability-studio/governed-runs/run-1/evidence")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer confidential-token")
                        .header("X-Purpose", "TEST_EXECUTION"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(evidence);
    }

    @Test
    void preservesExpectedCaseConflictAndOriginal404FromEvidenceService() throws Exception {
        CapabilityStudioGovernedRunEvidenceService evidence =
                mock(CapabilityStudioGovernedRunEvidenceService.class);
        when(evidence.read(any(), any(), any())).thenThrow(
                new IntegrationProblemException(IntegrationProblem.conflict(
                        "RG.CAPABILITY_STUDIO.GOVERNED_RUN.EXPECTED_CASE_MISMATCH",
                        "mismatch", "corr", java.util.Map.of())));
        mvcForEvidence(evidence, authenticator("confidential-token", "CONFIDENTIAL"))
                .perform(get("/api/capability-studio/governed-runs/run-1/evidence")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer confidential-token")
                        .header("X-Purpose", "CAPABILITY_STUDIO_REHEARSAL"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(
                        "RG.CAPABILITY_STUDIO.GOVERNED_RUN.EXPECTED_CASE_MISMATCH"));

        doThrow(new IntegrationProblemException(IntegrationProblem.notFound(
                        "RG.TEST.RUN_NOT_FOUND", "missing", "corr", java.util.Map.of())))
                .when(evidence).read(any(), any(), any());
        mvcForEvidence(evidence, authenticator("confidential-token", "CONFIDENTIAL"))
                .perform(get("/api/capability-studio/governed-runs/missing/evidence")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer confidential-token")
                        .header("X-Purpose", "CAPABILITY_STUDIO_REHEARSAL"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RG.TEST.RUN_NOT_FOUND"));
    }

    private MockMvc evidenceMvc(CapabilityStudioGovernedRunEvidenceService evidence,
                                IntegrationRequestAuthenticator authenticator) {
        return MockMvcBuilders.standaloneSetup(new CapabilityStudioDemoController(
                        pack, authority, evidence, authenticator))
                .setControllerAdvice(new IntegrationProblemHandler())
                .build();
    }

    private MockMvc mvcForEvidence(CapabilityStudioGovernedRunEvidenceService evidence,
                                   IntegrationRequestAuthenticator authenticator) {
        return evidenceMvc(evidence, authenticator);
    }

    private static CapabilityStudioGovernedRunEvidenceProjection emptyEvidenceProjection() {
        return new CapabilityStudioGovernedRunEvidenceProjection(
                CapabilityStudioGovernedRunEvidenceProjection.SCHEMA_VERSION,
                CapabilityStudioGovernedRunEvidenceProjection.EXACT_VERIFIED,
                "baseline", "sha256:" + "a".repeat(64), null, null, null, null, null, null,
                null, null, null, null, null);
    }

    private static CapabilityStudioGovernedBaselineProjection passedGovernedBaseline() {
        String fingerprintA = "sha256:" + "a".repeat(64);
        String fingerprintB = "sha256:" + "b".repeat(64);
        String fingerprintC = "sha256:" + "c".repeat(64);
        java.util.List<CapabilityStudioGovernedBaselineProjection.Round> rounds =
                java.util.stream.IntStream.rangeClosed(1, 3)
                        .mapToObj(round -> new CapabilityStudioGovernedBaselineProjection.Round(
                                round, "suite-run-" + round, fingerprintA, "PASSED", 9))
                        .toList();
        java.util.List<String> caseIds = java.util.List.of(
                "case-city-policy-missing",
                "case-compensation-history-empty",
                "case-compensation-history-timeout",
                "case-driver-responsible",
                "case-duplicate-cancellation",
                "case-forbidden-write-effect",
                "case-policy-revision-regression",
                "case-rider-not-responsible",
                "case-standard-cancellation-fee");
        java.util.List<CapabilityStudioGovernedBaselineProjection.CaseProjection> cases =
                java.util.stream.IntStream.range(0, caseIds.size())
                        .mapToObj(caseIndex -> new CapabilityStudioGovernedBaselineProjection.CaseProjection(
                                caseIds.get(caseIndex),
                                "oracle-" + caseIds.get(caseIndex).substring("case-".length()),
                                "PASS",
                                fingerprintC,
                                3,
                                3,
                                18,
                                18,
                                java.util.List.of(
                                        "BUSINESS_ASSERTION_PASSED",
                                        "SEMANTIC_RESULT_STABLE"),
                                java.util.stream.IntStream.rangeClosed(1, 3)
                                        .mapToObj(round -> new CapabilityStudioGovernedBaselineProjection.CaseRound(
                                                round, "child-run-" + caseIndex + "-" + round,
                                                "PASSED", "fixture-bundle-" + caseIndex, 1,
                                                fingerprintB, fingerprintA, fingerprintC,
                                                1, 1, 6, 6))
                                        .toList()))
                        .toList();
        return new CapabilityStudioGovernedBaselineProjection(
                CapabilityStudioGovernedBaselineProjection.SCHEMA_VERSION,
                CapabilityStudioGovernedBaselineProjection.EVIDENCE_KIND,
                "capability-studio-governed-9x3-v1",
                CapabilityStudioGovernedBaselineProjection.PASSED,
                CapabilityStudioGovernedBaselineProjection.VERIFICATION_SCOPE,
                CapabilityStudioGovernedBaselineProjection.RELEASE_GATE_STATUS,
                CapabilityStudioGovernedBaselineProjection.DEVELOPMENT_VERIFIED,
                CapabilityStudioGovernedBaselineProjection.CERTIFIABLE,
                9, 3, 3, 27, 9, 27, 27, 0,
                fingerprintA, fingerprintB, fingerprintC,
                null,
                null,
                new CapabilityStudioGovernedBaselineProjection.Publication(
                        fingerprintA,
                        new CapabilityStudioGovernedBaselineProjection.SuiteRef(
                                "TEST_SUITE", "suite-demo", 1, fingerprintB),
                        9),
                rounds,
                cases,
                java.util.List.of(
                        "IMMUTABLE_RELEASE_CANDIDATE_NOT_BOUND",
                        "RUNTIME_ENVIRONMENT_NOT_ATTESTED",
                        "DEPLOYMENT_EGRESS_NOT_OBSERVED",
                        "OWNER_SIGNOFF_NOT_PRESENT"),
                java.util.List.of());
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
    void exposesTheScenarioDatasetThroughTheInjectedPayloadFreeProjector() throws Exception {
        String response = mvc.perform(get("/api/capability-studio/scenario-dataset"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value(
                        "resource-gateway.capability-studio.scenario-dataset.v1"))
                .andExpect(jsonPath("$.datasetRef.kind").value("DATASET"))
                .andExpect(jsonPath("$.datasetRef.scope.tenantId").value("demo-tenant"))
                .andExpect(jsonPath("$.datasetRef.scope.organizationId").value("customer-service"))
                .andExpect(jsonPath("$.datasetRef.scope.projectId").value("capability-studio"))
                .andExpect(jsonPath("$.datasetRef.scope.environmentId").value("test"))
                .andExpect(jsonPath("$.datasetRef.scope.region").value("local"))
                .andExpect(jsonPath("$.targetRef.kind").value("TOOL"))
                .andExpect(jsonPath("$.lifecycle").value("REVIEW_READY"))
                .andExpect(jsonPath("$.quality.status").value("BLOCKED"))
                .andExpect(jsonPath("$.cases.length()").value(9))
                .andExpect(jsonPath("$.cases[0].qualityState").value("DESIGNED_NOT_RUN"))
                .andExpect(jsonPath("$.cases[0].behaviorProfiles[0].behaviorRef.kind")
                        .value("BEHAVIOR_PROFILE"))
                .andReturn().getResponse().getContentAsString();

        assertThat(response).doesNotContain("payload", "fixture", "mock", "replay", "material");
    }

    @Test
    void exposesTimeoutFeatureRehearsalFromTheRealTraceWithoutPayloadByDefault() throws Exception {
        String response = mvc.perform(authenticatedFeatureRehearsal(
                        "case-compensation-history-timeout"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value(
                        "resource-gateway.capability-studio.feature-rehearsal.v1"))
                .andExpect(jsonPath("$.scenario.id").value(
                        "case-compensation-history-timeout"))
                .andExpect(jsonPath("$.graph.id").value(
                        "feature-cancellation-dispute-context"))
                .andExpect(jsonPath("$.graph.operators").doesNotExist())
                .andExpect(jsonPath("$.run.status").value("PASSED"))
                .andExpect(jsonPath("$.run.realExternalCallCount").value(0))
                .andExpect(jsonPath("$.run.bindingMode").value(
                        "FIXTURE_CONTROLLED_NON_PRODUCTION"))
                .andExpect(jsonPath("$.dataLens.permissionMode").value("STRUCTURE_ONLY"))
                .andExpect(jsonPath("$.dataLens.nodes.length()").value(6))
                .andExpect(jsonPath("$.dataLens.edges.length()").value(5))
                .andExpect(jsonPath("$.dataLens.nodes[?(@.nodeId == 'compensationHistoryLookup')].status")
                        .value("MOCKED"))
                .andExpect(jsonPath("$.dataLens.nodes[?(@.nodeId == 'compensationHistoryLookup')].fallbackStatus")
                        .value("FALLBACK"))
                .andExpect(jsonPath("$.dataLens.nodes[?(@.nodeId == 'compensationHistoryLookup')].attempts[?(@.status == 'TIMEOUT')].errorCode")
                        .value("COMPENSATION_HISTORY_TIMEOUT"))
                .andExpect(jsonPath("$.dataLens.nodes[0].input").isEmpty())
                .andExpect(jsonPath("$.dataLens.nodes[0].output").isEmpty())
                .andReturn().getResponse().getContentAsString();

        assertThat(response)
                .doesNotContain("DEMO-ORDER-20260818-001", "fallbackToReal")
                .contains("sha256:");

        mvc.perform(authenticatedFeatureRehearsal("case-compensation-history-timeout")
                        .queryParam("permission", "PAYLOAD_VISIBLE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.run.status").value("PASSED"))
                .andExpect(jsonPath("$.dataLens.nodes[?(@.nodeId == 'cancellationDecision')].output.action")
                        .value("MANUAL_REVIEW"))
                .andExpect(jsonPath("$.dataLens.nodes[?(@.nodeId == 'cancellationDecision')].output.informationGap")
                        .value("COMPENSATION_HISTORY_TIMEOUT"));
    }

    @Test
    void revealsOnlyControlledDemoPayloadWhenTheExplicitPermissionIsRequested() throws Exception {
        mvc.perform(authenticatedFeatureRehearsal("case-standard-cancellation-fee")
                        .queryParam("permission", "PAYLOAD_VISIBLE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.run.status").value("PASSED"))
                .andExpect(jsonPath("$.run.realExternalCallCount").value(0))
                .andExpect(jsonPath("$.dataLens.permissionMode").value("PAYLOAD_VISIBLE"))
                .andExpect(jsonPath("$.dataLens.nodes[?(@.nodeId == 'orderLookup')].input.params.orderId")
                        .value("DEMO-ORDER-20260818-001"));
    }

    @Test
    void payloadQueryAndForgedClearanceCannotAuthorizeWithoutBearerAuthentication() throws Exception {
        mvc.perform(get("/api/capability-studio/feature-rehearsal")
                        .queryParam("caseId", "case-standard-cancellation-fee")
                        .queryParam("permission", "PAYLOAD_VISIBLE")
                        .header("X-Purpose", "CAPABILITY_STUDIO_REHEARSAL")
                        .header("X-Clearance", "CONFIDENTIAL"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("RG.INTEGRATION.AUTHENTICATION_REQUIRED"))
                .andExpect(jsonPath("$.title").isNotEmpty())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header().string(HttpHeaders.WWW_AUTHENTICATE,
                                "Bearer realm=\"resource-gateway-integration\""))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header().string(HttpHeaders.CACHE_CONTROL,
                                org.hamcrest.Matchers.containsString("no-store")));
    }

    @Test
    void missingAuthenticationFailsClosedForStructureOnly() throws Exception {
        mvc.perform(get("/api/capability-studio/feature-rehearsal")
                        .queryParam("caseId", "case-standard-cancellation-fee")
                        .queryParam("permission", "STRUCTURE_ONLY")
                        .header("X-Purpose", "CAPABILITY_STUDIO_REHEARSAL"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("RG.INTEGRATION.AUTHENTICATION_REQUIRED"));
    }

    @Test
    void invalidBearerFailsClosed() throws Exception {
        mvc.perform(get("/api/capability-studio/feature-rehearsal")
                        .queryParam("caseId", "case-standard-cancellation-fee")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer invalid-token")
                        .header("X-Purpose", "CAPABILITY_STUDIO_REHEARSAL"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("RG.INTEGRATION.AUTHENTICATION_FAILED"));
    }

    @Test
    void missingPurposeFailsClosedForAuthenticatedIdentity() throws Exception {
        mvc.perform(get("/api/capability-studio/feature-rehearsal")
                        .queryParam("caseId", "case-standard-cancellation-fee")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer confidential-token"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RG.INTEGRATION.PURPOSE_REQUIRED"));
    }

    @Test
    void publicIdentityCanUseStructureOnlyWithoutPayloadValues() throws Exception {
        String response = publicMvc.perform(publicFeatureRehearsal("STRUCTURE_ONLY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dataLens.permissionMode").value("STRUCTURE_ONLY"))
                .andExpect(jsonPath("$.dataLens.nodes[0].input").isEmpty())
                .andExpect(jsonPath("$.dataLens.nodes[0].output").isEmpty())
                .andReturn().getResponse().getContentAsString();

        assertThat(response).doesNotContain("DEMO-ORDER-20260818-001");
    }

    @Test
    void publicIdentityReceivesExplicitForbiddenForPayloadView() throws Exception {
        publicMvc.perform(publicFeatureRehearsal("PAYLOAD_VISIBLE"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(
                        "RG.CAPABILITY_STUDIO.PAYLOAD_CLEARANCE_REQUIRED"))
                .andExpect(jsonPath("$.details.requiredClearance").value("CONFIDENTIAL"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header().string(HttpHeaders.CACHE_CONTROL,
                                org.hamcrest.Matchers.containsString("no-store")));
    }

    @Test
    void forgedClearanceCannotElevatePublicIdentity() throws Exception {
        publicMvc.perform(publicFeatureRehearsal("PAYLOAD_VISIBLE")
                        .header("X-Clearance", "CONFIDENTIAL"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("RG.INTEGRATION.IDENTITY_CLAIM_MISMATCH"))
                .andExpect(jsonPath("$.details.X-Clearance").value(
                        "does-not-match-verified-identity"));
    }

    @Test
    void insufficientPayloadClearanceIsRejectedBeforeTheFeatureGraphRuns() throws Exception {
        CapabilityStudioFeatureRehearsalService rehearsal =
                mock(CapabilityStudioFeatureRehearsalService.class);
        MockMvc guardedMvc = guardedFeatureMvc(
                rehearsal, authenticator("public-token", "PUBLIC"));

        guardedMvc.perform(publicFeatureRehearsal("PAYLOAD_VISIBLE"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(
                        "RG.CAPABILITY_STUDIO.PAYLOAD_CLEARANCE_REQUIRED"));

        verifyNoInteractions(rehearsal);
    }

    @Test
    void unavailableSecurityAuditFailsClosedBeforeTheFeatureGraphRuns() throws Exception {
        CapabilityStudioFeatureRehearsalService rehearsal =
                mock(CapabilityStudioFeatureRehearsalService.class);
        IntegrationRequestAuthenticator authenticator = new IntegrationRequestAuthenticator(
                new StaticBearerIntegrationIdentityResolver(
                        "confidential-token", identity("CONFIDENTIAL"), true),
                new FailingAudit());
        MockMvc guardedMvc = guardedFeatureMvc(rehearsal, authenticator);

        guardedMvc.perform(authenticatedFeatureRehearsal("case-standard-cancellation-fee"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(
                        "RG.INTEGRATION.SECURITY_AUDIT_UNAVAILABLE"));

        verifyNoInteractions(rehearsal);
    }

    @Test
    void exposesPayloadFreeDevelopmentBaselineWithNineCasesAndThreeRounds() throws Exception {
        String response = mvc.perform(get("/api/capability-studio/feature-rehearsal-baseline"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value(
                        "resource-gateway.capability-studio.feature-rehearsal-baseline.v1"))
                .andExpect(jsonPath("$.evidenceKind").value("DEVELOPMENT_TEST_OWNED"))
                .andExpect(jsonPath("$.status").value("PASSED"))
                .andExpect(jsonPath("$.caseCount").value(9))
                .andExpect(jsonPath("$.roundCount").value(3))
                .andExpect(jsonPath("$.runCount").value(27))
                .andExpect(jsonPath("$.realExternalCallCount").value(0))
                .andExpect(jsonPath("$.cases.length()").value(9))
                .andExpect(jsonPath("$.cases[0].rounds.length()").value(3))
                .andExpect(jsonPath("$.cases[?(@.oracle.status == 'PASS')]").value(hasSize(9)))
                .andExpect(jsonPath("$.diagnostics.length()").value(0))
                .andReturn().getResponse().getContentAsString();

        assertThat(response).doesNotContain(
                "\"input\"", "\"output\"", "payload", "fixture", "mock", "fallbackToReal");
    }

    @Test
    void rejectsUnknownFeatureScenarioWithARecoverableBusiness404() throws Exception {
        mvc.perform(authenticatedFeatureRehearsal("case-does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(
                        "RG.CAPABILITY_STUDIO.FEATURE_REHEARSAL_NOT_FOUND"))
                .andExpect(jsonPath("$.field").value("caseId"))
                .andExpect(jsonPath("$.recoveryAction").isNotEmpty());
    }

    private static IntegrationRequestAuthenticator authenticator(
            String token, String clearance) {
        return new IntegrationRequestAuthenticator(
                new StaticBearerIntegrationIdentityResolver(token, identity(clearance), true),
                new RecordingAudit());
    }

    private static IntegrationWorkloadIdentity identity(String clearance) {
        return new IntegrationWorkloadIdentity(
                "capability-studio-test", "tenant-a", "org-a", "project-a", "test", "local",
                "WORKLOAD", "capability-studio-test", "",
                Set.of("CAPABILITY_STUDIO_REHEARSAL"), Instant.MAX, true,
                Set.of(), clearance, "", Instant.MAX);
    }

    private MockMvc guardedFeatureMvc(
            CapabilityStudioFeatureRehearsalService rehearsal,
            IntegrationRequestAuthenticator authenticator) {
        return MockMvcBuilders.standaloneSetup(new CapabilityStudioDemoController(
                        pack,
                        authority,
                        new CapabilityStudioScenarioDatasetProjector(pack),
                        rehearsal,
                        mock(CapabilityStudioFeatureRehearsalBaselineService.class),
                        mock(CapabilityStudioGovernedBaselineService.class),
                        authenticator))
                .setControllerAdvice(new IntegrationProblemHandler())
                .build();
    }

    private static MockHttpServletRequestBuilder authenticatedFeatureRehearsal(String caseId) {
        return get("/api/capability-studio/feature-rehearsal")
                .queryParam("caseId", caseId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer confidential-token")
                .header("X-Purpose", "CAPABILITY_STUDIO_REHEARSAL");
    }

    private static MockHttpServletRequestBuilder publicFeatureRehearsal(String permission) {
        return get("/api/capability-studio/feature-rehearsal")
                .queryParam("caseId", "case-standard-cancellation-fee")
                .queryParam("permission", permission)
                .header(HttpHeaders.AUTHORIZATION, "Bearer public-token")
                .header("X-Purpose", "CAPABILITY_STUDIO_REHEARSAL");
    }

    private static final class RecordingAudit implements IntegrationAccessAuditRepository {
        private final List<IntegrationAccessAuditRecord> records = new ArrayList<>();

        @Override
        public IntegrationAccessAuditRecord append(IntegrationAccessAuditRecord record) {
            IntegrationAccessAuditRecord stored = record.withSequence(records.size() + 1L);
            records.add(stored);
            return stored;
        }

        @Override
        public List<IntegrationAccessAuditRecord> recent(int limit) {
            return List.copyOf(records);
        }
    }

    private static final class FailingAudit implements IntegrationAccessAuditRepository {
        @Override
        public IntegrationAccessAuditRecord append(IntegrationAccessAuditRecord record) {
            throw new IllegalStateException("audit unavailable");
        }

        @Override
        public List<IntegrationAccessAuditRecord> recent(int limit) {
            return List.of();
        }
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
