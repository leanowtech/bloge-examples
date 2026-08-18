package com.leanowtech.bloge.gateway.capabilitystudio;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.ResourceGatewayApplication;
import com.leanowtech.bloge.gateway.authoring.scenario.ScenarioGovernedCompiler;
import com.leanowtech.bloge.gateway.authoring.scenario.ScenarioGovernedRegistryGateway;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.api.TestExecutionApiRequest;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteExecutionService;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidence;
import com.leanowtech.bloge.core.spi.OperatorRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Real governed Stage 0 proof over the existing testing control-plane authorities. */
@SpringBootTest(
        classes = ResourceGatewayApplication.class,
        properties = {
                "spring.profiles.active=test",
                "gateway.capability-studio.demo.enabled=true",
                "gateway.seed-descriptors=true",
                "gateway.base-url=http://127.0.0.1:1",
                "gateway.testing.durable.worker-quarantines.claim-token-protection.active-key-id=integration-test-v1",
                "gateway.testing.durable.worker-quarantines.claim-token-protection.key-ring=integration-test-v1=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
                "gateway.testing.durable.worker-quarantines.request-key-protection.active-key-id=integration-request-index-v1",
                "gateway.testing.durable.worker-quarantines.request-key-protection.key-ring=integration-request-index-v1=HyAdHBsaGRgXFhUUExIREA8ODQwLCgkIBwYFBAMCAQA=",
                "gateway.testing.durable.worker-quarantines.request-key-protection.write-mode=KEYED_ONLY",
                "gateway.testing.durable.worker-quarantines.request-index-rollout.instance-id=integration-replica-a",
                "gateway.testing.durable.worker-quarantines.request-index-rollout.artifact-fingerprint=sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "spring.datasource.url=jdbc:h2:mem:capability-studio-governed-candidate;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false",
                "gateway.testing.store.jdbc-url=jdbc:h2:mem:capability-studio-governed-candidate-control;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false"
        })
class CapabilityStudioGovernedCandidateIntegrationTest {

    private static final String TENANT = "tenant-a";
    private static final String ORGANIZATION = "org-a";
    private static final String PROJECT = "project-a";
    private static final String ENVIRONMENT = "test";
    private static final String REGION = "local";

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private OperatorRegistry operatorRegistry;

    @Autowired
    private CapabilityStudioGoldenDemoPack pack;

    @Autowired
    private CapabilityStudioFeatureRehearsalService rehearsal;

    @Autowired
    private ScenarioGovernedCompiler governedCompiler;

    @Autowired
    private ScenarioGovernedRegistryGateway registryGateway;

    @Autowired
    private TestSuiteExecutionService suiteExecutions;

    @Autowired
    private CapabilityStudioGovernedBaselineService governedBaseline;

    private IntegrationRequestContext publicationIdentity;
    private IntegrationRequestContext executionIdentity;
    private CapabilityStudioGoldenGovernedTarget.Target target;
    private CapabilityStudioScenarioDatasetProjector.ScenarioDatasetProjection dataset;
    private CapabilityStudioScenarioDatasetCompilation compilation;
    private CapabilityStudioGovernedCompilation governedCompilation;
    private CapabilityStudioGovernedCandidateService candidate;
    private CapabilityStudioFeatureRehearsalService.RuntimeAsset runtimeAsset;

    @BeforeEach
    void wireCanonicalRuntimeTargetIntoTheRealRegistry() {
        target = CapabilityStudioGoldenGovernedTarget.create(mapper);
        runtimeAsset = rehearsal.runtimeAsset();
        operatorRegistry.register(CapabilityStudioFeatureRehearsalService.TOOL_REF,
                runtimeAsset.operator());

        publicationIdentity = identity("TEST_SCENARIO_PUBLISH", "publisher");
        executionIdentity = identity("TEST_EXECUTION", "runner");
        TestExecutionApiRequest.Target runtimeTarget = registryGateway.describeOperatorTarget(
                CapabilityStudioFeatureRehearsalService.TOOL_REF, executionIdentity);
        assertThat(runtimeTarget.kind()).isEqualTo("OPERATOR");
        assertThat(runtimeTarget.id()).isEqualTo(target.operator().operatorRef());
        assertThat(runtimeTarget.fingerprint()).matches("sha256:[a-f0-9]{64}");
        assertThat(runtimeTarget.fingerprint()).isNotEqualTo(target.operator().fingerprint());

        dataset = CapabilityStudioGoldenGovernedTarget.retarget(
                new CapabilityStudioScenarioDatasetProjector(pack, mapper).project(), target);
        CapabilityStudioScenarioDatasetCompiler adapter =
                new CapabilityStudioScenarioDatasetCompiler(mapper);
        compilation = adapter.compile(
                dataset,
                new CapabilityStudioScenarioDatasetCompiler.ExactCompilationTarget(
                        target.exactTarget(), target.contract().fingerprint(mapper)),
                new CapabilityStudioGoldenScenarioMaterialResolver(pack));
        CapabilityStudioGovernedCompilationService compiler =
                new CapabilityStudioGovernedCompilationService(mapper, governedCompiler);
        governedCompilation = compiler.compile(
                null, target.operator(), target.contract(), runtimeTarget, compilation);
        assertThat(governedCompilation.compiled()).isTrue();
        CapabilityStudioGovernedAssetPublisher publisher =
                new CapabilityStudioGovernedAssetPublisher(mapper, registryGateway);
        candidate = new CapabilityStudioGovernedCandidateService(
                mapper, compiler, publisher, suiteExecutions);
    }

    @Test
    void publishesAndExecutesTheSameNineCaseGovernedClosureWithoutRealExternalCalls() {
        CapabilityStudioGovernedCandidateService.CandidateReceipt receipt = run("golden-client-001");

        assertThat(receipt.publication().fixtureRefs()).hasSize(9);
        assertThat(receipt.evidence().childRuns()).hasSize(9);
        assertThat(receipt.evidence().childRuns()).extracting(
                CapabilityStudioGovernedCandidateService.ChildRunRef::runId).doesNotHaveDuplicates();
        assertThat(receipt.evidence().childRuns()).extracting(
                CapabilityStudioGovernedCandidateService.ChildRunRef::fixtureBundleId).doesNotHaveDuplicates();
        assertThat(receipt.evidence().status()).isEqualTo(TestSuiteRunEvidence.Status.PASSED.name());
        assertThat(receipt.evidence().provenanceFingerprint()).matches("sha256:[a-f0-9]{64}");
        assertThat(receipt.evidence().sourceMapFingerprint()).matches("sha256:[a-f0-9]{64}");
        assertThat(receipt.evidence().childRuns()).allSatisfy(child ->
                assertThat(child.status()).isEqualTo(TestSuiteRunEvidence.Status.PASSED.name()));
        assertThat(receipt.publication().suiteRef().fingerprint())
                .isEqualTo(ProtocolFingerprint.of(
                        mapper, governedCompilation.plan().suite().testSuite()));

        String exactRefs = String.valueOf(
                governedCompilation.plan().suite().testSuite().metadata().get("governedExactRefs"));
        assertThat(exactRefs).contains("DATASET", "DATA_CASE", "CONTRACT", "BEHAVIOR_PROFILE",
                "API", "TOOL");
        assertThat(runtimeAsset.realExternalCalls()).hasValue(0);
    }

    @Test
    void repeatedClientRequestIsIdempotentAndNewClientRequestReusesPublication() {
        CapabilityStudioGovernedCandidateService.CandidateReceipt first = run("golden-client-002");
        CapabilityStudioGovernedCandidateService.CandidateReceipt retry = run("golden-client-002");
        CapabilityStudioGovernedCandidateService.CandidateReceipt second = run("golden-client-003");

        assertThat(retry).isEqualTo(first);
        assertThat(second.publication()).isEqualTo(first.publication());
        assertThat(second.evidence().suiteRunId()).isNotEqualTo(first.evidence().suiteRunId());
        assertThat(second.evidence().provenanceFingerprint())
                .isEqualTo(first.evidence().provenanceFingerprint());
        assertThat(second.evidence().sourceMapFingerprint())
                .isEqualTo(first.evidence().sourceMapFingerprint());
    }

    @Test
    void runsTheBrowserVisibleNineByThreeBaselineThroughTheRealGovernedControlPlane() {
        CapabilityStudioGovernedBaselineProjection projection = governedBaseline.run();

        assertThat(projection.status())
                .withFailMessage("Governed baseline failed closed: %s", projection.diagnostics())
                .isEqualTo(CapabilityStudioGovernedBaselineProjection.PASSED);
        assertThat(projection.caseCount()).isEqualTo(9);
        assertThat(projection.roundCount()).isEqualTo(3);
        assertThat(projection.suiteRunCount()).isEqualTo(3);
        assertThat(projection.childRunCount()).isEqualTo(27);
        assertThat(projection.realExternalCallCount()).isZero();
        assertThat(projection.publication()).isNotNull();
        assertThat(projection.publication().fixtureCount()).isEqualTo(9);
        assertThat(projection.rounds()).hasSize(3)
                .extracting(CapabilityStudioGovernedBaselineProjection.Round::suiteRunId)
                .doesNotHaveDuplicates();
        assertThat(projection.rounds()).allSatisfy(round -> {
            assertThat(round.status()).isEqualTo(TestSuiteRunEvidence.Status.PASSED.name());
            assertThat(round.childRunCount()).isEqualTo(9);
        });
        assertThat(projection.cases())
                .extracting(CapabilityStudioGovernedBaselineProjection.CaseProjection::caseId)
                .containsExactly(
                        "case-city-policy-missing",
                        "case-compensation-history-empty",
                        "case-compensation-history-timeout",
                        "case-driver-responsible",
                        "case-duplicate-cancellation",
                        "case-forbidden-write-effect",
                        "case-policy-revision-regression",
                        "case-rider-not-responsible",
                        "case-standard-cancellation-fee");
        assertThat(projection.cases()).hasSize(9).allSatisfy(caseProjection -> {
            assertThat(caseProjection.rounds()).hasSize(3)
                    .extracting(CapabilityStudioGovernedBaselineProjection.CaseRound::runId)
                    .doesNotHaveDuplicates();
            assertThat(caseProjection.rounds()).allSatisfy(round ->
                    assertThat(round.status()).isEqualTo(TestSuiteRunEvidence.Status.PASSED.name()));
        });
        assertThat(projection.cases().stream()
                .flatMap(caseProjection -> caseProjection.rounds().stream())
                .map(CapabilityStudioGovernedBaselineProjection.CaseRound::runId))
                .doesNotHaveDuplicates();
        assertThat(projection.limitations()).containsExactly(
                "BUSINESS_RESULT_FINGERPRINT_NOT_EXPORTED",
                "DEPLOYMENT_EGRESS_NOT_OBSERVED",
                "OWNER_SIGNOFF_NOT_PRESENT");
        assertThat(projection.diagnostics()).isEmpty();
    }

    private CapabilityStudioGovernedCandidateService.CandidateReceipt run(String requestId) {
        TestExecutionApiRequest.Target runtimeTarget = registryGateway.describeOperatorTarget(
                CapabilityStudioFeatureRehearsalService.TOOL_REF, executionIdentity);
        return candidate.run(null, target.operator(), target.contract(), runtimeTarget,
                compilation, requestId, publicationIdentity, executionIdentity);
    }

    private static IntegrationRequestContext identity(String purpose, String actor) {
        return new IntegrationRequestContext(TENANT, ORGANIZATION, PROJECT, ENVIRONMENT, REGION,
                "WORKLOAD", actor, "", purpose, "capability-studio-integration-" + actor,
                Set.of("resource-gateway-test-runtime-operators"), "RESTRICTED", "");
    }
}
