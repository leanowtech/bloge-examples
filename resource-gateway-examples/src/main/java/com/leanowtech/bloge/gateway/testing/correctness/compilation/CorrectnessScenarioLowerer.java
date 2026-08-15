package com.leanowtech.bloge.gateway.testing.correctness.compilation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.FixtureBundleRegistrationRequest;
import com.leanowtech.bloge.gateway.testing.api.TestExecutionApiRequest;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteRegistrationRequest;
import com.leanowtech.bloge.gateway.testing.correctness.compilation.CorrectnessCompilationReport.Diagnostic;
import com.leanowtech.bloge.gateway.testing.correctness.compilation.CorrectnessCompilationReport.DiagnosticSeverity;
import com.leanowtech.bloge.gateway.testing.correctness.compilation.CorrectnessCompilationReport.OutputCoordinate;
import com.leanowtech.bloge.gateway.testing.correctness.compilation.CorrectnessCompilationReport.SourceCoordinate;
import com.leanowtech.bloge.gateway.testing.correctness.compilation.CorrectnessCompilationReport.SourceMapping;
import com.leanowtech.bloge.gateway.testing.correctness.compilation.CorrectnessAssertionLowerer.CompiledAssertion;
import com.leanowtech.bloge.gateway.testing.correctness.compilation.CorrectnessSourceMapBuilder.PendingMapping;
import com.leanowtech.bloge.gateway.testing.correctness.compilation.FrozenCompilationInput.MaterializedFixture;
import com.leanowtech.bloge.gateway.testing.correctness.domain.AssertionSet;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactObligationRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.BehaviorKind;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.ControlledDependencyV2;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.FixtureVariantRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.ScenarioDraftV2;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.ValueSource;
import com.leanowtech.bloge.gateway.testing.correctness.oracle.AssertionEvaluatorProfile;
import com.leanowtech.bloge.gateway.testing.correctness.oracle.AssertionSetCompiler;
import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;
import com.leanowtech.bloge.gateway.testing.domain.FixtureRule;
import com.leanowtech.bloge.gateway.testing.domain.TestSuite;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Pure lowering, existing-protocol compilation, and source-map phase. */
final class CorrectnessScenarioLowerer {

    private static final int MAX_PROTOCOL_BYTES = 16 * 1_048_576;
    private static final Instant LOGICAL_CLOCK = Instant.parse("2000-01-01T00:00:00Z");

    private final ObjectMapper mapper;
    private final String compilerVersion;
    private final CorrectnessFixtureRuleLowerer ruleLowerer;
    private final CorrectnessAssertionLowerer assertionLowerer;

    CorrectnessScenarioLowerer(
            ObjectMapper mapper,
            AssertionSetCompiler assertionCompiler,
            AssertionEvaluatorProfile evaluatorProfile,
            String compilerVersion
    ) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.compilerVersion = Objects.requireNonNull(compilerVersion, "compilerVersion");
        this.ruleLowerer = new CorrectnessFixtureRuleLowerer();
        this.assertionLowerer = new CorrectnessAssertionLowerer(
                assertionCompiler, evaluatorProfile);
    }

    LoweringResult lower(
            FrozenCompilationInput input,
            Map<ExactAssetRef, MaterializedFixture> fixtures,
            Map<ExactAssetRef, AssertionSet> assertionSets
    ) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        TestExecutionApiRequest.Target runtimeTarget = new TestExecutionApiRequest.Target(
                input.coordinate().target().kind().name(), input.coordinate().target().id(),
                input.coordinate().target().fingerprint());
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
            return LoweringResult.blocked(diagnostics);
        }

        TestSuite suite = suite(input, runtimeTarget, cases,
                minimumAssertions == Integer.MAX_VALUE ? 0 : minimumAssertions);
        String suiteFingerprint = ProtocolFingerprint.ofBounded(
                mapper, suite, MAX_PROTOCOL_BYTES);
        ExactAssetRef suiteRef = new ExactAssetRef(
                "TEST_SUITE", suite.suiteId(), suite.revision(), suiteFingerprint);
        TestSuiteRegistrationRequest suiteRegistration =
                new TestSuiteRegistrationRequest("", suite);
        List<SourceMapping> sourceMap = CorrectnessSourceMapBuilder.materialize(
                mappings, suiteRef);
        return new LoweringResult(
                fixtureRegistrations, suiteRegistration, suiteRef, sourceMap, diagnostics);
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
        List<CompiledAssertion> assertions = assertionLowerer.lower(
                scenario, assertionSets, diagnostics);
        Object caseInput = ruleLowerer.resolveValue(
                scenario.given().input(), fixtures, scenarioSetRef,
                scenarioPath(scenario) + "/given/input", diagnostics);
        List<FixtureRule> rules = new ArrayList<>();
        List<PendingMapping> pending = new ArrayList<>();
        for (ControlledDependencyV2 dependency : scenario.dependencies()) {
            FixtureRule rule = ruleLowerer.lowerRule(
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
                .map(CompiledAssertion::assertion).toList();
        String classification = classification(scenario, fixtures);
        boolean logicalTime = scenario.dependencies().stream().anyMatch(dependency ->
                dependency.behavior().kind() == BehaviorKind.DELAY
                        || dependency.behavior().kind() == BehaviorKind.TIMEOUT);
        Map<String, Object> metadata = Map.of(
                "source", "correctness-authoring",
                "scenarioDraftSetId", input.scenarioDraftSet().scenarioDraftSetId(),
                "scenarioDraftSetRevision", input.scenarioDraftSet().revision(),
                "scenarioId", scenario.scenarioId(),
                "compilerVersion", compilerVersion);
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
            CompiledAssertion assertion = assertions.get(index);
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
                "compilerVersion", compilerVersion);
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
                .max(Comparator.comparingInt(CorrectnessScenarioLowerer::classificationRank))
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
                .max(Comparator.comparingInt(CorrectnessScenarioLowerer::classificationRank))
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

    record LoweringResult(
            List<FixtureBundleRegistrationRequest> fixtureRegistrations,
            TestSuiteRegistrationRequest suiteRegistration,
            ExactAssetRef suiteRef,
            List<SourceMapping> sourceMap,
            List<Diagnostic> diagnostics
    ) {
        LoweringResult {
            fixtureRegistrations = List.copyOf(fixtureRegistrations);
            sourceMap = List.copyOf(sourceMap);
            diagnostics = List.copyOf(diagnostics);
        }

        static LoweringResult blocked(List<Diagnostic> diagnostics) {
            return new LoweringResult(List.of(), null, null, List.of(), diagnostics);
        }

        boolean publishable() {
            return suiteRegistration != null && suiteRef != null && !hasErrors(diagnostics);
        }
    }

    private record CaseCompilation(
            FixtureBundleRegistrationRequest registration,
            TestSuite.TestCase testCase,
            List<PendingMapping> mappings
    ) {
    }

}
