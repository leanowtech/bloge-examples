package com.leanowtech.bloge.gateway.agenttdd;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.solution.journey.BusinessGoldenMaterialStore;
import com.leanowtech.bloge.gateway.solution.journey.BusinessGoldenContractGuard;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Map;

/**
 * Human review boundary that can make a pending business Oracle effective.
 *
 * <p>Agent-facing PROPOSE calls cannot invoke this service. A review surface must authenticate a
 * separate governed-write purpose and supply the exact revision it reviewed, preventing a stale
 * approval from silently accepting later Agent edits.</p>
 */
@Service
public final class AgentTddReviewService {
    private final AgentTddStateRepository states;
    private final BusinessGoldenMaterialStore goldenMaterials;

    /** Creates a focused legacy review boundary without protected business material access. */
    public AgentTddReviewService(AgentTddStateRepository states) {
        this(states, null);
    }

    /** Creates the Spring review boundary with protected business GOLDEN material access. */
    @Autowired
    public AgentTddReviewService(AgentTddStateRepository states,
                                 BusinessGoldenMaterialStore goldenMaterials) {
        this.states = Objects.requireNonNull(states, "states");
        this.goldenMaterials = goldenMaterials;
    }

    /**
     * Approves the pending Oracle for one GOLDEN row at an exact reviewed revision.
     *
     * @return stored case-set revision with the now-effective Oracle
     */
    public synchronized AgentTddStoredAsset approveOracle(String caseSetRef,
                                                          String caseId,
                                                          long expectedRevision,
                                                          String proposalFingerprint,
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
        JsonNode material = protectedMaterial(selected, identity);
        boolean completeBusinessCase = material != null && material.path("businessIntent").isTextual()
                && material.path("givenFacts").isArray()
                && material.path("dependencyAssumptions").isArray()
                && material.path("expectedOutcome").isObject()
                && material.path("oracleOwner").isTextual();
        JsonNode materialProposal = material == null ? proposal : material.path("proposedOracle");
        boolean reviewable = completeBusinessCase || materialProposal.has("expect");
        if (!"PENDING".equals(proposal.path("status").asText()) || !reviewable) {
            throw new AgentTddToolException("GOLDEN_REQUIRES_APPROVAL",
                    "The GOLDEN case has no pending Oracle proposal.");
        }
        requireIndependentHuman(identity, proposal.path("proposedBy").asText());
        requireProposalFingerprint(proposal, proposalFingerprint);
        if (!completeBusinessCase) selected.set("expect", materialProposal.path("expect").deepCopy());
        selected.put("oracleOwner", proposal.path("oracleOwner").asText());
        selected.put("lifecycle", "ACTIVE");
        ((ObjectNode) proposal).put("status", "APPROVED");
        ((ObjectNode) proposal).put("approvedBy", identity.actorId());
        ObjectNode approved = selected;
        ArrayNode rows = data.putArray("rows");
        current.data().path("rows").forEach(row -> rows.add(
                caseId.equals(row.path("caseId").asText()) ? approved : row.deepCopy()));
        return states.saveIfRevision(scope, AgentTddMutationService.CASE_SET, caseSetRef,
                expectedRevision, data);
    }

    /** Approves an exact pending specification proposal without turning it into executable code. */
    public synchronized AgentTddStoredAsset approvePublishSpec(String toolRef,
                                                               long expectedRevision,
                                                               String proposalFingerprint,
                                                               IntegrationRequestContext identity) {
        String scope = AgentTddMutationService.scopeKey(identity);
        AgentTddStoredAsset current = states.find(scope, AgentTddWorkflowService.PUBLISH_SPEC, toolRef)
                .orElseThrow(() -> new AgentTddToolException(
                        "DRAFT_NOT_FOUND", "Specification proposal was not found."));
        requireRevision(current, expectedRevision, "Specification proposal");
        ObjectNode data = (ObjectNode) current.data().deepCopy();
        if (!"PENDING".equals(data.path("status").asText())) {
            throw new AgentTddToolException("GATE_REJECTED", "Specification proposal is not pending.");
        }
        requireIndependentHuman(identity, data.path("proposedBy").asText());
        requireProposalFingerprint(data, proposalFingerprint);
        data.put("status", "APPROVED");
        data.put("approvedBy", identity.actorId());
        return states.saveIfRevision(scope, AgentTddWorkflowService.PUBLISH_SPEC, toolRef,
                expectedRevision, data);
    }

    /**
     * Records a separately authenticated human signoff for one exact reviewed baseline.
     *
     * @param toolRef exact Tool under review
     * @param signoffRef reviewer-owned approval reference
     * @param draftRevision exact canonical draft revision reviewed
     * @param goldenSetId exact contract-and-case identity reviewed
     * @param evidenceFingerprint exact GREEN evidence material reviewed
     * @param implementationFingerprint exact attested binding and resource implementation reviewed
     * @param identity governed human identity
     * @return immutable signoff overlay revision
     */
    public synchronized AgentTddStoredAsset approveToolSignoff(String toolRef,
                                                               String signoffRef,
                                                               long draftRevision,
                                                               String goldenSetId,
                                                               String evidenceFingerprint,
                                                               String implementationFingerprint,
                                                               IntegrationRequestContext identity) {
        requireHuman(identity);
        if (toolRef == null || toolRef.isBlank() || signoffRef == null || signoffRef.isBlank()
                || draftRevision < 1 || goldenSetId == null || goldenSetId.isBlank()
                || evidenceFingerprint == null || evidenceFingerprint.isBlank()
                || implementationFingerprint == null || implementationFingerprint.isBlank()) {
            throw new AgentTddToolException(
                    "SCHEMA_NONCONFORMANT",
                    "The complete GREEN and attestation identity is required for signoff.");
        }
        String scope = AgentTddMutationService.scopeKey(identity);
        JsonNode latest = states.find(scope, AgentTddWorkflowService.VERDICT, toolRef.trim())
                .map(AgentTddStoredAsset::data).map(data -> data.path("latest"))
                .orElseThrow(() -> new AgentTddToolException(
                        "GATE_REJECTED", "A current logical GREEN baseline is required."));
        JsonNode attestation = states.find(scope, AgentTddAttestationService.ATTESTATION, toolRef.trim())
                .map(AgentTddStoredAsset::data)
                .orElseThrow(() -> new AgentTddToolException(
                        "GATE_REJECTED", "A current sandbox attestation is required."));
        if (!"GREEN".equals(latest.path("side").asText())
                || !"GO".equals(latest.path("status").asText())
                || draftRevision != latest.path("draftRevision").asLong(-1)
                || !goldenSetId.trim().equals(latest.path("goldenSetId").asText())
                || !evidenceFingerprint.trim().equals(latest.path("evidenceFingerprint").asText())
                || !"ATTESTED".equals(attestation.path("status").asText())
                || draftRevision != attestation.path("draftRevision").asLong(-1)
                || !goldenSetId.trim().equals(attestation.path("goldenSetId").asText())
                || !evidenceFingerprint.trim().equals(attestation.path("evidenceFingerprint").asText())
                || !implementationFingerprint.trim().equals(
                        attestation.path("implementationFingerprint").asText())) {
            throw new AgentTddToolException(
                    "GATE_REJECTED", "The reviewed GREEN or attestation identity is no longer current.");
        }
        ObjectNode data = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        data.put("toolRef", toolRef.trim());
        data.put("signoffRef", signoffRef.trim());
        data.put("draftRevision", draftRevision);
        data.put("goldenSetId", goldenSetId.trim());
        data.put("evidenceFingerprint", evidenceFingerprint.trim());
        data.put("implementationFingerprint", implementationFingerprint.trim());
        data.put("status", "APPROVED");
        data.put("approvedBy", identity.actorId());
        return states.saveIfRevision(scope,
                AgentTddWorkflowService.SIGNOFF, signoffRef.trim(), 0, data);
    }

    private static void requireRevision(AgentTddStoredAsset current, long expectedRevision, String label) {
        if (current.revision() != expectedRevision) {
            throw new AgentTddToolException("GATE_REJECTED", label + " changed after the reviewer opened it.");
        }
    }

    /**
     * Returns the exact pending business case to a separately authenticated human reviewer.
     *
     * <p>This payload-bearing projection is deliberately not an MCP tool and is protected by the
     * governed-write identity boundary. New business cases are projected only through the business
     * vocabulary supplied by the proposer. Compiled aliases, entity references, stubs and execution
     * plans are never returned. The fingerprint must be echoed by approval so the decision is bound
     * to the complete case and current business contracts that the reviewer opened.</p>
     */
    public synchronized Map<String, Object> oracleReview(String caseSetRef,
                                                         String caseId,
                                                         long expectedRevision,
                                                         IntegrationRequestContext identity) {
        requireHuman(identity);
        AgentTddStoredAsset current = states.find(AgentTddMutationService.scopeKey(identity),
                        AgentTddMutationService.CASE_SET, caseSetRef)
                .orElseThrow(() -> new AgentTddToolException("DRAFT_NOT_FOUND", "Case set was not found."));
        requireRevision(current, expectedRevision, "Case set");
        for (JsonNode row : current.data().path("rows")) {
            if (!caseId.equals(row.path("caseId").asText())) continue;
            JsonNode proposal = row.path("proposedOracle");
            if (!"PENDING".equals(proposal.path("status").asText())) break;
            JsonNode material = protectedMaterial(row, identity);
            JsonNode reviewRow = material == null ? row : material;
            JsonNode reviewProposal = reviewRow.path("proposedOracle");
            if (reviewRow.path("businessIntent").isTextual()) {
                return Map.ofEntries(
                        Map.entry("caseSetRef", caseSetRef),
                        Map.entry("caseId", caseId),
                        Map.entry("revision", current.revision()),
                        Map.entry("businessIntent", reviewRow.path("businessIntent").deepCopy()),
                        Map.entry("givenFacts", reviewRow.path("givenFacts").deepCopy()),
                        Map.entry("dependencyAssumptions",
                                reviewRow.path("dependencyAssumptions").deepCopy()),
                        Map.entry("expectedOutcome", reviewRow.path("expectedOutcome").deepCopy()),
                        Map.entry("oracleOwner", proposal.path("oracleOwner").asText()),
                        Map.entry("proposedBy", proposal.path("proposedBy").asText()),
                        Map.entry("proposalFingerprint",
                                proposal.path("proposalFingerprint").asText()));
            }
            return Map.of(
                    "caseSetRef", caseSetRef,
                    "caseId", caseId,
                    "revision", current.revision(),
                    "intent", reviewRow.path("intent").deepCopy(),
                    "given", reviewRow.path("given").deepCopy(),
                    "stubs", reviewRow.path("controlledAssumptions").isObject()
                            ? reviewRow.path("controlledAssumptions").deepCopy()
                            : reviewRow.path("stubs").deepCopy(),
                    "expect", reviewProposal.path("expect").deepCopy(),
                    "oracleOwner", proposal.path("oracleOwner").asText(),
                    "proposedBy", proposal.path("proposedBy").asText(),
                    "proposalFingerprint", proposal.path("proposalFingerprint").asText());
        }
        throw new AgentTddToolException("DRAFT_NOT_FOUND", "Pending GOLDEN case was not found.");
    }

    private JsonNode protectedMaterial(JsonNode row, IntegrationRequestContext identity) {
        JsonNode receipt = row.path("materialReceipt");
        if (!receipt.isObject()) return null;
        BusinessGoldenContractGuard.requireCurrent(
                states, AgentTddMutationService.scopeKey(identity), row);
        if (goldenMaterials == null) throw new AgentTddToolException(
                "FIXTURE_MATERIAL_UNAVAILABLE", "Protected business case material is unavailable.");
        JsonNode material = goldenMaterials.read(receipt, identity);
        if (!row.path("goldenCaseFingerprint").asText().equals(
                material.path("goldenCaseFingerprint").asText())) {
            throw new AgentTddToolException("FIXTURE_MATERIAL_UNAVAILABLE",
                    "Protected business case material does not match its case metadata.");
        }
        return material;
    }

    /** Returns the exact pending publish specification that a human must inspect before approval. */
    public synchronized Map<String, Object> publishSpecReview(String toolRef,
                                                              long expectedRevision,
                                                              IntegrationRequestContext identity) {
        requireHuman(identity);
        AgentTddStoredAsset current = states.find(AgentTddMutationService.scopeKey(identity),
                        AgentTddWorkflowService.PUBLISH_SPEC, toolRef)
                .orElseThrow(() -> new AgentTddToolException(
                        "DRAFT_NOT_FOUND", "Specification proposal was not found."));
        requireRevision(current, expectedRevision, "Specification proposal");
        if (!"PENDING".equals(current.data().path("status").asText())) {
            throw new AgentTddToolException("GATE_REJECTED", "Specification proposal is not pending.");
        }
        return Map.of("toolRef", toolRef, "revision", current.revision(),
                "draftRevision", current.data().path("draftRevision").asLong(),
                "draft", current.data().path("draft").deepCopy(),
                "proposedBy", current.data().path("proposedBy").asText(),
                "proposalFingerprint", current.data().path("proposalFingerprint").asText());
    }

    /** Enforces the human side of the Agent-proposes, human-decides trust boundary. */
    private static void requireHuman(IntegrationRequestContext identity) {
        if (identity == null || !("USER".equals(identity.actorType()) || "HUMAN".equals(identity.actorType()))) {
            throw new AgentTddToolException("GATE_REJECTED",
                    "This governance decision requires a separately authenticated human identity.");
        }
    }

    /** Enforces both human review and maker-checker separation for a stored proposal. */
    private static void requireIndependentHuman(IntegrationRequestContext identity, String proposedBy) {
        requireHuman(identity);
        if (proposedBy == null || proposedBy.isBlank()) {
            throw new AgentTddToolException("GATE_REJECTED",
                    "The proposal has no authenticated proposer and cannot be approved.");
        }
        if (proposedBy.equals(identity.actorId())) {
            throw new AgentTddToolException("GATE_REJECTED",
                    "The proposal author cannot approve the same governance decision.");
        }
    }

    private static void requireProposalFingerprint(JsonNode proposal, String reviewedFingerprint) {
        String current = proposal.path("proposalFingerprint").asText();
        if (current.isBlank() || reviewedFingerprint == null || !current.equals(reviewedFingerprint.trim())) {
            throw new AgentTddToolException("GATE_REJECTED",
                    "The approval is not bound to the exact proposal opened by the reviewer.");
        }
    }
}
