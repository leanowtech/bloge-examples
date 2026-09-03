package com.leanowtech.bloge.gateway.agenttdd;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftRepository;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
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
                .filter(draft -> sameScope(draft, identity) && "TOOL".equals(assetKind(draft)))
                .sorted(Comparator.comparing(GraphDraft::draftId))
                .map(draft -> toolCard(draft, scope, identity))
                .toList();
        List<Map<String, Object>> reviews = new ArrayList<>();
        states.list(scope, AgentTddMutationService.CASE_SET).forEach(asset ->
                asset.data().path("rows").forEach(row -> {
                    if ("PENDING".equals(row.path("proposedOracle").path("status").asText())) {
                        reviews.add(Map.of("kind", "ORACLE", "assetRef", asset.assetRef(),
                                "caseId", row.path("caseId").asText(), "revision", asset.revision(),
                                "owner", row.path("proposedOracle").path("oracleOwner").asText()));
                    }
                }));
        states.list(scope, AgentTddWorkflowService.PUBLISH_SPEC).stream()
                .filter(asset -> "PENDING".equals(asset.data().path("status").asText()))
                .forEach(asset -> reviews.add(Map.of("kind", "PUBLISH_SPEC", "assetRef", asset.assetRef(),
                        "revision", asset.revision(), "owner", "tool-owner")));
        reviews.sort(Comparator.comparing(row -> row.get("kind") + ":" + row.get("assetRef")));
        return Map.of("tools", tools, "pendingReviews", reviews,
                "evidenceCount", states.list(scope, AgentTddWorkflowService.EVIDENCE).size(),
                "payloadPolicy", "STRUCTURE_ONLY");
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
        for (AgentTddStoredAsset asset : states.list(scope, AgentTddMutationService.CASE_SET)) {
            if (!draft.draftId().equals(asset.data().path("toolRef").asText())) continue;
            for (var row : asset.data().path("rows")) {
                if ("ACTIVE".equals(row.path("lifecycle").asText())) active++;
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
        return Map.copyOf(card);
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

    private static boolean sameScope(GraphDraft draft, IntegrationRequestContext identity) {
        return draft.tenantId().equals(identity.tenantId())
                && draft.environment().equals(identity.environmentId());
    }

    private static String assetKind(GraphDraft draft) {
        Object agentTdd = draft.visualLayout().get("agentTdd");
        if (agentTdd instanceof Map<?, ?> values) {
            return Objects.toString(values.get("assetKind"), "").toUpperCase(java.util.Locale.ROOT);
        }
        return "";
    }
}
