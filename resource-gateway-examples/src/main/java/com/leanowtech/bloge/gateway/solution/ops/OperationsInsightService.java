package com.leanowtech.bloge.gateway.solution.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddMutationService;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddStateRepository;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddStoredAsset;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddToolException;
import com.leanowtech.bloge.gateway.agenttdd.SolutionTestingService;
import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Persists zero-payload runtime disposition signals and aggregates business operating insight.
 *
 * <p>Raw inputs, results, reasoning, tokens, URLs, and exception text never enter the signal store.
 * Runtime signals are append-only by a hashed event coordinate; test evidence contributes only
 * current red-GOLDEN identifiers and never inflates live hit or escalation counts.</p>
 */
@Service
public final class OperationsInsightService {
    /** Durable append-only asset kind for one successful published Solution invocation. */
    public static final String OPERATIONS_SIGNAL = "SOLUTION_OPERATIONS_SIGNAL";
    private static final int MAX_BYTES = 16 * 1024 * 1024;

    private final AgentTddStateRepository states;
    private final ObjectMapper mapper;

    /** Creates the operating-signal boundary over the shared durable asset store. */
    public OperationsInsightService(AgentTddStateRepository states, ObjectMapper mapper) {
        this.states = Objects.requireNonNull(states, "states");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /**
     * Records one completed runtime disposition using only structural classification fields.
     * Replaying the same event coordinate does not create a second signal.
     */
    public void record(String scopeKey,
                       String solutionRef,
                       String eventRef,
                       Map<String, Object> runtimeResponse) {
        String assetRef = "ops:" + shortHash(VisualBundleFingerprint.fromCanonicalValue(mapper,
                Map.of("solutionRef", required(solutionRef), "eventRef", required(eventRef)), MAX_BYTES));
        ObjectNode signal = mapper.createObjectNode();
        signal.put("solutionRef", solutionRef);
        signal.set("rulePath", safeRulePath(runtimeResponse.get("rulePath")));
        signal.put("instructionRef", Objects.toString(runtimeResponse.get("instructionRef"), "TERMINAL"));
        signal.put("resultKind", resultKind(runtimeResponse.get("result")));
        signal.put("recordedAt", Instant.now().toString());
        var existing = states.find(scopeKey, OPERATIONS_SIGNAL, assetRef);
        if (existing.isPresent()) return;
        states.saveIfRevision(scopeKey, OPERATIONS_SIGNAL, assetRef, 0, signal);
    }

    /** Returns live distributions plus current red-GOLDEN and policy-revision hints. */
    public Map<String, Object> performance(String solutionRef, IntegrationRequestContext identity) {
        if (identity == null || !IntegrationOperation.AGENT_TDD_READ.accepts(identity.purpose())) {
            throw new AgentTddToolException("FORBIDDEN_PURPOSE", "Read purpose is required.");
        }
        identity.requireComplete();
        String scope = AgentTddMutationService.scopeKey(identity);
        List<AgentTddStoredAsset> signals = states.list(scope, OPERATIONS_SIGNAL).stream()
                .filter(asset -> solutionRef.equals(asset.data().path("solutionRef").asText())).toList();
        LinkedHashMap<String, Integer> rules = new LinkedHashMap<>();
        LinkedHashMap<String, Integer> dispositions = new LinkedHashMap<>();
        LinkedHashMap<String, Integer> escalationRules = new LinkedHashMap<>();
        for (AgentTddStoredAsset asset : signals) {
            JsonNode data = asset.data();
            String rule = lastRule(data.path("rulePath"));
            String instruction = data.path("instructionRef").asText("TERMINAL");
            rules.merge(rule, 1, Integer::sum);
            dispositions.merge(data.path("resultKind").asText("UNKNOWN"), 1, Integer::sum);
            if (instruction.toLowerCase(java.util.Locale.ROOT).contains("escalat")) {
                escalationRules.merge(rule, 1, Integer::sum);
            }
        }
        AgentTddStoredAsset evidence = states.find(
                scope, SolutionTestingService.SOLUTION_EVIDENCE, solutionRef).orElse(null);
        List<String> redGolden = new ArrayList<>();
        Map<String, String> redRules = new LinkedHashMap<>();
        int totalCases = 0;
        if (evidence != null) {
            for (JsonNode row : evidence.data().path("cases")) {
                totalCases++;
                if (row.path("verdict").asText().endsWith("_FAIL")) {
                    String caseId = row.path("caseId").asText();
                    redGolden.add(caseId);
                    redRules.put(caseId, lastRule(row.path("rulePath")));
                }
            }
        }
        int total = signals.size();
        int escalated = escalationRules.values().stream().mapToInt(Integer::intValue).sum();
        double escalationRate = total == 0 ? 0.0d : (double) escalated / total;
        List<Map<String, Object>> gaps = policyGaps(escalationRules, total, redRules);
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("solutionRef", solutionRef);
        result.put("signalFingerprint", VisualBundleFingerprint.fromCanonicalValue(mapper,
                signals.stream().map(AgentTddStoredAsset::fingerprint).toList(), MAX_BYTES));
        result.put("evidenceFingerprint", evidence == null ? "" : evidence.fingerprint());
        result.put("totalInvocations", total);
        result.put("totalCases", totalCases);
        result.put("hitDistribution", distribution(rules, "ruleId", total));
        result.put("dispositionDistribution", distribution(dispositions, "resultKind", total));
        result.put("escalationRate", escalationRate);
        result.put("redGolden", List.copyOf(redGolden));
        result.put("policyGaps", gaps);
        return Map.copyOf(result);
    }

    private List<Map<String, Object>> policyGaps(Map<String, Integer> escalationRules,
                                                  int total,
                                                  Map<String, String> redRules) {
        List<Map<String, Object>> gaps = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>();
        escalationRules.forEach((rule, count) -> {
            if (total > 0 && (double) count / total >= 0.5d && keys.add("rule:" + rule)) {
                gaps.add(Map.of("ruleId", rule, "symptom", "高频转人工",
                        "suggestedRevision", "复核该规则的判断依据和处置出口"));
            }
        });
        redRules.forEach((caseId, rule) -> gaps.add(Map.of(
                "ruleId", rule, "caseId", caseId, "symptom", "应然仍未通过",
                "suggestedRevision", "先修正规则并重新执行红绿基线")));
        return List.copyOf(gaps);
    }

    private static List<Map<String, Object>> distribution(
            Map<String, Integer> counts, String keyName, int total) {
        return counts.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(entry -> Map.of(
                keyName, (Object) entry.getKey(), "count", entry.getValue(),
                "share", total == 0 ? 0.0d : (double) entry.getValue() / total)).toList();
    }

    private JsonNode safeRulePath(Object value) {
        if (!(value instanceof Iterable<?> iterable)) return mapper.createArrayNode().add("OTHERWISE");
        var result = mapper.createArrayNode();
        for (Object item : iterable) {
            String rule = Objects.toString(item, "").trim();
            if (!rule.isBlank()) result.add(rule);
        }
        if (result.isEmpty()) result.add("OTHERWISE");
        return result;
    }

    private static String lastRule(JsonNode path) {
        return path.isArray() && !path.isEmpty()
                ? path.path(path.size() - 1).asText("OTHERWISE") : "OTHERWISE";
    }

    private static String resultKind(Object value) {
        if (value == null) return "NULL";
        if (value instanceof JsonNode node) {
            if (node.isObject()) return "OBJECT";
            if (node.isArray()) return "ARRAY";
            if (node.isBoolean()) return "BOOLEAN";
            if (node.isNumber()) return "NUMBER";
            if (node.isTextual()) return "TEXT";
            return node.isNull() ? "NULL" : "UNKNOWN";
        }
        if (value instanceof Map<?, ?>) return "OBJECT";
        if (value instanceof Iterable<?> || value.getClass().isArray()) return "ARRAY";
        if (value instanceof Boolean) return "BOOLEAN";
        if (value instanceof Number) return "NUMBER";
        if (value instanceof CharSequence) return "TEXT";
        return "UNKNOWN";
    }

    private static String required(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) throw new IllegalArgumentException("Signal coordinate is required");
        return normalized;
    }

    private static String shortHash(String fingerprint) {
        String hash = fingerprint.startsWith("sha256:") ? fingerprint.substring(7) : fingerprint;
        return hash.substring(0, Math.min(24, hash.length()));
    }
}
