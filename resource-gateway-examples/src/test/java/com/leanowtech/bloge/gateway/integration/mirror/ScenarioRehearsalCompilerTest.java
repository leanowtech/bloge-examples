package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.StoredFixtureBundle;
import com.leanowtech.bloge.gateway.testing.api.StoredTestSuite;
import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;
import com.leanowtech.bloge.gateway.testing.domain.FixtureRule;
import com.leanowtech.bloge.gateway.testing.domain.TestSuite;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteProtocolCodec;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScenarioRehearsalCompilerTest {
    private static final Instant COMPILED_AT =
            Instant.parse("2026-07-24T08:00:00Z");
    private static final Instant VALIDATED_AT =
            Instant.parse("2026-07-24T08:05:00Z");
    private static final CapabilitySnapshot.Scope SCOPE =
            new CapabilitySnapshot.Scope(
                    "tenant-a", "org-a", "support", "test", "sg");
    private static final String CASE_ID = "customer-found";
    private final ObjectMapper mapper =
            new ObjectMapper().findAndRegisterModules();
    private final ScenarioRehearsalCompiler compiler =
            new ScenarioRehearsalCompiler(mapper, null);

    @Test
    void compilesACompleteExactClosureIntoADeterministicPayloadFreeLicense() {
        Material material = material(
                FixtureRule.Behavior.returning(Map.of("status", "FOUND")),
                ScenarioCase.CaseType.GOLDEN,
                List.of(),
                100,
                null);

        CompiledScenarioRehearsalPlan first =
                compiler.compile(material.request());
        CompiledScenarioRehearsalPlan second =
                compiler.compile(material.request());

        CompiledScenarioRehearsalPlanIntegrity.verify(mapper, first);
        assertThat(first.fingerprint()).isEqualTo(second.fingerprint());
        assertThat(first.scenarioPackRef())
                .isEqualTo(ScenarioPackIntegrity.reference(material.pack()));
        assertThat(first.cases()).singleElement().satisfies(binding -> {
            assertThat(binding.testCaseId()).isEqualTo(CASE_ID);
            assertThat(binding.testSuiteRef())
                    .isEqualTo(material.scenarioCase().testSuiteRef());
            assertThat(binding.fixtureBundleRef())
                    .isEqualTo(material.scenarioCase().fixtureBundleRef());
            assertThat(binding.mirrorPlanRef())
                    .isEqualTo(material.scenarioCase().mirrorPlanRef());
        });
        assertThat(mapper.valueToTree(first).toString())
                .doesNotContain("Ada")
                .doesNotContain("FOUND");
    }

    @Test
    void rejectsImplicitFaultInjectionEvenWhenEveryFingerprintIsValid() {
        Material material = material(
                FixtureRule.Behavior.timeout(
                        Duration.ofSeconds(2),
                        "RG.DEPENDENCY.TIMEOUT",
                        "simulated timeout"),
                ScenarioCase.CaseType.GOLDEN,
                List.of(),
                100,
                null);

        assertRejected(
                material.request(),
                "RG.MIRROR.REHEARSAL.FAULT_SELECTION_INVALID");
    }

    @Test
    void admitsOnlyTheExactExplicitFaultRuleSet() {
        Material valid = material(
                FixtureRule.Behavior.throwing(
                        "RG.CUSTOMER.UNAVAILABLE",
                        "DEPENDENCY",
                        "simulated dependency failure"),
                ScenarioCase.CaseType.FAULT,
                List.of("customer-response"),
                100,
                null);
        Material missingSelection = material(
                FixtureRule.Behavior.throwing(
                        "RG.CUSTOMER.UNAVAILABLE",
                        "DEPENDENCY",
                        "simulated dependency failure"),
                ScenarioCase.CaseType.FAULT,
                List.of("another-rule"),
                100,
                null);

        assertThat(compiler.compile(valid.request()).fingerprint())
                .startsWith("sha256:");
        assertRejected(
                missingSelection.request(),
                "RG.MIRROR.REHEARSAL.FAULT_SELECTION_INVALID");
    }

    @Test
    void rejectsMirrorPlanPolicyDriftBeforeExecution() {
        Material material = material(
                FixtureRule.Behavior.returning(Map.of("status", "FOUND")),
                ScenarioCase.CaseType.GOLDEN,
                List.of(),
                99,
                null);

        assertRejected(
                material.request(),
                "RG.MIRROR.REHEARSAL.PLAN_POLICY_DRIFT");
    }

    @Test
    void rejectsAReverseScenarioReferenceThatWouldCreateAContentAddressCycle() {
        Material material = material(
                FixtureRule.Behavior.returning(Map.of("status", "FOUND")),
                ScenarioCase.CaseType.GOLDEN,
                List.of(),
                100,
                ref("SCENARIO_PACK", "unresolved-pack", '9'));

        assertRejected(
                material.request(),
                "RG.MIRROR.REHEARSAL.CYCLIC_PLAN_BINDING_INVALID");
    }

    @Test
    void rejectsExpiredGovernanceBeforeResolvingRuntimeValues() {
        Material material = material(
                FixtureRule.Behavior.returning(Map.of("status", "FOUND")),
                ScenarioCase.CaseType.GOLDEN,
                List.of(),
                100,
                null);
        ScenarioRehearsalCompilationRequest late =
                new ScenarioRehearsalCompilationRequest(
                        material.pack(),
                        material.request().cases(),
                        material.request().assertions(),
                        VALIDATED_AT.plus(Duration.ofDays(2)));

        assertRejected(
                late,
                "RG.MIRROR.REHEARSAL.PACK_LIFECYCLE_INVALID");
    }

    private Material material(
            FixtureRule.Behavior behavior,
            ScenarioCase.CaseType caseType,
            List<String> faultRuleRefs,
            int packInvocationBudget,
            MirrorArtifactRef reverseScenarioRef) {
        CapabilityMaterial capabilities = capabilities();
        FixtureRule rule = new FixtureRule(
                "", "customer-response",
                FixtureRule.Selector.node("loadCustomer"),
                behavior,
                FixtureRule.Consumption.once(),
                FixtureRule.SchemaCheck.strict());
        FixtureBundle fixture = new FixtureBundle(
                "", "customer-fixture", 1,
                capabilities.root().source().sourceFingerprint(),
                "CONFIDENTIAL",
                COMPILED_AT,
                42L,
                List.of(rule),
                List.of(),
                Map.of());
        String fixtureFingerprint = ProtocolFingerprint.of(mapper, fixture);
        StoredFixtureBundle storedFixture = new StoredFixtureBundle(
                "", SCOPE.tenantId(), SCOPE.organizationId(), SCOPE.projectId(),
                SCOPE.environmentId(), SCOPE.region(),
                fixture.fixtureBundleId(), fixture.revision(),
                fixtureFingerprint, fixture,
                COMPILED_AT, "test-owner");
        TestSuite suite = new TestSuite(
                "", "customer-suite", 1,
                new TestSuite.Target(
                        "GRAPH",
                        capabilities.root().source().sourceRef(),
                        capabilities.root().source().sourceFingerprint()),
                "CONFIDENTIAL",
                List.of(new TestSuite.TestCase(
                        CASE_ID,
                        TestSuite.CaseType.GOLDEN,
                        Map.of("customerId", "Ada"),
                        new TestSuite.FixtureBundleRef(
                                fixture.fixtureBundleId(),
                                fixture.revision(),
                                fixtureFingerprint),
                        List.of("rehearsal"),
                        Map.of())),
                TestSuite.CoveragePolicy.defaults(),
                TestSuite.PromotionPolicy.defaults(),
                Map.of());
        String suiteFingerprint =
                new TestSuiteProtocolCodec(mapper).fingerprint(suite);
        StoredTestSuite storedSuite = new StoredTestSuite(
                "", SCOPE.tenantId(), SCOPE.organizationId(), SCOPE.projectId(),
                SCOPE.environmentId(), SCOPE.region(),
                suite.suiteId(), suite.revision(), suiteFingerprint,
                suite, COMPILED_AT, "test-owner");
        MirrorPlan plan = mirrorPlan(
                capabilities, fixture, fixtureFingerprint, reverseScenarioRef);
        MirrorArtifactRef assertionRef;
        CaseHandlingAssertion assertion = ScenarioPackIntegrity.sealAssertion(
                mapper,
                new CaseHandlingAssertion(
                        "", "customer-node-success", 1, "", SCOPE,
                        CaseHandlingAssertion.Observation.NODE_STATUS,
                        new CaseHandlingAssertion.Selector(
                                "loadCustomer", "", "", null, ""),
                        new CaseHandlingAssertion.Expectation(
                                List.of("SUCCESS"), "", "", "",
                                null, null, null, null),
                        CaseHandlingAssertion.Severity.BLOCKER,
                        "RG.MIRROR.SCENARIO.CUSTOMER_NODE_FAILED",
                        provenance(),
                        CapabilitySnapshot.Lifecycle.ACTIVE,
                        COMPILED_AT));
        assertionRef = ScenarioPackIntegrity.reference(assertion);
        ScenarioCase scenarioCase = ScenarioPackIntegrity.sealCase(
                mapper,
                new ScenarioCase(
                        "", CASE_ID, 1, "", SCOPE, caseType,
                        capabilities.closure().rootRef(),
                        new MirrorArtifactRef(
                                "TEST_SUITE",
                                suite.suiteId(),
                                suite.revision(),
                                suiteFingerprint),
                        CASE_ID,
                        new MirrorArtifactRef(
                                "MIRROR_PLAN",
                                plan.planId(),
                                1,
                                plan.planFingerprint()),
                        new MirrorArtifactRef(
                                "FIXTURE_BUNDLE",
                                fixture.fixtureBundleId(),
                                fixture.revision(),
                                fixtureFingerprint),
                        null,
                        plan.executionServices(),
                        faultRuleRefs,
                        List.of(assertionRef),
                        provenance(),
                        CapabilitySnapshot.Lifecycle.ACTIVE,
                        COMPILED_AT));
        ScenarioPack pack = ScenarioPackIntegrity.seal(
                mapper,
                new ScenarioPack(
                        "", "customer-rehearsal", 1, "", SCOPE,
                        capabilities.closure().rootRef(),
                        List.of(ScenarioPackIntegrity.reference(scenarioCase)),
                        List.of(assertionRef),
                        List.of(),
                        null,
                        List.of(),
                        rehearsalPolicy(packInvocationBudget),
                        provenance(),
                        CapabilitySnapshot.Lifecycle.ACTIVE,
                        COMPILED_AT));
        ScenarioRehearsalCompilationRequest request =
                new ScenarioRehearsalCompilationRequest(
                        pack,
                        List.of(new ScenarioRehearsalCompilationRequest.ResolvedCase(
                                scenarioCase,
                                storedSuite,
                                storedFixture,
                                plan,
                                null)),
                        List.of(assertion),
                        VALIDATED_AT);
        return new Material(pack, scenarioCase, request);
    }

    private MirrorPlan mirrorPlan(
            CapabilityMaterial capabilities,
            FixtureBundle fixture,
            String fixtureFingerprint,
            MirrorArtifactRef reverseScenarioRef) {
        MirrorPlan.ExternalBinding binding = new MirrorPlan.ExternalBinding(
                capabilities.closure().rootRef(),
                "loadCustomer",
                capabilities.childRef(),
                "/root/loadCustomer#RESOURCE",
                "/root",
                capabilities.child().source().sourceKind(),
                capabilities.child().source().sourceRef(),
                List.of(
                        MirrorPlan.MirrorSource.OWNER_SPECIFIED,
                        MirrorPlan.MirrorSource.ABSTAINED),
                List.of("customer-response"));
        MirrorPlan plan = new MirrorPlan(
                "", "customer-plan", "",
                capabilities.closure().rootRef(),
                capabilities.closure().fingerprint(),
                capabilities.closure().snapshots(),
                SCOPE,
                new MirrorArtifactRef(
                        "FIXTURE_BUNDLE",
                        fixture.fixtureBundleId(),
                        fixture.revision(),
                        fixtureFingerprint),
                fingerprint('8'),
                null,
                List.of(binding),
                reverseScenarioRef,
                List.of(),
                new MirrorPlan.ExecutionServices(
                        fixture.logicalClock(),
                        fixture.randomSeed(),
                        null,
                        null),
                mirrorPolicy(),
                COMPILED_AT,
                COMPILED_AT.plus(Duration.ofHours(2)));
        return MirrorPlanIntegrity.seal(mapper, plan);
    }

    private CapabilityMaterial capabilities() {
        CapabilityContract contract = new CapabilityContract(
                "",
                SchemaEnvelope.opaque(),
                SchemaEnvelope.opaque(),
                List.of(),
                EffectContract.readOnly(List.of("resource:customers.get")),
                CapabilityContract.Determinism.CONTROLLED_NONDETERMINISTIC,
                new CapabilityContract.IdempotencyContract(
                        CapabilityContract.IdempotencyMode.IDEMPOTENT,
                        "",
                        true),
                null,
                CapabilityContract.CompatibilityPolicy.conservative(),
                new CapabilityContract.SecurityContract(
                        CapabilityContract.DataClassification.CONFIDENTIAL,
                        false,
                        List.of("sg"),
                        false),
                CapabilityContract.SloContract.unspecified());
        CapabilitySnapshot child = CapabilitySnapshotIntegrity.seal(
                mapper,
                new CapabilitySnapshot(
                        "", "resource:customers.get", 1, "",
                        CapabilitySnapshot.Kind.EXTERNAL,
                        SCOPE,
                        new CapabilitySnapshot.Source(
                                CapabilitySnapshot.SourceKind.RESOURCE,
                                "customers.get",
                                fingerprint('1')),
                        contract,
                        new CapabilitySnapshot.RuntimeBinding(
                                "HTTP_RESOURCE",
                                "customers.get@1",
                                fingerprint('2'),
                                true,
                                List.of()),
                        List.of(),
                        new CapabilitySnapshot.Ownership(
                                "support-owner", "support", "on-call"),
                        CapabilitySnapshot.Lifecycle.ACTIVE,
                        provenance(),
                        COMPILED_AT));
        MirrorArtifactRef childRef =
                CapabilityClosureIntegrity.reference(child);
        CapabilitySnapshot root = CapabilitySnapshotIntegrity.seal(
                mapper,
                new CapabilitySnapshot(
                        "", "graph:customerView", 1, "",
                        CapabilitySnapshot.Kind.COMPOSED,
                        SCOPE,
                        new CapabilitySnapshot.Source(
                                CapabilitySnapshot.SourceKind.GRAPH,
                                "customerView",
                                fingerprint('3')),
                        contract,
                        new CapabilitySnapshot.RuntimeBinding(
                                "BLOGE_GRAPH",
                                "customerView@1",
                                fingerprint('4'),
                                true,
                                List.of()),
                        List.of(new CapabilitySnapshot.Dependency(
                                "loadCustomer",
                                childRef,
                                true,
                                List.of())),
                        child.ownership(),
                        CapabilitySnapshot.Lifecycle.ACTIVE,
                        provenance(),
                        COMPILED_AT));
        CapabilityClosure closure = CapabilityClosureIntegrity.seal(
                mapper,
                new CapabilityClosure(
                        "",
                        CapabilityClosureIntegrity.reference(root),
                        List.of(root, child),
                        ""));
        return new CapabilityMaterial(child, root, childRef, closure);
    }

    private static ScenarioPack.RehearsalPolicy rehearsalPolicy(
            int invocationBudget) {
        return new ScenarioPack.RehearsalPolicy(
                ScenarioPack.Scheduling.SEQUENTIAL,
                true,
                false,
                false,
                false,
                ScenarioPack.EvidenceMode.HASH_ONLY,
                10,
                invocationBudget,
                Duration.ofMinutes(5),
                Duration.ofMinutes(30),
                true,
                CapabilityContract.DataClassification.CONFIDENTIAL,
                List.of("sg"));
    }

    private static MirrorPlan.ExecutionPolicy mirrorPolicy() {
        return new MirrorPlan.ExecutionPolicy(
                MirrorPlanIntegrationService.AUTHORIZED_PURPOSE,
                false,
                false,
                false,
                false,
                true,
                MirrorPlan.UnmatchedResolution.ABSTAINED,
                100,
                Duration.ofMinutes(5),
                CapabilityContract.DataClassification.CONFIDENTIAL,
                List.of("sg"),
                List.of(CapabilitySnapshot.Lifecycle.ACTIVE));
    }

    private static ArtifactProvenance provenance() {
        return new ArtifactProvenance(
                "",
                ArtifactProvenance.SourceType.OWNER,
                List.of(),
                SCOPE.tenantId(),
                MirrorPlanIntegrationService.AUTHORIZED_PURPOSE,
                null,
                null,
                null,
                null,
                List.of(),
                "support-owner",
                COMPILED_AT,
                COMPILED_AT.plus(Duration.ofDays(1)),
                "");
    }

    private void assertRejected(
            ScenarioRehearsalCompilationRequest request, String code) {
        assertThatThrownBy(() -> compiler.compile(request))
                .isInstanceOfSatisfying(
                        ScenarioRehearsalRejectedException.class,
                        rejected -> assertThat(rejected.code()).isEqualTo(code));
    }

    private static MirrorArtifactRef ref(
            String kind, String id, char fingerprint) {
        return new MirrorArtifactRef(kind, id, 1, fingerprint(fingerprint));
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }

    private record CapabilityMaterial(
            CapabilitySnapshot child,
            CapabilitySnapshot root,
            MirrorArtifactRef childRef,
            CapabilityClosure closure
    ) {
    }

    private record Material(
            ScenarioPack pack,
            ScenarioCase scenarioCase,
            ScenarioRehearsalCompilationRequest request
    ) {
    }
}
