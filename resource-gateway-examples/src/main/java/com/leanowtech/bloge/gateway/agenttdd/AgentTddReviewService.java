package com.leanowtech.bloge.gateway.agenttdd;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;

import java.util.Objects;

/**
 * Human review boundary that can make a pending business Oracle effective.
 *
 * <p>Agent-facing PROPOSE calls cannot invoke this service. A review surface must authenticate a
 * separate governed-write purpose and supply the exact revision it reviewed, preventing a stale
 * approval from silently accepting later Agent edits.</p>
 */
public final class AgentTddReviewService {
    private final AgentTddStateRepository states;

    /** Creates the review boundary over the durable Agent overlay repository. */
    public AgentTddReviewService(AgentTddStateRepository states) {
        this.states = Objects.requireNonNull(states, "states");
    }

    /**
     * Approves the pending Oracle for one GOLDEN row at an exact reviewed revision.
     *
     * @return stored case-set revision with the now-effective Oracle
     */
    public synchronized AgentTddStoredAsset approveOracle(String caseSetRef,
                                                          String caseId,
                                                          long expectedRevision,
                                                          IntegrationRequestContext identity) {
        String scope = AgentTddMutationService.scopeKey(identity);
        AgentTddStoredAsset current = states.find(scope, AgentTddMutationService.CASE_SET, caseSetRef)
                .orElseThrow(() -> new AgentTddToolException("DRAFT_NOT_FOUND", "Case set was not found."));
        if (current.revision() != expectedRevision) {
            throw new AgentTddToolException("GATE_REJECTED",
                    "Case set changed after the reviewer opened it.");
        }
        ObjectNode data = (ObjectNode) current.data().deepCopy();
        ObjectNode selected = null;
        for (JsonNode row : data.path("rows")) {
            if (caseId.equals(row.path("caseId").asText())) {
                selected = (ObjectNode) row;
                break;
            }
        }
        if (selected == null || !"GOLDEN".equals(selected.path("category").asText())) {
            throw new AgentTddToolException("DRAFT_NOT_FOUND", "Pending GOLDEN case was not found.");
        }
        JsonNode proposal = selected.path("proposedOracle");
        if (!"PENDING".equals(proposal.path("status").asText()) || !proposal.has("expect")) {
            throw new AgentTddToolException("GOLDEN_REQUIRES_APPROVAL",
                    "The GOLDEN case has no pending Oracle proposal.");
        }
        selected.set("expect", proposal.path("expect").deepCopy());
        selected.put("oracleOwner", proposal.path("oracleOwner").asText());
        selected.put("lifecycle", "ACTIVE");
        ((ObjectNode) proposal).put("status", "APPROVED");
        ((ObjectNode) proposal).put("approvedBy", identity.actorId());
        ObjectNode approved = selected;
        ArrayNode rows = data.putArray("rows");
        current.data().path("rows").forEach(row -> rows.add(
                caseId.equals(row.path("caseId").asText()) ? approved : row.deepCopy()));
        return states.save(scope, AgentTddMutationService.CASE_SET, caseSetRef, data);
    }
}
