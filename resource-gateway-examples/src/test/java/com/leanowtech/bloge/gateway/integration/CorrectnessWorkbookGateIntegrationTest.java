package com.leanowtech.bloge.gateway.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.catalog.VisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.draft.InMemoryGraphDraftRepository;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualGraphRunRepository;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphRunRecord;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphRunResponse;
import com.leanowtech.bloge.gateway.visual.testing.InMemoryVisualOperatorContractTestSuiteRepository;
import com.leanowtech.bloge.gateway.visual.testing.VisualOperatorContractTestCase;
import com.leanowtech.bloge.gateway.visual.testing.VisualOperatorContractTestSuite;
import com.leanowtech.bloge.gateway.visual.testing.VisualOperatorContractTestSuiteRequest;
import com.leanowtech.bloge.gateway.visual.testing.VisualOperatorContractTestSuiteRepository;
import com.leanowtech.bloge.gateway.visual.testing.VisualOperatorTestAssertion;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CorrectnessWorkbookGateIntegrationTest {
    private InMemoryGraphDraftRepository drafts;
    private InMemoryVisualOperatorContractTestSuiteRepository suites;
    private InMemoryVisualGraphRunRepository runs;
    private ToolStudioIntegrationService service;
    private GraphDraft draft;

    @BeforeEach
    void setUp() {
        drafts = new InMemoryGraphDraftRepository();
        suites = new InMemoryVisualOperatorContractTestSuiteRepository();
        suites.save(suite("suite-risk", "risk:score", "case-kind:golden"));
        draft = drafts.save(draft());
        runs = new InMemoryVisualGraphRunRepository(new InMemoryVisualEvidenceSigner());
        runs.create(record("run-workbook", draft));
        FixedSnapshotService snapshots = new FixedSnapshotService(suites, "risk:score");
        CorrectnessWorkbookProjectionService projection = new CorrectnessWorkbookProjectionService(suites, runs);
        service = new ToolStudioIntegrationService(drafts, null, null, runs,
                new InMemoryGovernanceGateResultRepository(), new ObjectMapper().findAndRegisterModules(),
                IntegrationIdentityResolver.unavailable(), new SideEffectReconcilerRegistry(List.of()),
                snapshots, projection);
    }

    @Test
    void exportsDeterministicSanitizedSuiteAndVerifiedEvidenceReferences() {
        CorrectnessWorkbookBundle first = service.correctnessWorkbook(
                draft.draftId(), draft.revision(), workbookContext()).payload();
        CorrectnessWorkbookBundle second = service.correctnessWorkbook(
                draft.draftId(), draft.revision(), workbookContext()).payload();

        assertThat(first).isEqualTo(second);
        assertThat(first.fingerprintVerified()).isTrue();
        assertThat(first.manifest().complete()).isTrue();
        assertThat(first.suites()).singleElement().satisfies(suite -> {
            assertThat(suite.revision()).isEqualTo(1);
            assertThat(suite.cases()).singleElement().satisfies(row -> {
                assertThat(row.caseId()).startsWith("case-");
                assertThat(row.caseKind()).isEqualTo("GOLDEN");
                assertThat(row.mappingStatus()).isEqualTo("EXPLICIT");
                assertThat(row.inputs()).containsEntry("apiToken", "[REDACTED]");
                assertThat(row.assertions()).singleElement()
                        .extracting(CorrectnessWorkbookBundle.Assertion::assertionId)
                        .asString().startsWith("assertion-");
            });
        });
        assertThat(first.evidence()).singleElement().satisfies(ref -> {
            assertThat(ref.runId()).isEqualTo("run-workbook");
            assertThat(ref.signatureStatus()).isEqualTo("VERIFIED");
            assertThat(ref.evidenceFingerprint()).startsWith("sha256:");
        });
        assertThat(first.redaction().redactedCount()).isPositive();
    }

    @Test
    void acceptsCompletePassedBasisAndMarksItStaleWhenSuiteSnapshotChanges() {
        CorrectnessWorkbookBundle workbook = service.correctnessWorkbook(
                draft.draftId(), draft.revision(), workbookContext()).payload();
        GovernanceGateResult passed = passedGate("gate-passed", workbook);

        GovernanceGateResult stored = service.submitGateResult(passed, gateContext()).payload();
        assertThat(stored.resultFingerprint()).isEqualTo(passed.resultFingerprint());
        assertThat(service.governanceGate(draft.draftId(), readContext()).payload().freshness())
                .isEqualTo("CURRENT");

        suites.save(suite("suite-risk", "risk:score", "case-kind:boundary"));

        assertThat(service.governanceGate(draft.draftId(), readContext()).payload().freshness())
                .isEqualTo("STALE");
    }

    @Test
    void rejectsStaleWorkbookMissingChecksAndForgedEvidence() {
        CorrectnessWorkbookBundle workbook = service.correctnessWorkbook(
                draft.draftId(), draft.revision(), workbookContext()).payload();
        GovernanceGateResult valid = passedGate("gate-valid", workbook);
        GovernanceGateResult.DecisionBasis basis = valid.decisionBasis();

        GovernanceGateResult staleWorkbook = withBasis("gate-stale", basisWithWorkbook(basis,
                new GovernanceGateResult.WorkbookRef("workbook-1", 1, sha("workbook"), sha("stale"))));
        assertProblem(() -> service.submitGateResult(staleWorkbook, gateContext()),
                "RG.INTEGRATION.GATE_BASIS_STALE");

        GovernanceGateResult missingCheck = withBasis("gate-missing-check", new GovernanceGateResult.DecisionBasis(
                basis.workbook(), basis.dependencySnapshotFingerprint(), basis.contractSuites(), basis.evidence(),
                basis.policy(), basis.checks().stream().filter(check -> !"OWNER_APPROVAL".equals(check.kind())).toList()));
        assertProblem(() -> service.submitGateResult(missingCheck, gateContext()),
                "RG.INTEGRATION.GATE_BASIS_INCOMPLETE");

        GovernanceGateResult forgedEvidence = withBasis("gate-forged", new GovernanceGateResult.DecisionBasis(
                basis.workbook(), basis.dependencySnapshotFingerprint(), basis.contractSuites(),
                List.of(new GovernanceGateResult.EvidenceRef("run-workbook", sha("forged"))), basis.policy(),
                basis.checks()));
        assertProblem(() -> service.submitGateResult(forgedEvidence, gateContext()),
                "RG.INTEGRATION.GATE_BASIS_STALE");
    }

    @Test
    void rejectsEvidenceFromAnotherTenantWithoutDisclosingIt() {
        CorrectnessWorkbookBundle workbook = service.correctnessWorkbook(
                draft.draftId(), draft.revision(), workbookContext()).payload();
        GovernanceGateResult.DecisionBasis basis = passedGate("gate-source", workbook).decisionBasis();
        GraphDraft foreign = draftWithScope("draft-foreign", "tenant-b");
        runs.create(record("run-foreign", foreign));
        VisualGraphRunRecord foreignRun = runs.find("run-foreign").orElseThrow();
        GovernanceGateResult crossTenant = withBasis("gate-cross-tenant", new GovernanceGateResult.DecisionBasis(
                basis.workbook(), basis.dependencySnapshotFingerprint(), basis.contractSuites(),
                List.of(new GovernanceGateResult.EvidenceRef(foreignRun.runId(),
                        foreignRun.evidenceMaterialFingerprint())), basis.policy(), basis.checks()));

        assertProblem(() -> service.submitGateResult(crossTenant, gateContext()),
                "RG.INTEGRATION.RUN_NOT_FOUND");
    }

    @Test
    void rejectsUnknownStatusAndMapsDisappearingSuiteRevisionToStableConflict() {
        CorrectnessWorkbookBundle workbook = service.correctnessWorkbook(
                draft.draftId(), draft.revision(), workbookContext()).payload();
        GovernanceGateResult valid = passedGate("gate-source", workbook);
        GovernanceGateResult unknownStatus = new GovernanceGateResult("", "gate-invalid-status", valid.target(),
                "APPROVED", valid.issues(), valid.producedAt(), valid.expiresAt(), "", valid.decisionBasis());
        assertProblem(() -> service.submitGateResult(unknownStatus, gateContext()),
                "RG.INTEGRATION.GATE_RESULT_INVALID");

        VisualOperatorContractTestSuiteRepository missingHistory = new VisualOperatorContractTestSuiteRepository() {
            @Override public Collection<VisualOperatorContractTestSuite> all() { return suites.all(); }
            @Override public Optional<VisualOperatorContractTestSuite> find(String id) { return suites.find(id); }
            @Override public VisualOperatorContractTestSuite save(VisualOperatorContractTestSuite suite) {
                return suites.save(suite);
            }
        };
        ToolStudioIntegrationService unstable = new ToolStudioIntegrationService(drafts, null, null, runs,
                new InMemoryGovernanceGateResultRepository(), new ObjectMapper().findAndRegisterModules(),
                IntegrationIdentityResolver.unavailable(), new SideEffectReconcilerRegistry(List.of()),
                new FixedSnapshotService(suites, "risk:score"),
                new CorrectnessWorkbookProjectionService(missingHistory, runs));
        assertProblem(() -> unstable.submitGateResult(valid, gateContext()),
                "RG.INTEGRATION.GATE_BASIS_STALE");
    }

    private GovernanceGateResult passedGate(String id, CorrectnessWorkbookBundle workbook) {
        List<GovernanceGateResult.SuiteRef> suiteRefs = workbook.suites().stream()
                .map(suite -> new GovernanceGateResult.SuiteRef(
                        suite.suiteId(), suite.revision(), suite.suiteFingerprint())).toList();
        List<GovernanceGateResult.EvidenceRef> evidence = workbook.evidence().stream()
                .map(ref -> new GovernanceGateResult.EvidenceRef(ref.runId(), ref.evidenceFingerprint())).toList();
        List<String> required = List.of("WORKBOOK", "CONTRACT_COVERAGE", "EVIDENCE", "RUNTIME_READINESS",
                "OWNER_APPROVAL", "BREAKING_MIGRATION");
        List<GovernanceGateResult.Check> checks = required.stream()
                .map(kind -> new GovernanceGateResult.Check(kind, "PASSED", "verified", List.of())).toList();
        GovernanceGateResult.DecisionBasis basis = new GovernanceGateResult.DecisionBasis(
                new GovernanceGateResult.WorkbookRef("workbook-1", 1, sha("workbook"),
                        workbook.manifest().bundleFingerprint()),
                workbook.dependencySnapshotFingerprint(), suiteRefs, evidence,
                new GovernanceGateResult.PolicyRef("enterprise-publish-gate", "2026-07", required), checks);
        return withBasis(id, basis);
    }

    private GovernanceGateResult withBasis(String id, GovernanceGateResult.DecisionBasis basis) {
        return new GovernanceGateResult("", id,
                new GovernanceGateResult.Target("GRAPH_DRAFT", draft.draftId(), draft.revision(),
                        ToolStudioIntegrationService.draftFingerprint(draft), draft.tenantId(), draft.namespace(),
                        draft.environment()), "PASSED", List.of(), Instant.parse("2026-07-13T00:00:00Z"),
                Instant.parse("2026-07-20T00:00:00Z"), "", basis);
    }

    private static GovernanceGateResult.DecisionBasis basisWithWorkbook(
            GovernanceGateResult.DecisionBasis basis,
            GovernanceGateResult.WorkbookRef workbook) {
        return new GovernanceGateResult.DecisionBasis(workbook, basis.dependencySnapshotFingerprint(),
                basis.contractSuites(), basis.evidence(), basis.policy(), basis.checks());
    }

    private static void assertProblem(Runnable action, String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(IntegrationProblemException.class,
                failure -> assertThat(failure.problem().code()).isEqualTo(code));
    }

    private static IntegrationRequestContext workbookContext() {
        return context("WORKBOOK_SYNC", "corr-workbook");
    }

    private static IntegrationRequestContext gateContext() {
        return context("GOVERNANCE_GATE_FEEDBACK", "corr-gate");
    }

    private static IntegrationRequestContext readContext() {
        return context("GOVERNANCE_EVIDENCE_INGESTION", "corr-read");
    }

    private static IntegrationRequestContext context(String purpose, String correlationId) {
        return new IntegrationRequestContext("tenant-a", "org-a", "project-a", "prod", "local",
                "WORKLOAD", "aneke", "", purpose, correlationId);
    }

    private static VisualOperatorContractTestSuite suite(String id, String operatorRef, String caseKindTag) {
        VisualOperatorContractTestCase row = new VisualOperatorContractTestCase("customer risk",
                Map.of("customerId", "customer-42", "apiToken", "secret-token"), Map.of("threshold", 7),
                Map.of("decision", "APPROVE"), Map.of("result", List.of(
                        new VisualOperatorTestAssertion(VisualOperatorTestAssertion.Mode.PATH_EQUALS,
                                "/decision", "APPROVE"))));
        return new VisualOperatorContractTestSuite(id, "Risk suite", "", List.of(caseKindTag),
                new VisualOperatorContractTestSuiteRequest(operatorRef, List.of(row)));
    }

    private static GraphDraft draft() {
        GraphDraft.DraftNode node = new GraphDraft.DraftNode("risk", "risk:score", "Risk", Map.of(), Map.of(),
                new GraphDraft.Position(100, 100));
        return new GraphDraft("", "draft-workbook", 0, "riskGraph", "tenant-a", "knowledge", "prod", "DRAFT",
                SchemaEnvelope.opaque(), SchemaEnvelope.opaque(), List.of(node), List.of(), Map.of(), Map.of(),
                new GraphDraft.OutputSelection("risk", "result"), Map.of(), Map.of(),
                GraphDraft.RevisionMetadata.empty());
    }

    private static GraphDraft draftWithScope(String draftId, String tenantId) {
        GraphDraft original = draft();
        return new GraphDraft("", draftId, 1, original.graphName(), tenantId, original.namespace(),
                original.environment(), original.status(), original.inputSchema(), original.outputSchema(),
                original.nodes(), original.edges(), original.visualLayout(), original.nodeFixtures(),
                original.output(), original.operatorFingerprints(), original.operatorSnapshots(),
                original.revisionMetadata());
    }

    private static VisualGraphRunRecord record(String runId, GraphDraft draft) {
        VisualGraphRunResponse response = new VisualGraphRunResponse(true, true, true, draft.graphName(), "risk",
                Map.of("decision", "APPROVE"), Map.of("risk", Map.of("decision", "APPROVE")),
                Map.of("risk", "COMPLETED"), 8, Map.of("risk", 4L), List.of(), List.of(), null, null,
                "graph riskGraph {}");
        return VisualGraphRunRecord.storedDraft(draft, Map.of("customerId", "customer-42"), response)
                .withIdentity(runId, Instant.parse("2026-07-13T00:00:00Z"));
    }

    private static String sha(String material) {
        return VisualBundleFingerprint.fromMaterial(Map.of("value", material));
    }

    private static final class FixedSnapshotService extends GraphDraftDependencySnapshotService {
        private final InMemoryVisualOperatorContractTestSuiteRepository suites;
        private final String operatorRef;

        FixedSnapshotService(InMemoryVisualOperatorContractTestSuiteRepository suites, String operatorRef) {
            super((VisualOperatorCatalog) null);
            this.suites = suites;
            this.operatorRef = operatorRef;
        }

        @Override
        public Snapshot capture(GraphDraft draft) {
            VisualOperatorContractTestSuite suite = suites.find("suite-risk").orElseThrow();
            long revision = suites.revision(suite.suiteId());
            String fingerprint = VisualBundleFingerprint.fromMaterial(Map.of("suite", suite));
            GraphDraftDependencyProfile.ContractSuiteRef suiteRef = new GraphDraftDependencyProfile.ContractSuiteRef(
                    suite.suiteId(), revision, suite.schemaVersion(), suite.request().cases().size(), fingerprint);
            GraphDraftDependencyProfile.OperatorAssetSnapshot asset = new GraphDraftDependencyProfile.OperatorAssetSnapshot(
                    null, List.of(), List.of(suiteRef),
                    new GraphDraftDependencyProfile.RuntimeReadiness(true, true, true,
                            "LOW", "risk-team", "99.9", "RUNTIME_EXECUTABLE"));
            String snapshotFingerprint = VisualBundleFingerprint.fromMaterial(Map.of(
                    "draft", ToolStudioIntegrationService.draftFingerprint(draft), "suite", suiteRef));
            return new Snapshot(snapshotFingerprint, Instant.EPOCH, List.of(), null, Map.of(operatorRef, asset));
        }
    }
}
