package com.leanowtech.bloge.gateway.testing.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.domain.TestRunEvidence;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Computes the canonical identity of a test run's business semantics.
 *
 * <p>The projection deliberately excludes run ids, wall-clock timestamps, durations, signatures,
 * evidence projection, governance provenance, and parallel completion order. It retains frozen
 * target/fixture/plan identity, stable graph coordinates, values, outcomes, fixture use,
 * assertions, diagnostics, and governed execution-service observations. Equivalent deterministic
 * runs therefore share this fingerprint while their complete evidence fingerprints remain unique.</p>
 */
public final class TestSemanticResultFingerprint {

    /** Version of the canonical semantic projection, independent of the evidence wire version. */
    public static final String MATERIAL_SCHEMA_VERSION = "bloge.semanticTestResult.v1";
    private static final List<String> SEMANTIC_METADATA_KEYS = List.of(
            "nodeControlModes",
            "executionServiceUsages",
            "logicalTime",
            "sideEffectIntents",
            TestRunEvidenceProtocolCodec.CONTROL_PROJECTION_METADATA_KEY);
    private static final Set<String> VOLATILE_SIDE_EFFECT_KEYS = Set.of(
            "attemptId", "executionId", "runId", "startedAt", "completedAt", "signedAt",
            "observedAt", "committedAt", "durationMs", "receiptId", "transactionRef");

    private TestSemanticResultFingerprint() {
    }

    /**
     * Computes a domain-separated canonical SHA-256 fingerprint.
     *
     * @param objectMapper canonical protocol mapper
     * @param evidence complete raw or sanitized evidence
     * @return canonical semantic result fingerprint
     */
    public static String compute(ObjectMapper objectMapper, TestRunEvidence evidence) {
        return ProtocolFingerprint.of(objectMapper, projection(objectMapper, evidence));
    }

    /**
     * Builds the inspectable canonical projection used by {@link #compute(ObjectMapper, TestRunEvidence)}.
     * This is useful for conformance tests and independent protocol implementations; callers must
     * still use the fingerprint, rather than map equality, as the portable identity.
     *
     * @param objectMapper canonical protocol mapper
     * @param evidence complete raw or sanitized evidence
     * @return immutable top-level semantic projection
     */
    public static Map<String, Object> projection(ObjectMapper objectMapper,
                                                 TestRunEvidence evidence) {
        Objects.requireNonNull(objectMapper, "objectMapper");
        Objects.requireNonNull(evidence, "evidence");
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("schemaVersion", MATERIAL_SCHEMA_VERSION);
        material.put("status", evidence.status().name());
        material.put("executionPurpose", evidence.executionPurpose());
        material.put("targetFingerprint", evidence.targetFingerprint());
        material.put("fixtureBundleFingerprint", evidence.fixtureBundleFingerprint());
        material.put("planFingerprint", evidence.planFingerprint());
        material.put("nodes", ordered(objectMapper, nodeFacts(objectMapper, evidence.nodeTrace())));
        material.put("edges", ordered(objectMapper, edgeFacts(objectMapper, evidence.edgeTrace())));
        material.put("fixtureConsumptions", ordered(objectMapper,
                consumptionFacts(evidence.fixtureConsumptions())));
        material.put("assertions", ordered(objectMapper,
                assertionFacts(objectMapper, evidence.assertionResults())));
        material.put("diagnostics", evidence.diagnostics().stream().sorted().toList());
        material.put("executionObservations", semanticMetadata(objectMapper, evidence.metadata()));
        return Map.copyOf(material);
    }

    /**
     * Computes and attaches the current semantic fingerprint.
     *
     * @param objectMapper canonical protocol mapper
     * @param evidence evidence to upgrade
     * @return current-version evidence with a self-consistent semantic fingerprint
     */
    public static TestRunEvidence attach(ObjectMapper objectMapper, TestRunEvidence evidence) {
        return Objects.requireNonNull(evidence, "evidence")
                .withSemanticResultFingerprint(compute(objectMapper, evidence));
    }

    /**
     * Verifies that current evidence carries the canonical fingerprint of its semantic projection.
     * Historical v1 evidence is accepted without this field.
     *
     * @param objectMapper canonical protocol mapper
     * @param evidence evidence to verify
     * @return true for valid current evidence or compatible v1 evidence
     */
    public static boolean matches(ObjectMapper objectMapper, TestRunEvidence evidence) {
        if (evidence == null) {
            return false;
        }
        if (TestRunEvidence.SCHEMA_VERSION_V1.equals(evidence.schemaVersion())) {
            return evidence.semanticResultFingerprint().isBlank();
        }
        return TestRunEvidence.SCHEMA_VERSION.equals(evidence.schemaVersion())
                && compute(objectMapper, evidence).equals(evidence.semanticResultFingerprint());
    }

    private static List<Fact> nodeFacts(ObjectMapper mapper,
                                        List<TestRunEvidence.NodeTrace> nodes) {
        List<Fact> facts = new ArrayList<>();
        for (TestRunEvidence.NodeTrace node : nodes) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("graphPath", node.graphPath());
            value.put("graphOccurrence", node.graphOccurrence());
            value.put("invocationSiteId", node.invocationSiteId());
            value.put("correlationKey", node.correlationKey());
            value.put("occurrence", node.occurrence());
            value.put("nodeId", node.nodeId());
            value.put("operatorRef", node.operatorRef());
            value.put("status", node.status());
            value.put("fidelity", node.fidelity());
            value.put("input", semanticValue(mapper, node.input()));
            value.put("output", semanticValue(mapper, node.output()));
            value.put("errorCode", node.errorCode());
            value.put("attempts", node.attempts().stream()
                    .sorted(Comparator.comparingInt(TestRunEvidence.AttemptTrace::attempt))
                    .map(attempt -> attemptFacts(mapper, attempt)).toList());
            facts.add(new Fact(nodeKey(node), value));
        }
        return facts;
    }

    private static Map<String, Object> attemptFacts(ObjectMapper mapper,
                                                    TestRunEvidence.AttemptTrace attempt) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("attempt", attempt.attempt());
        value.put("status", attempt.status());
        value.put("fidelity", attempt.fidelity());
        value.put("input", semanticValue(mapper, attempt.input()));
        value.put("output", semanticValue(mapper, attempt.output()));
        value.put("errorCode", attempt.errorCode());
        return value;
    }

    private static List<Fact> edgeFacts(ObjectMapper mapper,
                                        List<TestRunEvidence.EdgeTrace> edges) {
        List<Fact> facts = new ArrayList<>();
        for (TestRunEvidence.EdgeTrace edge : edges) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("graphPath", edge.graphPath());
            value.put("graphOccurrence", edge.graphOccurrence());
            value.put("correlationKey", edge.correlationKey());
            value.put("edgeId", edge.edgeId());
            value.put("fromInvocationSiteId", edge.fromInvocationSiteId());
            value.put("toInvocationSiteId", edge.toInvocationSiteId());
            value.put("status", edge.status());
            value.put("value", semanticValue(mapper, edge.value()));
            facts.add(new Fact(edgeKey(edge), value));
        }
        return facts;
    }

    private static List<Fact> consumptionFacts(
            List<TestRunEvidence.FixtureConsumption> consumptions) {
        return consumptions.stream().map(consumption -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("ruleId", consumption.ruleId());
            value.put("uses", consumption.uses());
            value.put("required", consumption.required());
            value.put("status", consumption.status());
            return new Fact(consumption.ruleId(), value);
        }).toList();
    }

    private static List<Fact> assertionFacts(ObjectMapper mapper,
                                             List<TestRunEvidence.AssertionResult> assertions) {
        return assertions.stream().map(assertion -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("scope", assertion.scope());
            value.put("path", assertion.path());
            value.put("passed", assertion.passed());
            value.put("expected", semanticValue(mapper, assertion.expected()));
            value.put("actual", semanticValue(mapper, assertion.actual()));
            value.put("diagnostic", assertion.diagnostic());
            return new Fact(assertion.scope() + '\u0000' + assertion.path(), value);
        }).toList();
    }

    private static List<Map<String, Object>> ordered(ObjectMapper mapper, List<Fact> facts) {
        return facts.stream().sorted(Comparator.comparing(Fact::key)
                        .thenComparing(fact -> ProtocolFingerprint.of(mapper, fact.value())))
                .map(Fact::value).toList();
    }

    private static Map<String, Object> semanticMetadata(ObjectMapper mapper,
                                                        Map<String, Object> metadata) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : SEMANTIC_METADATA_KEYS) {
            if (!metadata.containsKey(key)) {
                continue;
            }
            Object value = semanticValue(mapper, metadata.get(key));
            if (TestRunEvidenceProtocolCodec.CONTROL_PROJECTION_METADATA_KEY.equals(key)) {
                result.put(key, new TestRunEvidenceProtocolCodec(mapper)
                        .semanticProjection(metadata.get(key)));
            } else if ("executionServiceUsages".equals(key)) {
                result.put(key, semanticExecutionServiceUsages(mapper, value));
            } else {
                result.put(key, "sideEffectIntents".equals(key)
                        ? stableSideEffectIntents(mapper, value) : value);
            }
        }
        return result;
    }

    private static List<Map<String, Object>> semanticExecutionServiceUsages(
            ObjectMapper mapper, Object value) {
        if (!(value instanceof List<?> usages)) {
            return List.of();
        }
        List<Map<String, Object>> semantic = new ArrayList<>();
        for (Object usageValue : usages) {
            if (!(usageValue instanceof Map<?, ?> usage)) {
                continue;
            }
            Map<String, Object> copy = new LinkedHashMap<>();
            usage.forEach((key, item) -> copy.put(String.valueOf(key), item));
            if (number(copy.get("semanticProviderCalls")) > 0
                    || number(copy.get("functionCalls")) > 0) {
                Map<String, Object> observation = new LinkedHashMap<>();
                observation.put("schemaVersion", copy.getOrDefault("schemaVersion", ""));
                observation.put("service", copy.getOrDefault("service", ""));
                observation.put("mode", copy.getOrDefault("mode", ""));
                observation.put("semanticProviderCalls", number(copy.get("semanticProviderCalls")));
                observation.put("functionCalls", number(copy.get("functionCalls")));
                observation.put("functionCallSites", copy.getOrDefault("functionCallSites", List.of()));
                semantic.add(observation);
            }
        }
        return semantic.stream()
                .sorted(Comparator.<Map<String, Object>, String>comparing(
                                usage -> String.valueOf(usage.get("service")))
                        .thenComparing(usage -> ProtocolFingerprint.of(mapper, usage)))
                .toList();
    }

    private static long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0;
    }

    private static Object stableSideEffectIntents(ObjectMapper mapper, Object value) {
        Object stable = withoutVolatileSideEffectFacts(value);
        if (!(stable instanceof List<?> intents)) {
            return stable;
        }
        return intents.stream()
                .sorted(Comparator.comparing(intent -> ProtocolFingerprint.of(mapper, intent)))
                .toList();
    }

    private static Object withoutVolatileSideEffectFacts(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> stable = new LinkedHashMap<>();
            map.forEach((key, item) -> {
                String name = String.valueOf(key);
                if (!VOLATILE_SIDE_EFFECT_KEYS.contains(name)) {
                    stable.put(name, withoutVolatileSideEffectFacts(item));
                }
            });
            return stable;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(TestSemanticResultFingerprint::withoutVolatileSideEffectFacts)
                    .toList();
        }
        return value;
    }

    private static Object semanticValue(ObjectMapper mapper, Object value) {
        try {
            return mapper.convertValue(value, Object.class);
        } catch (IllegalArgumentException failure) {
            return String.valueOf(value);
        }
    }

    private static String nodeKey(TestRunEvidence.NodeTrace node) {
        return String.join("\u0000", node.graphPath(), String.valueOf(node.graphOccurrence()),
                node.invocationSiteId(), node.correlationKey(), String.valueOf(node.occurrence()),
                node.nodeId());
    }

    private static String edgeKey(TestRunEvidence.EdgeTrace edge) {
        return String.join("\u0000", edge.graphPath(), String.valueOf(edge.graphOccurrence()),
                edge.correlationKey(), edge.edgeId(), edge.fromInvocationSiteId(),
                edge.toInvocationSiteId());
    }

    private record Fact(String key, Map<String, Object> value) {
    }
}
