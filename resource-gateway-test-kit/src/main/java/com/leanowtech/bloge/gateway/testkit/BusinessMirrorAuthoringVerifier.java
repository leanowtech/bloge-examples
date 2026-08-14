package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Registry-free semantic verifier for durable Business Mirror Package authoring responses.
 *
 * <p>The verifier applies the packaged strict Schema, re-derives every stored draft fingerprint,
 * and checks temporal, page-order, scope, and cursor invariants. Failures expose only stable reason
 * codes and never echo Package content.</p>
 */
public final class BusinessMirrorAuthoringVerifier {
    /** Maximum canonical Package draft size accepted by the server and this verifier. */
    public static final int MAXIMUM_DRAFT_BYTES = 8 * 1_048_576;

    private BusinessMirrorAuthoringVerifier() {
    }

    /**
     * Verifies one repository-owned stored Package revision.
     *
     * @param stored decoded stored Package response
     * @return payload-free verified Package identity
     * @throws IllegalArgumentException when Schema, fingerprint, time, or Scope semantics fail
     */
    public static VerifiedStoredPackage verifyStoredPackageDraft(JsonNode stored) {
        BusinessMirrorSchemaValidator.require(stored,
                BusinessMirrorProtocol.STORED_PACKAGE_DRAFT_SCHEMA_RESOURCE,
                "RG.BUSINESS_MIRROR.CLIENT.STORED_PACKAGE_DRAFT_INVALID");
        return verifyStoredSemantics(stored);
    }

    /**
     * Verifies one exact durable Package save receipt and its stored draft.
     *
     * @param receipt decoded save receipt
     * @return payload-free verified receipt identity
     * @throws IllegalArgumentException when Schema or nested semantic verification fails
     */
    public static VerifiedSaveReceipt verifyPackageSaveReceipt(JsonNode receipt) {
        BusinessMirrorSchemaValidator.require(receipt,
                BusinessMirrorProtocol.PACKAGE_SAVE_RECEIPT_SCHEMA_RESOURCE,
                "RG.BUSINESS_MIRROR.CLIENT.PACKAGE_SAVE_RECEIPT_INVALID");
        VerifiedStoredPackage stored = verifyStoredSemantics(receipt.path("result"));
        Instant completedAt = instant(receipt.path("completedAt").asText(),
                "RG.BUSINESS_MIRROR.CLIENT.PACKAGE_SAVE_RECEIPT_TIME_INVALID");
        if (completedAt.isBefore(stored.updatedAt())) {
            throw invalid("RG.BUSINESS_MIRROR.CLIENT.PACKAGE_SAVE_RECEIPT_TIME_INVALID");
        }
        return new VerifiedSaveReceipt(receipt.path("requestFingerprint").asText(),
                stored.packageId(), stored.revision(), stored.draftFingerprint(), completedAt);
    }

    /**
     * Verifies one bounded, single-Scope, keyset-ordered Package page.
     *
     * @param page decoded Package page
     * @return payload-free verified page identity
     * @throws IllegalArgumentException when Schema, nested draft, order, Scope, or cursor checks fail
     */
    public static VerifiedPackagePage verifyPackagePage(JsonNode page) {
        BusinessMirrorSchemaValidator.require(page,
                BusinessMirrorProtocol.PACKAGE_PAGE_SCHEMA_RESOURCE,
                "RG.BUSINESS_MIRROR.CLIENT.PACKAGE_PAGE_INVALID");
        List<VerifiedStoredPackage> items = new ArrayList<>();
        page.path("items").forEach(item -> items.add(verifyStoredSemantics(item)));
        Set<String> packageIds = new HashSet<>();
        String scopeFingerprint = "";
        String previous = "";
        for (VerifiedStoredPackage item : items) {
            if (!packageIds.add(item.packageId()) || !previous.isEmpty()
                    && previous.compareTo(item.packageId()) >= 0) {
                throw invalid("RG.BUSINESS_MIRROR.CLIENT.PACKAGE_PAGE_ORDER_INVALID");
            }
            if (scopeFingerprint.isEmpty()) {
                scopeFingerprint = item.scopeFingerprint();
            } else if (!scopeFingerprint.equals(item.scopeFingerprint())) {
                throw invalid("RG.BUSINESS_MIRROR.CLIENT.PACKAGE_PAGE_SCOPE_MISMATCH");
            }
            previous = item.packageId();
        }
        String nextCursor = page.path("nextCursor").asText();
        if (!nextCursor.isEmpty()
                && (items.isEmpty() || !nextCursor.equals(items.getLast().packageId()))) {
            throw invalid("RG.BUSINESS_MIRROR.CLIENT.PACKAGE_PAGE_CURSOR_INVALID");
        }
        return new VerifiedPackagePage(items.size(), nextCursor, scopeFingerprint);
    }

    /**
     * Verifies one repository-owned Capability Proposal revision.
     *
     * @param stored decoded stored Capability Proposal
     * @return payload-free verified Proposal identity
     * @throws IllegalArgumentException when Schema, fingerprint, time, or Scope checks fail
     */
    public static VerifiedStoredProposal verifyStoredProposalDraft(JsonNode stored) {
        BusinessMirrorSchemaValidator.require(stored,
                BusinessMirrorProtocol.STORED_PROPOSAL_DRAFT_SCHEMA_RESOURCE,
                "RG.BUSINESS_MIRROR.CLIENT.STORED_PROPOSAL_DRAFT_INVALID");
        return verifyStoredProposalSemantics(stored);
    }

    /**
     * Verifies one exact durable Capability Proposal save receipt.
     *
     * @param receipt decoded Proposal save receipt
     * @return payload-free verified receipt identity
     * @throws IllegalArgumentException when Schema or nested semantic verification fails
     */
    public static VerifiedProposalSaveReceipt verifyProposalSaveReceipt(JsonNode receipt) {
        BusinessMirrorSchemaValidator.require(receipt,
                BusinessMirrorProtocol.PROPOSAL_SAVE_RECEIPT_SCHEMA_RESOURCE,
                "RG.BUSINESS_MIRROR.CLIENT.PROPOSAL_SAVE_RECEIPT_INVALID");
        VerifiedStoredProposal stored = verifyStoredProposalSemantics(receipt.path("result"));
        Instant completedAt = instant(receipt.path("completedAt").asText(),
                "RG.BUSINESS_MIRROR.CLIENT.PROPOSAL_SAVE_RECEIPT_TIME_INVALID");
        if (completedAt.isBefore(stored.updatedAt())) {
            throw invalid("RG.BUSINESS_MIRROR.CLIENT.PROPOSAL_SAVE_RECEIPT_TIME_INVALID");
        }
        return new VerifiedProposalSaveReceipt(receipt.path("requestFingerprint").asText(),
                stored.proposalId(), stored.revision(), stored.draftFingerprint(), completedAt);
    }

    /**
     * Verifies one bounded, ordered, single-Scope Capability Proposal page.
     *
     * @param page decoded Proposal page
     * @return payload-free verified page identity
     * @throws IllegalArgumentException when Schema, order, Scope, or cursor checks fail
     */
    public static VerifiedProposalPage verifyProposalPage(JsonNode page) {
        BusinessMirrorSchemaValidator.require(page,
                BusinessMirrorProtocol.PROPOSAL_PAGE_SCHEMA_RESOURCE,
                "RG.BUSINESS_MIRROR.CLIENT.PROPOSAL_PAGE_INVALID");
        List<VerifiedStoredProposal> items = new ArrayList<>();
        page.path("items").forEach(item -> items.add(verifyStoredProposalSemantics(item)));
        Set<String> proposalIds = new HashSet<>();
        String scopeFingerprint = "";
        String previous = "";
        for (VerifiedStoredProposal item : items) {
            if (!proposalIds.add(item.proposalId()) || !previous.isEmpty()
                    && previous.compareTo(item.proposalId()) >= 0) {
                throw invalid("RG.BUSINESS_MIRROR.CLIENT.PROPOSAL_PAGE_ORDER_INVALID");
            }
            if (scopeFingerprint.isEmpty()) {
                scopeFingerprint = item.scopeFingerprint();
            } else if (!scopeFingerprint.equals(item.scopeFingerprint())) {
                throw invalid("RG.BUSINESS_MIRROR.CLIENT.PROPOSAL_PAGE_SCOPE_MISMATCH");
            }
            previous = item.proposalId();
        }
        String nextCursor = page.path("nextCursor").asText();
        if (!nextCursor.isEmpty()
                && (items.isEmpty() || !nextCursor.equals(items.getLast().proposalId()))) {
            throw invalid("RG.BUSINESS_MIRROR.CLIENT.PROPOSAL_PAGE_CURSOR_INVALID");
        }
        return new VerifiedProposalPage(items.size(), nextCursor, scopeFingerprint);
    }

    private static VerifiedStoredPackage verifyStoredSemantics(JsonNode stored) {
        JsonNode draft = stored.path("draft");
        String attached = stored.path("draftFingerprint").asText();
        String expected = canonicalFingerprint(draft);
        if (!expected.equals(attached)) {
            throw invalid("RG.BUSINESS_MIRROR.CLIENT.PACKAGE_DRAFT_FINGERPRINT_MISMATCH");
        }
        Instant createdAt = instant(stored.path("createdAt").asText(),
                "RG.BUSINESS_MIRROR.CLIENT.STORED_PACKAGE_DRAFT_TIME_INVALID");
        Instant updatedAt = instant(stored.path("updatedAt").asText(),
                "RG.BUSINESS_MIRROR.CLIENT.STORED_PACKAGE_DRAFT_TIME_INVALID");
        if (updatedAt.isBefore(createdAt)) {
            throw invalid("RG.BUSINESS_MIRROR.CLIENT.STORED_PACKAGE_DRAFT_TIME_INVALID");
        }
        JsonNode scope = draft.path("scope");
        if (!scope.path("tenantId").asText()
                .equals(draft.path("provenance").path("tenantId").asText())) {
            throw invalid("RG.BUSINESS_MIRROR.CLIENT.PACKAGE_DRAFT_SCOPE_MISMATCH");
        }
        return new VerifiedStoredPackage(draft.path("packageId").asText(),
                draft.path("revision").asLong(), attached, scopeFingerprint(scope),
                createdAt, updatedAt);
    }

    private static VerifiedStoredProposal verifyStoredProposalSemantics(JsonNode stored) {
        JsonNode draft = stored.path("draft");
        String attached = stored.path("draftFingerprint").asText();
        String expected = canonicalFingerprint(draft);
        if (!expected.equals(attached)) {
            throw invalid("RG.BUSINESS_MIRROR.CLIENT.PROPOSAL_DRAFT_FINGERPRINT_MISMATCH");
        }
        Instant createdAt = instant(stored.path("createdAt").asText(),
                "RG.BUSINESS_MIRROR.CLIENT.STORED_PROPOSAL_DRAFT_TIME_INVALID");
        Instant updatedAt = instant(stored.path("updatedAt").asText(),
                "RG.BUSINESS_MIRROR.CLIENT.STORED_PROPOSAL_DRAFT_TIME_INVALID");
        if (updatedAt.isBefore(createdAt)) {
            throw invalid("RG.BUSINESS_MIRROR.CLIENT.STORED_PROPOSAL_DRAFT_TIME_INVALID");
        }
        JsonNode scope = draft.path("scope");
        if (!scope.path("tenantId").asText()
                .equals(draft.path("provenance").path("tenantId").asText())) {
            throw invalid("RG.BUSINESS_MIRROR.CLIENT.PROPOSAL_DRAFT_SCOPE_MISMATCH");
        }
        return new VerifiedStoredProposal(draft.path("proposalId").asText(),
                draft.path("revision").asLong(), attached, scopeFingerprint(scope),
                createdAt, updatedAt);
    }

    private static String scopeFingerprint(JsonNode scope) {
        return canonicalFingerprint(scope);
    }

    private static String canonicalFingerprint(JsonNode value) {
        return BusinessMirrorCanonical.fingerprint(value,
                "RG.BUSINESS_MIRROR.CLIENT.PACKAGE_DRAFT_TOO_LARGE",
                "RG.BUSINESS_MIRROR.CLIENT.PACKAGE_CANONICALIZATION_FAILED");
    }

    private static Instant instant(String value, String code) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException failure) {
            throw invalid(code);
        }
    }

    private static IllegalArgumentException invalid(String code) {
        return new IllegalArgumentException(code);
    }

    /**
     * Payload-free identity of one verified stored Package revision.
     *
     * @param packageId stable Package id
     * @param revision exact stored revision
     * @param draftFingerprint verified canonical draft fingerprint
     * @param scopeFingerprint canonical enterprise Scope fingerprint
     * @param createdAt Package creation time
     * @param updatedAt revision update time
     */
    public record VerifiedStoredPackage(
            String packageId,
            long revision,
            String draftFingerprint,
            String scopeFingerprint,
            Instant createdAt,
            Instant updatedAt) {
    }

    /**
     * Payload-free identity of one verified save receipt.
     *
     * @param requestFingerprint producer-owned canonical command fingerprint
     * @param packageId stable Package id
     * @param revision exact stored revision
     * @param draftFingerprint verified canonical draft fingerprint
     * @param completedAt durable receipt completion time
     */
    public record VerifiedSaveReceipt(
            String requestFingerprint,
            String packageId,
            long revision,
            String draftFingerprint,
            Instant completedAt) {
    }

    /**
     * Payload-free identity of one verified Package page.
     *
     * @param itemCount number of verified items
     * @param nextCursor exact next-page cursor, or blank
     * @param scopeFingerprint shared Scope fingerprint, or blank for an empty page
     */
    public record VerifiedPackagePage(
            int itemCount,
            String nextCursor,
            String scopeFingerprint) {
    }

    /**
     * Payload-free identity of one verified stored Capability Proposal revision.
     *
     * @param proposalId stable Proposal id
     * @param revision exact stored revision
     * @param draftFingerprint verified canonical draft fingerprint
     * @param scopeFingerprint canonical enterprise Scope fingerprint
     * @param createdAt Proposal creation time
     * @param updatedAt revision update time
     */
    public record VerifiedStoredProposal(
            String proposalId,
            long revision,
            String draftFingerprint,
            String scopeFingerprint,
            Instant createdAt,
            Instant updatedAt) {
    }

    /**
     * Payload-free identity of one verified Capability Proposal save receipt.
     *
     * @param requestFingerprint producer-owned canonical command fingerprint
     * @param proposalId stable Proposal id
     * @param revision exact stored revision
     * @param draftFingerprint verified canonical draft fingerprint
     * @param completedAt durable receipt completion time
     */
    public record VerifiedProposalSaveReceipt(
            String requestFingerprint,
            String proposalId,
            long revision,
            String draftFingerprint,
            Instant completedAt) {
    }

    /**
     * Payload-free identity of one verified Capability Proposal page.
     *
     * @param itemCount number of verified items
     * @param nextCursor exact next-page cursor, or blank
     * @param scopeFingerprint shared Scope fingerprint, or blank for an empty page
     */
    public record VerifiedProposalPage(
            int itemCount,
            String nextCursor,
            String scopeFingerprint) {
    }
}
