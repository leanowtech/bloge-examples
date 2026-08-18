package com.leanowtech.bloge.gateway.capabilitystudio;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.authoring.scenario.ScenarioGovernedRegistryGateway;
import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.api.TestExecutionApiService;
import com.leanowtech.bloge.gateway.testing.api.TestExecutionPreflightResponse;
import com.leanowtech.bloge.gateway.testing.domain.EffectiveExecutionPlan;
import com.leanowtech.bloge.gateway.testing.domain.FixtureRule;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

class CapabilityStudioGovernedRunEvidenceServiceTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void rejectsAnEmptyRunIdBeforeTouchingPersistedExecutionEvidence() {
        TestExecutionApiService executions = mock(TestExecutionApiService.class);
        CapabilityStudioGovernedRunEvidenceService service = service(executions);

        assertThatThrownBy(() -> service.read("  ", null, identity()))
                .isInstanceOf(IntegrationProblemException.class)
                .satisfies(failure -> assertThat(((IntegrationProblemException) failure)
                        .problem().code()).isEqualTo(
                        "RG.CAPABILITY_STUDIO.GOVERNED_RUN.RUN_ID_REQUIRED"));
        verifyNoInteractions(executions);
    }

    @Test
    void projectionFingerprintIsStableAndStructureOnlyLensHasNoPayload() throws Exception {
        CapabilityStudioDataLensProjection lens = new CapabilityStudioDataLensProjection(
                CapabilityStudioDataLensProjection.SCHEMA_VERSION, "run-1", "PASSED",
                CapabilityStudioDataLensProjection.PermissionMode.STRUCTURE_ONLY,
                List.of(), List.of(), null,
                CapabilityStudioDataLensProjection.Truncation.none(), "sha256:" + "a".repeat(64));
        CapabilityStudioGovernedRunEvidenceProjection.ExactRef ref =
                new CapabilityStudioGovernedRunEvidenceProjection.ExactRef(
                        "DATA_CASE", "case-1", 1, "sha256:" + "b".repeat(64));
        CapabilityStudioGovernedRunEvidenceProjection value = projection(lens, ref);

        String first = VisualBundleFingerprint.fromCanonicalValue(
                mapper, value.fingerprintMaterial(), 16 * 1_048_576);
        String second = VisualBundleFingerprint.fromCanonicalValue(
                mapper, value.fingerprintMaterial(), 16 * 1_048_576);

        assertThat(first).isEqualTo(second);
        assertThat(first).isNotEqualTo(VisualBundleFingerprint.fromCanonicalValue(
                mapper, value, 16 * 1_048_576));
        CapabilityStudioGovernedRunEvidenceProjection changed = new CapabilityStudioGovernedRunEvidenceProjection(
                value.schemaVersion(), value.verificationStatus(), value.baselineId(), "",
                value.scenario(), value.graphRef(), value.capabilityRef(), value.contractRef(),
                value.datasetRef(), value.caseRef(), value.runtimeTarget(), value.bindingPlan(),
                value.run(), "different-node", value.dataLens());
        assertThat(VisualBundleFingerprint.fromCanonicalValue(
                mapper, changed.fingerprintMaterial(), 16 * 1_048_576)).isNotEqualTo(first);
        assertThat(VisualBundleFingerprint.fromCanonicalValue(
                mapper, value.bindingPlan().fingerprintMaterial(), 16 * 1_048_576))
                .isEqualTo(value.bindingPlan().ref().fingerprint());
        assertThat(VisualBundleFingerprint.fromCanonicalValue(
                mapper, value.bindingPlan().fingerprintMaterial(), 16 * 1_048_576))
                .isNotEqualTo(VisualBundleFingerprint.fromCanonicalValue(
                        mapper, value.bindingPlan(), 16 * 1_048_576));
        assertThat(mapper.writeValueAsString(value)).doesNotContain("customerName", "phoneNumber");
        assertThat(lens.permissionMode())
                .isEqualTo(CapabilityStudioDataLensProjection.PermissionMode.STRUCTURE_ONLY);
    }

    @Test
    void preservesTheOriginal404FromTheExecutionRepository() {
        CapabilityStudioGoldenDemoPack pack = new CapabilityStudioGoldenDemoPackLoader().load(mapper);
        TestExecutionApiService executions = mock(TestExecutionApiService.class);
        IntegrationProblemException notFound = new IntegrationProblemException(IntegrationProblem.notFound(
                "RG.TEST.RUN_NOT_FOUND", "missing", "corr", java.util.Map.of()));
        when(executions.find(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(com.leanowtech.bloge.gateway.testing.api.TestExecutionApiRequest.Verbosity.class),
                org.mockito.ArgumentMatchers.any(IntegrationRequestContext.class))).thenThrow(notFound);
        CapabilityStudioGovernedRunEvidenceService service = new CapabilityStudioGovernedRunEvidenceService(
                pack, mapper, new CapabilityStudioScenarioDatasetProjector(pack, mapper),
                mock(ScenarioGovernedRegistryGateway.class),
                mock(CapabilityStudioGovernedCompilationService.class), executions);

        assertThatThrownBy(() -> service.read("missing", null, identity()))
                .isSameAs(notFound);
    }

    @Test
    void capabilityRefUsesTheRetargetedDatasetTargetClosure() {
        CapabilityStudioGoldenDemoPack pack = new CapabilityStudioGoldenDemoPackLoader().load(mapper);
        CapabilityStudioGoldenGovernedTarget.Target target =
                CapabilityStudioGoldenGovernedTarget.create(mapper);
        CapabilityStudioScenarioDatasetProjector.ScenarioDatasetProjection dataset =
                CapabilityStudioGoldenGovernedTarget.retarget(
                        new CapabilityStudioScenarioDatasetProjector(pack, mapper).project(), target);

        assertThat(CapabilityStudioGovernedRunEvidenceService.capabilityRef(dataset))
                .isEqualTo(new CapabilityStudioGovernedRunEvidenceProjection.ExactRef(
                        dataset.targetRef().kind(), dataset.targetRef().id(), dataset.targetRef().revision(),
                        dataset.targetRef().fingerprint()));
        assertThat(dataset.targetRef().kind()).isEqualTo("TOOL");
    }

    @Test
    void applicableContractClosureUnionsPrimaryAndDependenciesWithExactDeduplication() {
        CapabilityStudioGovernedRunEvidenceProjection.ExactRef primary =
                contractRef("contract-tool", "a");
        CapabilityStudioGovernedRunEvidenceProjection.ExactRef dependency =
                contractRef("contract-dependency", "b");

        assertThat(CapabilityStudioGovernedRunEvidenceService.applicableContractClosure(
                primary, primary, List.of(primary, dependency, primary, dependency)))
                .containsExactly(dependency, primary);
    }

    @Test
    void applicableContractClosureRejectsPrimaryContractDriftFromTopLevelRef() {
        CapabilityStudioGovernedRunEvidenceProjection.ExactRef primary =
                contractRef("contract-tool", "a");
        CapabilityStudioGovernedRunEvidenceProjection.ExactRef drifted =
                contractRef("contract-tool", "b");

        assertThatThrownBy(() -> CapabilityStudioGovernedRunEvidenceService
                .applicableContractClosure(primary, drifted, List.of()))
                .isInstanceOf(CapabilityStudioGovernedCompilationException.class)
                .satisfies(error -> assertThat(
                        ((CapabilityStudioGovernedCompilationException) error).code())
                        .isEqualTo("RG.CAPABILITY_STUDIO.GOVERNED_RUN.PRIMARY_CONTRACT_DRIFT"));
    }

    @Test
    void businessFallbackOnAFixtureResolvedPlanDoesNotMeanFallbackToReal() {
        CapabilityStudioDataLensProjection lens = new CapabilityStudioDataLensProjection(
                CapabilityStudioDataLensProjection.SCHEMA_VERSION, "run-1", "PASSED",
                CapabilityStudioDataLensProjection.PermissionMode.STRUCTURE_ONLY,
                List.of(new CapabilityStudioDataLensProjection.Node(
                        "timeout-node", "tool", "MOCKED", "OUTPUT_LEVEL", "/root", "site-1",
                        "corr", 1, 1, null, "sha256:" + "a".repeat(64), null,
                        "sha256:" + "b".repeat(64), "", 1, List.of(), 0, "FALLBACK")),
                List.of(), null, CapabilityStudioDataLensProjection.Truncation.none(),
                "sha256:" + "c".repeat(64));
        EffectiveExecutionPlan fixturePlan = new EffectiveExecutionPlan(
                EffectiveExecutionPlan.SCHEMA_VERSION, "plan-1", "sha256:" + "d".repeat(64),
                "AUTHORIZED_OPERATOR_UNIT_TEST", "sha256:" + "e".repeat(64),
                "sha256:" + "f".repeat(64),
                List.of(new EffectiveExecutionPlan.ResolvedSite(
                        "site-1", EffectiveExecutionPlan.Resolution.TEST_DOUBLE,
                        FixtureRule.BehaviorKind.TIMEOUT, FixtureRule.DoubleBoundary.NODE,
                        List.of("rule-1"), "MOCKED")),
                List.of(), List.of(), java.util.Map.of(), List.of());

        assertThat(lens.nodes().getFirst().fallbackStatus()).isEqualTo("FALLBACK");
        assertThat(CapabilityStudioGovernedRunEvidenceService.hasRealResolution(fixturePlan))
                .isFalse();
    }

    @Test
    void internalRealResolutionWithFailClosedPoliciesIsNotFallbackToReal() {
        EffectiveExecutionPlan internalRealPlan = new EffectiveExecutionPlan(
                EffectiveExecutionPlan.SCHEMA_VERSION, "plan-1", "sha256:" + "d".repeat(64),
                "AUTHORIZED_OPERATOR_UNIT_TEST", "sha256:" + "e".repeat(64),
                "sha256:" + "f".repeat(64),
                List.of(new EffectiveExecutionPlan.ResolvedSite(
                        "/root/subject#PRIMARY", EffectiveExecutionPlan.Resolution.REAL,
                        FixtureRule.BehaviorKind.REAL, FixtureRule.DoubleBoundary.NODE,
                        List.of(), "REAL")),
                List.of(), List.of(), java.util.Map.of(), List.of());

        assertThat(CapabilityStudioGovernedRunEvidenceService.hasRealResolution(internalRealPlan))
                .isTrue();
        assertThat(CapabilityStudioGovernedRunEvidenceService.fallbackToReal(
                List.of(policy(FixtureRule.UnmatchedAction.FAIL, FixtureRule.ExhaustedAction.FAIL))))
                .isFalse();
    }

    @Test
    void anyExplicitAllowRealPolicyIsFallbackToReal() {
        assertThat(CapabilityStudioGovernedRunEvidenceService.fallbackToReal(List.of(
                policy(FixtureRule.UnmatchedAction.ALLOW_REAL, FixtureRule.ExhaustedAction.FAIL))))
                .isTrue();
        assertThat(CapabilityStudioGovernedRunEvidenceService.fallbackToReal(List.of(
                policy(FixtureRule.UnmatchedAction.FAIL, FixtureRule.ExhaustedAction.FALLBACK_TO_REAL))))
                .isTrue();
    }

    private CapabilityStudioGovernedRunEvidenceService service(TestExecutionApiService executions) {
        return new CapabilityStudioGovernedRunEvidenceService(
                mock(CapabilityStudioGoldenDemoPack.class), mapper,
                mock(CapabilityStudioScenarioDatasetProjector.class),
                mock(ScenarioGovernedRegistryGateway.class),
                mock(CapabilityStudioGovernedCompilationService.class), executions);
    }

    private static TestExecutionPreflightResponse.RulePolicyDescriptor policy(
            FixtureRule.UnmatchedAction onUnmatched,
            FixtureRule.ExhaustedAction onExhausted) {
        return new TestExecutionPreflightResponse.RulePolicyDescriptor(
                "rule-1", FixtureRule.BehaviorKind.RETURN, FixtureRule.DoubleBoundary.TRANSPORT,
                true, 1, 1, onUnmatched, onExhausted, FixtureRule.SchemaCheckMode.STRICT);
    }

    private static CapabilityStudioGovernedRunEvidenceProjection.ExactRef contractRef(
            String id, String fingerprintCharacter) {
        return new CapabilityStudioGovernedRunEvidenceProjection.ExactRef(
                "CONTRACT", id, 1, "sha256:" + fingerprintCharacter.repeat(64));
    }

    private static CapabilityStudioGovernedRunEvidenceProjection projection(
            CapabilityStudioDataLensProjection lens,
            CapabilityStudioGovernedRunEvidenceProjection.ExactRef ref) {
        CapabilityStudioGovernedRunEvidenceProjection.Scenario scenario =
                new CapabilityStudioGovernedRunEvidenceProjection.Scenario(
                        "case-1", "Case", "business intent", "GOLDEN", "ACTIVE", "READY",
                        new CapabilityStudioGovernedRunEvidenceProjection.Owner("owner", "Owner"),
                        ref, ref, ref, ref, List.of(ref));
        CapabilityStudioGovernedRunEvidenceProjection.BindingPlan binding =
                new CapabilityStudioGovernedRunEvidenceProjection.BindingPlan(
                        new CapabilityStudioGovernedRunEvidenceProjection.ExactRef(
                                "BINDING_PLAN", "binding-plan-case-1", 1, ""),
                        ref, "sha256:" + "c".repeat(64), List.of(ref), List.of(ref),
                        false, "sha256:" + "d".repeat(64), "sha256:" + "e".repeat(64));
        String bindingFingerprint = VisualBundleFingerprint.fromCanonicalValue(
                new ObjectMapper().findAndRegisterModules(), binding.fingerprintMaterial(), 16 * 1_048_576);
        binding = new CapabilityStudioGovernedRunEvidenceProjection.BindingPlan(
                new CapabilityStudioGovernedRunEvidenceProjection.ExactRef(
                        "BINDING_PLAN", "binding-plan-case-1", 1, bindingFingerprint),
                binding.fixtureBundleRef(), binding.effectiveExecutionPlanFingerprint(),
                binding.behaviorRefs(), binding.dependencyRefs(), binding.fallbackToReal(),
                binding.sourceMapFingerprint(), binding.provenanceFingerprint());
        CapabilityStudioGovernedRunEvidenceProjection.Run run =
                new CapabilityStudioGovernedRunEvidenceProjection.Run(
                        "run-1", "PASSED", "EXPLORATORY", "sha256:" + "f".repeat(64),
                        "sha256:" + "0".repeat(64), 1, 1, 1, 1);
        return new CapabilityStudioGovernedRunEvidenceProjection(
                CapabilityStudioGovernedRunEvidenceProjection.SCHEMA_VERSION,
                CapabilityStudioGovernedRunEvidenceProjection.EXACT_VERIFIED,
                "baseline", "", scenario, ref, ref, ref, ref, ref,
                new CapabilityStudioGovernedRunEvidenceProjection.RuntimeTargetRef(
                        "OPERATOR", "operator", "sha256:" + "1".repeat(64)),
                binding, run, "node-1", lens);
    }

    private static IntegrationRequestContext identity() {
        return new IntegrationRequestContext("caller-tenant", "caller-org", "caller-project",
                "staging", "remote", "WORKLOAD", "caller", "", "CAPABILITY_STUDIO_REHEARSAL",
                "corr", Set.of(), "PUBLIC", "");
    }
}
