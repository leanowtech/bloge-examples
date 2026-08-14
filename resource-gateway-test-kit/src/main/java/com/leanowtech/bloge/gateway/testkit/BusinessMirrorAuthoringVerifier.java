package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
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

    private static final ObjectMapper JSON = new ObjectMapper();

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

    private static String scopeFingerprint(JsonNode scope) {
        return canonicalFingerprint(scope);
    }

    private static String canonicalFingerprint(JsonNode value) {
        try {
            byte[] canonical = JSON.writeValueAsBytes(canonical(value));
            if (canonical.length > MAXIMUM_DRAFT_BYTES) {
                throw invalid("RG.BUSINESS_MIRROR.CLIENT.PACKAGE_DRAFT_TOO_LARGE");
            }
            return "sha256:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (JsonProcessingException | GeneralSecurityException failure) {
            throw invalid("RG.BUSINESS_MIRROR.CLIENT.PACKAGE_CANONICALIZATION_FAILED");
        }
    }

    private static JsonNode canonical(JsonNode value) {
        if (value.isObject()) {
            ObjectNode sorted = JSON.createObjectNode();
            List<String> names = new ArrayList<>();
            value.fieldNames().forEachRemaining(names::add);
            names.sort(Comparator.naturalOrder());
            names.forEach(name -> sorted.set(name, canonical(value.get(name))));
            return sorted;
        }
        if (value.isArray()) {
            ArrayNode array = JSON.createArrayNode();
            value.forEach(item -> array.add(canonical(item)));
            return array;
        }
        return value.deepCopy();
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
}
