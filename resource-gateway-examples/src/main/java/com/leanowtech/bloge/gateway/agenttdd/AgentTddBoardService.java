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
        card.put("journey", journey(card));
        return Map.copyOf(card);
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
