package com.leanowtech.bloge.gateway.agenttdd;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic, bounded decision-table-to-scenario enumerator for the MCP authoring surface.
 *
 * <p>The enumerator deliberately recognizes only numeric comparisons and ranges. It never guesses
 * at opaque predicates. Integer thresholds receive the exact {@code t-1, t, t+1} neighborhood;
 * decimal thresholds use a one-millionth epsilon. Combinatorial enumeration fails closed when the
 * requested cap would be exceeded instead of silently dropping business boundaries.</p>
 */
public final class AgentTddDecisionScenarioEnumerator {
    private static final Pattern COMPARISON = Pattern.compile(
            "^\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*(<=|>=|==|<|>)\\s*(-?(?:\\d+(?:\\.\\d*)?|\\.\\d+))\\s*$");
    private static final Pattern RANGE = Pattern.compile(
            "^\\s*(-?(?:\\d+(?:\\.\\d*)?|\\.\\d+))\\s*(<=|<)\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*(<=|<)\\s*(-?(?:\\d+(?:\\.\\d*)?|\\.\\d+))\\s*$");
    private final ObjectMapper mapper;

    /** Creates the canonical enumerator using the protocol JSON mapper. */
    public AgentTddDecisionScenarioEnumerator(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Enumerates rows from one decision-table node in an existing graph draft.
     *
     * @param draft authoritative graph draft
     * @param request enumerateFrom request
     * @return deterministic generated BOUNDARY rows
     */
    public List<ObjectNode> enumerate(GraphDraft draft, JsonNode request) {
        String nodeId = requiredText(request, "decisionTableRef");
        String mode = requiredText(request, "mode").toLowerCase(Locale.ROOT);
        int cap = request.path("maxCases").canConvertToInt() ? request.path("maxCases").asInt() : 500;
        if (!Set.of("per-rule", "combinatorial").contains(mode) || cap < 1 || cap > 500) {
            throw new AgentTddToolException("SCHEMA_NONCONFORMANT",
                    "Enumeration mode and maxCases must be per-rule|combinatorial and 1..500.");
        }
        GraphDraft.DraftNode node = draft.nodes().stream().filter(value -> nodeId.equals(value.id()))
                .findFirst().orElseThrow(() -> new AgentTddToolException(
                        "DRAFT_NOT_FOUND", "Decision table node was not found."));
        if (!"bloge:decisionTable".equals(node.operatorRef())) {
            throw new AgentTddToolException("SCHEMA_NONCONFORMANT",
                    "enumerateFrom must reference a decision-table node.");
        }
        List<Rule> rules = rules(node.config().get("rules"));
        return "per-rule".equals(mode)
                ? perRule(nodeId, rules, cap)
                : combinations(nodeId, rules, cap);
    }

    private List<ObjectNode> perRule(String nodeId, List<Rule> rules, int cap) {
        if (rules.size() > cap) {
            throw capExceeded(rules.size(), cap);
        }
        List<ObjectNode> rows = new ArrayList<>();
        int ordinal = 0;
        for (Rule rule : rules) {
            LinkedHashMap<String, BigDecimal> given = new LinkedHashMap<>();
            for (Predicate predicate : rule.predicates()) {
                given.put(predicate.column(), predicate.representative());
            }
            if (given.isEmpty() && !rule.otherwise()) {
                throw opaque();
            }
            rows.add(row(nodeId + "-rule-" + (++ordinal), given,
                    Map.of("enumerationRule", rule.id())));
        }
        return List.copyOf(rows);
    }

    private List<ObjectNode> combinations(String nodeId, List<Rule> rules, int cap) {
        LinkedHashMap<String, LinkedHashSet<BigDecimal>> domains = new LinkedHashMap<>();
        for (Rule rule : rules) {
            for (Predicate predicate : rule.predicates()) {
                predicate.neighborhood().forEach(value -> domains
                        .computeIfAbsent(predicate.column(), ignored -> new LinkedHashSet<>()).add(value));
            }
        }
        if (domains.isEmpty()) throw opaque();
        List<String> columns = domains.keySet().stream().sorted().toList();
        long total = 1;
        for (String column : columns) {
            if (total > cap / domains.get(column).size()) throw capExceeded((long) cap + 1, cap);
            total *= domains.get(column).size();
        }
        if (total > cap) throw capExceeded(total, cap);
        List<Map<String, BigDecimal>> values = new ArrayList<>();
        values.add(new LinkedHashMap<>());
        for (String column : columns) {
            List<Map<String, BigDecimal>> next = new ArrayList<>();
            List<BigDecimal> domain = domains.get(column).stream().sorted().toList();
            for (Map<String, BigDecimal> prefix : values) {
                for (BigDecimal value : domain) {
                    LinkedHashMap<String, BigDecimal> item = new LinkedHashMap<>(prefix);
                    item.put(column, value);
                    next.add(item);
                }
            }
            values = next;
        }
        List<ObjectNode> rows = new ArrayList<>();
        for (int index = 0; index < values.size(); index++) {
            rows.add(row(nodeId + "-boundary-" + (index + 1), values.get(index),
                    Map.of("enumerationMode", "combinatorial")));
        }
        return List.copyOf(rows);
    }

    private ObjectNode row(String caseId, Map<String, BigDecimal> given, Map<String, String> provenance) {
        ObjectNode row = mapper.createObjectNode();
        row.put("caseId", caseId);
        row.put("category", "BOUNDARY");
        row.put("lifecycle", "DRAFT");
        row.put("qualityState", "DESIGNED_NOT_RUN");
        row.set("given", mapper.valueToTree(given));
        row.set("stubs", mapper.createObjectNode());
        row.set("enumeration", mapper.valueToTree(provenance));
        return row;
    }

    private List<Rule> rules(Object raw) {
        if (!(raw instanceof List<?> list)) {
            throw new AgentTddToolException("SCHEMA_NONCONFORMANT", "Decision table has no rule list.");
        }
        List<Rule> values = new ArrayList<>();
        int ordinal = 0;
        for (Object entry : list) {
            if (!(entry instanceof Map<?, ?> rule)) continue;
            boolean otherwise = Boolean.TRUE.equals(rule.get("otherwise"))
                    || "otherwise".equalsIgnoreCase(String.valueOf(rule.get("conditions")));
            String id = rule.get("id") == null ? "R" + (++ordinal) : String.valueOf(rule.get("id"));
            values.add(new Rule(id, otherwise, predicates(rule.get("conditions"))));
        }
        return values.stream().sorted(Comparator.comparing(Rule::id)).toList();
    }

    private List<Predicate> predicates(Object raw) {
        if (!(raw instanceof Map<?, ?> conditions)) {
            return List.of();
        }
        List<Predicate> values = new ArrayList<>();
        conditions.values().forEach(value -> values.addAll(parse(String.valueOf(value))));
        return values;
    }

    private List<Predicate> parse(String expression) {
        Matcher comparison = COMPARISON.matcher(expression);
        if (comparison.matches()) {
            return List.of(new Predicate(comparison.group(1), comparison.group(2),
                    new BigDecimal(comparison.group(3))));
        }
        Matcher range = RANGE.matcher(expression);
        if (range.matches()) {
            return List.of(
                    new Predicate(range.group(3), range.group(2).equals("<=") ? ">=" : ">",
                            new BigDecimal(range.group(1))),
                    new Predicate(range.group(3), range.group(4), new BigDecimal(range.group(5))));
        }
        throw opaque();
    }

    private static AgentTddToolException opaque() {
        return new AgentTddToolException("SCHEMA_NONCONFORMANT",
                "Opaque decision predicates require explicit author samples.");
    }

    private static AgentTddToolException capExceeded(long total, int cap) {
        return new AgentTddToolException("COMBINATORIAL_CAP_EXCEEDED",
                "Decision scenario product " + total + " exceeds maxCases " + cap + ".");
    }

    private static String requiredText(JsonNode node, String field) {
        String value = node.path(field).isTextual() ? node.path(field).asText().trim() : "";
        if (value.isBlank()) {
            throw new AgentTddToolException("SCHEMA_NONCONFORMANT", field + " is required.");
        }
        return value;
    }

    private record Rule(String id, boolean otherwise, List<Predicate> predicates) { }

    private record Predicate(String column, String operator, BigDecimal threshold) {
        BigDecimal epsilon() {
            return threshold.stripTrailingZeros().scale() <= 0 ? BigDecimal.ONE : new BigDecimal("0.000001");
        }

        List<BigDecimal> neighborhood() {
            return List.of(threshold.subtract(epsilon()), threshold, threshold.add(epsilon()));
        }

        BigDecimal representative() {
            return switch (operator) {
                case ">" -> threshold.add(epsilon());
                case "<" -> threshold.subtract(epsilon());
                default -> threshold;
            };
        }
    }
}
