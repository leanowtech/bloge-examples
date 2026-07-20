package com.leanowtech.bloge.gateway.testing.api;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Read-only signed inventory boundary for external observation-archive reconciliation.
 *
 * <p>This interface intentionally exposes no delete, purge, overwrite, legal-hold release, or
 * retention-shortening operation. Implementations may read one bounded page from a pinned snapshot
 * and verify signed pages; governed remediation belongs to a separate system and authority.</p>
 */
public interface TestSuiteStabilityObservationExternalArchiveInventoryAuthority {
    /**
     * Returns configured authorities in stable lexical order.
     *
     * @return immutable authority identifiers used only inside the reconciliation control plane
     */
    List<String> inventoryAuthorities();

    /**
     * Reads one fresh challenge-bound page from one authority.
     *
     * @param authorityId exact configured authority
     * @param cursor first-page or pinned continuation cursor
     * @param maximumItems bounded requested page size
     * @return locally verified signed page
     * @throws InventoryException when the page cannot be admitted
     */
    TestSuiteStabilityObservationExternalArchiveInventoryPage inventoryPage(
            String authorityId,
            Cursor cursor,
            int maximumItems);

    /**
     * Re-verifies a page against configured topology, key lifecycle, and current admission time.
     *
     * @param page signed page to verify
     * @return closed verification state
     */
    Verification verifyInventoryPage(
            TestSuiteStabilityObservationExternalArchiveInventoryPage page);

    /**
     * Re-verifies a previously admitted page as historical evidence.
     *
     * <p>Unlike {@link
     * #verifyInventoryPage(TestSuiteStabilityObservationExternalArchiveInventoryPage)}, this
     * operation must not reject an otherwise valid page merely because its short admission window
     * or snapshot-freshness window has elapsed. Implementations must still verify canonical
     * material, configured topology, snapshot identity, and a key that was valid at signing time.
     * Revoked, missing, or unverifiable historical trust fails closed.</p>
     *
     * <p>The default is deliberately unavailable so an implementation cannot silently claim
     * historical verification from its live-admission verifier.</p>
     *
     * @param page signed page already admitted into durable staging
     * @return closed historical verification state
     */
    default Verification verifyStoredInventoryPage(
            TestSuiteStabilityObservationExternalArchiveInventoryPage page) {
        Objects.requireNonNull(page, "page");
        return Verification.UNAVAILABLE;
    }

    /** First-page or exact continuation cursor. */
    record Cursor(String snapshotId, String afterObjectId, long pageSequence) {
        private static final Pattern SNAPSHOT_ID = Pattern.compile(
                "stability-observation-external-inventory-[a-f0-9]{64}");
        private static final Pattern OBJECT_ID =
                Pattern.compile("stability-observation-worm-[a-f0-9]{64}");

        /** Enforces an empty page-zero cursor or a complete pinned continuation. */
        public Cursor {
            snapshotId = normalized(snapshotId);
            afterObjectId = normalized(afterObjectId);
            boolean first = pageSequence == 0 && snapshotId.isEmpty()
                    && afterObjectId.isEmpty();
            boolean continuation = pageSequence > 0
                    && SNAPSHOT_ID.matcher(snapshotId).matches()
                    && OBJECT_ID.matcher(afterObjectId).matches();
            if (!first && !continuation) {
                throw new IllegalArgumentException("Invalid external inventory cursor");
            }
        }

        /** @return canonical first-page cursor */
        public static Cursor initial() {
            return new Cursor("", "", 0);
        }

        /**
         * Derives the exact continuation from a non-terminal verified page.
         *
         * @param page preceding verified page
         * @return next pinned cursor
         */
        public static Cursor after(
                TestSuiteStabilityObservationExternalArchiveInventoryPage page) {
            Objects.requireNonNull(page, "page");
            if (page.complete()) {
                throw new IllegalArgumentException(
                        "A complete external inventory page has no continuation");
            }
            return new Cursor(page.snapshotId(), page.nextAfterObjectId(),
                    page.request().pageSequence() + 1);
        }
    }

    /** Closed verification states separating invalid evidence from an unavailable authority. */
    enum Verification {
        /** Request, topology, snapshot identity, item/page fingerprints, and signature verified. */
        VERIFIED,
        /** Page material, cursor, snapshot identity, freshness, or signature is invalid. */
        INVALID,
        /** Required key or external authority is unavailable. */
        UNAVAILABLE
    }

    /** Fail-closed read-only inventory failure without endpoint, object, or signature details. */
    final class InventoryException extends IllegalStateException {
        /** Stable inventory failure families. */
        public enum Reason {
            /** The authority or required verification key is unavailable. */
            UNAVAILABLE,
            /** Returned material, continuity, freshness, or signature is invalid. */
            INVALID_PAGE,
            /** The remote authority no longer serves the pinned snapshot. */
            SNAPSHOT_EXPIRED,
            /** The inventory boundary has been closed. */
            CLOSED
        }

        private final Reason reason;

        /**
         * Creates one payload-free inventory failure.
         *
         * @param reason stable failure family
         */
        public InventoryException(Reason reason) {
            super("External observation archive inventory rejected the page: "
                    + Objects.requireNonNull(reason, "reason"));
            this.reason = reason;
        }

        /** @return stable payload-free failure family */
        public Reason reason() {
            return reason;
        }
    }

    private static String normalized(String value) {
        return Objects.requireNonNullElse(value, "").trim();
    }
}
