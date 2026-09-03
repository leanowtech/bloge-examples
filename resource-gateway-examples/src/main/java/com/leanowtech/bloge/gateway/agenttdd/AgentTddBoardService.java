package com.leanowtech.bloge.gateway.agenttdd;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftRepository;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Builds the read-oriented Agent TDD status and human-review projection. */
@Service
public final class AgentTddBoardService {
    private final GraphDraftRepository drafts;
    private final AgentTddStateRepository states;
    private final AgentTddWorkflowService workflow;
    private final ObjectMapper mapper;

    /** Creates the board projection from authoritative drafts and durable Agent overlays. */
    public AgentTddBoardService(GraphDraftRepository drafts,
                                AgentTddStateRepository states,
                                AgentTddWorkflowService workflow,
                                ObjectMapper mapper) {
        this.drafts = Objects.requireNonNull(drafts, "drafts");
        this.states = Objects.requireNonNull(states, "states");
        this.workflow = Objects.requireNonNull(workflow, "workflow");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /** Returns scoped Tool readiness cards and pending human decisions without fixture payloads. */
    public Map<String, Object> board(IntegrationRequestContext identity) {
        String scope = AgentTddMutationService.scopeKey(identity);
        List<Map<String, Object>> tools = drafts.all().stream()
                .filter(Objects::nonNull)
                .filter(draft -> identity.matchesDraftScope(draft) && "TOOL".equals(assetKind(draft)))
                .sorted(Comparator.comparing(GraphDraft::draftId))
                .map(draft -> toolCard(draft, scope, identity))
                .toList();
        List<Map<String, Object>> reviews = new ArrayList<>();
        states.list(scope, AgentTddMutationService.CASE_SET).forEach(asset ->
                asset.data().path("rows").forEach(row -> {
                    if ("PENDING".equals(row.path("proposedOracle").path("status").asText())) {
                        reviews.add(Map.of("kind", "ORACLE", "assetRef", asset.assetRef(),
                                "caseId", row.path("caseId").asText(), "revision", asset.revision(),
                                "owner", row.path("proposedOracle").path("oracleOwner").asText(),
                                "proposalFingerprint",
                                row.path("proposedOracle").path("proposalFingerprint").asText()));
                    }
                }));
        states.list(scope, AgentTddWorkflowService.PUBLISH_SPEC).stream()
                .filter(asset -> "PENDING".equals(asset.data().path("status").asText()))
                .forEach(asset -> reviews.add(Map.of("kind", "PUBLISH_SPEC", "assetRef", asset.assetRef(),
                        "revision", asset.revision(), "owner", "tool-owner",
                        "proposalFingerprint", asset.data().path("proposalFingerprint").asText())));
        tools.stream()
                .filter(tool -> gate(tool, "greenBaseline") && !gate(tool, "ownerSignoff"))
                .forEach(tool -> pendingSignoff(scope, tool).ifPresent(reviews::add));
        reviews.sort(Comparator.comparing(row -> row.get("kind") + ":" + row.get("assetRef")));
        return Map.of("tools", tools, "pendingReviews", reviews,
                "evidenceCount", states.list(scope, AgentTddWorkflowService.EVIDENCE).size(),
                "payloadPolicy", "STRUCTURE_ONLY");
    }

    /**
     * Projects the immutable GREEN baseline identity needed by the separate human signoff endpoint.
     *
     * <p>The projection contains no fixture input, expected output, provider response, or diagnostic
     * message. It lets the browser submit the exact revision and fingerprints already visible in
     * the governed board without letting an Agent manufacture an approval.</p>
     */
    private java.util.Optional<Map<String, Object>> pendingSignoff(String scope,
                                                                   Map<String, Object> tool) {
        String toolRef = Objects.toString(tool.get("toolRef"), "");
        return states.find(scope, AgentTddWorkflowService.VERDICT, toolRef)
                .map(AgentTddStoredAsset::data)
                .map(data -> data.path("latest"))
                .filter(latest -> "GREEN".equals(latest.path("side").asText()))
                .filter(latest -> "GO".equals(latest.path("status").asText()))
                .filter(latest -> latest.path("draftRevision").asLong() > 0)
                .filter(latest -> !latest.path("goldenSetId").asText().isBlank())
                .filter(latest -> !latest.path("evidenceFingerprint").asText().isBlank())
                .map(latest -> Map.of(
                        "kind", (Object) "PUBLISH_SIGNOFF",
                        "assetRef", toolRef,
                        "draftRevision", latest.path("draftRevision").asLong(),
                        "goldenSetId", latest.path("goldenSetId").asText(),
                        "evidenceFingerprint", latest.path("evidenceFingerprint").asText(),
                        "owner", "tool-owner"));
    }

    private static boolean gate(Map<String, Object> tool, String name) {
        Object gates = tool.get("gates");
        return gates instanceof Map<?, ?> values && Boolean.TRUE.equals(values.get(name));
    }

    private Map<String, Object> toolCard(GraphDraft draft,
                                         String scope,
                                         IntegrationRequestContext identity) {
        Map<String, Object> readiness = workflow.readiness(
                mapper.valueToTree(Map.of("toolRef", draft.draftId())), identity);
        java.util.LinkedHashMap<String, Object> card = new java.util.LinkedHashMap<>(readiness);
        card.put("contract", Map.of("inputFields", schemaFields(draft.inputSchema()),
                "outputFields", schemaFields(draft.outputSchema()),
                "nodeCount", draft.nodes().size(), "edgeCount", draft.edges().size()));
        card.put("structure", Map.of(
                "graphName", draft.graphName(),
                "nodes", draft.nodes().stream().map(node -> Map.of(
                        "id", node.id(), "label", node.label(), "operatorRef", node.operatorRef(),
                        "inputNames", node.inputs().keySet().stream().sorted().toList())).toList(),
                "flows", draft.edges().stream().map(edge -> Map.of(
                        "kind", edge.kind(), "from", edge.source().nodeId(), "fromPort", edge.source().port(),
                        "to", edge.target().nodeId(), "toPort", edge.target().port())).toList()));
        List<Map<String, Object>> matrices = ruleMatrices(draft);
        card.put("ruleMatrices", matrices);
        card.put("flowSummary", flowSummary(draft));
        states.find(scope, AgentTddWorkflowService.VERDICT, draft.draftId()).ifPresent(asset -> {
            var latest = asset.data().path("latest");
            List<Map<String, String>> cases = new ArrayList<>();
            latest.path("cases").forEach(row -> cases.add(Map.of(
                    "caseId", row.path("caseId").asText(),
                    "layer", row.path("layer").asText(),
                    "verdict", row.path("verdict").asText())));
            card.put("redToGreen", Map.of(
                    "side", latest.path("side").asText(),
                    "status", latest.path("status").asText(),
                    "byLayer", asset.data().path("byLayer"),
                    "businessBacklog", asset.data().path("businessBacklog"),
                    "cases", cases));
        });
        long active = 0;
        long stale = 0;
        long pending = 0;
        List<Map<String, String>> caseRows = new ArrayList<>();
        List<JsonNode> activeGoldenGiven = new ArrayList<>();
        for (AgentTddStoredAsset asset : states.list(scope, AgentTddMutationService.CASE_SET)) {
            if (!draft.draftId().equals(asset.data().path("toolRef").asText())) continue;
            for (var row : asset.data().path("rows")) {
                if ("ACTIVE".equals(row.path("lifecycle").asText())) active++;
                if ("ACTIVE".equals(row.path("lifecycle").asText())
                        && "GOLDEN".equals(row.path("category").asText())
                        && row.path("given").isObject()) {
                    activeGoldenGiven.add(row.path("given").deepCopy());
                }
                if ("STALE".equals(row.path("lifecycle").asText())) stale++;
                if ("PENDING".equals(row.path("proposedOracle").path("status").asText())) pending++;
                caseRows.add(Map.of(
                        "caseSetRef", asset.assetRef(),
                        "caseId", row.path("caseId").asText(),
                        "category", row.path("category").asText(),
                        "lifecycle", row.path("lifecycle").asText(),
                        "qualityState", row.path("qualityState").asText()));
            }
        }
        card.put("caseCoverage", Map.of("active", active, "stale", stale, "pendingApproval", pending));
        caseRows.sort(Comparator.comparing(row -> row.get("caseSetRef") + ":" + row.get("caseId")));
        card.put("caseTable", List.copyOf(caseRows));
        card.put("factCoverage", factCoverage(draft, activeGoldenGiven));
        card.put("journey", journey(card));
        return Map.copyOf(card);
    }

    /** Projects declarative decision nodes into business-readable tables without runtime payloads. */
    private List<Map<String, Object>> ruleMatrices(GraphDraft draft) {
        return draft.nodes().stream()
                .filter(AgentTddBoardService::isDecisionTable)
                .map(this::ruleMatrix)
                .toList();
    }

    private Map<String, Object> ruleMatrix(GraphDraft.DraftNode node) {
        List<Map<String, String>> conditionColumns = columns(node.config().get("conditionColumns"));
        List<Map<String, String>> outputColumns = columns(node.config().get("outputColumns"));
        List<Map<String, Object>> rules = new ArrayList<>();
        Map<String, String> otherwise = new LinkedHashMap<>();
        if (node.config().get("rules") instanceof List<?> rawRules) {
            int ordinal = 0;
            for (Object raw : rawRules) {
                if (!(raw instanceof Map<?, ?> rule)) continue;
                String id = Objects.toString(rule.get("id"), "R" + (++ordinal));
                Map<String, String> conditions = displayMap(rule.get("conditions"), true);
                Map<String, String> outputs = displayMap(
                        rule.containsKey("outputs") ? rule.get("outputs") : rule.get("output"), false);
                boolean fallback = Boolean.TRUE.equals(rule.get("otherwise"))
                        || "otherwise".equalsIgnoreCase(Objects.toString(rule.get("conditions"), ""));
                if (fallback) {
                    otherwise.putAll(outputs);
                } else {
                    rules.add(Map.of("id", id, "conditions", conditions, "outputs", outputs));
                }
                conditionColumns = addMissingColumns(conditionColumns, conditions.keySet());
                outputColumns = addMissingColumns(outputColumns, outputs.keySet());
            }
        }
        if (node.config().get("otherwise") instanceof Map<?, ?> configured) {
            otherwise.putAll(displayMap(configured, false));
            outputColumns = addMissingColumns(outputColumns, otherwise.keySet());
        }
        LinkedHashMap<String, Object> matrix = new LinkedHashMap<>();
        matrix.put("nodeId", node.id());
        matrix.put("label", node.label().isBlank() ? node.id() : node.label());
        matrix.put("hitPolicy", Objects.toString(node.config().get("hitPolicy"), "first"));
        matrix.put("conditionColumns", conditionColumns);
        matrix.put("outputColumns", outputColumns);
        matrix.put("rules", List.copyOf(rules));
        matrix.put("otherwise", java.util.Collections.unmodifiableMap(new LinkedHashMap<>(otherwise)));
        return java.util.Collections.unmodifiableMap(matrix);
    }

    private List<Map<String, String>> columns(Object raw) {
        if (!(raw instanceof List<?> values)) return List.of();
        List<Map<String, String>> columns = new ArrayList<>();
        for (Object value : values) {
            String id;
            String label;
            if (value instanceof Map<?, ?> map) {
                id = firstText(map, "id", "key", "name");
                label = firstText(map, "label", "title", "displayName");
            } else {
                id = Objects.toString(value, "").trim();
                label = id;
            }
            if (!id.isBlank()) columns.add(Map.of("id", id, "label", label.isBlank() ? id : label));
        }
        return List.copyOf(columns);
    }

    private static List<Map<String, String>> addMissingColumns(List<Map<String, String>> existing,
                                                                Set<String> discovered) {
        List<Map<String, String>> result = new ArrayList<>(existing);
        Set<String> ids = new LinkedHashSet<>();
        existing.forEach(column -> ids.add(column.get("id")));
        discovered.stream().sorted().filter(ids::add)
                .forEach(id -> result.add(Map.of("id", id, "label", id)));
        return List.copyOf(result);
    }

    private Map<String, String> displayMap(Object raw, boolean predicates) {
        if (!(raw instanceof Map<?, ?> values)) return Map.of();
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        values.entrySet().stream().sorted(Comparator.comparing(entry -> Objects.toString(entry.getKey(), "")))
                .forEach(entry -> {
                    String key = Objects.toString(entry.getKey(), "");
                    String display = displayValue(entry.getValue());
                    if (predicates) {
                        display = display.replaceFirst("^\\s*" + java.util.regex.Pattern.quote(key) + "\\s*", "")
                                .replace("==", "=").replace("\"", "").trim();
                    }
                    result.put(key, display);
                });
        return java.util.Collections.unmodifiableMap(result);
    }

    private String displayValue(Object value) {
        JsonNode node = mapper.valueToTree(value);
        return node.isTextual() ? node.asText() : node.toString();
    }

    private static String firstText(Map<?, ?> values, String... keys) {
        for (String key : keys) {
            String value = Objects.toString(values.get(key), "").trim();
            if (!value.isBlank()) return value;
        }
        return "";
    }

    /** Summarizes topologically ordered nodes from operator effects using fixed business prose. */
    private static String flowSummary(GraphDraft draft) {
        if (draft.nodes().isEmpty()) return "接收输入事实 → 产出结果";
        List<String> stages = new ArrayList<>();
        boolean hasRead = draft.nodes().stream().anyMatch(node -> {
            var definition = draft.operatorSnapshots().get(node.id());
            return definition != null && "READ_EXTERNAL".equals(definition.capabilities().effect());
        });
        if (!hasRead) stages.add("接收输入事实");
        Set<String> sources = draft.edges().stream().map(edge -> edge.source().nodeId())
                .collect(java.util.stream.Collectors.toSet());
        for (GraphDraft.DraftNode node : topologicalNodes(draft)) {
            String label = node.label().isBlank() ? node.id() : node.label();
            var definition = draft.operatorSnapshots().get(node.id());
            String effect = definition == null ? "" : definition.capabilities().effect();
            if ("READ_EXTERNAL".equals(effect)) {
                stages.add("取『" + label + "』事实");
            } else if (isDecisionTable(node)) {
                stages.add("按『" + label + "』规则表判定");
            } else if (node.operatorRef().toLowerCase(java.util.Locale.ROOT).contains("transform")) {
                stages.add("汇总『" + label + "』");
            } else if (!sources.contains(node.id())) {
                stages.add("产出『" + label + "』");
            } else {
                stages.add("处理『" + label + "』");
            }
        }
        if (stages.stream().noneMatch(stage -> stage.startsWith("产出"))) stages.add("产出结果");
        return String.join(" → ", stages);
    }

    private static List<GraphDraft.DraftNode> topologicalNodes(GraphDraft draft) {
        Map<String, GraphDraft.DraftNode> byId = new LinkedHashMap<>();
        draft.nodes().stream().sorted(Comparator.comparing(GraphDraft.DraftNode::id))
                .forEach(node -> byId.put(node.id(), node));
        Map<String, Integer> indegree = new LinkedHashMap<>();
        Map<String, List<String>> outgoing = new LinkedHashMap<>();
        byId.keySet().forEach(id -> {
            indegree.put(id, 0);
            outgoing.put(id, new ArrayList<>());
        });
        for (GraphDraft.DraftEdge edge : draft.edges()) {
            String source = edge.source().nodeId();
            String target = edge.target().nodeId();
            if (byId.containsKey(source) && byId.containsKey(target)) {
                outgoing.get(source).add(target);
                indegree.put(target, indegree.get(target) + 1);
            }
        }
        java.util.PriorityQueue<String> ready = new java.util.PriorityQueue<>();
        indegree.forEach((id, degree) -> {
            if (degree == 0) ready.add(id);
        });
        List<GraphDraft.DraftNode> ordered = new ArrayList<>();
        while (!ready.isEmpty()) {
            String id = ready.remove();
            ordered.add(byId.get(id));
            outgoing.get(id).stream().sorted().forEach(target -> {
                int degree = indegree.compute(target, (ignored, value) -> value - 1);
                if (degree == 0) ready.add(target);
            });
        }
        if (ordered.size() < byId.size()) {
            Set<String> emitted = ordered.stream().map(GraphDraft.DraftNode::id)
                    .collect(java.util.stream.Collectors.toSet());
            byId.values().stream().filter(node -> !emitted.contains(node.id())).forEach(ordered::add);
        }
        return List.copyOf(ordered);
    }

    /** Computes bounded, deterministic golden coverage over enumerated decision-table facts. */
    private Map<String, Object> factCoverage(GraphDraft draft, List<JsonNode> activeGoldenGiven) {
        AgentTddDecisionScenarioEnumerator enumerator = new AgentTddDecisionScenarioEnumerator(mapper);
        LinkedHashMap<String, LinkedHashSet<JsonNode>> merged = new LinkedHashMap<>();
        draft.nodes().stream().filter(AgentTddBoardService::isDecisionTable).forEach(node ->
                enumerator.factDomains(node).forEach((column, values) -> values.forEach(value ->
                        merged.computeIfAbsent(column, ignored -> new LinkedHashSet<>()).add(value))));
        List<String> columns = merged.keySet().stream().sorted().toList();
        LinkedHashMap<String, List<JsonNode>> domains = new LinkedHashMap<>();
        columns.forEach(column -> domains.put(column, List.copyOf(merged.get(column))));
        List<Map<String, Object>> dimensions = columns.stream().map(column -> Map.<String, Object>of(
                "column", column,
                "values", domains.get(column).stream().map(this::plainValue).toList())).toList();
        Set<List<JsonNode>> covered = new LinkedHashSet<>();
        for (JsonNode given : activeGoldenGiven) {
            List<JsonNode> combination = new ArrayList<>();
            boolean complete = true;
            for (String column : columns) {
                JsonNode value = given.path(column);
                if (value.isMissingNode() || !domains.get(column).contains(value)) {
                    complete = false;
                    break;
                }
                combination.add(value.deepCopy());
            }
            if (complete && !columns.isEmpty()) covered.add(List.copyOf(combination));
        }
        long total = columns.isEmpty() ? 0L : 1L;
        for (String column : columns) {
            try {
                total = Math.multiplyExact(total, domains.get(column).size());
            } catch (ArithmeticException overflow) {
                total = Long.MAX_VALUE;
            }
        }
        List<Map<String, Object>> blindSpots = new ArrayList<>();
        appendBlindSpots(columns, domains, covered, 0, new ArrayList<>(), blindSpots);
        return Map.of("dimensions", dimensions, "coveredCount", (long) covered.size(),
                "totalCount", total, "blindSpots", List.copyOf(blindSpots));
    }

    private void appendBlindSpots(List<String> columns,
                                  Map<String, List<JsonNode>> domains,
                                  Set<List<JsonNode>> covered,
                                  int index,
                                  List<JsonNode> values,
                                  List<Map<String, Object>> result) {
        if (result.size() >= 20) return;
        if (index == columns.size()) {
            if (!values.isEmpty() && !covered.contains(values)) {
                LinkedHashMap<String, Object> combination = new LinkedHashMap<>();
                for (int item = 0; item < columns.size(); item++) {
                    combination.put(columns.get(item), plainValue(values.get(item)));
                }
                result.add(java.util.Collections.unmodifiableMap(combination));
            }
            return;
        }
        for (JsonNode value : domains.get(columns.get(index))) {
            values.add(value);
            appendBlindSpots(columns, domains, covered, index + 1, values, result);
            values.removeLast();
            if (result.size() >= 20) return;
        }
    }

    private Object plainValue(JsonNode value) {
        return mapper.convertValue(value, Object.class);
    }

    private static boolean isDecisionTable(GraphDraft.DraftNode node) {
        return Set.of("bloge:decisionTable", "decision_table").contains(node.operatorRef());
    }

    /**
     * Derives the business journey position from facts already present on one Tool card.
     *
     * <p>The projection intentionally performs no repository reads. A later gate therefore cannot
     * make the journey claim stronger than the readiness and case-set facts returned in the same
     * board response.</p>
     */
    private static Map<String, Object> journey(Map<String, Object> card) {
        boolean speccing = "SPECCING".equals(card.get("state"));
        Map<?, ?> coverage = card.get("caseCoverage") instanceof Map<?, ?> value ? value : Map.of();
        long active = number(coverage, "active");
        long pending = number(coverage, "pendingApproval");
        boolean green = gate(card, "greenBaseline");
        boolean publishable = Boolean.TRUE.equals(card.get("publishable"));
        String stage;
        String nextAction;
        if (publishable) {
            stage = "PUBLISH";
            nextAction = "SIGNOFF_OR_PUBLISH";
        } else if (green) {
            stage = "PUBLISH";
            nextAction = "AWAIT_ATTEST_OR_SIGNOFF";
        } else if (active > 0 || pending > 0) {
            stage = "GOLDEN";
            nextAction = pending > 0 ? "APPROVE_GOLDEN" : "RUN_RED_GREEN";
        } else if (!speccing) {
            stage = "ORCHESTRATION";
            nextAction = "ADD_GOLDEN";
        } else {
            stage = "RESOURCES";
            nextAction = "BIND_OR_FIXTURE";
        }
        int stageIndex = List.of("CONTRACT", "RESOURCES", "ORCHESTRATION", "GOLDEN", "PUBLISH")
                .indexOf(stage);
        return Map.of(
                "stage", stage,
                "stageIndex", stageIndex,
                "nextAction", nextAction,
                "blocking", card.getOrDefault("remainingLimitations", List.of()));
    }

    private static long number(Map<?, ?> values, String key) {
        return values.get(key) instanceof Number number ? number.longValue() : 0L;
    }

    private static List<Map<String, Object>> schemaFields(SchemaEnvelope envelope) {
        if (envelope == null) return List.of();
        Set<String> required = Set.copyOf(envelope.required());
        return envelope.properties().entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(entry -> Map.<String, Object>of(
                        "name", entry.getKey(),
                        "type", schemaType(entry.getValue()),
                        "required", required.contains(entry.getKey())))
                .toList();
    }

    private static String schemaType(Object schema) {
        if (schema instanceof Map<?, ?> values) {
            return Objects.toString(values.get("type"), "unknown");
        }
        return "unknown";
    }

    private static String assetKind(GraphDraft draft) {
        Object agentTdd = draft.visualLayout().get("agentTdd");
        if (agentTdd instanceof Map<?, ?> values) {
            return Objects.toString(values.get("assetKind"), "").toUpperCase(java.util.Locale.ROOT);
        }
        return "";
    }
}
