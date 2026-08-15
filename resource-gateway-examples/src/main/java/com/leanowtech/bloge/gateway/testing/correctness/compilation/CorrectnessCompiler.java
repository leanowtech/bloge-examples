package com.leanowtech.bloge.gateway.testing.correctness.compilation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.FixtureBundleRegistrationRequest;
import com.leanowtech.bloge.gateway.testing.api.TestExecutionApiRequest;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteRegistrationRequest;
import com.leanowtech.bloge.gateway.testing.correctness.compilation.CorrectnessCompilationReport.CompiledAssetSummary;
import com.leanowtech.bloge.gateway.testing.correctness.compilation.CorrectnessCompilationReport.Diagnostic;
import com.leanowtech.bloge.gateway.testing.correctness.compilation.CorrectnessCompilationReport.DiagnosticSeverity;
import com.leanowtech.bloge.gateway.testing.correctness.compilation.CorrectnessCompilationReport.ExecutionRiskSummary;
import com.leanowtech.bloge.gateway.testing.correctness.compilation.CorrectnessCompilationReport.OutputCoordinate;
import com.leanowtech.bloge.gateway.testing.correctness.compilation.CorrectnessCompilationReport.SourceCoordinate;
import com.leanowtech.bloge.gateway.testing.correctness.compilation.CorrectnessCompilationReport.SourceMapping;
import com.leanowtech.bloge.gateway.testing.correctness.compilation.FrozenCompilationInput.MaterializedFixture;
import com.leanowtech.bloge.gateway.testing.correctness.domain.AssertionSet;
import com.leanowtech.bloge.gateway.testing.correctness.domain.BusinessOracle;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessDefinition.DefinitionLifecycle;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactObligationRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactTargetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessPublication.CompilationCoordinate;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CoverageInventory;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CoverageInventory.InventoryLifecycle;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CoverageInventory.ObligationLifecycle;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureAssetDescriptor.FixtureLifecycle;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.BehaviorBoundary;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.BehaviorKind;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.ControlledBehavior;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.ControlledDependencyV2;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.ExhaustionPolicy;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.FixtureVariantRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.GeneratedValueRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.InlineValue;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.ReplayMaterialRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.ScenarioDraftV2;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.ScenarioLifecycle;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.UnmatchedPolicy;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.ValueSource;
import com.leanowtech.bloge.gateway.testing.correctness.oracle.AssertionCompilationReport;
import com.leanowtech.bloge.gateway.testing.correctness.oracle.AssertionCompilationReport.DispositionStatus;
import com.leanowtech.bloge.gateway.testing.correctness.oracle.AssertionEvaluatorProfile;
import com.leanowtech.bloge.gateway.testing.correctness.oracle.AssertionSetCompiler;
import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;
import com.leanowtech.bloge.gateway.testing.domain.FixtureRule;
import com.leanowtech.bloge.gateway.testing.domain.InvocationSite;
import com.leanowtech.bloge.gateway.testing.domain.ReplayPayloadRef;
import com.leanowtech.bloge.gateway.testing.domain.TestSuite;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Pure, deterministic compiler from exact correctness authoring snapshots to testing v1 assets. */
public final class CorrectnessCompiler {

    public static final String COMPILER_VERSION = "bloge.correctnessCompiler.v1";
    private static final int MAX_PROTOCOL_BYTES = 16 * 1_048_576;
    private static final Instant LOGICAL_CLOCK = Instant.parse("2000-01-01T00:00:00Z");

    private final ObjectMapper mapper;
    private final AssertionSetCompiler assertionCompiler;
    private final AssertionEvaluatorProfile evaluatorProfile;

    public CorrectnessCompiler(
            ObjectMapper mapper,
            AssertionSetCompiler assertionCompiler,
            AssertionEvaluatorProfile evaluatorProfile
    ) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.assertionCompiler = Objects.requireNonNull(assertionCompiler, "assertionCompiler");
        this.evaluatorProfile = Objects.requireNonNull(evaluatorProfile, "evaluatorProfile");
    }

    /** Compiles an already authorized frozen input and returns only its payload-free report. */
    public CorrectnessCompilationReport compileReport(FrozenCompilationInput input) {
        return compile(input).report();
    }

    CompiledCorrectnessPlan compile(FrozenCompilationInput input) {
        Objects.requireNonNull(input, "input");
        List<Diagnostic> diagnostics = new ArrayList<>();
        CompilationCoordinate coordinate = input.coordinate();
        Map<ExactAssetRef, MaterializedFixture> fixtures = input.fixtures().stream()
                .collect(Collectors.toMap(MaterializedFixture::descriptorRef, Function.identity()));
        Map<ExactAssetRef, BusinessOracle> oracles = indexOracles(input);
        Map<ExactAssetRef, AssertionSet> assertionSets = indexAssertionSets(input);

        validateExactClosure(input, fixtures.keySet(), oracles.keySet(), assertionSets.keySet(),
                diagnostics);
        validateAuthority(input, fixtures, oracles, assertionSets, diagnostics);
        ExecutionRiskSummary riskSummary = riskSummary(input.scenarioDraftSet());
        riskDiagnostics(riskSummary, coordinate.scenarioDraftSetRef(), diagnostics);
        if (hasErrors(diagnostics)) {
            return blocked(coordinate, diagnostics, riskSummary);
        }

        TestExecutionApiRequest.Target runtimeTarget = new TestExecutionApiRequest.Target(
                coordinate.target().kind().name(), coordinate.target().id(),
                coordinate.target().fingerprint());
        List<FixtureBundleRegistrationRequest> fixtureRegistrations = new ArrayList<>();
        List<TestSuite.TestCase> cases = new ArrayList<>();
        List<PendingMapping> mappings = new ArrayList<>();
        int minimumAssertions = Integer.MAX_VALUE;

        for (ScenarioDraftV2 scenario : input.scenarioDraftSet().scenarios().stream()
                .sorted(Comparator.comparing(ScenarioDraftV2::scenarioId)).toList()) {
            CaseCompilation compiledCase = compileCase(
                    input, scenario, fixtures, assertionSets, runtimeTarget, diagnostics);
            if (compiledCase == null || hasErrors(diagnostics)) continue;
            fixtureRegistrations.add(compiledCase.registration());
            cases.add(compiledCase.testCase());
            mappings.addAll(compiledCase.mappings());
            minimumAssertions = Math.min(
                    minimumAssertions,
                    compiledCase.registration().fixtureBundle().assertions().size());
        }
        if (hasErrors(diagnostics)) {
            return blocked(coordinate, diagnostics, riskSummary);
        }

        TestSuite suite = suite(input, runtimeTarget, cases,
                minimumAssertions == Integer.MAX_VALUE ? 0 : minimumAssertions);
        String suiteFingerprint = ProtocolFingerprint.ofBounded(
                mapper, suite, MAX_PROTOCOL_BYTES);
        ExactAssetRef suiteRef = new ExactAssetRef(
                "TEST_SUITE", suite.suiteId(), suite.revision(), suiteFingerprint);
        TestSuiteRegistrationRequest suiteRegistration =
                new TestSuiteRegistrationRequest("", suite);
        List<SourceMapping> sourceMap = materializeMappings(mappings, suiteRef);
        List<CompiledAssetSummary> assets = compiledAssets(
                fixtureRegistrations, suiteRef, sourceMap);
        String fingerprint = compilationFingerprint(
                coordinate, fixtureRegistrations, suiteRegistration,
                sourceMap, assets, diagnostics, riskSummary);
        CorrectnessCompilationReport report = new CorrectnessCompilationReport(
                "", true, COMPILER_VERSION, coordinate, fingerprint,
                sourceMap, assets, diagnostics, riskSummary);
        return new CompiledCorrectnessPlan(report, fixtureRegistrations, suiteRegistration);
    }

    private CaseCompilation compileCase(
            FrozenCompilationInput input,
            ScenarioDraftV2 scenario,
            Map<ExactAssetRef, MaterializedFixture> fixtures,
            Map<ExactAssetRef, AssertionSet> assertionSets,
            TestExecutionApiRequest.Target runtimeTarget,
            List<Diagnostic> diagnostics
    ) {
        ExactAssetRef scenarioSetRef = input.coordinate().scenarioDraftSetRef();
        List<PendingAssertion> assertions = compileAssertions(
                scenario, assertionSets, diagnostics);
        Object caseInput = resolveValue(
                scenario.given().input(), fixtures, scenarioSetRef,
                scenarioPath(scenario) + "/given/input", diagnostics);
        List<FixtureRule> rules = new ArrayList<>();
        List<PendingMapping> pending = new ArrayList<>();
        for (ControlledDependencyV2 dependency : scenario.dependencies()) {
            FixtureRule rule = rule(
                    dependency, fixtures, scenarioSetRef,
                    scenarioPath(scenario) + "/dependencies/" + dependency.dependencyId(),
                    diagnostics);
            if (rule != null) {
                rules.add(rule);
            }
            ExactAssetRef sourceFixture = fixtureRef(dependency.behavior().value());
            if (sourceFixture != null) {
                pending.add(PendingMapping.fixtureToRule(
                        sourceFixture, dependency.dependencyId()));
            }
        }
        if (assertions.isEmpty()) {
            diagnostics.add(Diagnostic.error(
                    "RG.CORRECTNESS.ASSERTION_NONE", scenarioSetRef,
                    scenarioPath(scenario) + "/assertionSetRefs",
                    "correctness.compilation.assertionNone"));
        }
        if (hasErrors(diagnostics)) return null;

        List<FixtureBundle.Assertion> runtimeAssertions = assertions.stream()
                .map(PendingAssertion::assertion).toList();
        String classification = classification(scenario, fixtures);
        boolean logicalTime = scenario.dependencies().stream().anyMatch(dependency ->
                dependency.behavior().kind() == BehaviorKind.DELAY
                        || dependency.behavior().kind() == BehaviorKind.TIMEOUT);
        Map<String, Object> metadata = Map.of(
                "source", "correctness-authoring",
                "scenarioDraftSetId", input.scenarioDraftSet().scenarioDraftSetId(),
                "scenarioDraftSetRevision", input.scenarioDraftSet().revision(),
                "scenarioId", scenario.scenarioId(),
                "compilerVersion", COMPILER_VERSION);
        FixtureBundle idMaterial = new FixtureBundle(
                "", "", 1, runtimeTarget.fingerprint(), classification,
                logicalTime ? LOGICAL_CLOCK : null, null, rules, runtimeAssertions, metadata);
        String fixtureId = contentAddressedId(
                "correctness-" + input.scenarioDraftSet().scenarioDraftSetId()
                        + '-' + scenario.scenarioId(),
                ProtocolFingerprint.ofBounded(mapper, idMaterial, MAX_PROTOCOL_BYTES));
        FixtureBundle bundle = new FixtureBundle(
                "", fixtureId, 1, runtimeTarget.fingerprint(), classification,
                logicalTime ? LOGICAL_CLOCK : null, null, rules, runtimeAssertions, metadata);
        String bundleFingerprint = ProtocolFingerprint.ofBounded(
                mapper, bundle, MAX_PROTOCOL_BYTES);
        ExactAssetRef bundleRef = new ExactAssetRef(
                "FIXTURE_BUNDLE", bundle.fixtureBundleId(), bundle.revision(), bundleFingerprint);

        pending.add(PendingMapping.scenarioToFixture(
                scenarioSetRef, scenario.scenarioId(), bundleRef));
        for (int index = 0; index < assertions.size(); index++) {
            PendingAssertion assertion = assertions.get(index);
            pending.add(PendingMapping.exact(
                    new SourceCoordinate(
                            assertion.assertionSetRef(), "ASSERTION", assertion.assertionId()),
                    new OutputCoordinate(
                            bundleRef, "FIXTURE_ASSERTION", "assertion-" + index)));
        }
        ExactAssetRef givenFixture = fixtureRef(scenario.given().input());
        if (givenFixture != null) {
            pending.add(PendingMapping.exact(
                    new SourceCoordinate(givenFixture, "FIXTURE_VARIANT",
                            fixtureVariantKey(scenario.given().input())),
                    new OutputCoordinate(bundleRef, "TEST_INPUT_SOURCE", scenario.scenarioId())));
        }
        pending.replaceAll(mapping -> mapping.bindFixture(bundleRef));

        TestSuite.TestCase testCase = new TestSuite.TestCase(
                scenario.scenarioId(),
                TestSuite.CaseType.valueOf(scenario.caseType().name()),
                caseInput,
                new TestSuite.FixtureBundleRef(
                        bundle.fixtureBundleId(), bundle.revision(), bundleFingerprint),
                scenario.tags(),
                Map.of(
                        "source", "correctness-authoring",
                        "scenarioDraftSetId", input.scenarioDraftSet().scenarioDraftSetId(),
                        "scenarioDraftSetRevision", input.scenarioDraftSet().revision(),
                        "scenarioId", scenario.scenarioId()));
        pending.add(PendingMapping.scenarioToCase(
                scenarioSetRef, scenario.scenarioId(), scenario.scenarioId()));
        for (ExactObligationRef obligation : scenario.obligationRefs()) {
            pending.add(PendingMapping.obligationToCase(
                    obligation.inventoryRef(), obligation.obligationId(), scenario.scenarioId()));
        }
        for (ExactAssetRef oracleRef : scenario.oracleRefs()) {
            pending.add(PendingMapping.oracleToFixture(
                    oracleRef, scenario.scenarioId(), bundleRef));
        }
        return new CaseCompilation(
                new FixtureBundleRegistrationRequest("", runtimeTarget, bundle),
                testCase, pending);
    }

    private List<PendingAssertion> compileAssertions(
            ScenarioDraftV2 scenario,
            Map<ExactAssetRef, AssertionSet> assertionSets,
            List<Diagnostic> diagnostics
    ) {
        List<PendingAssertion> result = new ArrayList<>();
        for (ExactAssetRef ref : scenario.assertionSetRefs()) {
            AssertionSet source = assertionSets.get(ref);
            if (source == null) continue;
            AssertionCompilationReport report = assertionCompiler.compile(source, evaluatorProfile);
            int loweredIndex = 0;
            for (AssertionCompilationReport.AssertionDisposition disposition
                    : report.dispositions()) {
                if (disposition.status() == DispositionStatus.COMPILED_RUNTIME) {
                    if (loweredIndex >= report.runtimeAssertions().size()) {
                        diagnostics.add(Diagnostic.error(
                                "RG.CORRECTNESS.ASSERTION_LOWERING_INCOMPLETE", ref,
                                "/assertions/" + disposition.assertionId(),
                                "correctness.compilation.assertionLoweringIncomplete"));
                        continue;
                    }
                    result.add(new PendingAssertion(
                            ref, disposition.assertionId(),
                            report.runtimeAssertions().get(loweredIndex++)));
                } else if (disposition.status() == DispositionStatus.RETAINED_GATE) {
                    // The exact Assertion Set remains in the Publication manifest as gate authority.
                } else {
                    diagnostics.add(Diagnostic.error(
                            disposition.reasonCode().isBlank()
                                    ? "RG.CORRECTNESS.ASSERTION_NOT_RUNTIME"
                                    : disposition.reasonCode(),
                            ref, "/assertions/" + disposition.assertionId(),
                            "correctness.compilation.assertionNotRuntime"));
                }
            }
            if (loweredIndex != report.runtimeAssertions().size()) {
                diagnostics.add(Diagnostic.error(
                        "RG.CORRECTNESS.ASSERTION_LOWERING_UNACCOUNTED", ref,
                        "/assertions",
                        "correctness.compilation.assertionLoweringUnaccounted"));
            }
        }
        return result;
    }

    private FixtureRule rule(
            ControlledDependencyV2 source,
            Map<ExactAssetRef, MaterializedFixture> fixtures,
            ExactAssetRef scenarioSetRef,
            String path,
            List<Diagnostic> diagnostics
    ) {
        if (!source.selector().functionRef().isBlank()) {
            diagnostics.add(Diagnostic.error(
                    "RG.CORRECTNESS.FUNCTION_SELECTOR_UNSUPPORTED", scenarioSetRef,
                    path + "/selector/functionRef",
                    "correctness.compilation.functionSelectorUnsupported"));
        }
        if (source.consumption().onExhausted() == ExhaustionPolicy.REPEAT_LAST) {
            diagnostics.add(Diagnostic.error(
                    "RG.CORRECTNESS.REPEAT_LAST_UNSUPPORTED", scenarioSetRef,
                    path + "/consumption/onExhausted",
                    "correctness.compilation.repeatLastUnsupported"));
        }
        if (source.behavior().boundary() == BehaviorBoundary.TRANSPORT
                && source.behavior().kind() != BehaviorKind.REAL
                && source.behavior().kind() != BehaviorKind.OBSERVE) {
            diagnostics.add(Diagnostic.error(
                    "RG.CORRECTNESS.TRANSPORT_LOWERING_UNSUPPORTED", scenarioSetRef,
                    path + "/behavior/boundary",
                    "correctness.compilation.transportLoweringUnsupported"));
        }
        FixtureRule.Behavior behavior = behavior(
                source.behavior(), fixtures, scenarioSetRef, path + "/behavior", diagnostics);
        if (hasErrors(diagnostics)) return null;

        Map<String, Object> pathEquals = new LinkedHashMap<>();
        source.selector().pathMatches().forEach(match ->
                pathEquals.put(match.path(), match.expected()));
        FixtureRule.Selector selector = new FixtureRule.Selector(
                source.selector().graphPath(), source.selector().nodeId(),
                source.selector().operatorRef(), source.selector().resourceRef(),
                source.selector().functionRef(), List.of(), List.of(),
                invocationKind(source), source.selector().attempts(),
                source.selector().occurrences(), source.selector().correlationKey(),
                new FixtureRule.Match(
                        null, pathEquals, List.of(), List.of(), Map.of(), "", Map.of()));
        FixtureRule.Consumption consumption = new FixtureRule.Consumption(
                source.consumption().required(), source.consumption().minUses(),
                source.consumption().maxUses(),
                source.consumption().onExhausted() == ExhaustionPolicy.FALLBACK_TO_REAL
                        ? FixtureRule.ExhaustedAction.FALLBACK_TO_REAL
                        : FixtureRule.ExhaustedAction.FAIL,
                source.consumption().onUnmatched() == UnmatchedPolicy.ALLOW_REAL
                        ? FixtureRule.UnmatchedAction.ALLOW_REAL
                        : FixtureRule.UnmatchedAction.FAIL);
        return new FixtureRule(
                "", source.dependencyId(), selector, behavior, consumption,
                FixtureRule.SchemaCheck.strict());
    }

    private FixtureRule.Behavior behavior(
            ControlledBehavior source,
            Map<ExactAssetRef, MaterializedFixture> fixtures,
            ExactAssetRef scenarioSetRef,
            String path,
            List<Diagnostic> diagnostics
    ) {
        FixtureRule.DoubleBoundary boundary = FixtureRule.DoubleBoundary.valueOf(
                source.boundary().name());
        Object value = null;
        if (source.kind() == BehaviorKind.RETURN || source.kind() == BehaviorKind.DELAY) {
            value = resolveValue(source.value(), fixtures, scenarioSetRef, path + "/value", diagnostics);
        }
        if ((source.kind() == BehaviorKind.DELAY || source.kind() == BehaviorKind.TIMEOUT)
                && source.delayMs() < 1) {
            diagnostics.add(Diagnostic.error(
                    "RG.CORRECTNESS.LOGICAL_DELAY_INVALID", scenarioSetRef,
                    path + "/delayMs", "correctness.compilation.logicalDelayInvalid"));
        }
        return switch (source.kind()) {
            case REAL -> new FixtureRule.Behavior(
                    FixtureRule.BehaviorKind.REAL, boundary, null, "", null, Map.of(),
                    "", "", "", null, List.of(), "");
            case RETURN -> new FixtureRule.Behavior(
                    FixtureRule.BehaviorKind.RETURN, boundary, value, "", null, Map.of(),
                    "", "", "", null, List.of(), "");
            case ERROR -> new FixtureRule.Behavior(
                    FixtureRule.BehaviorKind.THROW, boundary, null, "", null, Map.of(),
                    source.errorCode(), "CONTROLLED_ERROR", "", null, List.of(), "");
            case DELAY -> new FixtureRule.Behavior(
                    FixtureRule.BehaviorKind.DELAY, boundary, value, "", null, Map.of(),
                    "", "", "", Duration.ofMillis(source.delayMs()), List.of(), "");
            case TIMEOUT -> new FixtureRule.Behavior(
                    FixtureRule.BehaviorKind.TIMEOUT, boundary, null, "", null, Map.of(),
                    source.errorCode().isBlank() ? "TEST_TIMEOUT" : source.errorCode(),
                    "TIMEOUT", "", Duration.ofMillis(source.delayMs()), List.of(), "");
            case REPLAY -> replayBehavior(source, scenarioSetRef, path, diagnostics);
            case OBSERVE -> new FixtureRule.Behavior(
                    FixtureRule.BehaviorKind.SPY, boundary, null, "", null, Map.of(),
                    "", "", "", null, List.of(), "");
            case MUST_NOT_CALL -> new FixtureRule.Behavior(
                    FixtureRule.BehaviorKind.DENY, boundary, null, "", null, Map.of(),
                    source.errorCode().isBlank()
                            ? "CORRECTNESS_MUST_NOT_CALL" : source.errorCode(),
                    "DENIED_INVOCATION", "", null, List.of(), "");
        };
    }

    private static FixtureRule.Behavior replayBehavior(
            ControlledBehavior source,
            ExactAssetRef scenarioSetRef,
            String path,
            List<Diagnostic> diagnostics
    ) {
        if (!(source.value() instanceof ReplayMaterialRef replay)
                || !"REPLAY_PAYLOAD".equals(replay.replayMaterialRef().kind())) {
            diagnostics.add(Diagnostic.error(
                    "RG.CORRECTNESS.REPLAY_REFERENCE_INVALID", scenarioSetRef,
                    path + "/value", "correctness.compilation.replayReferenceInvalid"));
            return FixtureRule.Behavior.real();
        }
        ExactAssetRef ref = replay.replayMaterialRef();
        String canonical = new ReplayPayloadRef(
                ref.id(), ref.revision(), ref.fingerprint()).canonical();
        return new FixtureRule.Behavior(
                FixtureRule.BehaviorKind.REPLAY, FixtureRule.DoubleBoundary.NODE,
                null, "", null, Map.of(), "", "", "", null, List.of(), canonical);
    }

    private Object resolveValue(
            ValueSource source,
            Map<ExactAssetRef, MaterializedFixture> fixtures,
            ExactAssetRef scenarioSetRef,
            String path,
            List<Diagnostic> diagnostics
    ) {
        if (source instanceof InlineValue inline) return inline.value();
        if (source instanceof FixtureVariantRef fixtureRef) {
            MaterializedFixture fixture = fixtures.get(fixtureRef.fixtureAssetRef());
            if (fixture == null
                    || !fixture.descriptor().variantKey().equals(fixtureRef.variantKey())) {
                diagnostics.add(Diagnostic.error(
                        "RG.CORRECTNESS.FIXTURE_VARIANT_UNAVAILABLE", scenarioSetRef,
                        path, "correctness.compilation.fixtureVariantUnavailable"));
                return Map.of();
            }
            return fixture.payload();
        }
        if (source instanceof GeneratedValueRef) {
            diagnostics.add(Diagnostic.error(
                    "RG.CORRECTNESS.GENERATOR_LOWERING_UNSUPPORTED", scenarioSetRef,
                    path, "correctness.compilation.generatorLoweringUnsupported"));
            return Map.of();
        }
        if (source instanceof ReplayMaterialRef) {
            diagnostics.add(Diagnostic.error(
                    "RG.CORRECTNESS.REPLAY_INPUT_UNSUPPORTED", scenarioSetRef,
                    path, "correctness.compilation.replayInputUnsupported"));
            return Map.of();
        }
        diagnostics.add(Diagnostic.error(
                "RG.CORRECTNESS.VALUE_SOURCE_UNKNOWN", scenarioSetRef,
                path, "correctness.compilation.valueSourceUnknown"));
        return Map.of();
    }

    private void validateExactClosure(
            FrozenCompilationInput input,
            Set<ExactAssetRef> fixtureRefs,
            Set<ExactAssetRef> oracleRefs,
            Set<ExactAssetRef> assertionRefs,
            List<Diagnostic> diagnostics
    ) {
        CompilationCoordinate coordinate = input.coordinate();
        requireExactAsset(
                coordinate.definitionRef(), "DEFINITION", input.definition().definitionId(),
                input.definition().revision(),
                CorrectnessProtocolFingerprint.fingerprint(mapper, input.definition()), diagnostics);
        requireExactAsset(
                coordinate.inventoryRef(), "INVENTORY", input.inventory().inventoryId(),
                input.inventory().revision(),
                CorrectnessProtocolFingerprint.fingerprint(mapper, input.inventory()), diagnostics);
        requireExactAsset(
                coordinate.scenarioDraftSetRef(), "SCENARIO_DRAFT_SET",
                input.scenarioDraftSet().scenarioDraftSetId(), input.scenarioDraftSet().revision(),
                CorrectnessProtocolFingerprint.fingerprint(mapper, input.scenarioDraftSet()),
                diagnostics);
        if (!Set.copyOf(coordinate.oracleRefs()).equals(oracleRefs)) {
            diagnostics.add(Diagnostic.error(
                    "RG.CORRECTNESS.ORACLE_CLOSURE_MISMATCH", coordinate.definitionRef(),
                    "/oracleRefs", "correctness.compilation.oracleClosureMismatch"));
        }
        if (!Set.copyOf(coordinate.assertionSetRefs()).equals(assertionRefs)) {
            diagnostics.add(Diagnostic.error(
                    "RG.CORRECTNESS.ASSERTION_CLOSURE_MISMATCH", coordinate.definitionRef(),
                    "/assertionSetRefs", "correctness.compilation.assertionClosureMismatch"));
        }
        if (!Set.copyOf(coordinate.fixtureAssetRefs()).equals(fixtureRefs)) {
            diagnostics.add(Diagnostic.error(
                    "RG.CORRECTNESS.FIXTURE_CLOSURE_MISMATCH", coordinate.definitionRef(),
                    "/fixtureAssetRefs", "correctness.compilation.fixtureClosureMismatch"));
        }

        Set<ExactAssetRef> referencedOracles = input.scenarioDraftSet().scenarios().stream()
                .flatMap(value -> value.oracleRefs().stream()).collect(Collectors.toSet());
        Set<ExactAssetRef> referencedAssertions = input.scenarioDraftSet().scenarios().stream()
                .flatMap(value -> value.assertionSetRefs().stream()).collect(Collectors.toSet());
        Set<ExactAssetRef> referencedFixtures = referencedFixtures(input.scenarioDraftSet());
        requireSameClosure("ORACLE", oracleRefs, referencedOracles,
                coordinate.scenarioDraftSetRef(), diagnostics);
        requireSameClosure("ASSERTION", assertionRefs, referencedAssertions,
                coordinate.scenarioDraftSetRef(), diagnostics);
        requireSameClosure("FIXTURE", fixtureRefs, referencedFixtures,
                coordinate.scenarioDraftSetRef(), diagnostics);
    }

    private void validateAuthority(
            FrozenCompilationInput input,
            Map<ExactAssetRef, MaterializedFixture> fixtures,
            Map<ExactAssetRef, BusinessOracle> oracles,
            Map<ExactAssetRef, AssertionSet> assertionSets,
            List<Diagnostic> diagnostics
    ) {
        CompilationCoordinate coordinate = input.coordinate();
        ExactTargetRef target = coordinate.target();
        if (!input.scope().equals(input.definition().scope())
                || !input.scope().equals(input.inventory().scope())
                || !input.scope().equals(input.scenarioDraftSet().scope())
                || fixtures.values().stream().anyMatch(value ->
                !input.scope().equals(value.descriptor().scope()))
                || oracles.values().stream().anyMatch(value ->
                !input.scope().equals(value.scope()))) {
            diagnostics.add(Diagnostic.error(
                    "RG.CORRECTNESS.SCOPE_MISMATCH", coordinate.definitionRef(),
                    "/scope", "correctness.compilation.scopeMismatch"));
        }
        if (!target.equals(input.definition().target())
                || !target.equals(input.inventory().target())
                || !target.equals(input.scenarioDraftSet().target())
                || oracles.values().stream().anyMatch(value -> !target.equals(value.target()))
                || assertionSets.values().stream().anyMatch(value -> !target.equals(value.target()))) {
            diagnostics.add(Diagnostic.error(
                    "RG.CORRECTNESS.TARGET_MISMATCH", coordinate.definitionRef(),
                    "/target", "correctness.compilation.targetMismatch"));
        }
        if (input.definition().lifecycle() != DefinitionLifecycle.ACTIVE
                || !input.definition().review().approved()) {
            diagnostics.add(Diagnostic.error(
                    "RG.CORRECTNESS.DEFINITION_NOT_ACTIVE", coordinate.definitionRef(),
                    "/lifecycle", "correctness.compilation.definitionNotActive"));
        }
        if (!coordinate.inventoryRef().equals(input.definition().activeInventoryRef())) {
            diagnostics.add(Diagnostic.error(
                    "RG.CORRECTNESS.ACTIVE_INVENTORY_MISMATCH", coordinate.definitionRef(),
                    "/activeInventoryRef", "correctness.compilation.activeInventoryMismatch"));
        }
        if (input.inventory().lifecycle() != InventoryLifecycle.FROZEN
                || !input.inventory().freezeReview().approved()) {
            diagnostics.add(Diagnostic.error(
                    "RG.CORRECTNESS.DENOMINATOR_NOT_FROZEN", coordinate.inventoryRef(),
                    "/lifecycle", "correctness.compilation.denominatorNotFrozen"));
        }
        String environment = input.scope().environment().toLowerCase(Locale.ROOT);
        if (environment.equals("prod") || environment.equals("production")) {
            diagnostics.add(Diagnostic.error(
                    "RG.CORRECTNESS.PRODUCTION_COMPILATION_FORBIDDEN", coordinate.definitionRef(),
                    "/scope/environment", "correctness.compilation.productionForbidden"));
        }
        if (target.kind().name().equals("FUNCTION")) {
            diagnostics.add(Diagnostic.error(
                    "RG.CORRECTNESS.FUNCTION_TARGET_UNSUPPORTED", coordinate.definitionRef(),
                    "/target/kind", "correctness.compilation.functionTargetUnsupported"));
        }

        Map<String, CoverageInventory.CoverageObligation> obligations =
                input.inventory().obligations().stream().collect(Collectors.toMap(
                        CoverageInventory.CoverageObligation::obligationId,
                        Function.identity()));
        for (ScenarioDraftV2 scenario : input.scenarioDraftSet().scenarios()) {
            validateScenarioAuthority(
                    input, scenario, obligations, oracles, assertionSets, diagnostics);
        }
        fixtures.forEach((ref, fixture) -> {
            if (fixture.descriptor().lifecycle() != FixtureLifecycle.ACTIVE
                    || !fixture.descriptor().quality().schemaValid()
                    || !fixture.descriptor().quality().redactionVerified()
                    || !fixture.descriptor().redaction().reviewed()) {
                diagnostics.add(Diagnostic.error(
                        "RG.CORRECTNESS.FIXTURE_NOT_ACTIVE", ref, "/lifecycle",
                        "correctness.compilation.fixtureNotActive"));
            }
        });
    }

    private void validateScenarioAuthority(
            FrozenCompilationInput input,
            ScenarioDraftV2 scenario,
            Map<String, CoverageInventory.CoverageObligation> obligations,
            Map<ExactAssetRef, BusinessOracle> oracles,
            Map<ExactAssetRef, AssertionSet> assertionSets,
            List<Diagnostic> diagnostics
    ) {
        ExactAssetRef scenarioRef = input.coordinate().scenarioDraftSetRef();
        String path = scenarioPath(scenario);
        if (scenario.lifecycle() != ScenarioLifecycle.CANONICAL || !scenario.review().approved()) {
            diagnostics.add(Diagnostic.error(
                    "RG.CORRECTNESS.SCENARIO_NOT_CANONICAL", scenarioRef,
                    path + "/lifecycle", "correctness.compilation.scenarioNotCanonical"));
        }
        for (ExactObligationRef ref : scenario.obligationRefs()) {
            CoverageInventory.CoverageObligation obligation = obligations.get(ref.obligationId());
            if (!ref.inventoryRef().equals(input.coordinate().inventoryRef())
                    || obligation == null
                    || obligation.lifecycle() != ObligationLifecycle.FROZEN
                    || !CorrectnessProtocolFingerprint.obligationFingerprint(mapper, obligation)
                    .equals(ref.obligationFingerprint())) {
                diagnostics.add(Diagnostic.error(
                        "RG.CORRECTNESS.OBLIGATION_REFERENCE_DRIFT", scenarioRef,
                        path + "/obligationRefs/" + ref.obligationId(),
                        "correctness.compilation.obligationReferenceDrift"));
            }
        }
        for (ExactAssetRef ref : scenario.oracleRefs()) {
            BusinessOracle oracle = oracles.get(ref);
            if (oracle == null
                    || oracle.lifecycle() != BusinessOracle.OracleLifecycle.APPROVED
                    || !oracle.approval().approved()) {
                diagnostics.add(Diagnostic.error(
                        "RG.CORRECTNESS.ORACLE_NOT_APPROVED", ref,
                        path + "/oracleRefs", "correctness.compilation.oracleNotApproved"));
            }
        }
        Set<ExactAssetRef> scenarioOracleRefs = Set.copyOf(scenario.oracleRefs());
        for (ExactAssetRef ref : scenario.assertionSetRefs()) {
            AssertionSet assertionSet = assertionSets.get(ref);
            if (assertionSet == null
                    || assertionSet.lifecycle() != AssertionSet.AssertionLifecycle.VALID
                    || !assertionSet.compatibility().supported()) {
                diagnostics.add(Diagnostic.error(
                        "RG.CORRECTNESS.ASSERTION_UNSUPPORTED", ref,
                        path + "/assertionSetRefs",
                        "correctness.compilation.assertionUnsupported"));
            } else if (!scenarioOracleRefs.contains(assertionSet.oracleRef())) {
                diagnostics.add(Diagnostic.error(
                        "RG.CORRECTNESS.ASSERTION_ORACLE_MISMATCH", ref,
                        path + "/assertionSetRefs",
                        "correctness.compilation.assertionOracleMismatch"));
            }
        }
    }

    private Map<ExactAssetRef, BusinessOracle> indexOracles(FrozenCompilationInput input) {
        Map<ExactAssetRef, BusinessOracle> result = new LinkedHashMap<>();
        for (BusinessOracle value : input.oracles()) {
            ExactAssetRef ref = new ExactAssetRef(
                    "ORACLE", value.oracleId(), value.revision(),
                    CorrectnessProtocolFingerprint.fingerprint(mapper, value));
            result.put(ref, value);
        }
        return Map.copyOf(result);
    }

    private Map<ExactAssetRef, AssertionSet> indexAssertionSets(FrozenCompilationInput input) {
        Map<ExactAssetRef, AssertionSet> result = new LinkedHashMap<>();
        for (AssertionSet value : input.assertionSets()) {
            ExactAssetRef ref = new ExactAssetRef(
                    "ASSERTION_SET", value.assertionSetId(), value.revision(),
                    CorrectnessProtocolFingerprint.fingerprint(mapper, value));
            result.put(ref, value);
        }
        return Map.copyOf(result);
    }

    private TestSuite suite(
            FrozenCompilationInput input,
            TestExecutionApiRequest.Target runtimeTarget,
            List<TestSuite.TestCase> cases,
            int minimumAssertions
    ) {
        List<TestSuite.CaseType> caseTypes = cases.stream()
                .map(TestSuite.TestCase::caseType).distinct().toList();
        Map<String, Object> metadata = Map.of(
                "source", "correctness-authoring",
                "definitionId", input.definition().definitionId(),
                "inventoryId", input.inventory().inventoryId(),
                "scenarioDraftSetId", input.scenarioDraftSet().scenarioDraftSetId(),
                "compilerVersion", COMPILER_VERSION);
        TestSuite idMaterial = new TestSuite(
                "", "", 1,
                new TestSuite.Target(
                        runtimeTarget.kind(), runtimeTarget.id(), runtimeTarget.fingerprint()),
                suiteClassification(cases, input), cases,
                new TestSuite.CoveragePolicy(
                        cases.size(), caseTypes, List.of(), List.of(),
                        minimumAssertions, true),
                new TestSuite.PromotionPolicy(true, cases.size(), true),
                metadata);
        String id = contentAddressedId(
                "correctness-suite-" + input.scenarioDraftSet().scenarioDraftSetId(),
                ProtocolFingerprint.ofBounded(mapper, idMaterial, MAX_PROTOCOL_BYTES));
        return new TestSuite(
                "", id, 1, idMaterial.target(), idMaterial.classification(),
                idMaterial.cases(), idMaterial.coveragePolicy(),
                idMaterial.promotionPolicy(), idMaterial.metadata());
    }

    private String suiteClassification(
            List<TestSuite.TestCase> cases,
            FrozenCompilationInput input
    ) {
        Set<String> used = cases.stream()
                .map(TestSuite.TestCase::caseId).collect(Collectors.toSet());
        return input.scenarioDraftSet().scenarios().stream()
                .filter(value -> used.contains(value.scenarioId()))
                .map(value -> classification(value,
                        input.fixtures().stream().collect(Collectors.toMap(
                                MaterializedFixture::descriptorRef, Function.identity()))))
                .max(Comparator.comparingInt(CorrectnessCompiler::classificationRank))
                .orElse("INTERNAL");
    }

    private static String classification(
            ScenarioDraftV2 scenario,
            Map<ExactAssetRef, MaterializedFixture> fixtures
    ) {
        Set<ExactAssetRef> refs = new LinkedHashSet<>();
        addFixtureRef(refs, scenario.given().input());
        scenario.dependencies().forEach(dependency ->
                addFixtureRef(refs, dependency.behavior().value()));
        return refs.stream().map(fixtures::get).filter(Objects::nonNull)
                .map(value -> value.descriptor().classification())
                .max(Comparator.comparingInt(CorrectnessCompiler::classificationRank))
                .orElse("INTERNAL");
    }

    private static int classificationRank(String value) {
        return switch (value == null ? "" : value.toUpperCase(Locale.ROOT)) {
            case "PUBLIC" -> 0;
            case "INTERNAL" -> 1;
            case "CONFIDENTIAL" -> 2;
            case "RESTRICTED" -> 3;
            default -> 4;
        };
    }

    private static ExecutionRiskSummary riskSummary(ScenarioDraftSetV2 draftSet) {
        int real = 0;
        int controlled = 0;
        int faults = 0;
        int denied = 0;
        int fallback = 0;
        int transport = 0;
        boolean logicalClock = false;
        for (ScenarioDraftV2 scenario : draftSet.scenarios()) {
            for (ControlledDependencyV2 dependency : scenario.dependencies()) {
                BehaviorKind kind = dependency.behavior().kind();
                if (kind == BehaviorKind.REAL || kind == BehaviorKind.OBSERVE) real++;
                else controlled++;
                if (kind == BehaviorKind.ERROR || kind == BehaviorKind.TIMEOUT) faults++;
                if (kind == BehaviorKind.MUST_NOT_CALL) denied++;
                if (dependency.consumption().onExhausted() == ExhaustionPolicy.FALLBACK_TO_REAL
                        || dependency.consumption().onUnmatched() == UnmatchedPolicy.ALLOW_REAL) {
                    fallback++;
                }
                if (dependency.behavior().boundary() == BehaviorBoundary.TRANSPORT) transport++;
                if (kind == BehaviorKind.DELAY || kind == BehaviorKind.TIMEOUT) logicalClock = true;
            }
        }
        List<String> codes = new ArrayList<>();
        if (real > 0) codes.add("REAL_DEPENDENCY");
        if (fallback > 0) codes.add("FALLBACK_TO_REAL");
        if (faults > 0) codes.add("CONTROLLED_FAULT");
        if (transport > 0) codes.add("TRANSPORT_BOUNDARY");
        return new ExecutionRiskSummary(
                real, controlled, faults, denied, fallback, transport, logicalClock, codes);
    }

    private static void riskDiagnostics(
            ExecutionRiskSummary risk,
            ExactAssetRef source,
            List<Diagnostic> diagnostics
    ) {
        if (risk.realDependencyCount() > 0) {
            diagnostics.add(Diagnostic.warning(
                    "RG.CORRECTNESS.REAL_DEPENDENCY_PRESENT", source, "/scenarios",
                    "correctness.compilation.realDependencyPresent"));
        }
        if (risk.fallbackToRealCount() > 0) {
            diagnostics.add(Diagnostic.warning(
                    "RG.CORRECTNESS.FALLBACK_TO_REAL_PRESENT", source, "/scenarios",
                    "correctness.compilation.fallbackToRealPresent"));
        }
    }

    private CompiledCorrectnessPlan blocked(
            CompilationCoordinate coordinate,
            List<Diagnostic> diagnostics,
            ExecutionRiskSummary riskSummary
    ) {
        List<Diagnostic> normalized = diagnostics.isEmpty()
                ? List.of(Diagnostic.error(
                "RG.CORRECTNESS.COMPILATION_BLOCKED", coordinate.definitionRef(), "",
                "correctness.compilation.blocked"))
                : List.copyOf(diagnostics);
        CorrectnessCompilationReport template = new CorrectnessCompilationReport(
                "", false, COMPILER_VERSION, coordinate, zeroFingerprint(),
                List.of(), List.of(), normalized, riskSummary);
        String fingerprint = reportFingerprint(template, List.of(), null);
        return new CompiledCorrectnessPlan(
                new CorrectnessCompilationReport(
                        "", false, COMPILER_VERSION, coordinate, fingerprint,
                        List.of(), List.of(), normalized, riskSummary),
                List.of(), null);
    }

    private String compilationFingerprint(
            CompilationCoordinate coordinate,
            List<FixtureBundleRegistrationRequest> fixtures,
            TestSuiteRegistrationRequest suite,
            List<SourceMapping> sourceMap,
            List<CompiledAssetSummary> assets,
            List<Diagnostic> diagnostics,
            ExecutionRiskSummary risk
    ) {
        CorrectnessCompilationReport template = new CorrectnessCompilationReport(
                "", true, COMPILER_VERSION, coordinate, zeroFingerprint(),
                sourceMap, assets, diagnostics, risk);
        return reportFingerprint(template, fixtures, suite);
    }

    private String reportFingerprint(
            CorrectnessCompilationReport report,
            List<FixtureBundleRegistrationRequest> fixtures,
            TestSuiteRegistrationRequest suite
    ) {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("schemaVersion", report.schemaVersion());
        material.put("publishable", report.publishable());
        material.put("compilerVersion", report.compilerVersion());
        material.put("coordinate", report.coordinate());
        material.put("sourceMap", report.sourceMap());
        material.put("compiledAssets", report.compiledAssets());
        material.put("diagnostics", report.diagnostics());
        material.put("riskSummary", report.riskSummary());
        material.put("fixtureRegistrations", fixtures);
        material.put("suiteRegistration", suite);
        return ProtocolFingerprint.ofBounded(mapper, material, MAX_PROTOCOL_BYTES);
    }

    private List<CompiledAssetSummary> compiledAssets(
            List<FixtureBundleRegistrationRequest> fixtures,
            ExactAssetRef suiteRef,
            List<SourceMapping> sourceMap
    ) {
        List<ExactAssetRef> refs = new ArrayList<>();
        for (FixtureBundleRegistrationRequest registration : fixtures) {
            FixtureBundle bundle = registration.fixtureBundle();
            refs.add(new ExactAssetRef(
                    "FIXTURE_BUNDLE", bundle.fixtureBundleId(), bundle.revision(),
                    ProtocolFingerprint.ofBounded(mapper, bundle, MAX_PROTOCOL_BYTES)));
        }
        refs.add(suiteRef);
        return refs.stream().map(ref -> new CompiledAssetSummary(
                ref, (int) sourceMap.stream()
                .filter(mapping -> mapping.output().assetRef().equals(ref)).count()))
                .toList();
    }

    private static List<SourceMapping> materializeMappings(
            List<PendingMapping> mappings,
            ExactAssetRef suiteRef
    ) {
        return mappings.stream().map(mapping -> mapping.materialize(suiteRef)).toList();
    }

    private static void requireExactAsset(
            ExactAssetRef actual,
            String kind,
            String id,
            long revision,
            String fingerprint,
            List<Diagnostic> diagnostics
    ) {
        if (!actual.kind().equals(kind) || !actual.id().equals(id)
                || actual.revision() != revision || !actual.fingerprint().equals(fingerprint)) {
            diagnostics.add(Diagnostic.error(
                    "RG.CORRECTNESS.REFERENCE_DRIFT", actual, "",
                    "correctness.compilation.referenceDrift"));
        }
    }

    private static void requireSameClosure(
            String kind,
            Set<ExactAssetRef> supplied,
            Set<ExactAssetRef> referenced,
            ExactAssetRef source,
            List<Diagnostic> diagnostics
    ) {
        if (!supplied.equals(referenced)) {
            diagnostics.add(Diagnostic.error(
                    "RG.CORRECTNESS." + kind + "_REFERENCE_CLOSURE_INCOMPLETE", source,
                    "/scenarios", "correctness.compilation.referenceClosureIncomplete"));
        }
    }

    private static Set<ExactAssetRef> referencedFixtures(ScenarioDraftSetV2 draftSet) {
        Set<ExactAssetRef> result = new LinkedHashSet<>();
        for (ScenarioDraftV2 scenario : draftSet.scenarios()) {
            scenario.sourceRefs().stream().filter(ref -> "FIXTURE_ASSET".equals(ref.kind()))
                    .forEach(result::add);
            addFixtureRef(result, scenario.given().input());
            scenario.dependencies().forEach(dependency ->
                    addFixtureRef(result, dependency.behavior().value()));
        }
        return Set.copyOf(result);
    }

    private static void addFixtureRef(Set<ExactAssetRef> target, ValueSource value) {
        if (value instanceof FixtureVariantRef fixture) {
            target.add(fixture.fixtureAssetRef());
        }
    }

    private static ExactAssetRef fixtureRef(ValueSource value) {
        return value instanceof FixtureVariantRef fixture ? fixture.fixtureAssetRef() : null;
    }

    private static String fixtureVariantKey(ValueSource value) {
        return value instanceof FixtureVariantRef fixture ? fixture.variantKey() : "";
    }

    private static InvocationSite.InvocationKind invocationKind(ControlledDependencyV2 source) {
        if (!source.selector().resourceRef().isBlank()) {
            return InvocationSite.InvocationKind.RESOURCE;
        }
        if (!source.selector().functionRef().isBlank()) {
            return InvocationSite.InvocationKind.FUNCTION;
        }
        return InvocationSite.InvocationKind.PRIMARY;
    }

    private static boolean hasErrors(List<Diagnostic> diagnostics) {
        return diagnostics.stream().anyMatch(value ->
                value.severity() == DiagnosticSeverity.ERROR);
    }

    private static String scenarioPath(ScenarioDraftV2 scenario) {
        return "/scenarios/" + scenario.scenarioId();
    }

    private static String contentAddressedId(String prefix, String fingerprint) {
        String normalizedPrefix = prefix.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9._-]+", "-")
                .replaceAll("^-+|-+$", "");
        String digest = fingerprint.startsWith("sha256:")
                ? fingerprint.substring("sha256:".length()) : fingerprint;
        int prefixLimit = Math.max(0, 255 - digest.length() - 1);
        return normalizedPrefix.substring(0, Math.min(prefixLimit, normalizedPrefix.length()))
                + '-' + digest;
    }

    private static String zeroFingerprint() {
        return "sha256:" + "0".repeat(64);
    }

    private record PendingAssertion(
            ExactAssetRef assertionSetRef,
            String assertionId,
            FixtureBundle.Assertion assertion
    ) {
    }

    private record CaseCompilation(
            FixtureBundleRegistrationRequest registration,
            TestSuite.TestCase testCase,
            List<PendingMapping> mappings
    ) {
    }

    private record PendingMapping(
            SourceCoordinate source,
            OutputCoordinate exactOutput,
            String suiteElementKind,
            String suiteElementId,
            String fixtureElementKind,
            String fixtureElementId
    ) {
        static PendingMapping exact(SourceCoordinate source, OutputCoordinate output) {
            return new PendingMapping(source, output, "", "", "", "");
        }

        static PendingMapping scenarioToFixture(
                ExactAssetRef sourceRef, String scenarioId, ExactAssetRef fixtureRef) {
            return exact(new SourceCoordinate(sourceRef, "SCENARIO_CASE", scenarioId),
                    new OutputCoordinate(fixtureRef, "FIXTURE_BUNDLE", fixtureRef.id()));
        }

        static PendingMapping scenarioToCase(
                ExactAssetRef sourceRef, String scenarioId, String caseId) {
            return new PendingMapping(
                    new SourceCoordinate(sourceRef, "SCENARIO_CASE", scenarioId),
                    null, "TEST_CASE", caseId, "", "");
        }

        static PendingMapping obligationToCase(
                ExactAssetRef sourceRef, String obligationId, String caseId) {
            return new PendingMapping(
                    new SourceCoordinate(sourceRef, "OBLIGATION", obligationId),
                    null, "TEST_CASE", caseId, "", "");
        }

        static PendingMapping oracleToFixture(
                ExactAssetRef sourceRef, String scenarioId, ExactAssetRef fixtureRef) {
            return exact(new SourceCoordinate(sourceRef, "BUSINESS_ORACLE", sourceRef.id()),
                    new OutputCoordinate(fixtureRef, "CASE_ASSERTION_SET", scenarioId));
        }

        static PendingMapping fixtureToRule(ExactAssetRef sourceRef, String ruleId) {
            return new PendingMapping(
                    new SourceCoordinate(sourceRef, "FIXTURE_VARIANT", sourceRef.id()),
                    null, "", "", "FIXTURE_RULE", ruleId);
        }

        PendingMapping bindFixture(ExactAssetRef fixtureRef) {
            if (exactOutput != null || fixtureElementKind.isEmpty()) return this;
            return exact(source, new OutputCoordinate(
                    fixtureRef, fixtureElementKind, fixtureElementId));
        }

        SourceMapping materialize(ExactAssetRef suiteRef) {
            OutputCoordinate output = exactOutput != null ? exactOutput
                    : new OutputCoordinate(suiteRef, suiteElementKind, suiteElementId);
            return new SourceMapping(source, output);
        }
    }
}
