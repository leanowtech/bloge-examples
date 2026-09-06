package com.leanowtech.bloge.gateway.solution.journey;

import com.fasterxml.jackson.databind.JsonNode;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddMutationService;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddStateRepository;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddStoredAsset;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddToolException;
import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static com.leanowtech.bloge.gateway.solution.journey.BusinessGoldenReviewAuditRepository.BusinessGoldenReviewAccess;

/**
 * Human-only read projection for protected business GOLDEN assets.
 *
 * <p>The list path reads case-set metadata only. The material path enforces the dedicated purpose,
 * human actor, owner/reviewer role, receipt classification and current business contracts before
 * decrypting one exact receipt. Both accepted and denied requests require an independent,
 * payload-free audit append; an unavailable audit fails the request closed.</p>
 */
@Service
public final class BusinessGoldenReviewService {
    /** Stable group claim granting cross-owner GOLDEN review inside the authenticated scope. */
    public static final String REVIEWER_GROUP = "solution-golden-reviewers";
    private final AgentTddStateRepository states;
    private final BusinessGoldenMaterialStore materials;
    private final BusinessGoldenReviewAuditRepository audits;

    /** Creates the review boundary over canonical metadata, protected material and human audit. */
    public BusinessGoldenReviewService(AgentTddStateRepository states,
                                       BusinessGoldenMaterialStore materials,
                                       BusinessGoldenReviewAuditRepository audits) {
        this.states = Objects.requireNonNull(states, "states");
        this.materials = Objects.requireNonNull(materials, "materials");
        this.audits = Objects.requireNonNull(audits, "audits");
    }

    /** Returns only metadata rows the human owns or may review; protected receipts never cross. */
    public Map<String, Object> list(String solutionRef,
                                    String journeyRef,
                                    IntegrationRequestContext identity) {
        requireComplete(identity);
        AgentTddStoredAsset caseSet = null;
        try {
            requireHumanPurpose(identity);
            caseSet = caseSet(solutionRef, journeyRef, identity);
            boolean reviewer = isReviewer(identity);
            List<Map<String, Object>> rows = new ArrayList<>();
            for (JsonNode row : caseSet.data().path("rows")) {
                if (!"GOLDEN".equals(row.path("category").asText())) continue;
                if (!reviewer && !identity.actorId().equals(row.path("oracleOwner").asText())) continue;
                Map<String, Object> summary = new LinkedHashMap<>();
                summary.put("caseId", row.path("caseId").asText());
                summary.put("lifecycle", row.path("lifecycle").asText());
                summary.put("qualityState", row.path("qualityState").asText());
                summary.put("factCount", row.path("factCount").asInt());
                summary.put("assumptionCount", row.path("assumptionCount").asInt());
                summary.put("goldenCaseFingerprint", row.path("goldenCaseFingerprint").asText());
                summary.put("materialViewable", canReadReceipt(row, identity));
                rows.add(Map.copyOf(summary));
            }
            if (rows.isEmpty()) throw forbidden("GOLDEN_REVIEW_ROLE_FORBIDDEN",
                    "The human is neither the GOLDEN owner nor an authorized reviewer.");
            boolean active = rows.stream().anyMatch(row -> "ACTIVE".equals(row.get("lifecycle")));
            Map<String, Object> result = Map.of(
                    "solutionRef", solutionRef.trim(), "journeyRef", journeyRef.trim(),
                    "caseSetRef", caseSet.assetRef(), "revision", caseSet.revision(),
                    "approvalState", active ? "APPROVED" : "PENDING", "cases", List.copyOf(rows));
            audit(identity, caseSet.assetRef(), "*", "GOLDEN_SET_LIST", "ACCEPTED");
            return result;
        } catch (AgentTddToolException failure) {
            auditDeniedUnlessAuditFailure(identity, auditRef(caseSet), "*", "GOLDEN_SET_LIST", failure);
            throw failure;
        }
    }

    /** Decrypts and returns the business-language fields of one exact authorized case. */
    public Map<String, Object> readMaterial(String solutionRef,
                                            String journeyRef,
                                            String caseId,
                                            IntegrationRequestContext identity) {
        requireComplete(identity);
        AgentTddStoredAsset caseSet = null;
        String normalizedCase = "*";
        try {
            requireHumanPurpose(identity);
            normalizedCase = required(caseId, "caseId");
            caseSet = caseSet(solutionRef, journeyRef, identity);
            JsonNode row = row(caseSet, normalizedCase);
            requireOwnerOrReviewer(row, identity);
            requireClearance(row, identity);
            BusinessGoldenContractGuard.requireCurrent(
                    states, AgentTddMutationService.scopeKey(identity), row);
            JsonNode payload = materials.read(row.path("materialReceipt"), identity);
            verifyMaterial(row, payload);
            Map<String, Object> result = Map.of(
                    "caseId", payload.path("caseId").asText(),
                    "businessIntent", payload.path("businessIntent").deepCopy(),
                    "givenFacts", payload.path("givenFacts").deepCopy(),
                    "dependencyAssumptions", payload.path("dependencyAssumptions").deepCopy(),
                    "expectedOutcome", payload.path("expectedOutcome").deepCopy(),
                    "oracleOwner", payload.path("oracleOwner").asText());
            audit(identity, caseSet.assetRef(), normalizedCase,
                    "GOLDEN_MATERIAL_REVIEW", "ACCEPTED");
            return result;
        } catch (AgentTddToolException failure) {
            auditDeniedUnlessAuditFailure(identity, auditRef(caseSet), normalizedCase,
                    "GOLDEN_MATERIAL_REVIEW", failure);
            throw failure;
        }
    }

    private static String auditRef(AgentTddStoredAsset caseSet) {
        return caseSet == null ? "unresolved" : caseSet.assetRef();
    }

    private AgentTddStoredAsset caseSet(String solutionRef,
                                        String journeyRef,
                                        IntegrationRequestContext identity) {
        String solution = required(solutionRef, "solutionRef");
        String journey = required(journeyRef, "journeyRef");
        return states.list(AgentTddMutationService.scopeKey(identity), AgentTddMutationService.CASE_SET)
                .stream()
                .filter(asset -> solution.equals(asset.data().path("toolRef").asText()))
                .filter(asset -> journey.equals(asset.data().path("journeyRef").asText()))
                .findFirst()
                .orElseThrow(() -> new AgentTddToolException(
                        "DRAFT_NOT_FOUND", "Business GOLDEN set was not found."));
    }

    private static JsonNode row(AgentTddStoredAsset caseSet, String caseId) {
        return caseSet.data().path("rows").valueStream()
                .filter(value -> caseId.equals(value.path("caseId").asText()))
                .filter(value -> "GOLDEN".equals(value.path("category").asText()))
                .findFirst()
                .orElseThrow(() -> new AgentTddToolException(
                        "DRAFT_NOT_FOUND", "Business GOLDEN case was not found."));
    }

    private static void requireComplete(IntegrationRequestContext identity) {
        if (identity == null) throw forbidden(
                "GOLDEN_REVIEW_AUTH_REQUIRED", "Authenticated human review identity is required.");
        identity.requireComplete();
    }

    private static void requireHumanPurpose(IntegrationRequestContext identity) {
        if (!"HUMAN".equals(identity.actorType())) throw forbidden(
                "GOLDEN_REVIEW_HUMAN_REQUIRED", "A separately authenticated human is required.");
        if (!IntegrationOperation.SOLUTION_GOLDEN_REVIEW.accepts(identity.purpose())) throw forbidden(
                "GOLDEN_REVIEW_PURPOSE_FORBIDDEN", "The dedicated GOLDEN review purpose is required.");
    }

    private static void requireOwnerOrReviewer(JsonNode row, IntegrationRequestContext identity) {
        if (!identity.actorId().equals(row.path("oracleOwner").asText()) && !isReviewer(identity)) {
            throw forbidden("GOLDEN_REVIEW_ROLE_FORBIDDEN",
                    "The human is neither the GOLDEN owner nor an authorized reviewer.");
        }
    }

    private static boolean isReviewer(IntegrationRequestContext identity) {
        return identity.groups().stream().anyMatch(REVIEWER_GROUP::equalsIgnoreCase);
    }

    private static boolean canReadReceipt(JsonNode row, IntegrationRequestContext identity) {
        JsonNode receipt = row.path("materialReceipt");
        return receipt.isObject() && identity.hasClearanceAtLeast(receipt.path("classification").asText());
    }

    private static void requireClearance(JsonNode row, IntegrationRequestContext identity) {
        if (!canReadReceipt(row, identity)) throw forbidden(
                "GOLDEN_REVIEW_CLEARANCE_FORBIDDEN",
                "The human clearance is insufficient for this protected GOLDEN material.");
    }

    private static void verifyMaterial(JsonNode row, JsonNode payload) {
        boolean complete = payload != null
                && row.path("caseId").asText().equals(payload.path("caseId").asText())
                && row.path("oracleOwner").asText().equals(payload.path("oracleOwner").asText())
                && row.path("goldenCaseFingerprint").asText().equals(
                        payload.path("goldenCaseFingerprint").asText())
                && payload.path("businessIntent").isTextual()
                && payload.path("givenFacts").isArray()
                && payload.path("dependencyAssumptions").isArray()
                && payload.path("expectedOutcome").isObject();
        if (!complete) throw new AgentTddToolException(
                "FIXTURE_MATERIAL_UNAVAILABLE",
                "Protected business case material does not match its case metadata.");
    }

    private void auditDeniedUnlessAuditFailure(IntegrationRequestContext identity,
                                               String caseSetRef,
                                               String caseId,
                                               String action,
                                               AgentTddToolException failure) {
        if ("GOLDEN_REVIEW_AUDIT_UNAVAILABLE".equals(failure.code())) return;
        audit(identity, caseSetRef, caseId, action, deniedOutcome(failure.code()));
    }

    private void audit(IntegrationRequestContext identity,
                       String caseSetRef,
                       String caseId,
                       String action,
                       String outcome) {
        try {
            audits.append(new BusinessGoldenReviewAccess(
                    UUID.randomUUID().toString(), identity, caseSetRef, caseId,
                    action, outcome, null));
        } catch (RuntimeException unavailable) {
            AgentTddToolException failure = new AgentTddToolException(
                    "GOLDEN_REVIEW_AUDIT_UNAVAILABLE",
                    "GOLDEN review access is unavailable because its audit could not commit.",
                    Map.of(), true);
            failure.initCause(unavailable);
            throw failure;
        }
    }

    private static String deniedOutcome(String code) {
        String normalized = code == null ? "DENIED" : code.toUpperCase(Locale.ROOT);
        return normalized.length() > 64 ? normalized.substring(0, 64) : normalized;
    }

    private static AgentTddToolException forbidden(String code, String message) {
        return new AgentTddToolException(code, message);
    }

    private static String required(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) throw new AgentTddToolException(
                "SCHEMA_NONCONFORMANT", field + " is required.");
        return normalized;
    }
}
