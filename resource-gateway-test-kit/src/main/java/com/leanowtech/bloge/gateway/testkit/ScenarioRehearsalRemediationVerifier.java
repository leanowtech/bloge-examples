package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

/**
 * Dependency-light offline verifier for one reviewed Scenario remediation lineage.
 *
 * <p>The verifier applies the packaged strict Schema, independently re-derives the frozen plan,
 * successor request, every approval fact, optional receipt, and complete lineage fingerprints,
 * and checks role order, actor separation, scope, ticket, state, and predecessor/successor
 * closure. It consumes only payload-free governance facts and does not require a Resource Gateway
 * server or database connection.</p>
 */
public final class ScenarioRehearsalRemediationVerifier {
    /** Maximum canonical frozen-plan bytes accepted by the producer protocol. */
    public static final int MAXIMUM_PLAN_BYTES = 4 * 1024 * 1024;
    /** Maximum canonical approval or receipt bytes accepted by the producer protocol. */
    public static final int MAXIMUM_FACT_BYTES = 128 * 1024;
    /** Maximum canonical public-lineage bytes accepted by the producer protocol. */
    public static final int MAXIMUM_LINEAGE_BYTES = 8 * 1024 * 1024;

    /** Creates a verifier with the fixed first-generation role and closure policy. */
    public ScenarioRehearsalRemediationVerifier() {
    }

    /** Bounded offline verification outcome. */
    public enum Outcome {
        /** Schema, content addresses, approval chain, and state closure passed. */
        VERIFIED,
        /** Structure, content address, identity, role, or state closure is invalid. */
        INVALID
    }

    /**
     * Payload-free result suitable for CI, Owner tooling, and governance ingestion.
     *
     * @param outcome bounded verification outcome
     * @param reasonCode stable machine-readable result
     * @param remediationId reviewed lineage identity, or blank when unavailable
     * @param state derived lifecycle state, or blank when unavailable
     * @param predecessorJobId exact predecessor batch, or blank when unavailable
     * @param successorJobId admitted successor batch, or blank before submission
     * @param lineageFingerprint independently verified lineage address, or blank on failure
     */
    public record VerificationResult(
            Outcome outcome,
            String reasonCode,
            String remediationId,
            String state,
            String predecessorJobId,
            String successorJobId,
            String lineageFingerprint
    ) {
        /** Keeps verification output bounded and log-safe. */
        public VerificationResult {
            outcome = java.util.Objects.requireNonNull(
                    outcome, "outcome");
            reasonCode = normalized(reasonCode);
            remediationId = normalized(remediationId);
            state = normalized(state);
            predecessorJobId = normalized(predecessorJobId);
            successorJobId = normalized(successorJobId);
            lineageFingerprint = normalized(
                    lineageFingerprint);
            if (!reasonCode.matches(
                    "[A-Z][A-Z0-9_.-]{0,254}")) {
                throw new IllegalArgumentException(
                        "Scenario remediation verification result is invalid");
            }
        }

        /**
         * Reports whether every retained fact and derived projection was verified.
         *
         * @return true only for a fully verified lineage
         */
        public boolean verified() {
            return outcome == Outcome.VERIFIED;
        }
    }

    /**
     * Independently verifies one decoded v1 remediation lineage.
     *
     * @param lineage decoded payload from the reviewed-remediation read API
     * @return bounded payload-free verification result
     */
    public VerificationResult verify(JsonNode lineage) {
        Coordinates coordinates =
                Coordinates.from(lineage);
        try {
            CapabilityMirrorSchemaValidator.require(
                    lineage,
                    CapabilityMirrorProtocol
                            .SCENARIO_REHEARSAL_REMEDIATION_LINEAGE_SCHEMA_RESOURCE,
                    "RG.MIRROR.CLIENT.SCENARIO_REMEDIATION_LINEAGE_SCHEMA_INVALID");
        } catch (RuntimeException invalid) {
            return invalid(
                    "SCENARIO_REMEDIATION_LINEAGE_SCHEMA_INVALID",
                    coordinates);
        }
        try {
            JsonNode plan = lineage.path("plan");
            verifyPlan(plan);
            JsonNode approvals = lineage.path("approvals");
            String approvalHead =
                    verifyApprovals(plan, approvals);
            verifyState(
                    lineage, plan, approvals, approvalHead);
            String expected =
                    fingerprintWithout(
                            lineage,
                            "lineageFingerprint",
                            MAXIMUM_LINEAGE_BYTES);
            if (!expected.equals(
                    lineage.path("lineageFingerprint")
                            .asText())) {
                fail("SCENARIO_REMEDIATION_LINEAGE_FINGERPRINT_INVALID");
            }
            return new VerificationResult(
                    Outcome.VERIFIED,
                    "VERIFIED",
                    coordinates.remediationId(),
                    coordinates.state(),
                    coordinates.predecessorJobId(),
                    coordinates.successorJobId(),
                    expected);
        } catch (VerificationFailure failure) {
            return invalid(
                    failure.reasonCode, coordinates);
        } catch (RuntimeException invalid) {
            return invalid(
                    "SCENARIO_REMEDIATION_LINEAGE_MATERIAL_INVALID",
                    coordinates);
        }
    }

    private static void verifyPlan(JsonNode plan) {
        if (!fingerprintWithout(
                plan,
                "planFingerprint",
                MAXIMUM_PLAN_BYTES).equals(
                plan.path("planFingerprint").asText())) {
            fail("SCENARIO_REMEDIATION_PLAN_FINGERPRINT_INVALID");
        }
        if (!EvidenceVerificationSupport.sha256Bounded(
                plan.path("successorRequest"),
                MAXIMUM_PLAN_BYTES).equals(
                plan.path("successorRequestFingerprint")
                        .asText())
                || !plan.path("remediationId").asText()
                .equals(
                        plan.path("successorRequest")
                                .path("requestId").asText())) {
            fail("SCENARIO_REMEDIATION_SUCCESSOR_REQUEST_INVALID");
        }
    }

    private static String verifyApprovals(
            JsonNode plan,
            JsonNode approvals) {
        String previous = "";
        Instant previousDecision =
                Instant.parse(
                        plan.path("generatedAt").asText());
        Instant expiresAt =
                Instant.parse(
                        plan.path("expiresAt").asText());
        Set<String> controlPrincipals =
                new HashSet<>();
        for (int index = 0;
             index < approvals.size();
             index++) {
            JsonNode approval = approvals.get(index);
            if (!fingerprintWithout(
                    approval,
                    "approvalFingerprint",
                    MAXIMUM_FACT_BYTES).equals(
                    approval.path("approvalFingerprint")
                            .asText())
                    || approval.path("generation")
                    .asInt(-1) != index + 1
                    || !previous.equals(
                    approval.path(
                            "previousApprovalFingerprint")
                            .asText())
                    || !same(
                    plan,
                    approval,
                    "remediationId")
                    || !plan.path("planFingerprint")
                    .asText().equals(
                    approval.path(
                            "remediationPlanFingerprint")
                            .asText())
                    || !plan.path("scope").equals(
                    approval.path("scope"))
                    || !plan.path("governanceTicketRef")
                    .equals(
                    approval.path(
                            "governanceTicketRef"))
                    || Instant.parse(
                    approval.path("decidedAt").asText())
                    .isBefore(previousDecision)
                    || Instant.parse(
                    approval.path("decidedAt").asText())
                    .isAfter(expiresAt)) {
                fail("SCENARIO_REMEDIATION_APPROVAL_CHAIN_INVALID");
            }
            String expectedRole =
                    index == 0
                            ? "OWNER"
                            : "INDEPENDENT_REVIEWER";
            if (!expectedRole.equals(
                    approval.path("role").asText())) {
                fail("SCENARIO_REMEDIATION_APPROVAL_ROLE_INVALID");
            }
            String actor =
                    approval.path("actorId").asText();
            String delegated =
                    approval.path("delegatedBy").asText();
            if (!controlPrincipals.add(actor)
                    || !delegated.isBlank()
                    && !controlPrincipals.add(delegated)) {
                fail("SCENARIO_REMEDIATION_ACTOR_SEPARATION_INVALID");
            }
            previous =
                    approval.path("approvalFingerprint")
                            .asText();
            previousDecision =
                    Instant.parse(
                            approval.path("decidedAt")
                                    .asText());
        }
        return previous;
    }

    private static void verifyState(
            JsonNode lineage,
            JsonNode plan,
            JsonNode approvals,
            String approvalHead) {
        int generation =
                lineage.path("approvalGeneration")
                        .asInt(-1);
        if (generation != approvals.size()
                || !approvalHead.equals(
                lineage.path("approvalHeadFingerprint")
                        .asText())) {
            fail("SCENARIO_REMEDIATION_APPROVAL_PROJECTION_INVALID");
        }
        boolean rejected = false;
        boolean approved = approvals.size() == 2;
        for (JsonNode approval : approvals) {
            boolean decisionApproved =
                    "APPROVE".equals(
                            approval.path("decision")
                                    .asText());
            rejected |= !decisionApproved;
            approved &= decisionApproved;
        }
        String state =
                lineage.path("state").asText();
        JsonNode receipt =
                lineage.path("receipt");
        if ("REJECTED".equals(state) != rejected
                || "APPROVED".equals(state)
                && (!approved || !receipt.isNull())
                || "PENDING_APPROVAL".equals(state)
                && (rejected || approved
                || !receipt.isNull())
                || "SUBMITTED".equals(state)
                != !receipt.isNull()) {
            fail("SCENARIO_REMEDIATION_STATE_INVALID");
        }
        if (!receipt.isNull()) {
            verifyReceipt(
                    lineage,
                    plan,
                    receipt,
                    approvalHead,
                    approvals.get(
                            approvals.size() - 1));
        }
    }

    private static void verifyReceipt(
            JsonNode lineage,
            JsonNode plan,
            JsonNode receipt,
            String approvalHead,
            JsonNode finalApproval) {
        if (!fingerprintWithout(
                receipt,
                "receiptFingerprint",
                MAXIMUM_FACT_BYTES).equals(
                receipt.path("receiptFingerprint")
                        .asText())
                || !same(
                plan,
                receipt,
                "remediationId")
                || !plan.path("planFingerprint")
                .asText().equals(
                receipt.path(
                        "remediationPlanFingerprint")
                        .asText())
                || !plan.path("scope").equals(
                receipt.path("scope"))
                || !same(
                plan,
                receipt,
                "predecessorJobId")
                || !plan.path(
                "successorRequestFingerprint")
                .asText().equals(
                receipt.path(
                        "successorRequestFingerprint")
                        .asText())
                || receipt.path("approvalGeneration")
                .asInt(-1)
                != lineage.path("approvalGeneration")
                .asInt(-2)
                || !approvalHead.equals(
                receipt.path("approvalHeadFingerprint")
                        .asText())
                || receipt.path("predecessorJobId")
                .asText().equals(
                receipt.path("successorJobId")
                        .asText())
                || Instant.parse(
                receipt.path("acceptedAt").asText())
                .isBefore(
                        Instant.parse(
                                finalApproval.path(
                                "decidedAt").asText()))
                || Instant.parse(
                receipt.path("acceptedAt").asText())
                .isAfter(
                        Instant.parse(
                                plan.path("expiresAt")
                                        .asText()))) {
            fail("SCENARIO_REMEDIATION_RECEIPT_INVALID");
        }
    }

    private static String fingerprintWithout(
            JsonNode value,
            String field,
            int maximumBytes) {
        ObjectNode material =
                ((ObjectNode) value.deepCopy());
        material.put(field, "");
        return EvidenceVerificationSupport.sha256Bounded(
                material, maximumBytes);
    }

    private static boolean same(
            JsonNode left,
            JsonNode right,
            String field) {
        return left.path(field).equals(
                right.path(field));
    }

    private static VerificationResult invalid(
            String reason,
            Coordinates coordinates) {
        return new VerificationResult(
                Outcome.INVALID,
                reason,
                coordinates.remediationId(),
                coordinates.state(),
                coordinates.predecessorJobId(),
                coordinates.successorJobId(),
                "");
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private static void fail(String reasonCode) {
        throw new VerificationFailure(reasonCode);
    }

    private record Coordinates(
            String remediationId,
            String state,
            String predecessorJobId,
            String successorJobId
    ) {
        private static Coordinates from(
                JsonNode lineage) {
            JsonNode exact = lineage == null
                    ? com.fasterxml.jackson.databind.node
                    .MissingNode.getInstance()
                    : lineage;
            return new Coordinates(
                    exact.path("plan")
                            .path("remediationId").asText(),
                    exact.path("state").asText(),
                    exact.path("plan")
                            .path("predecessorJobId")
                            .asText(),
                    exact.path("receipt")
                            .path("successorJobId")
                            .asText());
        }
    }

    private static final class VerificationFailure
            extends RuntimeException {
        private final String reasonCode;

        private VerificationFailure(String reasonCode) {
            super(null, null, false, false);
            this.reasonCode = reasonCode;
        }
    }
}
