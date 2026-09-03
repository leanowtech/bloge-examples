package com.leanowtech.bloge.gateway.agenttdd;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
 * Pure, deterministic and bounded decision-table-to-scenario enumerator for Agent TDD.
 *
 * <p>The accepted grammar is intentionally small: numeric comparisons, numeric ranges, finite
 * membership, {@code otherwise}, and explicitly sampled opaque predicates. Per-rule mode creates
 * one proposed GOLDEN representative plus linear boundary neighbors. Combinatorial mode creates a
 * sorted Cartesian product and fails closed before exceeding {@code maxCases}. An opaque predicate
 * without an author sample is materialized as a BLOCKED row, never guessed or silently omitted.</p>
 */
public final class AgentTddDecisionScenarioEnumerator {
    private static final Pattern COMPARISON = Pattern.compile(
            "^\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*(<=|>=|==|!=|<|>)\\s*(-?(?:\\d+(?:\\.\\d*)?|\\.\\d+))\\s*$");
    private static final Pattern RANGE = Pattern.compile(
            "^\\s*(-?(?:\\d+(?:\\.\\d*)?|\\.\\d+))\\s*(<=|<)\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*(<=|<)\\s*(-?(?:\\d+(?:\\.\\d*)?|\\.\\d+))\\s*$");
    private static final Pattern MEMBERSHIP = Pattern.compile(
            "^\\s*([A-Za-z_][A-Za-z0-9_]*)\\s+in\\s*\\{(.*)}\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern LITERAL_EQUALITY = Pattern.compile(
            "^\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*(==|!=)\\s*(.+?)\\s*$");

    private final ObjectMapper mapper;

    /** Creates the canonical enumerator using the protocol JSON mapper. */
    public AgentTddDecisionScenarioEnumerator(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Enumerates rows from one decision-table node in an existing graph draft.
     *
     * @param draft authoritative graph draft
     * @param request enumerateFrom request, optionally containing authorSamples and oracleOwner
     * @return deterministic generated scenario rows
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
        List<Rule> rules = rules(node.config().get("rules"), request.path("authorSamples"));
        return "per-rule".equals(mode)
                ? perRule(nodeId, rules, cap, optionalText(request, "oracleOwner"))
                : combinations(nodeId, rules, cap);
    }

    /**
     * Derives the same deterministic fact domains used by combinatorial scenario enumeration.
     *
     * <p>The returned columns and values are sorted and detached from the draft. Opaque predicates
     * without author samples are omitted because the board must expose them as unknown rather than
     * inventing business values. Both the canonical and legacy decision-table references are read
     * so older stored drafts retain an honest coverage projection.</p>
     *
     * @param node decision-table node whose rules define the bounded fact space
     * @return ordered fact columns and their representative boundary values
     */
    public Map<String, List<JsonNode>> factDomains(GraphDraft.DraftNode node) {
        if (node == null || !Set.of("bloge:decisionTable", "decision_table").contains(node.operatorRef())) {
            return Map.of();
        }
        if (!(node.config().get("rules") instanceof List<?>)) {
            return Map.of();
        }
        LinkedHashMap<String, LinkedHashSet<JsonNode>> domains = new LinkedHashMap<>();
        for (Rule rule : rules(node.config().get("rules"), mapper.createObjectNode())) {
            for (Predicate predicate : rule.predicates()) {
                if (!predicate.blocked()) {
                    predicate.values().forEach(value -> domains
                            .computeIfAbsent(predicate.column(), ignored -> new LinkedHashSet<>())
                            .add(value.deepCopy()));
                }
            }
        }
        LinkedHashMap<String, List<JsonNode>> ordered = new LinkedHashMap<>();
        domains.keySet().stream().sorted().forEach(column -> ordered.put(column,
                domains.get(column).stream().sorted(AgentTddDecisionScenarioEnumerator::compareValues)
                        .map(value -> (JsonNode) value.deepCopy()).toList()));
        return java.util.Collections.unmodifiableMap(ordered);
    }

    private List<ObjectNode> perRule(String nodeId, List<Rule> rules, int cap, String oracleOwner) {
        List<ObjectNode> rows = new ArrayList<>();
        int ruleOrdinal = 0;
        int boundaryOrdinal = 0;
        for (Rule rule : rules) {
            if (rule.blocked()) {
                rows.add(blockedRow(nodeId + "-rule-" + (++ruleOrdinal), rule));
                ensureCap(rows.size(), cap);
                continue;
            }
            if (oracleOwner.isBlank()) {
                throw new AgentTddToolException("SCHEMA_NONCONFORMANT",
                        "per-rule enumeration requires oracleOwner for generated GOLDEN proposals.");
            }
            if (rule.conclusion().isNull()) {
                throw new AgentTddToolException("SCHEMA_NONCONFORMANT",
                        "Every enumerated decision rule requires an output conclusion.");
            }
            LinkedHashMap<String, JsonNode> representative = new LinkedHashMap<>();
            for (Predicate predicate : rule.predicates()) {
                representative.put(predicate.column(), predicate.representative());
            }
            rows.add(goldenRow(nodeId + "-rule-" + (++ruleOrdinal), representative,
                    rule, oracleOwner));
            ensureCap(rows.size(), cap);
            for (Predicate predicate : rule.predicates()) {
                for (JsonNode neighbor : predicate.values()) {
                    if (neighbor.equals(predicate.representative())) continue;
                    LinkedHashMap<String, JsonNode> given = new LinkedHashMap<>(representative);
                    given.put(predicate.column(), neighbor);
                    rows.add(boundaryRow(nodeId + "-boundary-" + (++boundaryOrdinal), given,
                            Map.of("enumerationMode", "per-rule", "enumerationRule", rule.id(),
                                    "boundaryInput", predicate.column())));
                    ensureCap(rows.size(), cap);
                }
            }
        }
        return List.copyOf(rows);
    }

    private List<ObjectNode> combinations(String nodeId, List<Rule> rules, int cap) {
        List<Rule> blocked = rules.stream().filter(Rule::blocked).toList();
        if (!blocked.isEmpty()) {
            if (blocked.size() > cap) throw capExceeded(blocked.size(), cap);
            List<ObjectNode> rows = new ArrayList<>();
            int ordinal = 0;
            for (Rule rule : blocked) rows.add(blockedRow(nodeId + "-blocked-" + (++ordinal), rule));
            return List.copyOf(rows);
        }
        LinkedHashMap<String, LinkedHashSet<JsonNode>> domains = new LinkedHashMap<>();
        for (Rule rule : rules) {
            for (Predicate predicate : rule.predicates()) {
                predicate.values().forEach(value -> domains
                        .computeIfAbsent(predicate.column(), ignored -> new LinkedHashSet<>()).add(value));
            }
        }
        if (domains.isEmpty()) {
            throw new AgentTddToolException("SCHEMA_NONCONFORMANT",
                    "Combinatorial enumeration requires at least one bounded predicate.");
        }
        List<String> columns = domains.keySet().stream().sorted().toList();
        long total = 1;
        for (String column : columns) {
            if (total > cap / domains.get(column).size()) throw capExceeded((long) cap + 1, cap);
            total *= domains.get(column).size();
        }
        if (total > cap) throw capExceeded(total, cap);
        List<Map<String, JsonNode>> products = new ArrayList<>();
        products.add(new LinkedHashMap<>());
        for (String column : columns) {
            List<Map<String, JsonNode>> next = new ArrayList<>();
            List<JsonNode> domain = domains.get(column).stream()
                    .sorted(AgentTddDecisionScenarioEnumerator::compareValues).toList();
            for (Map<String, JsonNode> prefix : products) {
                for (JsonNode value : domain) {
                    LinkedHashMap<String, JsonNode> item = new LinkedHashMap<>(prefix);
                    item.put(column, value);
                    next.add(item);
                }
            }
            products = next;
        }
        List<ObjectNode> rows = new ArrayList<>();
        for (int index = 0; index < products.size(); index++) {
            rows.add(boundaryRow(nodeId + "-boundary-" + (index + 1), products.get(index),
                    Map.of("enumerationMode", "combinatorial")));
        }
        return List.copyOf(rows);
    }

    private ObjectNode goldenRow(String caseId, Map<String, JsonNode> given,
                                 Rule rule, String oracleOwner) {
        ObjectNode row = baseRow(caseId, "GOLDEN", "DESIGNED_NOT_RUN", given);
        row.set("expect", rule.conclusion().deepCopy());
        row.put("oracleOwner", oracleOwner);
        row.set("enumeration", mapper.valueToTree(Map.of(
                "enumerationMode", "per-rule", "enumerationRule", rule.id())));
        return row;
    }

    private ObjectNode boundaryRow(String caseId, Map<String, JsonNode> given,
                                   Map<String, String> provenance) {
        ObjectNode row = baseRow(caseId, "BOUNDARY", "DESIGNED_NOT_RUN", given);
        row.set("enumeration", mapper.valueToTree(provenance));
        return row;
    }

    private ObjectNode blockedRow(String caseId, Rule rule) {
        ObjectNode row = baseRow(caseId, "GOLDEN", "BLOCKED", Map.of());
        row.set("enumeration", mapper.valueToTree(Map.of(
                "enumerationMode", "opaque", "enumerationRule", rule.id(),
                "reason", "AUTHOR_SAMPLES_REQUIRED")));
        return row;
    }

    private ObjectNode baseRow(String caseId, String category, String quality,
                               Map<String, JsonNode> given) {
        ObjectNode row = mapper.createObjectNode();
        row.put("caseId", caseId);
        row.put("category", category);
        row.put("lifecycle", "DRAFT");
        row.put("qualityState", quality);
        ObjectNode givenNode = row.putObject("given");
        given.forEach((key, value) -> givenNode.set(key, value.deepCopy()));
        row.set("stubs", mapper.createObjectNode());
        return row;
    }

    private List<Rule> rules(Object raw, JsonNode authorSamples) {
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
            JsonNode conclusion = mapper.valueToTree(rule.get("output"));
            values.add(new Rule(id, otherwise, predicates(rule.get("conditions"), authorSamples), conclusion));
        }
        return values.stream().sorted(Comparator.comparing(Rule::id)).toList();
    }

    private List<Predicate> predicates(Object raw, JsonNode authorSamples) {
        if (!(raw instanceof Map<?, ?> conditions)) return List.of();
        List<Predicate> values = new ArrayList<>();
        conditions.entrySet().stream().sorted(Comparator.comparing(entry -> String.valueOf(entry.getKey())))
                .forEach(entry -> values.add(parse(String.valueOf(entry.getKey()), entry.getValue(), authorSamples)));
        return values;
    }

    private Predicate parse(String inputName, Object raw, JsonNode authorSamples) {
        String expression = String.valueOf(raw);
        Matcher comparison = COMPARISON.matcher(expression);
        if (comparison.matches()) {
            BigDecimal threshold = new BigDecimal(comparison.group(3));
            BigDecimal epsilon = epsilon(threshold);
            List<JsonNode> values = decimalNodes(
                    threshold.subtract(epsilon), threshold, threshold.add(epsilon));
            JsonNode representative = switch (comparison.group(2)) {
                case ">" -> values.get(2);
                case "<", "!=" -> values.get(0);
                default -> values.get(1);
            };
            return new Predicate(comparison.group(1), values, representative, false);
        }
        Matcher range = RANGE.matcher(expression);
        if (range.matches()) {
            BigDecimal lower = new BigDecimal(range.group(1));
            BigDecimal upper = new BigDecimal(range.group(5));
            if (lower.compareTo(upper) >= 0) {
                throw new AgentTddToolException("SCHEMA_NONCONFORMANT",
                        "Decision range lower bound must be smaller than upper bound.");
            }
            BigDecimal epsilon = epsilon(lower).min(epsilon(upper));
            BigDecimal midpoint = lower.add(upper).divide(BigDecimal.valueOf(2));
            return new Predicate(range.group(3), decimalNodes(
                    lower.subtract(epsilon), lower, midpoint, upper, upper.add(epsilon)),
                    mapper.valueToTree(midpoint), false);
        }
        Matcher membership = MEMBERSHIP.matcher(expression);
        if (membership.matches()) {
            List<JsonNode> members = membershipValues(membership.group(2));
            if (members.isEmpty()) {
                throw new AgentTddToolException("SCHEMA_NONCONFORMANT", "Membership set cannot be empty.");
            }
            return new Predicate(membership.group(1), members, members.getFirst(), false);
        }
        Matcher literalEquality = LITERAL_EQUALITY.matcher(expression);
        if (literalEquality.matches()) {
            JsonNode literal = literal(literalEquality.group(3));
            if ("==".equals(literalEquality.group(2))) {
                return new Predicate(literalEquality.group(1), List.of(literal), literal, false);
            }
            JsonNode other = literal.isTextual()
                    ? mapper.valueToTree(literal.asText() + "__OTHER__")
                    : literal.isBoolean() ? mapper.valueToTree(!literal.asBoolean())
                    : mapper.valueToTree("__OTHER__");
            return new Predicate(literalEquality.group(1), List.of(literal, other), other, false);
        }
        JsonNode samples = authorSamples.path(inputName);
        if (!samples.isMissingNode() && !samples.isNull()) {
            List<JsonNode> values = new ArrayList<>();
            if (samples.isArray()) samples.forEach(sample -> values.add(sample.deepCopy()));
            else values.add(samples.deepCopy());
            if (!values.isEmpty()) return new Predicate(inputName, List.copyOf(values), values.getFirst(), false);
        }
        return new Predicate(inputName, List.of(), mapper.nullNode(), true);
    }

    private List<JsonNode> membershipValues(String body) {
        ArrayNode parsed;
        try {
            parsed = (ArrayNode) mapper.readTree("[" + body + "]");
        } catch (Exception ignored) {
            parsed = mapper.createArrayNode();
            for (String token : body.split(",")) parsed.add(token.trim());
        }
        List<JsonNode> values = new ArrayList<>();
        parsed.forEach(value -> values.add(value.deepCopy()));
        return List.copyOf(values);
    }

    private JsonNode literal(String token) {
        try {
            return mapper.readTree(token);
        } catch (Exception ignored) {
            return mapper.valueToTree(token.trim());
        }
    }

    private List<JsonNode> decimalNodes(BigDecimal... values) {
        List<JsonNode> nodes = new ArrayList<>();
        for (BigDecimal value : values) nodes.add(mapper.valueToTree(value.stripTrailingZeros()));
        return List.copyOf(nodes);
    }

    private static BigDecimal epsilon(BigDecimal value) {
        return value.stripTrailingZeros().scale() <= 0 ? BigDecimal.ONE : new BigDecimal("0.000001");
    }

    private static int compareValues(JsonNode left, JsonNode right) {
        if (left.isNumber() && right.isNumber()) {
            return left.decimalValue().compareTo(right.decimalValue());
        }
        return left.toString().compareTo(right.toString());
    }

    private static void ensureCap(long total, int cap) {
        if (total > cap) throw capExceeded(total, cap);
    }

    private static AgentTddToolException capExceeded(long total, int cap) {
        return new AgentTddToolException("COMBINATORIAL_CAP_EXCEEDED",
                "Decision scenario product " + total + " exceeds maxCases " + cap + ".");
    }

    private static String requiredText(JsonNode node, String field) {
        String value = optionalText(node, field);
        if (value.isBlank()) {
            throw new AgentTddToolException("SCHEMA_NONCONFORMANT", field + " is required.");
        }
        return value;
    }

    private static String optionalText(JsonNode node, String field) {
        return node != null && node.path(field).isTextual() ? node.path(field).asText().trim() : "";
    }

    private record Rule(String id, boolean otherwise, List<Predicate> predicates, JsonNode conclusion) {
        boolean blocked() {
            return predicates.stream().anyMatch(Predicate::blocked);
        }
    }

    private record Predicate(String column, List<JsonNode> values,
                             JsonNode representative, boolean blocked) { }
}
