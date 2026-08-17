package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.operator.OperatorContext;
import com.leanowtech.bloge.core.operator.SideEffectType;
import com.leanowtech.bloge.core.operator.StreamingOperator;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.core.stream.NodeChannel;
import com.leanowtech.bloge.gateway.expression.BlgeExpressionEvaluator;
import com.leanowtech.bloge.gateway.gateway.GatewayGraphService;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.operator.HttpResourceOperator;
import com.leanowtech.bloge.gateway.resource.ResourceDescriptor;
import com.leanowtech.bloge.gateway.resource.ResourceRegistry;
import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;
import com.leanowtech.bloge.gateway.testing.domain.FixtureRule;
import com.leanowtech.bloge.gateway.testing.domain.TestRunEvidence;
import com.leanowtech.bloge.gateway.testing.evidence.TestEvidenceIntegrityService;
import com.leanowtech.bloge.gateway.testing.evidence.OperatorExecutionTargetSnapshot;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.runtime.OperatorComposabilityManifest;
import com.leanowtech.bloge.gateway.testing.runtime.OperatorComposabilityManifestProvider;
import com.leanowtech.bloge.gateway.testing.runtime.OperatorRuntimeBindingSnapshotProvider;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class TestOperatorExecutionApiServiceTest {

    private static final String CONFORMANCE_FINGERPRINT = "sha256:" + "c".repeat(64);

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final DefaultOperatorRegistry operators = new DefaultOperatorRegistry();
    private final EmptyResourceRegistry resources = new EmptyResourceRegistry();
    private final InMemoryFixtures fixtures = new InMemoryFixtures();
    private final InMemoryRuns runs = new InMemoryRuns();
    private TestExecutionApiService service;

    @BeforeEach
    void setUp() {
        operators.register("customer.greeting", new GreetingOperator());
        operators.register("http.resource", new HttpResourceOperator(null, resources, null, null, null, null));
        operators.register("legacy.external", new OpaqueExternalOperator());
        operators.register("configured.read", new ConfiguredReadOperator("tenant-a:"));
        operators.register("configured.snapshot", new SnapshotConfiguredReadOperator("tenant-a:"));
        operators.register("configured.oversized", new OversizedSnapshotOperator());
        operators.register("undeclared.read", new UndeclaredReadOperator());
        operators.register("declared.read", new DeclaredResourceReadOperator(
                OperatorComposabilityManifest.ControlBoundary.RESOURCE_BINDING));
        operators.register("unmanaged.declared", new UnmanagedDeclaredReadOperator());
        operators.register("clock.read", new ClockDependentReadOperator());
        operators.register("ambient.read", new AmbientDependentReadOperator(List.of(
                OperatorComposabilityManifest.ExecutionService.IDENTITY,
                OperatorComposabilityManifest.ExecutionService.FEATURE_FLAG)));
        operators.register("secret.read", new AmbientDependentReadOperator(List.of(
                OperatorComposabilityManifest.ExecutionService.SECRET)));
        operators.register("invalid.contract", new InvalidBehaviorContractOperator());
        operators.registerRaw("customer.events", new EventStreamOperator());
        service = new TestExecutionApiService(mock(GatewayGraphService.class), operators, resources,
                new BlgeExpressionEvaluator(), mapper, fixtures, runs, new InMemorySecurityEvents(),
                Duration.ofDays(7), null,
                new TestEvidenceIntegrityService(mapper, new InMemoryVisualEvidenceSigner()));
    }

    @Test
    void discoversBytecodeSchemasAndAffirmativeTestabilityWithoutExecutingTheBinding() {
        TestOperatorTargetDescriptor target = service.describeOperatorTarget(
                "customer.greeting", identity());

        assertThat(target.target().kind()).isEqualTo("OPERATOR");
        assertThat(target.target().fingerprint()).startsWith("sha256:");
        assertThat(target.implementationFingerprint()).startsWith("sha256:");
        assertThat(target.runtimeBindingStateFingerprint()).startsWith("sha256:");
        assertThat(target.schemaFingerprint()).startsWith("sha256:");
        assertThat(target.composabilityFingerprint()).startsWith("sha256:");
        assertThat(target.composabilityManifest())
                .containsEntry("dependencyMode", "NONE")
                .containsEntry("globalStateFree", true)
                .containsEntry("conformanceFingerprint", CONFORMANCE_FINGERPRINT);
        assertThat(target.inputSchema()).isNotEmpty();
        assertThat(target.outputSchema()).isNotEmpty();
        assertThat(target.executionModel()).isEqualTo("SYNCHRONOUS");
        assertThat(target.sideEffectType()).isEqualTo("READ_ONLY");
        assertThat(target.testabilityClass()).isEqualTo(OperatorExecutionTargetSnapshot.EXECUTABLE_UNIT);
        assertThat(target.executionSupported()).isTrue();
        assertThat(target.certificationEligible()).isTrue();
        assertThat(target.certificationGaps()).isEmpty();
    }

    @Test
    void emptyResourceRegistryKeepsHttpDiscoverySafeButNotRunnableOrCertifiable() {
        TestOperatorTargetDescriptor target = service.describeOperatorTarget("http.resource", identity());

        assertThat(target.testabilityClass())
                .isEqualTo(OperatorExecutionTargetSnapshot.CONDITIONAL_TRANSPORT);
        assertThat(target.executionSupported()).isFalse();
        assertThat(target.certificationEligible()).isFalse();
        assertThat(target.composabilityManifest()).containsEntry("dependencyMode", "OPAQUE");
        assertThat(target.composabilityManifest()).containsEntry("dependencies", List.of());
        assertThat(target.certificationRequirements())
                .anyMatch(requirement -> requirement.contains("resource descriptor"));
        assertThat(target.certificationGaps())
                .anyMatch(gap -> gap.contains("resource descriptor"));
    }

    @Test
    void operatorBoundaryPlanUsesTheProjectedSchemaAndExactBindingFingerprint() {
        TestOperatorTargetDescriptor target = service.describeOperatorTarget(
                "customer.greeting", identity());

        TestBoundaryCasePlan plan = service.planOperatorBoundaryCases(
                "customer.greeting", identity());
        TestSchemaAdmissionTarget resolved = service.resolveSchemaAdmissionTarget(
                new TestExecutionApiRequest.Target(
                        "OPERATOR", "customer.greeting", "stale"), identity());

        assertThat(plan.target()).isEqualTo(target.target());
        assertThat(resolved.target()).isEqualTo(plan.target());
        assertThat(resolved.boundaryPlan()).isEqualTo(plan);
        assertThat(ProtocolFingerprint.of(mapper, resolved.inputSchema()))
                .isEqualTo(plan.inputSchemaFingerprint());
        assertThat(plan.inputSchemaFingerprint()).matches("sha256:[a-f0-9]{64}");
        assertThat(plan.cases()).extracting(TestBoundaryCasePlan.BoundaryCase::kind)
                .contains(TestBoundaryCasePlan.BoundaryKind.BASELINE,
                        TestBoundaryCasePlan.BoundaryKind.REQUIRED_PROPERTY_MISSING,
                        TestBoundaryCasePlan.BoundaryKind.UNKNOWN_PROPERTY,
                        TestBoundaryCasePlan.BoundaryKind.TYPE_MISMATCH);
        assertThat(plan.cases()).allSatisfy(testCase -> {
            if (testCase.expectedOutcome() == TestBoundaryCasePlan.ExpectedOutcome.ACCEPTED) {
                assertThat(testCase.validationCodes()).isEmpty();
            } else {
                assertThat(testCase.validationCodes()).isNotEmpty();
            }
        });
    }

    @Test
    void targetFingerprintChangesWhenPublicInputConversionProfileChanges() {
        OperatorExecutionTargetSnapshot baseline = OperatorExecutionTargetSnapshot.capture(
                mapper, "customer.greeting", operators, resources);
        ObjectMapper changedMapper = mapper.copy()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        OperatorExecutionTargetSnapshot changed = OperatorExecutionTargetSnapshot.capture(
                changedMapper, "customer.greeting", operators, resources);

        assertThat(changed.fingerprint()).isNotEqualTo(baseline.fingerprint());
        assertThat(changed.implementationFingerprint()).isEqualTo(baseline.implementationFingerprint());
        assertThat(changed.runtimeBindingStateFingerprint())
                .isEqualTo(baseline.runtimeBindingStateFingerprint());
    }

    @Test
    void governedFixtureExecutesTypedRealBindingAndProducesCertifiableEvidence() {
        TestOperatorTargetDescriptor target = service.describeOperatorTarget(
                "customer.greeting", identity());
        FixtureBundle bundle = bundle("greeting-real", target.target().fingerprint(),
                new FixtureRule(FixtureRule.SCHEMA_VERSION, "subject-spy",
                        FixtureRule.Selector.operator("customer.greeting"), FixtureRule.Behavior.spy(),
                        FixtureRule.Consumption.once(), FixtureRule.SchemaCheck.strict()));
        StoredFixtureBundle stored = service.registerFixture("greeting-real",
                new FixtureBundleRegistrationRequest("", target.target(), bundle), identity());

        TestExecutionApiResponse response = service.executeOperator("customer.greeting",
                request(target.target(), Map.of("name", "Ada"), null,
                        new TestExecutionApiRequest.FixtureBundleRef(
                                stored.fixtureBundleId(), stored.revision(), stored.fingerprint())), identity());

        assertThat(response.target().kind()).isEqualTo("OPERATOR");
        assertThat(response.plan().authorizedPurpose()).isEqualTo("OPERATOR_UNIT_TEST");
        assertThat(response.evidence().status()).isEqualTo(TestRunEvidence.Status.PASSED);
        assertThat(response.evidence().evidenceClass()).isEqualTo(TestRunEvidence.EvidenceClass.CERTIFIABLE);
        assertThat(response.evidence().nodeTrace()).singleElement().satisfies(node -> {
            assertThat(node.operatorRef()).isEqualTo("customer.greeting");
            assertThat(node.output()).isEqualTo(Map.of("message", "Hello Ada"));
        });
        assertThat(response.evidence().metadata())
                .containsEntry("implementationFingerprint", target.implementationFingerprint())
                .containsEntry("composabilityFingerprint", target.composabilityFingerprint())
                .containsEntry("testabilityClass", "EXECUTABLE_UNIT");
        assertThat(runs.find("tenant-a", "test", response.runId())).isPresent();
    }

    @Test
    void operatorPreflightUsesTheCanonicalMicroGraphWithoutExecutingTheBinding() {
        TestOperatorTargetDescriptor target = service.describeOperatorTarget(
                "customer.greeting", identity());
        FixtureBundle bundle = bundle("greeting-preflight", target.target().fingerprint(),
                new FixtureRule(FixtureRule.SCHEMA_VERSION, "subject-spy",
                        FixtureRule.Selector.operator("customer.greeting"),
                        FixtureRule.Behavior.spy(), FixtureRule.Consumption.once(),
                        FixtureRule.SchemaCheck.strict()));
        StoredFixtureBundle stored = service.registerFixture("greeting-preflight",
                new FixtureBundleRegistrationRequest("", target.target(), bundle), identity());

        TestExecutionPreflightResponse response = service.preflightOperator(
                "customer.greeting", request(target.target(), Map.of("name", "Ada"), null,
                        new TestExecutionApiRequest.FixtureBundleRef(
                                stored.fixtureBundleId(), stored.revision(), stored.fingerprint())),
                identity());

        assertThat(response.target()).isEqualTo(target.target());
        assertThat(response.effectivePlan().authorizedPurpose()).isEqualTo("OPERATOR_UNIT_TEST");
        assertThat(response.effectivePlan().resolvedSites()).singleElement().satisfies(site -> {
            assertThat(site.invocationSiteId()).isEqualTo("/root/subject#PRIMARY");
            assertThat(site.behavior()).isEqualTo(FixtureRule.BehaviorKind.SPY);
        });
        assertThat(response.invocationSites()).singleElement().satisfies(site -> {
            assertThat(site.site().operatorRef()).isEqualTo("customer.greeting");
            assertThat(site.sideEffectType()).isEqualTo("READ_ONLY");
        });
        assertThat(response.rulePolicies()).singleElement().satisfies(rule ->
                assertThat(rule.ruleId()).isEqualTo("subject-spy"));
        assertThat(runs.values).isEmpty();
    }

    @Test
    void governedOutputDoubleCannotUpgradeAnOpaqueRuntimeToCertifiableEvidence() {
        TestOperatorTargetDescriptor target = service.describeOperatorTarget("legacy.external", identity());
        assertThat(target.testabilityClass()).isEqualTo(OperatorExecutionTargetSnapshot.OPAQUE_RUNTIME);
        assertThat(target.certificationEligible()).isFalse();
        FixtureBundle bundle = bundle("legacy-double", target.target().fingerprint(),
                new FixtureRule(FixtureRule.SCHEMA_VERSION, "subject-return",
                        FixtureRule.Selector.operator("legacy.external"),
                        FixtureRule.Behavior.returning(Map.of("status", "controlled")),
                        FixtureRule.Consumption.once(), FixtureRule.SchemaCheck.strict()));
        StoredFixtureBundle stored = service.registerFixture("legacy-double",
                new FixtureBundleRegistrationRequest("", target.target(), bundle), identity());

        TestExecutionApiResponse response = service.executeOperator("legacy.external",
                request(target.target(), Map.of(), null,
                        new TestExecutionApiRequest.FixtureBundleRef(
                                stored.fixtureBundleId(), stored.revision(), stored.fingerprint())), identity());

        assertThat(response.evidence().status()).isEqualTo(TestRunEvidence.Status.PASSED);
        assertThat(response.evidence().evidenceClass()).isEqualTo(TestRunEvidence.EvidenceClass.EXPLORATORY);
        assertThat(response.evidence().metadata())
                .containsEntry("testabilityClass", "OPAQUE_RUNTIME")
                .containsEntry("targetCertificationEligible", false);
    }

    @Test
    void configuredReadOnlyBindingNeedsAFormalStateSnapshotBeforeCertification() {
        TestOperatorTargetDescriptor target = service.describeOperatorTarget("configured.read", identity());

        assertThat(target.testabilityClass()).isEqualTo(OperatorExecutionTargetSnapshot.EXECUTABLE_UNIT);
        assertThat(target.executionSupported()).isTrue();
        assertThat(target.certificationEligible()).isFalse();
        assertThat(target.certificationGaps())
                .anyMatch(gap -> gap.contains("instance state") && gap.contains("snapshot contract"));
    }

    @Test
    void statelessReadOnlyBindingWithoutComposabilityManifestIsOpaque() {
        TestOperatorTargetDescriptor target = service.describeOperatorTarget("undeclared.read", identity());

        assertThat(target.runtimeBindingStateFingerprint()).startsWith("sha256:");
        assertThat(target.composabilityFingerprint()).isEmpty();
        assertThat(target.composabilityManifest()).isEmpty();
        assertThat(target.testabilityClass()).isEqualTo(OperatorExecutionTargetSnapshot.OPAQUE_RUNTIME);
        assertThat(target.executionSupported()).isTrue();
        assertThat(target.certificationEligible()).isFalse();
        assertThat(target.certificationGaps())
                .contains("Binding has no formal operator composability manifest; hidden dependencies cannot be excluded.");
    }

    @Test
    void declaredRuntimeControllableDependenciesAreConditionallyCertifiable() {
        TestOperatorTargetDescriptor target = service.describeOperatorTarget("declared.read", identity());

        assertThat(target.dependencyPolicy()).isEqualTo("DECLARED");
        assertThat(target.composabilityManifest()).containsEntry("dependencyMode", "DECLARED");
        List<String> dependencies = ((List<?>) target.composabilityManifest().get("dependencies"))
                .stream()
                .map(dependency -> String.valueOf(((Map<?, ?>) dependency).get("ref")))
                .toList();
        assertThat(dependencies).containsExactly("resource:customer-profile");
        assertThat(target.certificationEligible()).isTrue();
        assertThat(target.certificationGaps()).isEmpty();
        assertThat(target.certificationRequirements())
                .contains(
                        "Fixture bundle must define a strict controlled fixture for declared dependency ref '"
                                + "resource:customer-profile'.",
                        "Runtime binding must route declared dependency ref 'resource:customer-profile' through "
                                + "RESOURCE_BINDING.");
    }

    @Test
    void unmanagedDeclaredDependencyRemainsIneligibleWithAnExplicitGap() {
        TestOperatorTargetDescriptor target = service.describeOperatorTarget("unmanaged.declared", identity());

        assertThat(target.dependencyPolicy()).isEqualTo("DECLARED");
        assertThat(target.composabilityFingerprint()).startsWith("sha256:");
        assertThat(target.certificationEligible()).isFalse();
        assertThat(target.certificationGaps())
                .anyMatch(gap -> gap.contains("resource:customer-profile") && gap.contains("unmanaged"));
        assertThat(target.certificationRequirements())
                .contains("Fixture bundle must define a strict controlled fixture for declared dependency ref '"
                        + "resource:customer-profile'.");
    }

    @Test
    void targetFingerprintIncludesTheFormalManifestFingerprint() {
        ManifestSwitchingReadOperator operator = new ManifestSwitchingReadOperator();
        operators.register("manifest.switch", operator);

        OperatorExecutionTargetSnapshot baseline = OperatorExecutionTargetSnapshot.capture(
                mapper, "manifest.switch", operators, resources);
        operator.switchDependency("resource:other-profile");
        OperatorExecutionTargetSnapshot changed = OperatorExecutionTargetSnapshot.capture(
                mapper, "manifest.switch", operators, resources);

        assertThat(changed.implementationFingerprint()).isEqualTo(baseline.implementationFingerprint());
        assertThat(changed.runtimeBindingStateFingerprint())
                .isEqualTo(baseline.runtimeBindingStateFingerprint());
        assertThat(changed.composabilityFingerprint()).isNotEqualTo(baseline.composabilityFingerprint());
        assertThat(changed.fingerprint()).isNotEqualTo(baseline.fingerprint());
    }

    @Test
    void declaredControllableExecutionServiceBecomesAFixtureRequirement() {
        TestOperatorTargetDescriptor target = service.describeOperatorTarget("clock.read", identity());

        assertThat(target.composabilityFingerprint()).startsWith("sha256:");
        assertThat(target.composabilityManifest()).containsEntry("executionServices", List.of("TIME"));
        assertThat(target.testabilityClass()).isEqualTo(OperatorExecutionTargetSnapshot.EXECUTABLE_UNIT);
        assertThat(target.certificationEligible()).isTrue();
        assertThat(target.certificationRequirements())
                .contains("Fixture bundle must define logicalClock when the operator uses TIME.");
        assertThat(target.certificationGaps()).isEmpty();
    }

    @Test
    void identityAndFlagAreFixtureControllableWhileSecretRemainsUnsupported() {
        TestOperatorTargetDescriptor ambient = service.describeOperatorTarget("ambient.read", identity());
        TestOperatorTargetDescriptor secret = service.describeOperatorTarget("secret.read", identity());

        assertThat(ambient.testabilityClass()).isEqualTo(OperatorExecutionTargetSnapshot.EXECUTABLE_UNIT);
        assertThat(ambient.certificationEligible()).isTrue();
        assertThat(ambient.certificationRequirements()).containsExactly(
                "Fixture bundle metadata.executionServices.identityAttributes must bind every "
                        + "identity attribute used by the operator.",
                "Fixture bundle metadata.executionServices.featureFlags must bind every feature "
                        + "flag used by the operator.");
        assertThat(ambient.certificationGaps()).isEmpty();

        assertThat(secret.certificationEligible()).isFalse();
        assertThat(secret.certificationGaps())
                .contains("Operator consumes execution services without a governed test authority: "
                        + "[SECRET].");
    }

    @Test
    void explicitRuntimeBindingSnapshotMakesConfiguredReadOnlyBindingFreezable() {
        TestOperatorTargetDescriptor target = service.describeOperatorTarget(
                "configured.snapshot", identity());

        assertThat(target.testabilityClass()).isEqualTo(OperatorExecutionTargetSnapshot.EXECUTABLE_UNIT);
        assertThat(target.runtimeBindingStateFingerprint()).startsWith("sha256:");
        assertThat(target.certificationEligible()).isTrue();
        assertThat(target.certificationGaps()).isEmpty();
    }

    @Test
    void oversizedRuntimeBindingSnapshotFailsCertificationClosed() {
        TestOperatorTargetDescriptor target = service.describeOperatorTarget(
                "configured.oversized", identity());

        assertThat(target.runtimeBindingStateFingerprint()).isEmpty();
        assertThat(target.certificationEligible()).isFalse();
        assertThat(target.certificationGaps()).contains("Runtime-binding snapshot exceeds 65536 bytes.");
    }

    @Test
    void incompleteBehavioralContractIsDiscoverableButFailsCertificationClosed() {
        TestOperatorTargetDescriptor target = service.describeOperatorTarget("invalid.contract", identity());

        assertThat(target.sideEffectType()).isEqualTo("MIXED");
        assertThat(target.idempotency()).isEqualTo("UNKNOWN");
        assertThat(target.testabilityClass()).isEqualTo(OperatorExecutionTargetSnapshot.OPAQUE_RUNTIME);
        assertThat(target.executionSupported()).isTrue();
        assertThat(target.certificationEligible()).isFalse();
        assertThat(target.certificationGaps())
                .contains("Operator behavioral declarations are incomplete and cannot be certified.");
    }

    @Test
    void streamingBindingIsDiscoverableButV1ExecutionFailsBeforeFixtureResolution() {
        TestOperatorTargetDescriptor target = service.describeOperatorTarget("customer.events", identity());

        assertThat(target.executionModel()).isEqualTo("STREAMING");
        assertThat(target.executionSupported()).isFalse();
        assertThat(target.testabilityClass())
                .isEqualTo(OperatorExecutionTargetSnapshot.UNSUPPORTED_EXECUTION_MODEL);
        assertThatThrownBy(() -> service.executeOperator("customer.events",
                request(target.target(), Map.of(), bundle("inline", target.target().fingerprint()), null),
                identity()))
                .isInstanceOfSatisfying(IntegrationProblemException.class, failure ->
                        assertThat(failure.problem().code())
                                .isEqualTo("RG.TEST.OPERATOR_EXECUTION_MODEL_UNSUPPORTED"));
        assertThat(runs.values).isEmpty();
    }

    @Test
    void pathTargetMismatchAndStaleOperatorFingerprintFailClosed() {
        TestOperatorTargetDescriptor target = service.describeOperatorTarget(
                "customer.greeting", identity());
        TestExecutionApiRequest.Target wrong = new TestExecutionApiRequest.Target(
                "OPERATOR", "legacy.external", target.target().fingerprint());
        assertThatThrownBy(() -> service.executeOperator("customer.greeting",
                request(wrong, Map.of("name", "Ada"), bundle("inline", target.target().fingerprint()), null),
                identity()))
                .isInstanceOfSatisfying(IntegrationProblemException.class, failure ->
                        assertThat(failure.problem().code()).isEqualTo("RG.TEST.OPERATOR_TARGET_INVALID"));

        TestExecutionApiRequest.Target stale = new TestExecutionApiRequest.Target(
                "OPERATOR", "customer.greeting", "sha256:" + "0".repeat(64));
        assertThatThrownBy(() -> service.executeOperator("customer.greeting",
                request(stale, Map.of("name", "Ada"), bundle("inline", target.target().fingerprint()), null),
                identity()))
                .isInstanceOfSatisfying(IntegrationProblemException.class, failure ->
                        assertThat(failure.problem().code()).isEqualTo("RG.TEST.TARGET_FINGERPRINT_CONFLICT"));
        assertThat(runs.values).isEmpty();
    }

    @Test
    void staleFixtureFailsBeforeApplicationInputCoercion() {
        TestOperatorTargetDescriptor target = service.describeOperatorTarget(
                "customer.greeting", identity());
        FixtureBundle stale = bundle("stale", "sha256:" + "0".repeat(64));

        assertThatThrownBy(() -> service.executeOperator("customer.greeting",
                request(target.target(), Map.of("unknown", Map.of("nested", true)), stale, null),
                identity()))
                .isInstanceOfSatisfying(IntegrationProblemException.class, failure ->
                        assertThat(failure.problem().code()).isEqualTo("RG.TEST.FIXTURE_TARGET_STALE"));
        assertThat(runs.values).isEmpty();
    }

    private TestOperatorExecutionApiRequest request(TestExecutionApiRequest.Target target, Object input,
                                                    FixtureBundle inline,
                                                    TestExecutionApiRequest.FixtureBundleRef reference) {
        return new TestOperatorExecutionApiRequest("", target, "OPERATOR_UNIT_TEST", input,
                inline, reference, TestExecutionApiRequest.Verbosity.FULL,
                Map.of("suiteRef", "operator-contracts", "caseRef", "case-1"));
    }

    private static FixtureBundle bundle(String id, String targetFingerprint, FixtureRule... rules) {
        return new FixtureBundle(FixtureBundle.SCHEMA_VERSION, id, 1, targetFingerprint,
                "INTERNAL", null, null, List.of(rules), List.of(), Map.of());
    }

    private static IntegrationRequestContext identity() {
        return new IntegrationRequestContext("tenant-a", "org-a", "project-a", "test",
                "local", "WORKLOAD", "test-runner", "", "TEST_EXECUTION", "correlation-1",
                java.util.Set.of("quality"), "CONFIDENTIAL", "");
    }

    private record GreetingInput(String name) {
    }

    private static final class GreetingOperator implements Operator<GreetingInput, Map<String, Object>>,
            OperatorComposabilityManifestProvider {
        @Override
        public Map<String, Object> execute(GreetingInput input, OperatorContext context) {
            return Map.of("message", "Hello " + input.name());
        }

        @Override
        public SideEffectType sideEffectType() {
            return SideEffectType.READ_ONLY;
        }

        @Override
        public OperatorComposabilityManifest operatorComposabilityManifest() {
            return selfContained("test:customer-greeting");
        }
    }

    private static final class OpaqueExternalOperator implements Operator<Object, Object> {
        @Override
        public Object execute(Object input, OperatorContext context) {
            throw new AssertionError("The governed output double must replace this opaque binding");
        }

        @Override
        public SideEffectType sideEffectType() {
            return SideEffectType.EXTERNAL_CALL;
        }
    }

    private static final class ConfiguredReadOperator implements Operator<Object, Object>,
            OperatorComposabilityManifestProvider {
        private final String prefix;

        private ConfiguredReadOperator(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Object execute(Object input, OperatorContext context) {
            return prefix + input;
        }

        @Override
        public SideEffectType sideEffectType() {
            return SideEffectType.READ_ONLY;
        }

        @Override
        public OperatorComposabilityManifest operatorComposabilityManifest() {
            return selfContained("test:configured-read");
        }
    }

    private static final class SnapshotConfiguredReadOperator implements Operator<Object, Object>,
            OperatorRuntimeBindingSnapshotProvider, OperatorComposabilityManifestProvider {
        private final String prefix;

        private SnapshotConfiguredReadOperator(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Object execute(Object input, OperatorContext context) {
            return prefix + input;
        }

        @Override
        public SideEffectType sideEffectType() {
            return SideEffectType.READ_ONLY;
        }

        @Override
        public Map<String, ?> runtimeBindingSnapshot() {
            return Map.of("prefix", prefix);
        }

        @Override
        public OperatorComposabilityManifest operatorComposabilityManifest() {
            return selfContained("test:configured-snapshot");
        }
    }

    private static final class OversizedSnapshotOperator implements Operator<Object, Object>,
            OperatorRuntimeBindingSnapshotProvider, OperatorComposabilityManifestProvider {
        private final Object marker = new Object();

        @Override
        public Object execute(Object input, OperatorContext context) {
            return input;
        }

        @Override
        public SideEffectType sideEffectType() {
            return SideEffectType.READ_ONLY;
        }

        @Override
        public Map<String, ?> runtimeBindingSnapshot() {
            return Map.of("value", "x".repeat(65_537));
        }

        @Override
        public OperatorComposabilityManifest operatorComposabilityManifest() {
            return selfContained("test:oversized-snapshot");
        }
    }

    private static final class UndeclaredReadOperator implements Operator<Object, Object> {
        @Override
        public Object execute(Object input, OperatorContext context) {
            return input;
        }

        @Override
        public SideEffectType sideEffectType() {
            return SideEffectType.READ_ONLY;
        }
    }

    private static class DeclaredResourceReadOperator implements Operator<Object, Object>,
            OperatorComposabilityManifestProvider, OperatorRuntimeBindingSnapshotProvider {
        private final OperatorComposabilityManifest.ControlBoundary boundary;

        private DeclaredResourceReadOperator(OperatorComposabilityManifest.ControlBoundary boundary) {
            this.boundary = boundary;
        }

        @Override
        public Object execute(Object input, OperatorContext context) {
            return input;
        }

        @Override
        public SideEffectType sideEffectType() {
            return SideEffectType.READ_ONLY;
        }

        @Override
        public Map<String, ?> runtimeBindingSnapshot() {
            return Map.of("binding", "declared-resource-reader-v1");
        }

        @Override
        public OperatorComposabilityManifest operatorComposabilityManifest() {
            return new OperatorComposabilityManifest(
                    OperatorComposabilityManifest.SCHEMA_VERSION,
                    OperatorComposabilityManifest.DependencyMode.DECLARED,
                    List.of(new OperatorComposabilityManifest.Dependency(
                            "resource:customer-profile",
                            OperatorComposabilityManifest.DependencyKind.RESOURCE,
                            boundary)),
                    List.of(),
                    true,
                    "test:declared-resource-reader",
                    CONFORMANCE_FINGERPRINT);
        }
    }

    private static final class UnmanagedDeclaredReadOperator extends DeclaredResourceReadOperator {
        private UnmanagedDeclaredReadOperator() {
            super(OperatorComposabilityManifest.ControlBoundary.UNMANAGED);
        }
    }

    private static final class ManifestSwitchingReadOperator implements Operator<Object, Object>,
            OperatorComposabilityManifestProvider, OperatorRuntimeBindingSnapshotProvider {
        private String dependencyRef = "resource:customer-profile";

        @Override
        public Object execute(Object input, OperatorContext context) {
            return input;
        }

        @Override
        public SideEffectType sideEffectType() {
            return SideEffectType.READ_ONLY;
        }

        @Override
        public Map<String, ?> runtimeBindingSnapshot() {
            return Map.of("binding", "manifest-switch-reader-v1");
        }

        @Override
        public OperatorComposabilityManifest operatorComposabilityManifest() {
            return new OperatorComposabilityManifest(
                    OperatorComposabilityManifest.SCHEMA_VERSION,
                    OperatorComposabilityManifest.DependencyMode.DECLARED,
                    List.of(new OperatorComposabilityManifest.Dependency(
                            dependencyRef,
                            OperatorComposabilityManifest.DependencyKind.RESOURCE,
                            OperatorComposabilityManifest.ControlBoundary.RESOURCE_BINDING)),
                    List.of(),
                    true,
                    "test:manifest-switch-reader",
                    CONFORMANCE_FINGERPRINT);
        }

        private void switchDependency(String ref) {
            dependencyRef = ref;
        }
    }

    private static final class ClockDependentReadOperator implements Operator<Object, Object>,
            OperatorComposabilityManifestProvider {
        @Override
        public Object execute(Object input, OperatorContext context) {
            return input;
        }

        @Override
        public SideEffectType sideEffectType() {
            return SideEffectType.READ_ONLY;
        }

        @Override
        public OperatorComposabilityManifest operatorComposabilityManifest() {
            return new OperatorComposabilityManifest(OperatorComposabilityManifest.SCHEMA_VERSION,
                    OperatorComposabilityManifest.DependencyMode.NONE, List.of(),
                    List.of(OperatorComposabilityManifest.ExecutionService.TIME), true,
                    "test:clock-read", CONFORMANCE_FINGERPRINT);
        }
    }

    private static final class AmbientDependentReadOperator implements Operator<Object, Object>,
            OperatorComposabilityManifestProvider, OperatorRuntimeBindingSnapshotProvider {
        private final List<OperatorComposabilityManifest.ExecutionService> services;

        private AmbientDependentReadOperator(
                List<OperatorComposabilityManifest.ExecutionService> services) {
            this.services = List.copyOf(services);
        }

        @Override
        public Object execute(Object input, OperatorContext context) {
            return input;
        }

        @Override
        public SideEffectType sideEffectType() {
            return SideEffectType.READ_ONLY;
        }

        @Override
        public Map<String, ?> runtimeBindingSnapshot() {
            return Map.of("executionServices", services.stream().map(Enum::name).toList());
        }

        @Override
        public OperatorComposabilityManifest operatorComposabilityManifest() {
            return new OperatorComposabilityManifest(OperatorComposabilityManifest.SCHEMA_VERSION,
                    OperatorComposabilityManifest.DependencyMode.NONE, List.of(), services, true,
                    "test:ambient-read", CONFORMANCE_FINGERPRINT);
        }
    }

    private static final class InvalidBehaviorContractOperator implements Operator<Object, Object>,
            OperatorComposabilityManifestProvider {
        @Override
        public Object execute(Object input, OperatorContext context) {
            return input;
        }

        @Override
        public SideEffectType sideEffectType() {
            return null;
        }

        @Override
        public OperatorComposabilityManifest operatorComposabilityManifest() {
            return selfContained("test:invalid-behavior");
        }
    }

    private static OperatorComposabilityManifest selfContained(String suiteRef) {
        return OperatorComposabilityManifest.selfContained(suiteRef, CONFORMANCE_FINGERPRINT);
    }

    private static final class EventStreamOperator implements StreamingOperator<Object, Object> {
        @Override
        public void execute(Object input, NodeChannel<Object> output, OperatorContext context) throws Exception {
            output.send(input);
        }
    }

    private static final class EmptyResourceRegistry implements ResourceRegistry {
        @Override public ResourceDescriptor resolve(String resourceId) { throw new IllegalArgumentException(); }
        @Override public boolean contains(String resourceId) { return false; }
        @Override public java.util.Collection<ResourceDescriptor> all() { return List.of(); }
    }

    private static final class InMemoryFixtures implements FixtureBundleRepository {
        private final Map<String, StoredFixtureBundle> values = new LinkedHashMap<>();
        @Override public StoredFixtureBundle create(StoredFixtureBundle value) {
            String key = key(value.tenantId(), value.environmentId(), value.fixtureBundleId(), value.revision());
            StoredFixtureBundle existing = values.putIfAbsent(key, value);
            if (existing != null && !existing.fingerprint().equals(value.fingerprint())) {
                throw new FixtureBundleConflictException("immutable conflict");
            }
            return existing == null ? value : existing;
        }
        @Override public Optional<StoredFixtureBundle> find(String tenant, String environment,
                                                            String id, long revision) {
            return Optional.ofNullable(values.get(key(tenant, environment, id, revision)));
        }
        @Override public StoredFixtureBundle create(
                TestingArtifactScope scope, StoredFixtureBundle value) {
            String key = key(scope, value.fixtureBundleId(), value.revision());
            StoredFixtureBundle existing = values.putIfAbsent(key, value);
            if (existing != null && !existing.fingerprint().equals(value.fingerprint())) {
                throw new FixtureBundleConflictException("immutable conflict");
            }
            return existing == null ? value : existing;
        }
        @Override public Optional<StoredFixtureBundle> find(
                TestingArtifactScope scope, String id, long revision) {
            return Optional.ofNullable(values.get(key(scope, id, revision)));
        }
        private static String key(String tenant, String environment, String id, long revision) {
            return tenant + "|" + environment + "|" + id + "|" + revision;
        }
        private static String key(TestingArtifactScope scope, String id, long revision) {
            return scope + "|" + id + "|" + revision;
        }
    }

    private static final class InMemoryRuns implements TestRunRepository {
        private final Map<String, TestRunRecord> values = new LinkedHashMap<>();
        @Override public TestRunRecord create(TestRunRecord record) {
            values.put(record.runId(), record);
            return record;
        }
        @Override public Optional<TestRunRecord> find(String tenant, String environment, String runId) {
            TestRunRecord record = values.get(runId);
            return record != null && record.tenantId().equals(tenant)
                    && record.environmentId().equals(environment) ? Optional.of(record) : Optional.empty();
        }
    }

    private static final class InMemorySecurityEvents implements TestSecurityEventRepository {
        private final List<TestSecurityEvent> events = new ArrayList<>();
        @Override public TestSecurityEvent append(TestSecurityEvent event) {
            TestSecurityEvent stored = event.withSequence(events.size() + 1L);
            events.add(stored);
            return stored;
        }
        @Override public List<TestSecurityEvent> recent(int limit) { return List.copyOf(events); }
    }
}
