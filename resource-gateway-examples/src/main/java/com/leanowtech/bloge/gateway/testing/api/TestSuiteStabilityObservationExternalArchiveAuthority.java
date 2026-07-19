package com.leanowtech.bloge.gateway.testing.api;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * External write-once authority for signed compact-observation floor retirements.
 *
 * <p>The authority is outside the rollbackable Resource Gateway database. A successful archive
 * operation means independently verified receipts prove that the exact signed retirement is stored
 * in the required number of independent failure domains until at least the requested retention
 * deadline. Callers must obtain and verify the receipt set before local active rows may be
 * deleted.</p>
 */
public interface TestSuiteStabilityObservationExternalArchiveAuthority {
    /** Stable failure when no external authority can durably accept the retirement. */
    String ARCHIVE_UNAVAILABLE = "STABILITY_OBSERVATION_EXTERNAL_ARCHIVE_UNAVAILABLE";
    /** Stable failure when returned receipt material or signatures are invalid. */
    String ARCHIVE_RECEIPT_INVALID = "STABILITY_OBSERVATION_EXTERNAL_ARCHIVE_RECEIPT_INVALID";
    /** Stable failure when an external authority reports conflicting immutable material. */
    String ARCHIVE_CONFLICT = "STABILITY_OBSERVATION_EXTERNAL_ARCHIVE_CONFLICT";
    /** Closed aggregate descriptor vocabulary; endpoint and key material are forbidden. */
    Set<String> DESCRIPTOR_PROPERTIES = Set.of(
            "sourceType", "externalFirstCommit", "writeOnce", "complianceRetention");

    /**
     * Stores one complete signed retirement outside Resource Gateway before local mutation.
     *
     * @param retirement exact signed retirement and payload-free compact archive
     * @param retainUntil minimum immutable retention deadline requested by policy
     * @return independently verified multi-copy receipt set
     * @throws ExternalArchiveException when durable admission cannot be proved
     */
    TestSuiteStabilityObservationExternalArchiveReceiptSet archive(
            TestSuiteStabilityObservationFloorRetirement retirement,
            Instant retainUntil);

    /**
     * Re-verifies a previously returned receipt set without remote mutation.
     *
     * @param receiptSet complete canonical request and signed authority receipts
     * @return closed trust result
     */
    Verification verify(TestSuiteStabilityObservationExternalArchiveReceiptSet receiptSet);

    /** @return key-free aggregate configuration facts safe for health and capability gates */
    Descriptor descriptor();

    /** @return aggregate process-local runtime state without archive identities or key material */
    Snapshot snapshot();

    /** Closed verification states separating invalid evidence from authority outage. */
    enum Verification {
        /** Canonical bindings, copy policy, and every configured signature verified. */
        VERIFIED,
        /** Receipt material, copy topology, policy, or signature is invalid. */
        INVALID,
        /** Required external trust or verification authority is unavailable. */
        UNAVAILABLE
    }

    /**
     * Key-free external archive configuration facts.
     *
     * @param schemaVersion descriptor protocol version
     * @param available whether archive and verification operations are configured
     * @param externallyDurable whether objects live outside the Gateway database fault domain
     * @param challengeBound whether receipts bind fresh request entropy and a short validity window
     * @param complianceRetention whether accepted objects deny shortening or early deletion
     * @param authorityCount configured external archive authorities
     * @param requiredCopies minimum independently verified copies per retirement
     * @param independentFailureDomainCount distinct configured failure domains
     * @param minimumRetention minimum accepted immutable retention interval
     * @param properties bounded implementation metadata without identities
     */
    record Descriptor(
            String schemaVersion,
            boolean available,
            boolean externallyDurable,
            boolean challengeBound,
            boolean complianceRetention,
            int authorityCount,
            int requiredCopies,
            int independentFailureDomainCount,
            Duration minimumRetention,
            Map<String, Object> properties) {
        /** Current external archive descriptor generation. */
        public static final String SCHEMA_VERSION =
                "bloge.testSuiteStabilityObservationExternalArchiveDescriptor.v1";

        /** Rejects availability claims that do not prove independent durable copies. */
        public Descriptor {
            schemaVersion = normalized(schemaVersion);
            properties = properties == null ? Map.of() : Map.copyOf(properties);
            boolean copyShape = authorityCount >= 1 && requiredCopies >= 1
                    && requiredCopies <= authorityCount
                    && independentFailureDomainCount == authorityCount;
            if (!SCHEMA_VERSION.equals(schemaVersion) || authorityCount < 0
                    || requiredCopies < 0 || independentFailureDomainCount < 0
                    || minimumRetention == null || minimumRetention.isNegative()
                    || minimumRetention.isZero()
                    || !DESCRIPTOR_PROPERTIES.containsAll(properties.keySet())
                    || properties.entrySet().stream().anyMatch(
                    entry -> !safeDescriptorValue(entry.getValue()))
                    || available && (!externallyDurable || !challengeBound
                    || !complianceRetention || !copyShape)) {
                throw new IllegalArgumentException(
                        "Invalid external observation-archive descriptor");
            }
        }
    }

    /**
     * Aggregate process-local archive state.
     *
     * @param schemaVersion snapshot protocol version
     * @param available whether the latest operation left the authority usable
     * @param status bounded status family
     * @param lastSuccessfulArchiveAt last successful receipt-set confirmation time
     * @param successCount successful archive operations
     * @param failureCount unavailable or invalid-receipt operations
     * @param conflictCount authenticated immutable-object conflicts
     * @param authorityCount configured authority count
     * @param requiredCopies configured copy threshold
     * @param independentFailureDomainCount configured failure-domain count
     */
    record Snapshot(
            String schemaVersion,
            boolean available,
            String status,
            Instant lastSuccessfulArchiveAt,
            long successCount,
            long failureCount,
            long conflictCount,
            int authorityCount,
            int requiredCopies,
            int independentFailureDomainCount) {
        /** Current aggregate archive snapshot generation. */
        public static final String SCHEMA_VERSION =
                "bloge.testSuiteStabilityObservationExternalArchiveSnapshot.v1";

        /** Enforces bounded aggregate state without private material. */
        public Snapshot {
            schemaVersion = normalized(schemaVersion);
            status = normalized(status);
            boolean empty = authorityCount == 0 && requiredCopies == 0
                    && independentFailureDomainCount == 0;
            boolean configured = authorityCount >= 1 && requiredCopies >= 1
                    && requiredCopies <= authorityCount
                    && independentFailureDomainCount == authorityCount;
            if (!SCHEMA_VERSION.equals(schemaVersion) || status.isBlank()
                    || successCount < 0 || failureCount < 0 || conflictCount < 0
                    || authorityCount < 0 || requiredCopies < 0
                    || independentFailureDomainCount < 0
                    || (!empty && !configured) || (available && empty)) {
                throw new IllegalArgumentException(
                        "Invalid external observation-archive snapshot");
            }
        }
    }

    /** @return fail-closed authority for deployments without external WORM admission */
    static TestSuiteStabilityObservationExternalArchiveAuthority unavailable() {
        return new TestSuiteStabilityObservationExternalArchiveAuthority() {
            private final Descriptor descriptor = new Descriptor(
                    Descriptor.SCHEMA_VERSION, false, false, false, false,
                    0, 0, 0, Duration.ofDays(1), Map.of("sourceType", "UNAVAILABLE"));
            private final Snapshot snapshot = new Snapshot(
                    Snapshot.SCHEMA_VERSION, false, "UNAVAILABLE", null,
                    0, 0, 0, 0, 0, 0);

            @Override
            public TestSuiteStabilityObservationExternalArchiveReceiptSet archive(
                    TestSuiteStabilityObservationFloorRetirement retirement,
                    Instant retainUntil) {
                Objects.requireNonNull(retirement, "retirement");
                Objects.requireNonNull(retainUntil, "retainUntil");
                throw new ExternalArchiveException(
                        ExternalArchiveException.Reason.UNAVAILABLE);
            }

            @Override
            public Verification verify(
                    TestSuiteStabilityObservationExternalArchiveReceiptSet receiptSet) {
                Objects.requireNonNull(receiptSet, "receiptSet");
                return Verification.UNAVAILABLE;
            }

            @Override
            public Descriptor descriptor() {
                return descriptor;
            }

            @Override
            public Snapshot snapshot() {
                return snapshot;
            }
        };
    }

    /** Fail-closed external archive error without endpoint, object, or response details. */
    final class ExternalArchiveException extends IllegalStateException {
        /** Stable external archive failure families. */
        public enum Reason {
            /** No required external copy set could be established. */
            UNAVAILABLE,
            /** Returned material or cryptographic verification failed. */
            INVALID_RECEIPT,
            /** An authority already stores different immutable material for the object id. */
            AUTHENTICATED_CONFLICT,
            /** The authority has been closed and cannot accept work. */
            CLOSED
        }

        private final Reason reason;

        /**
         * Creates one bounded failure without remote diagnostics.
         *
         * @param reason stable failure family
         */
        public ExternalArchiveException(Reason reason) {
            super("External observation archive rejected the retirement: "
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

    private static boolean safeDescriptorValue(Object value) {
        return value instanceof Boolean
                || value instanceof String text && !text.isBlank() && text.length() <= 128
                && text.chars().noneMatch(Character::isISOControl);
    }
}
