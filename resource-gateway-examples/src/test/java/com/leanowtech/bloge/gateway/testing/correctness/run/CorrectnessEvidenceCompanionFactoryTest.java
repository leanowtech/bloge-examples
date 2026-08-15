package com.leanowtech.bloge.gateway.testing.correctness.compilation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteExecutionRequest;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteExecutionResponse;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.AuditMetadata;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalKind;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessPublication;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessPublication.AttemptStage;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessPublication.Failure;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessPublication.PublicationAttempt;
import com.leanowtech.bloge.gateway.testing.correctness.oracle.AssertionEvaluatorProfile;
import com.leanowtech.bloge.gateway.testing.correctness.oracle.AssertionSetCompiler;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.FixtureAssetRepository;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.ScenarioDraftSetV2Repository;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.StoredFixtureAsset;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.StoredScenarioDraftSetV2;
import com.leanowtech.bloge.gateway.testing.correctness.publication.StoredCorrectnessPublication;
import com.leanowtech.bloge.gateway.testing.correctness.publication.StoredCorrectnessPublicationAttempt;
import com.leanowtech.bloge.gateway.testing.correctness.run.CorrectnessEvidenceCompanion;
import com.leanowtech.bloge.gateway.testing.correctness.run.CorrectnessEvidenceCompanionFactory;
import com.leanowtech.bloge.gateway.testing.correctness.run.CorrectnessPreflightReport;
import com.leanowtech.bloge.gateway.testing.correctness.run.CorrectnessRunException;
import com.leanowtech.bloge.gateway.testing.correctness.run.CorrectnessRunRequest;
import com.leanowtech.bloge.gateway.testing.correctness.run.CorrectnessVerdictProjector;
import com.leanowtech.bloge.gateway.testing.correctness.run.StoredCorrectnessEvidenceCompanion;
import com.leanowtech.bloge.gateway.testing.domain.TestRunEvidence;
import com.leanowtech.bloge.gateway.testing.domain.TestSuite;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunAttestation;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CorrectnessEvidenceCompanionFactoryTest {

    private static final Instant COMPLETED = Instant.parse("2026-08-15T12:00:00Z");

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private ScenarioDraftSetV2Repository scenarios;
    private FixtureAssetRepository fixtures;
    private CorrectnessEvidenceCompanionFactory factory;
    private FrozenCompilationInput source;
    private CompiledCorrectnessPlan plan;

    @BeforeEach
    void setUp() {
        scenarios = mock(ScenarioDraftSetV2Repository.class);
        fixtures = mock(FixtureAssetRepository.class);
        factory = new CorrectnessEvidenceCompanionFactory(
                scenarios, fixtures, new CorrectnessVerdictProjector(), mapper);
        source = CorrectnessCompilationTestData.input(
                new com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2
                        .InlineValue(Map.of("decision", "APPROVE")), true);
        plan = new CorrectnessCompiler(
                mapper, new AssertionSetCompiler(mapper),
                AssertionEvaluatorProfile.fixtureEvaluatorV1()).compile(source);
        when(scenarios.findRevision(
                source.scope(), source.coordinate().scenarioDraftSetRef().id(),
                source.coordinate().scenarioDraftSetRef().revision()))
                .thenReturn(java.util.Optional.of(
                        StoredScenarioDraftSetV2.verified(mapper, source.scenarioDraftSet())));
        when(fixtures.resolveExact(source.scope(), source.coordinate().fixtureAssetRefs()))
                .thenReturn(source.fixtures().stream().map(value ->
                        StoredFixtureAsset.verified(mapper, value.descriptor())).toList());
    }

    @Test
    void buildsExactPayloadFreeAcceptedLineageFromRealCompilerSourceMap() throws Exception {
        Fixture fixture = fixture(plan.report().sourceMap());

        StoredCorrectnessEvidenceCompanion stored = factory.create(
                fixture.request(), fixture.preflight(), fixture.publication(), fixture.attempt(),
                fixture.response(), fp('c'), identity());

        CorrectnessEvidenceCompanion companion = stored.companion();
        assertThat(companion.caseRefs()).singleElement().satisfies(ref -> {
            assertThat(ref.caseId()).isEqualTo("prime-approval");
            assertThat(ref.scenarioDraftSetRef())
                    .isEqualTo(source.coordinate().scenarioDraftSetRef());
        });
        assertThat(companion.sourceMap()).containsExactlyElementsOf(plan.report().sourceMap());
        assertThat(companion.caseExecutions()).singleElement().satisfies(execution -> {
            assertThat(execution.executionPlanFingerprint()).isEqualTo(fp('d'));
            assertThat(execution.childRunId()).isEqualTo("child-run-1");
        });
        assertThat(companion.verdict().gate())
                .isEqualTo(com.leanowtech.bloge.gateway.testing.correctness.domain
                        .CorrectnessVerdict.GateVerdict.ACCEPTED);
        assertThat(companion.dataClassifications())
                .containsExactly(source.fixtures().getFirst().descriptor().classification());
        assertThat(mapper.writeValueAsString(stored))
                .doesNotContain(CorrectnessCompilationTestData.SECRET);
    }

    @Test
    void rejectsAReportWhoseSelectedCaseLineageWasRemoved() {
        List<com.leanowtech.bloge.gateway.testing.correctness.compilation
                .CorrectnessCompilationReport.SourceMapping> incomplete =
                plan.report().sourceMap().stream()
                        .filter(mapping -> !"OBLIGATION".equals(mapping.source().elementKind()))
                        .toList();
        Fixture fixture = fixture(incomplete);

        assertThatThrownBy(() -> factory.create(
                fixture.request(), fixture.preflight(), fixture.publication(), fixture.attempt(),
                fixture.response(), fp('c'), identity()))
                .isInstanceOf(CorrectnessRunException.class)
                .extracting(value -> ((CorrectnessRunException) value).code())
                .isEqualTo("RG.CORRECTNESS.EVIDENCE_SOURCE_MAP_INCOMPLETE");
    }

    @Test
    void rejectsFixtureDescriptorDriftWithoutReadingMaterialPayload() {
        when(fixtures.resolveExact(any(), any())).thenReturn(List.of());
        Fixture fixture = fixture(plan.report().sourceMap());

        assertThatThrownBy(() -> factory.create(
                fixture.request(), fixture.preflight(), fixture.publication(), fixture.attempt(),
                fixture.response(), fp('c'), identity()))
                .isInstanceOf(CorrectnessRunException.class)
                .extracting(value -> ((CorrectnessRunException) value).code())
                .isEqualTo("RG.CORRECTNESS.EVIDENCE_FIXTURE_LINEAGE_MISSING");
    }

    private Fixture fixture(List<com.leanowtech.bloge.gateway.testing.correctness.compilation
            .CorrectnessCompilationReport.SourceMapping> sourceMap) {
        ExactAssetRef fixtureRef = compiledRef("FIXTURE_BUNDLE");
        ExactAssetRef suiteRef = compiledRef("TEST_SUITE");
        CorrectnessPublication value = new CorrectnessPublication(
                "", "publication-1", source.scope(), source.coordinate().target(),
                source.coordinate().definitionRef(), source.coordinate().inventoryRef(),
                source.coordinate().scenarioDraftSetRef(), source.coordinate().oracleRefs(),
                source.coordinate().assertionSetRefs(), source.coordinate().fixtureAssetRefs(),
                List.of(fixtureRef), suiteRef, CorrectnessCompiler.COMPILER_VERSION,
                plan.report().compilationFingerprint(), metadata(COMPLETED.minusSeconds(60)));
        StoredCorrectnessPublication publication =
                StoredCorrectnessPublication.verified(mapper, value);
        var report = new com.leanowtech.bloge.gateway.testing.correctness.compilation
                .CorrectnessCompilationReport(
                "", true, plan.report().compilerVersion(), plan.report().coordinate(),
                plan.report().compilationFingerprint(), sourceMap,
                plan.report().compiledAssets(), plan.report().diagnostics(),
                plan.report().riskSummary());
        PublicationAttempt attemptValue = new PublicationAttempt(
                "", "attempt-1", 1, fp('a'), source.coordinate(), AttemptStage.COMMITTED,
                List.of(fixtureRef, suiteRef), Failure.none(), metadata(COMPLETED.minusSeconds(30)));
        StoredCorrectnessPublicationAttempt attempt =
                new StoredCorrectnessPublicationAttempt("", source.scope(), attemptValue, report);
        CorrectnessRunRequest.PublicationRef publicationRef =
                new CorrectnessRunRequest.PublicationRef(
                        value.publicationId(), 1, publication.publicationFingerprint());
        CorrectnessRunRequest.Selection selection = new CorrectnessRunRequest.Selection(
                CorrectnessRunRequest.Selection.Mode.ALL, List.of(), fp('b'));
        CorrectnessRunRequest request = new CorrectnessRunRequest(
                "", publicationRef, selection, fp('e'), "client-1",
                CorrectnessRunRequest.Strategy.COLLECT_ALL);
        CorrectnessPreflightReport preflight = new CorrectnessPreflightReport(
                "", publicationRef, value.target(), suiteRef, selection,
                CorrectnessPreflightReport.ProofLevel.SIMULATED_BUSINESS,
                List.of(new CorrectnessPreflightReport.CasePlan(
                        "prime-approval", TestSuite.CaseType.GOLDEN, fixtureRef, fp('d'),
                        List.of(), List.of(), List.of(), 0)),
                new CorrectnessPreflightReport.RiskSummary(
                        0, 1, 0, 0, 0, 0, 0, 0, 0, true, List.of("READ")),
                List.of(), fp('e'));
        return new Fixture(request, preflight, publication, attempt,
                suiteResponse(suiteRef, fixtureRef));
    }

    private TestSuiteExecutionResponse suiteResponse(
            ExactAssetRef suiteRef,
            ExactAssetRef fixtureRef
    ) {
        TestSuite suite = (TestSuite) plan.suiteRegistration().testSuite();
        TestSuite.FixtureBundleRef fixture = new TestSuite.FixtureBundleRef(
                fixtureRef.id(), fixtureRef.revision(), fixtureRef.fingerprint());
        TestSuiteRunEvidence.CaseResult caseResult = new TestSuiteRunEvidence.CaseResult(
                "prime-approval", TestSuite.CaseType.GOLDEN, fixture,
                TestSuiteRunEvidence.CaseStatus.PASSED, "child-run-1",
                TestRunEvidence.Status.PASSED, TestRunEvidence.EvidenceClass.CERTIFIABLE,
                1, 1, "", "");
        TestSuiteRunEvidence.CoverageVerdict coverage =
                new TestSuiteRunEvidence.CoverageVerdict(
                        TestSuiteRunEvidence.CoverageStatus.SATISFIED,
                        1, 1, List.of(TestSuite.CaseType.GOLDEN),
                        List.of(TestSuite.CaseType.GOLDEN), List.of(),
                        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                        1, List.of(), List.of(), true);
        TestSuiteRunEvidence.PromotionVerdict promotion =
                new TestSuiteRunEvidence.PromotionVerdict(
                        TestSuiteRunEvidence.PromotionStatus.ELIGIBLE, List.of(),
                        true, 1, 1, true, true, true);
        TestSuiteExecutionRequest.SuiteRef exactSuite =
                new TestSuiteExecutionRequest.SuiteRef(
                        suiteRef.id(), suiteRef.revision(), suiteRef.fingerprint());
        TestSuiteRunEvidence evidence = new TestSuiteRunEvidence(
                "", "suite-run-1", "derived-client-key", TestSuiteRunEvidence.Status.PASSED,
                "TEST_SUITE_EXECUTION", exactSuite, suite.target(),
                COMPLETED.minusSeconds(2), COMPLETED, List.of(caseResult), coverage,
                promotion, List.of(), Map.of("source", "CORRECTNESS_RUN"));
        String evidenceFingerprint = fp('f');
        TestSuiteRunAttestation attestation = new TestSuiteRunAttestation(
                "", TestSuiteRunAttestation.SignatureStatus.VERIFIED,
                TestSuiteRunAttestation.Scope.TERMINAL, "suite-run-1", exactSuite,
                fp('1'), evidenceFingerprint,
                List.of(new TestSuiteRunAttestation.ChildEvidenceRef(
                        "prime-approval", "child-run-1", fp('2'))),
                COMPLETED, "key-1", "HMAC-SHA256", "c2lnbmF0dXJl", true);
        return new TestSuiteExecutionResponse(
                "", "suite-run-1", evidenceFingerprint, evidence, attestation);
    }

    private ExactAssetRef compiledRef(String kind) {
        return plan.report().compiledAssets().stream()
                .map(value -> value.assetRef()).filter(ref -> kind.equals(ref.kind()))
                .findFirst().orElseThrow();
    }

    private AuditMetadata metadata(Instant instant) {
        PrincipalRef actor = new PrincipalRef("author-1", PrincipalKind.USER, "Author");
        return new AuditMetadata(instant, instant, actor, actor);
    }

    private IntegrationRequestContext identity() {
        var scope = source.scope();
        return new IntegrationRequestContext(
                scope.tenantId(), scope.organizationId(), scope.projectId(),
                scope.environment(), scope.region(), "USER", "author-1", "",
                "TEST_EXECUTION", "corr-1", Set.of(), "RESTRICTED", "");
    }

    private String fp(char digit) {
        return "sha256:" + String.valueOf(digit).repeat(64);
    }

    private record Fixture(
            CorrectnessRunRequest request,
            CorrectnessPreflightReport preflight,
            StoredCorrectnessPublication publication,
            StoredCorrectnessPublicationAttempt attempt,
            TestSuiteExecutionResponse response
    ) { }
}
