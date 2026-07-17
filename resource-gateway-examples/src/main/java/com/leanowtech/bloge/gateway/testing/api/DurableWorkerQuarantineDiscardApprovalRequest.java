package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

/**
 * Checker intent to approve one exact live quarantine claim for discard.
 *
 * <p>{@code claimOwner}, version, and expiry are observations from the payload-free queue, never
 * authority selected by the caller. The database rechecks all three under the checkpoint lock.
 * The claim token is intentionally absent so the checker cannot perform the maker's mutation.</p>
 *
 * @param schemaVersion request protocol version
 * @param clientRequestId caller-stable approval idempotency key
 * @param key exact quarantine identity
 * @param claimOwner observed maker identity
 * @param claimVersion observed maintenance generation
 * @param claimUntil observed database-clock claim deadline
 * @param reasonCode non-payload rationale later required by discard
 * @param approvalDurationSeconds requested lifetime from 1 through 900 seconds
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record DurableWorkerQuarantineDiscardApprovalRequest(
        String schemaVersion,
        String clientRequestId,
        DurableWorkerQuarantineKey key,
        String claimOwner,
        long claimVersion,
        Instant claimUntil,
        String reasonCode,
        long approvalDurationSeconds) {
    /** Current checker approval request protocol version. */
    public static final String SCHEMA_VERSION =
            "bloge.durableWorkerQuarantineDiscardApprovalRequest.v1";

    /** Applies the current version when omitted and normalizes textual fields. */
    public DurableWorkerQuarantineDiscardApprovalRequest {
        schemaVersion = normalized(schemaVersion).isBlank()
                ? SCHEMA_VERSION : normalized(schemaVersion);
        clientRequestId = normalized(clientRequestId);
        claimOwner = normalized(claimOwner);
        reasonCode = normalized(reasonCode);
    }

    /** Rejects caller-selected scope, token, approver, audit, or future command fields. */
    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException(
                "Unknown worker quarantine discard approval field: " + field);
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
