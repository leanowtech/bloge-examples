package com.leanowtech.bloge.gateway.authoring.scenario;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.FixtureBundleRegistrationRequest;
import com.leanowtech.bloge.gateway.testing.api.TestExecutionApiRequest;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteRegistryService;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteRegistrationRequest;
import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;
import com.leanowtech.bloge.gateway.testing.domain.FixtureRule;
import com.leanowtech.bloge.gateway.testing.domain.InvocationSite;
import com.leanowtech.bloge.gateway.testing.domain.TestSuite;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorExecutionLowering;
import com.leanowtech.bloge.gateway.visual.contract.ContractDraft;
import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Compiles author-facing Scenarios into exact FixtureBundle and TestSuite v1 registrations.
 *
 * <p>The compiler is deterministic and side-effect free. It does not trust a caller-supplied
 * runtime fingerprint as authoritative; the later publication service discovers that target from
 * the testing control plane and both registries re-verify it. Unsupported or ambiguous semantics
 * produce diagnostics rather than a weaker test asset.</p>
 */
@Service
public class ScenarioGovernedCompiler {

    private static final int MAX_PROTOCOL_BYTES = 16 * 1_048_576;
    private static final Instant LOGICAL_CLOCK = Instant.parse("2000-01-01T00:00:00Z");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    /** Fixed server-derived metadata keys consumed by testing evidence propagation. */
    public static final String GOVERNED_PROVENANCE_SCHEMA_VERSION =
            "governedProvenanceSchemaVersion";
    public static final String GOVERNED_PROVENANCE_FINGERPRINT =
            "governedProvenanceFingerprint";
    public static final String GOVERNED_SOURCE_MAP_FINGERPRINT =
            "governedSourceMapFingerprint";
    public static final String GOVERNED_EXACT_REFS = "governedExactRefs";

    private final ScenarioValidationService validation;
    private final ObjectMapper objectMapper;

    /**
     * @param validation exact visual target and Contract validator
     * @param objectMapper canonical testing protocol serializer
     */
    public ScenarioGovernedCompiler(
            ScenarioValidationService validation,
            ObjectMapper objectMapper) {
        this.validation = Objects.requireNonNull(validation, "validation");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    /**
     * Compiles a complete Scenario revision to immutable governed registration requests.
     *
     * @param graph current exact visual graph, or null for an operator target
     * @param contract current exact Contract projection
     * @param draftSet mutable Scenario source revision
     * @param runtimeTarget independently discovered runtime graph or operator coordinate
     * @return deterministic publication plan
     */
    public ScenarioGovernedCompilationPlan compile(
            GraphDraft graph,
            ContractDraft contract,
            ScenarioDraftSet draftSet,
            TestExecutionApiRequest.Target runtimeTarget) {
        return compile(graph, null, contract, draftSet, runtimeTarget,
                ScenarioGovernedCompilationProvenance.empty(objectMapper));
    }

    /**
     * Compiles a complete Scenario revision and applies catalog-declared operator lowering.
     *
     * @param graph current exact visual graph, or null for an operator target
     * @param operator current exact visual operator, or null for a graph target
     * @param contract current exact Contract projection
     * @param draftSet mutable Scenario source revision
     * @param runtimeTarget independently discovered runtime graph or operator coordinate
     * @return deterministic publication plan
     */
    public ScenarioGovernedCompilationPlan compile(
            GraphDraft graph,
            OperatorDefinition operator,
            ContractDraft contract,
            ScenarioDraftSet draftSet,
            TestExecutionApiRequest.Target runtimeTarget) {
        return compile(graph, operator, contract, draftSet, runtimeTarget,
                ScenarioGovernedCompilationProvenance.empty(objectMapper));
    }

    /**
     * Compiles a Scenario revision with typed, server-derived source closure provenance.
     *
     * <p>The provenance is injected into every content-addressed protocol object. No arbitrary
     * caller metadata map is accepted at this boundary.</p>
     */
    public ScenarioGovernedCompilationPlan compile(
            GraphDraft graph,
            OperatorDefinition operator,
            ContractDraft contract,
            ScenarioDraftSet draftSet,
            TestExecutionApiRequest.Target runtimeTarget,
            ScenarioGovernedCompilationProvenance provenance) {
        Objects.requireNonNull(provenance, "provenance");
        String provenanceFingerprint = provenance.fingerprint(objectMapper);
        List<VisualDiagnostic> diagnostics = new ArrayList<>();
        ScenarioValidationReport report = validation.validate(draftSet, contract, graph);
        diagnostics.addAll(report.diagnostics());
        validateGovernedLimits(draftSet, diagnostics);
        validateRuntimeTarget(graph, operator, draftSet, runtimeTarget, diagnostics);
        if (diagnostics.stream().anyMatch(VisualDiagnostic::error)) {
            return blocked(draftSet, runtimeTarget, diagnostics);
        }

        List<ScenarioGovernedCompilationPlan.CompiledFixture> fixtures = new ArrayList<>();
        List<TestSuite.TestCase> cases = new ArrayList<>();
        List<TestSuite.EdgeTransferRef> requiredEdges = new ArrayList<>();
        int minimumAssertions = Integer.MAX_VALUE;
        for (ScenarioDraftSet.ScenarioDraft scenario : draftSet.scenarios()) {
            List<FixtureRule> rules = scenario.dependencies().stream()
                    .map(this::rule)
                    .toList();
            List<FixtureBundle.Assertion> assertions = new ArrayList<>();
            for (ScenarioDraftSet.AssertionDraft assertion : scenario.then().assertions()) {
                if (assertion.scope() == ScenarioDraftSet.AssertionScope.EDGE_TRANSFER) {
                    requiredEdges.add(edge(assertion));
                } else {
                    FixtureBundle.Assertion compiled = assertion(assertion, scenario, diagnostics);
                    if (compiled != null) {
                        assertions.add(compiled);
                    }
                }
            }
            if (diagnostics.stream().anyMatch(VisualDiagnostic::error)) {
                continue;
            }
            Object runtimeInput = lowerInput(operator, scenario, diagnostics);
            if (diagnostics.stream().anyMatch(VisualDiagnostic::error)) {
                continue;
            }
            minimumAssertions = Math.min(minimumAssertions, assertions.size());
            FixtureBundle fixture = fixture(
                    draftSet, scenario, operator, runtimeTarget, rules, assertions,
                    provenance, provenanceFingerprint);
            String fixtureFingerprint = ProtocolFingerprint.ofBounded(
                    objectMapper, fixture, MAX_PROTOCOL_BYTES);
            FixtureBundleRegistrationRequest request = new FixtureBundleRegistrationRequest(
                    "",
                    runtimeTarget,
                    fixture);
            fixtures.add(new ScenarioGovernedCompilationPlan.CompiledFixture(
                    scenario.scenarioId(), fixtureFingerprint, request));
            cases.add(testCase(
                    scenario, fixture, fixtureFingerprint, draftSet, runtimeTarget, runtimeInput,
                    provenance, provenanceFingerprint));
        }
        if (diagnostics.stream().anyMatch(VisualDiagnostic::error)) {
            return blocked(draftSet, runtimeTarget, diagnostics);
        }

        TestSuite suite = suite(
                draftSet,
                operator,
                runtimeTarget,
                cases,
                requiredEdges,
                minimumAssertions == Integer.MAX_VALUE ? 0 : minimumAssertions,
                provenance,
                provenanceFingerprint);
        return new ScenarioGovernedCompilationPlan(
                "",
                true,
                draftSet.scenarioDraftSetId(),
                draftSet.revision(),
                draftSet.target().fingerprint(),
                draftSet.contractFingerprint(),
                runtimeTarget,
                fixtures,
                new TestSuiteRegistrationRequest("", suite),
                diagnostics);
    }

    private FixtureRule rule(ScenarioDraftSet.DependencyBehaviorDraft source) {
        ScenarioDraftSet.DependencySelector selector = source.selector();
        FixtureRule.Match match = new FixtureRule.Match(
                null,
                selector.pathEquals(),
                List.of(),
                List.of(),
                Map.of(),
                "",
                Map.of());
        FixtureRule.Selector compiledSelector = new FixtureRule.Selector(
                selector.graphPath(),
                selector.nodeId(),
                selector.operatorRef(),
                selector.resourceRef(),
                selector.functionRef(),
                List.of(),
                List.of(),
                invocationKind(selector),
                selector.attempts(),
                selector.occurrences(),
                selector.correlationKey(),
                match);
        ScenarioDraftSet.Consumption consumption = source.consumption();
        FixtureRule.Consumption compiledConsumption = new FixtureRule.Consumption(
                consumption.required(),
                consumption.minUses(),
                consumption.maxUses(),
                FixtureRule.ExhaustedAction.valueOf(consumption.onExhausted()),
                FixtureRule.UnmatchedAction.valueOf(consumption.onUnmatched()));
        FixtureRule.SchemaCheck schemaCheck = new FixtureRule.SchemaCheck(
                FixtureRule.SchemaCheckMode.valueOf(source.schemaCheck().mode()),
                source.schemaCheck().waiverReason());
        return new FixtureRule(
                "",
                source.dependencyId(),
                compiledSelector,
                behavior(source.behavior()),
                compiledConsumption,
                schemaCheck);
    }

    private static FixtureRule.Behavior behavior(ScenarioDraftSet.DependencyBehavior source) {
        FixtureRule.DoubleBoundary boundary =
                FixtureRule.DoubleBoundary.valueOf(source.boundary().name());
        return switch (source.kind()) {
            case REAL -> new FixtureRule.Behavior(
                    FixtureRule.BehaviorKind.REAL, boundary, null, "", null, Map.of(),
                    "", "", "", null, List.of(), "");
            case RETURN -> new FixtureRule.Behavior(
                    FixtureRule.BehaviorKind.RETURN, boundary, source.output(), source.rawBody(),
                    source.statusCode(), source.headers(), "", "", "", null, List.of(), "");
            case ERROR -> new FixtureRule.Behavior(
                    FixtureRule.BehaviorKind.THROW, boundary, null, "", null, Map.of(),
                    source.errorCode(), source.errorType(), source.errorMessage(), null, List.of(), "");
            case DELAY -> new FixtureRule.Behavior(
                    FixtureRule.BehaviorKind.DELAY, boundary, source.output(), source.rawBody(),
                    source.statusCode(), source.headers(), "", "", "", source.after(), List.of(), "");
            case TIMEOUT -> new FixtureRule.Behavior(
                    FixtureRule.BehaviorKind.TIMEOUT, boundary, null, "", null, Map.of(),
                    source.errorCode(), "TIMEOUT", source.errorMessage(), source.after(), List.of(), "");
            case REPLAY -> new FixtureRule.Behavior(
                    FixtureRule.BehaviorKind.REPLAY, boundary, null, "", null, Map.of(),
                    "", "", "", null, List.of(), source.replayRef());
            case OBSERVE -> new FixtureRule.Behavior(
                    FixtureRule.BehaviorKind.SPY, boundary, null, "", null, Map.of(),
                    "", "", "", null, List.of(), "");
            case MUST_NOT_CALL -> new FixtureRule.Behavior(
                    FixtureRule.BehaviorKind.DENY, boundary, null, "", null, Map.of(),
                    defaulted(source.errorCode(), "SCENARIO_MUST_NOT_CALL"),
                    "DENIED_INVOCATION",
                    defaulted(source.errorMessage(), "Scenario forbids this dependency invocation."),
                    null, List.of(), "");
        };
    }

    private FixtureBundle.Assertion assertion(
            ScenarioDraftSet.AssertionDraft source,
            ScenarioDraftSet.ScenarioDraft scenario,
            List<VisualDiagnostic> diagnostics) {
        String scope = source.scope().name();
        String nodeId = source.nodeId();
        String operator = source.operator().name();
        Object expected = source.expected();
        if (source.scope() == ScenarioDraftSet.AssertionScope.NODE_STATUS
                && source.operator() == ScenarioDraftSet.AssertionOperator.STATUS) {
            operator = "EQUALS";
        }
        if (source.scope() == ScenarioDraftSet.AssertionScope.INVOCATION) {
            ScenarioDraftSet.DependencyBehaviorDraft dependency = scenario.dependencies().stream()
                    .filter(candidate -> candidate.dependencyId().equals(source.nodeId())
                            || candidate.selector().nodeId().equals(source.nodeId()))
                    .findFirst()
                    .orElse(null);
            if (dependency == null) {
                diagnostics.add(VisualDiagnostic.error(
                        "visual.scenario.compile.invocationTargetUnknown",
                        "Invocation assertion '%s' does not identify a dependency rule."
                                .formatted(source.assertionId()),
                        "/scenarios/" + scenario.scenarioId() + "/then/assertions/"
                                + source.assertionId()));
                return null;
            }
            scope = "FIXTURE_USES";
            nodeId = dependency.dependencyId();
            if (source.operator() == ScenarioDraftSet.AssertionOperator.USED) {
                operator = "GREATER_OR_EQUAL";
                expected = source.expected() instanceof Number ? source.expected() : 1;
            } else if (source.operator() == ScenarioDraftSet.AssertionOperator.NOT_USED) {
                operator = "EQUALS";
                expected = 0;
            }
        }
        return new FixtureBundle.Assertion(
                scope, nodeId, pointer(source.path()), operator, expected, source.numericTolerance());
    }

    private FixtureBundle fixture(
            ScenarioDraftSet draftSet,
            ScenarioDraftSet.ScenarioDraft scenario,
            OperatorDefinition operator,
            TestExecutionApiRequest.Target runtimeTarget,
            List<FixtureRule> rules,
            List<FixtureBundle.Assertion> assertions,
            ScenarioGovernedCompilationProvenance provenance,
            String provenanceFingerprint) {
        Map<String, Object> metadata =
                fixtureMetadata(draftSet, scenario, operator, runtimeTarget,
                        provenance, provenanceFingerprint);
        boolean usesLogicalTime = scenario.dependencies().stream().anyMatch(dependency ->
                dependency.behavior().kind() == ScenarioDraftSet.BehaviorKind.DELAY
                        || dependency.behavior().kind() == ScenarioDraftSet.BehaviorKind.TIMEOUT);
        FixtureBundle idMaterial = new FixtureBundle(
                "",
                "",
                1,
                runtimeTarget.fingerprint(),
                draftSet.metadata().classification(),
                usesLogicalTime ? LOGICAL_CLOCK : null,
                null,
                rules,
                assertions,
                metadata);
        String digest = suffix(ProtocolFingerprint.ofBounded(
                objectMapper, idMaterial, MAX_PROTOCOL_BYTES));
        String id = contentAddressedId(
                "scenario-" + draftSet.scenarioDraftSetId() + "-" + scenario.scenarioId(),
                digest);
        return new FixtureBundle(
                "",
                id,
                1,
                runtimeTarget.fingerprint(),
                draftSet.metadata().classification(),
                usesLogicalTime ? LOGICAL_CLOCK : null,
                null,
                rules,
                assertions,
                metadata);
    }

    private TestSuite.TestCase testCase(
            ScenarioDraftSet.ScenarioDraft scenario,
            FixtureBundle fixture,
            String fixtureFingerprint,
            ScenarioDraftSet draftSet,
            TestExecutionApiRequest.Target runtimeTarget,
            Object runtimeInput,
            ScenarioGovernedCompilationProvenance provenance,
            String provenanceFingerprint) {
        return new TestSuite.TestCase(
                scenario.scenarioId(),
                TestSuite.CaseType.valueOf(scenario.caseType().name()),
                runtimeInput,
                new TestSuite.FixtureBundleRef(
                        fixture.fixtureBundleId(), fixture.revision(), fixtureFingerprint),
                scenario.tags(),
                testCaseMetadata(draftSet, scenario, runtimeTarget,
                        provenance, provenanceFingerprint));
    }

    private TestSuite suite(
            ScenarioDraftSet draftSet,
            OperatorDefinition operator,
            TestExecutionApiRequest.Target runtimeTarget,
            List<TestSuite.TestCase> cases,
            List<TestSuite.EdgeTransferRef> edges,
            int minimumAssertions,
            ScenarioGovernedCompilationProvenance provenance,
            String provenanceFingerprint) {
        List<TestSuite.CaseType> caseTypes = cases.stream()
                .map(TestSuite.TestCase::caseType)
                .distinct()
                .toList();
        TestSuite idMaterial = new TestSuite(
                "",
                "",
                1,
                new TestSuite.Target(runtimeTarget.kind(), runtimeTarget.id(), runtimeTarget.fingerprint()),
                draftSet.metadata().classification(),
                cases,
                new TestSuite.CoveragePolicy(
                        cases.size(), caseTypes, List.of(), edges, minimumAssertions, true),
                new TestSuite.PromotionPolicy(true, cases.size(), true),
                targetMetadata(draftSet, operator, runtimeTarget,
                        provenance, provenanceFingerprint, true));
        String digest = suffix(ProtocolFingerprint.ofBounded(
                objectMapper, idMaterial, MAX_PROTOCOL_BYTES));
        String id = contentAddressedId(
                "scenario-suite-" + draftSet.scenarioDraftSetId(), digest);
        return new TestSuite(
                "",
                id,
                1,
                idMaterial.target(),
                idMaterial.classification(),
                idMaterial.cases(),
                idMaterial.coveragePolicy(),
                idMaterial.promotionPolicy(),
                idMaterial.metadata());
    }

    private static Map<String, Object> fixtureMetadata(
            ScenarioDraftSet draftSet,
            ScenarioDraftSet.ScenarioDraft scenario,
            OperatorDefinition operator,
            TestExecutionApiRequest.Target runtimeTarget,
            ScenarioGovernedCompilationProvenance provenance,
            String provenanceFingerprint) {
        Map<String, Object> metadata =
                new LinkedHashMap<>(targetMetadata(draftSet, operator, runtimeTarget,
                        provenance, provenanceFingerprint, true));
        metadata.put("scenarioId", scenario.scenarioId());
        metadata.put("assertionIds", scenario.then().assertions().stream()
                .map(ScenarioDraftSet.AssertionDraft::assertionId).toList());
        return Map.copyOf(metadata);
    }

    private static Map<String, Object> testCaseMetadata(
            ScenarioDraftSet draftSet,
            ScenarioDraftSet.ScenarioDraft scenario,
            TestExecutionApiRequest.Target runtimeTarget,
            ScenarioGovernedCompilationProvenance provenance,
            String provenanceFingerprint) {
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "scenario-authoring");
        metadata.put("scenarioName", scenario.name());
        metadata.put("scenarioDraftSetId", draftSet.scenarioDraftSetId());
        metadata.put("scenarioDraftSetRevision", draftSet.revision());
        metadata.put("givenProvenance", scenario.given().provenance().name());
        metadata.put("sourceTargetKind", draftSet.target().kind().name());
        metadata.put("sourceTargetId", draftSet.target().id());
        metadata.put("runtimeTargetKind", runtimeTarget.kind());
        metadata.put("runtimeTargetId", runtimeTarget.id());
        metadata.putAll(governedMetadata(provenance, provenanceFingerprint, false));
        return Map.copyOf(metadata);
    }

    private static Map<String, Object> targetMetadata(
            ScenarioDraftSet draftSet,
            OperatorDefinition operator,
            TestExecutionApiRequest.Target runtimeTarget,
            ScenarioGovernedCompilationProvenance provenance,
            String provenanceFingerprint,
            boolean includeExactRefs) {
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "scenario-authoring");
        metadata.put("scenarioDraftSetId", draftSet.scenarioDraftSetId());
        metadata.put("scenarioDraftSetRevision", draftSet.revision());
        metadata.put("sourceTargetKind", draftSet.target().kind().name());
        metadata.put("sourceTargetId", draftSet.target().id());
        metadata.put("sourceTargetFingerprint", draftSet.target().fingerprint());
        metadata.put("contractFingerprint", draftSet.contractFingerprint());
        metadata.put("runtimeTargetKind", runtimeTarget.kind());
        metadata.put("runtimeTargetId", runtimeTarget.id());
        metadata.put("runtimeTargetFingerprint", runtimeTarget.fingerprint());
        if (operator != null) {
            metadata.put("loweringMode", operator.lowering().mode());
        }
        metadata.putAll(governedMetadata(provenance, provenanceFingerprint, includeExactRefs));
        return Map.copyOf(metadata);
    }

    private static Map<String, Object> governedMetadata(
            ScenarioGovernedCompilationProvenance provenance,
            String provenanceFingerprint,
            boolean includeExactRefs) {
        if (provenance.empty()) {
            return Map.of();
        }
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(GOVERNED_PROVENANCE_SCHEMA_VERSION, provenance.schemaVersion());
        metadata.put(GOVERNED_PROVENANCE_FINGERPRINT, provenanceFingerprint);
        metadata.put(GOVERNED_SOURCE_MAP_FINGERPRINT, provenance.sourceMapFingerprint());
        if (includeExactRefs) {
            metadata.put(GOVERNED_EXACT_REFS, provenance.exactRefs());
        }
        return metadata;
    }

    private static TestSuite.EdgeTransferRef edge(ScenarioDraftSet.AssertionDraft assertion) {
        return new TestSuite.EdgeTransferRef(
                invocationSiteId(assertion.fromNodeId()),
                invocationSiteId(assertion.toNodeId()));
    }

    private static String invocationSiteId(String nodeId) {
        return "/" + nodeId.replace("~", "~0").replace("/", "~1") + "#PRIMARY";
    }

    private static InvocationSite.InvocationKind invocationKind(
            ScenarioDraftSet.DependencySelector selector) {
        if (!selector.resourceRef().isBlank()) {
            return InvocationSite.InvocationKind.RESOURCE;
        }
        if (!selector.functionRef().isBlank()) {
            return InvocationSite.InvocationKind.FUNCTION;
        }
        return InvocationSite.InvocationKind.PRIMARY;
    }

    private static void validateRuntimeTarget(
            GraphDraft graph,
            OperatorDefinition operator,
            ScenarioDraftSet draftSet,
            TestExecutionApiRequest.Target runtimeTarget,
            List<VisualDiagnostic> diagnostics) {
        String expectedKind = draftSet == null
                ? ""
                : draftSet.target().kind().name();
        if (runtimeTarget == null
                || !expectedKind.equals(runtimeTarget.kind())
                || runtimeTarget.id().isBlank()
                || !FINGERPRINT.matcher(runtimeTarget.fingerprint()).matches()) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.scenario.compile.runtimeTargetInvalid",
                    "Governed compilation requires an exact independently discovered runtime target "
                            + "with the same target kind.",
                    "/runtimeTarget"));
            return;
        }
        if ("GRAPH".equals(expectedKind)
                && graph != null
                && !runtimeTarget.id().equals(graph.graphName())) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.scenario.compile.runtimeTargetGraphMismatch",
                    "Runtime graph id does not match the visual GraphDraft graphName.",
                    "/runtimeTarget/id"));
        }
        if ("OPERATOR".equals(expectedKind)
                && !runtimeTarget.id().equals(operator == null
                ? draftSet.target().id()
                : OperatorExecutionLowering.runtimeOperatorRef(operator))) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.scenario.compile.runtimeTargetOperatorMismatch",
                    "Runtime operator id does not match the catalog-declared lowering target.",
                    "/runtimeTarget/id"));
        }
    }

    private static Object lowerInput(
            OperatorDefinition operator,
            ScenarioDraftSet.ScenarioDraft scenario,
            List<VisualDiagnostic> diagnostics) {
        if (operator == null) {
            return scenario.given().input();
        }
        try {
            return OperatorExecutionLowering.lowerInput(operator, scenario.given().input());
        } catch (IllegalArgumentException invalid) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.scenario.compile.operatorLoweringInvalid",
                    invalid.getMessage(),
                    "/scenarios/" + scenario.scenarioId() + "/given/input"));
            return Map.of();
        }
    }

    private static void validateGovernedLimits(
            ScenarioDraftSet draftSet,
            List<VisualDiagnostic> diagnostics) {
        if (draftSet == null) {
            return;
        }
        if (draftSet.scenarios().size() > TestSuiteRegistryService.MAX_CASES) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.scenario.compile.caseLimitExceeded",
                    "A governed publication may contain at most %d Scenarios."
                            .formatted(TestSuiteRegistryService.MAX_CASES),
                    "/scenarios"));
        }
        for (int index = 0; index < draftSet.scenarios().size(); index++) {
            if (draftSet.scenarios().get(index).caseType() == ScenarioDraftSet.CaseType.PROPERTY) {
                diagnostics.add(VisualDiagnostic.error(
                        "visual.scenario.compile.propertyMaterializationRequired",
                        "PROPERTY cases require the validator-proven property-plan materialization endpoint.",
                        "/scenarios/" + index + "/caseType"));
            }
        }
    }

    private static ScenarioGovernedCompilationPlan blocked(
            ScenarioDraftSet draftSet,
            TestExecutionApiRequest.Target runtimeTarget,
            List<VisualDiagnostic> diagnostics) {
        return new ScenarioGovernedCompilationPlan(
                "",
                false,
                draftSet == null ? "" : draftSet.scenarioDraftSetId(),
                draftSet == null ? 0 : draftSet.revision(),
                draftSet == null ? "" : draftSet.target().fingerprint(),
                draftSet == null ? "" : draftSet.contractFingerprint(),
                runtimeTarget,
                List.of(),
                null,
                diagnostics);
    }

    private static String pointer(String path) {
        return path == null ? "" : path.trim();
    }

    private static String contentAddressedId(String prefix, String digest) {
        String normalizedPrefix = prefix.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9._-]+", "-")
                .replaceAll("^-+|-+$", "");
        String normalizedDigest = digest.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-f0-9]+", "");
        int prefixLimit = Math.max(0, 255 - normalizedDigest.length() - 1);
        String boundedPrefix = normalizedPrefix.substring(
                0, Math.min(prefixLimit, normalizedPrefix.length()));
        return boundedPrefix + "-" + normalizedDigest;
    }

    private static String suffix(String fingerprint) {
        int separator = fingerprint.indexOf(':');
        return separator < 0 ? fingerprint : fingerprint.substring(separator + 1);
    }

    private static String defaulted(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
