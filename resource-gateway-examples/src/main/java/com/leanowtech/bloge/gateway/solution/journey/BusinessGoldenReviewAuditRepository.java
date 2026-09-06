package com.leanowtech.bloge.gateway.solution.journey;

import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;

import java.time.Instant;

/** Append-only human attribution boundary for payload-free business GOLDEN review access. */
public interface BusinessGoldenReviewAuditRepository {
    /**
     * Appends one allowed or denied access fact.
     *
     * @param event complete enterprise, actor, purpose and requested asset coordinate
     * @return persisted event with its occurrence time
     */
    BusinessGoldenReviewAccess append(BusinessGoldenReviewAccess event);

    /** Human review access coordinate. Business values and protected receipts are forbidden. */
    record BusinessGoldenReviewAccess(
            String accessId,
            String tenantId,
            String organizationId,
            String projectId,
            String environmentId,
            String region,
            String caseSetRef,
            String caseId,
            String actorId,
            String purpose,
            String action,
            String outcome,
            String correlationId,
            Instant occurredAt
    ) {
        /** Creates a new event from an already authenticated request identity. */
        public BusinessGoldenReviewAccess(String accessId,
                                          IntegrationRequestContext identity,
                                          String caseSetRef,
                                          String caseId,
                                          String action,
                                          String outcome,
                                          Instant occurredAt) {
            this(accessId, identity.tenantId(), identity.organizationId(), identity.projectId(),
                    identity.environmentId(), identity.region(), caseSetRef, caseId,
                    identity.actorId(), identity.purpose(), action, outcome,
                    identity.correlationId(), occurredAt);
        }

        /** Requires payload-free coordinates for every append. */
        public BusinessGoldenReviewAccess {
            accessId = required(accessId, "accessId");
            tenantId = required(tenantId, "tenantId");
            organizationId = required(organizationId, "organizationId");
            projectId = required(projectId, "projectId");
            environmentId = required(environmentId, "environmentId");
            region = required(region, "region");
            caseSetRef = required(caseSetRef, "caseSetRef");
            caseId = required(caseId, "caseId");
            actorId = required(actorId, "actorId");
            purpose = required(purpose, "purpose");
            action = required(action, "action");
            outcome = required(outcome, "outcome");
            correlationId = required(correlationId, "correlationId");
        }

        private static String required(String value, String field) {
            String normalized = value == null ? "" : value.trim();
            if (normalized.isBlank()) throw new IllegalArgumentException(field + " is required");
            return normalized;
        }
    }
}
