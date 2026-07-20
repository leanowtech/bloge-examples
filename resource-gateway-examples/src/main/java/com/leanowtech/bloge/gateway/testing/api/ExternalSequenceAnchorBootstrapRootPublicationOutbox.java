package com.leanowtech.bloge.gateway.testing.api;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Durable ordered outbox between bootstrap-root ceremony completion and external publication.
 *
 * <p>A ceremony outcome is not externally effective merely because its signatures were committed.
 * Database implementations must enqueue the exact complete-chain bundle in the same transaction as
 * the ceremony's {@code PRODUCED} transition. Publishers then claim the oldest unpublished bundle
 * under a database lease and use its content-addressed publication id for exact remote replay.</p>
 *
 * <p>This interface is an embedded process-to-database control plane. It deliberately does not
 * extend the ceremony v1 or journal v2 wire schemas. A remote publisher adapter is responsible for
 * authenticated transport and idempotent handling of {@link PublicationRequest#publicationId()}.</p>
 */
public interface ExternalSequenceAnchorBootstrapRootPublicationOutbox {

    /**
     * Claims the oldest unpublished complete-chain bundle in this exact root-set scope.
     *
     * @param command stable publisher identity and bounded database lease
     * @return acquired fence, bounded wait reason, or absence of publication work
     */
    PublicationAcquisition acquirePublication(PublicationAcquisitionCommand command);

    /**
     * Commits an exact publisher receipt under the live database claim fence.
     *
     * @param claim exact outbox claim returned by {@link #acquirePublication}
     * @param receipt publisher acknowledgement bound to the claimed request
     * @return durable completion classification and projection
     */
    PublicationCompletion completePublication(
            PublicationClaim claim,
            PublicationReceipt receipt);

    /**
     * Releases a live publication claim into database-time retry backoff.
     *
     * @param claim exact live outbox claim
     * @param reason bounded failure classification without provider diagnostics
     * @return release or stale-fence classification
     */
    PublicationFailure releasePublication(
            PublicationClaim claim,
            PublicationFailureReason reason);

    /**
     * Reads one integrity-verified durable publication projection.
     *
     * @param ceremonyId exact source ceremony identity
     * @return publication projection, or empty when no outbox fact exists
     */
    Optional<PublicationSnapshot> publicationSnapshot(String ceremonyId);

    /**
     * Reports whether publication state and claim fences survive process restart.
     *
     * @return {@code true} only for durable implementations
     */
    default boolean durablePublicationOutbox() {
        return false;
    }

    /** Durable publication lifecycle state. */
    enum PublicationState {
        /** Complete-chain request is waiting for an eligible publisher. */
        PENDING,

        /** One database-fenced publisher owns the current attempt. */
        PUBLISHING,

        /** An exact matching publisher receipt is durably committed. */
        PUBLISHED
    }

    /** Atomic publication acquisition disposition. */
    enum PublicationAcquisitionDisposition {
        /** Caller owns a new publication fence. */
        ACQUIRED,

        /** Every enqueued publication in this root set is complete. */
        NO_WORK,

        /** Another publisher owns the oldest unpublished row's live lease. */
        BUSY,

        /** The durable failed-attempt retry instant has not arrived. */
        RETRY_DELAYED,

        /** The oldest unpublished row exhausted its automatic attempt budget. */
        ATTEMPT_LIMIT_REACHED
    }

    /** Exact publication completion disposition. */
    enum PublicationCompletionDisposition {
        /** This call committed the publisher receipt. */
        PUBLISHED,

        /** The same receipt had already been committed. */
        IDEMPOTENT_REPLAY,

        /** Claim owner, version, lease, or request is stale. */
        FENCE_REJECTED,

        /** A different receipt was already committed for this publication id. */
        RECEIPT_CONFLICT
    }

    /** Publication failure release disposition. */
    enum PublicationFailureDisposition {
        /** Live claim was released into durable retry backoff. */
        RELEASED,

        /** Claim owner, version, lease, or request is stale. */
        FENCE_REJECTED
    }

    /** Bounded publication failure categories safe for durable storage and aggregation. */
    enum PublicationFailureReason {
        /** Publisher adapter or remote service could not produce a bounded response. */
        PUBLISHER_UNAVAILABLE,

        /** Publisher response did not exactly bind the claimed request. */
        RESPONSE_INVALID,

        /** Local claim control became unavailable before a safe terminal mutation. */
        CONTROL_UNAVAILABLE
    }

    /**
     * Root-set-scoped publication retry policy.
     *
     * <p>Database implementations bind its canonical fingerprint to the root-set lock row. A
     * policy change therefore requires an explicit maintenance migration and cannot silently alter
     * retry pressure during a rolling deployment.</p>
     *
     * @param schemaVersion publication policy generation
     * @param initialRetryDelaySeconds delay after the first recorded failed attempt
     * @param maximumRetryDelaySeconds cap for exponential failed-attempt delay
     * @param maximumAutomaticAttempts durable automatic acquisition budget
     */
    record PublicationPolicy(
            String schemaVersion,
            long initialRetryDelaySeconds,
            long maximumRetryDelaySeconds,
            long maximumAutomaticAttempts) {

        /** Current embedded publication policy generation. */
        public static final String SCHEMA_VERSION =
                "bloge.externalSequenceAnchorBootstrapRootPublicationPolicy.v1";

        /** Conservative default for idempotent unattended publication. */
        public static final PublicationPolicy DEFAULT = new PublicationPolicy(
                SCHEMA_VERSION, 5L, 300L, 20L);

        /** Enforces bounded retry pressure and attempt cardinality. */
        public PublicationPolicy {
            schemaVersion = normalized(schemaVersion);
            if (!SCHEMA_VERSION.equals(schemaVersion)
                    || initialRetryDelaySeconds < 1L
                    || initialRetryDelaySeconds > 3_600L
                    || maximumRetryDelaySeconds < initialRetryDelaySeconds
                    || maximumRetryDelaySeconds > 86_400L
                    || maximumAutomaticAttempts < 1L
                    || maximumAutomaticAttempts > 10_000L) {
                throw new IllegalArgumentException(
                        "Bootstrap-root publication policy is invalid");
            }
        }

        /**
         * Computes an overflow-safe capped exponential delay.
         *
         * @param attemptCount durable acquisition count, starting at one
         * @return delay in whole seconds
         */
        public long retryDelaySeconds(long attemptCount) {
            if (attemptCount < 1L) {
                throw new IllegalArgumentException(
                        "Publication attempt count must be positive");
            }
            long delay = initialRetryDelaySeconds;
            for (long exponent = 1L;
                    exponent < attemptCount && delay < maximumRetryDelaySeconds;
                    exponent++) {
                delay = delay > maximumRetryDelaySeconds / 2L
                        ? maximumRetryDelaySeconds : delay * 2L;
            }
            return Math.min(delay, maximumRetryDelaySeconds);
        }
    }

    /**
     * Immutable content-addressed complete-chain publication request.
     *
     * @param schemaVersion publication request generation
     * @param publicationId stable remote idempotency identity
     * @param scopeId exact Resource Gateway fleet scope
     * @param rootSetId exact bootstrap-root chain identity
     * @param ceremonyId source ceremony identity
     * @param sequence desired complete-chain head sequence
     * @param expectedPreviousMaterialFingerprint exact predecessor head
     * @param bundle complete genesis-to-head transition bundle
     * @param bundleFingerprint canonical complete bundle fingerprint
     * @param headMaterialFingerprint desired head material fingerprint
     */
    record PublicationRequest(
            String schemaVersion,
            String publicationId,
            String scopeId,
            String rootSetId,
            String ceremonyId,
            long sequence,
            String expectedPreviousMaterialFingerprint,
            ExternalSequenceAnchorBootstrapRootBundle bundle,
            String bundleFingerprint,
            String headMaterialFingerprint) {

        /** Current embedded publication request generation. */
        public static final String SCHEMA_VERSION =
                "bloge.externalSequenceAnchorBootstrapRootPublicationRequest.v1";

        /** Enforces complete-chain, predecessor, and desired-head structural binding. */
        public PublicationRequest {
            schemaVersion = normalized(schemaVersion);
            publicationId = identifier(publicationId, "publicationId");
            scopeId = identifier(scopeId, "scopeId");
            rootSetId = identifier(rootSetId, "rootSetId");
            ceremonyId = identifier(ceremonyId, "ceremonyId");
            expectedPreviousMaterialFingerprint = fingerprint(
                    expectedPreviousMaterialFingerprint,
                    "expectedPreviousMaterialFingerprint");
            bundle = Objects.requireNonNull(bundle, "bundle");
            bundleFingerprint = fingerprint(bundleFingerprint, "bundleFingerprint");
            headMaterialFingerprint = fingerprint(
                    headMaterialFingerprint, "headMaterialFingerprint");
            if (!SCHEMA_VERSION.equals(schemaVersion) || sequence < 1L
                    || !publicationId.equals("root-pub-"
                    + bundleFingerprint.substring("sha256:".length()))
                    || bundle.transitions().isEmpty()
                    || bundle.transitions().getLast().material().sequence() != sequence
                    || !bundle.transitions().getLast().material().scopeId().equals(scopeId)
                    || !bundle.transitions().getLast().material().rootSetId().equals(rootSetId)
                    || !bundle.headMaterialFingerprint().equals(headMaterialFingerprint)
                    || !expectedPrevious(bundle).equals(
                    expectedPreviousMaterialFingerprint)) {
                throw new IllegalArgumentException(
                        "Bootstrap-root publication request is invalid");
            }
        }

        private static String expectedPrevious(
                ExternalSequenceAnchorBootstrapRootBundle bundle) {
            int size = bundle.transitions().size();
            return size == 1
                    ? bundle.genesisMaterialFingerprint()
                    : bundle.transitions().get(size - 2).materialFingerprint();
        }
    }

    /**
     * Database-atomic request for the oldest unpublished row.
     *
     * @param schemaVersion acquisition command generation
     * @param workerId stable pre-authenticated publisher identity
     * @param leaseDurationSeconds database-clock lease from 1 through 300 seconds
     */
    record PublicationAcquisitionCommand(
            String schemaVersion,
            String workerId,
            long leaseDurationSeconds) {

        /** Current publication acquisition generation. */
        public static final String SCHEMA_VERSION =
                "bloge.externalSequenceAnchorBootstrapRootPublicationAcquisition.v1";

        /** Enforces bounded publisher identity and lease. */
        public PublicationAcquisitionCommand {
            schemaVersion = normalized(schemaVersion);
            workerId = identifier(workerId, "workerId");
            if (!SCHEMA_VERSION.equals(schemaVersion)
                    || leaseDurationSeconds < 1L || leaseDurationSeconds > 300L) {
                throw new IllegalArgumentException(
                        "Bootstrap-root publication acquisition is invalid");
            }
        }
    }

    /**
     * Exact database-issued publication fence.
     *
     * @param schemaVersion claim generation
     * @param publicationId content-addressed publication identity
     * @param ceremonyId source ceremony identity
     * @param workerId current claim owner
     * @param claimVersion monotonically increasing claim generation
     * @param claimUntil database lease expiry
     * @param request immutable complete-chain request
     */
    record PublicationClaim(
            String schemaVersion,
            String publicationId,
            String ceremonyId,
            String workerId,
            long claimVersion,
            Instant claimUntil,
            PublicationRequest request) {

        /** Current publication claim generation. */
        public static final String SCHEMA_VERSION =
                "bloge.externalSequenceAnchorBootstrapRootPublicationClaim.v1";

        /** Enforces exact claim-to-request identity. */
        public PublicationClaim {
            schemaVersion = normalized(schemaVersion);
            publicationId = identifier(publicationId, "publicationId");
            ceremonyId = identifier(ceremonyId, "ceremonyId");
            workerId = identifier(workerId, "workerId");
            claimUntil = Objects.requireNonNull(claimUntil, "claimUntil");
            request = Objects.requireNonNull(request, "request");
            if (!SCHEMA_VERSION.equals(schemaVersion) || claimVersion < 1L
                    || !publicationId.equals(request.publicationId())
                    || !ceremonyId.equals(request.ceremonyId())) {
                throw new IllegalArgumentException(
                        "Bootstrap-root publication claim is invalid");
            }
        }
    }

    /**
     * Exact public publisher acknowledgement.
     *
     * @param schemaVersion receipt generation
     * @param status applied or exact remote replay
     * @param publicationId content-addressed publication identity
     * @param sequence published head sequence
     * @param bundleFingerprint canonical complete bundle fingerprint
     * @param headMaterialFingerprint published head material fingerprint
     * @param publishedAt stable original remote completion instant, replayed unchanged
     */
    record PublicationReceipt(
            String schemaVersion,
            PublicationReceiptStatus status,
            String publicationId,
            long sequence,
            String bundleFingerprint,
            String headMaterialFingerprint,
            Instant publishedAt) {

        /** Current publication receipt generation. */
        public static final String SCHEMA_VERSION =
                "bloge.externalSequenceAnchorBootstrapRootPublicationReceipt.v1";

        /** Enforces bounded public receipt structure. */
        public PublicationReceipt {
            schemaVersion = normalized(schemaVersion);
            status = Objects.requireNonNull(status, "status");
            publicationId = identifier(publicationId, "publicationId");
            bundleFingerprint = fingerprint(bundleFingerprint, "bundleFingerprint");
            headMaterialFingerprint = fingerprint(
                    headMaterialFingerprint, "headMaterialFingerprint");
            publishedAt = Objects.requireNonNull(publishedAt, "publishedAt");
            if (!SCHEMA_VERSION.equals(schemaVersion) || sequence < 1L) {
                throw new IllegalArgumentException(
                        "Bootstrap-root publication receipt is invalid");
            }
        }
    }

    /** Remote publication acknowledgement status. */
    enum PublicationReceiptStatus {
        /** Remote publication atomically advanced to this complete bundle. */
        PUBLISHED,

        /** Remote publication already held this exact publication id and bundle. */
        IDEMPOTENT_REPLAY
    }

    /**
     * Immutable durable publication projection.
     *
     * @param schemaVersion projection generation
     * @param state durable publication lifecycle state
     * @param request immutable complete-chain request
     * @param requestFingerprint canonical request fingerprint
     * @param enqueuedAt database enqueue instant
     * @param claimOwner latest claim owner, empty before first acquisition
     * @param claimVersion monotonic claim generation
     * @param claimUntil latest lease horizon, absent before first acquisition
     * @param attemptCount durable acquisition count
     * @param lastFailure latest bounded failure classification
     * @param lastFailedAt database failure instant
     * @param receipt exact terminal receipt
     * @param receiptFingerprint canonical receipt fingerprint
     * @param publishedAt database terminal commit instant
     * @param updatedAt database mutation instant
     * @param recordFingerprint whole-record integrity fingerprint
     */
    record PublicationSnapshot(
            String schemaVersion,
            PublicationState state,
            PublicationRequest request,
            String requestFingerprint,
            Instant enqueuedAt,
            String claimOwner,
            long claimVersion,
            Instant claimUntil,
            long attemptCount,
            PublicationFailureReason lastFailure,
            Instant lastFailedAt,
            PublicationReceipt receipt,
            String receiptFingerprint,
            Instant publishedAt,
            Instant updatedAt,
            String recordFingerprint) {

        /** Current embedded publication projection generation. */
        public static final String SCHEMA_VERSION =
                "bloge.externalSequenceAnchorBootstrapRootPublicationSnapshot.v1";

        /** Enforces state-dependent claim, failure, and terminal receipt semantics. */
        public PublicationSnapshot {
            schemaVersion = normalized(schemaVersion);
            state = Objects.requireNonNull(state, "state");
            request = Objects.requireNonNull(request, "request");
            requestFingerprint = fingerprint(requestFingerprint, "requestFingerprint");
            enqueuedAt = Objects.requireNonNull(enqueuedAt, "enqueuedAt");
            claimOwner = normalized(claimOwner);
            receiptFingerprint = normalized(receiptFingerprint);
            updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
            recordFingerprint = fingerprint(recordFingerprint, "recordFingerprint");
            boolean attempted = attemptCount > 0L;
            boolean publishing = state == PublicationState.PUBLISHING;
            boolean failed = lastFailure != null;
            boolean published = state == PublicationState.PUBLISHED;
            if (!SCHEMA_VERSION.equals(schemaVersion)
                    || claimVersion < 0L || attemptCount < 0L
                    || claimVersion != attemptCount
                    || attempted != (!claimOwner.isEmpty() && claimUntil != null)
                    || publishing && !attempted
                    || failed != (lastFailedAt != null)
                    || published != (receipt != null)
                    || published != (!receiptFingerprint.isEmpty())
                    || published != (publishedAt != null)
                    || updatedAt.isBefore(enqueuedAt)) {
                throw new IllegalArgumentException(
                        "Bootstrap-root publication snapshot is invalid");
            }
        }

        /**
         * Returns the source ceremony identity.
         *
         * @return exact ceremony id carried by the immutable request
         */
        public String ceremonyId() {
            return request.ceremonyId();
        }
    }

    /**
     * Atomic publication acquisition result.
     *
     * @param disposition acquisition or wait classification
     * @param claim exact fence only when acquired
     * @param snapshot oldest unpublished projection, absent only when no work exists
     * @param eligibleAt database instant for a busy lease or delayed retry
     */
    record PublicationAcquisition(
            PublicationAcquisitionDisposition disposition,
            PublicationClaim claim,
            PublicationSnapshot snapshot,
            Instant eligibleAt) {

        /** Enforces disposition-dependent claim, projection, and timing presence. */
        public PublicationAcquisition {
            disposition = Objects.requireNonNull(disposition, "disposition");
            boolean acquired = disposition == PublicationAcquisitionDisposition.ACQUIRED;
            boolean absent = disposition == PublicationAcquisitionDisposition.NO_WORK;
            boolean timed = disposition == PublicationAcquisitionDisposition.BUSY
                    || disposition == PublicationAcquisitionDisposition.RETRY_DELAYED;
            if (acquired != (claim != null)
                    || absent != (snapshot == null)
                    || timed != (eligibleAt != null)) {
                throw new IllegalArgumentException(
                        "Bootstrap-root publication acquisition result is invalid");
            }
        }
    }

    /**
     * Immutable publication completion result.
     *
     * @param disposition terminal mutation classification
     * @param snapshot current durable projection
     */
    record PublicationCompletion(
            PublicationCompletionDisposition disposition,
            PublicationSnapshot snapshot) {

        /** Enforces a complete durable projection. */
        public PublicationCompletion {
            disposition = Objects.requireNonNull(disposition, "disposition");
            snapshot = Objects.requireNonNull(snapshot, "snapshot");
        }
    }

    /**
     * Immutable publication failure result.
     *
     * @param disposition release or stale-fence classification
     * @param snapshot current durable projection
     */
    record PublicationFailure(
            PublicationFailureDisposition disposition,
            PublicationSnapshot snapshot) {

        /** Enforces a complete durable projection. */
        public PublicationFailure {
            disposition = Objects.requireNonNull(disposition, "disposition");
            snapshot = Objects.requireNonNull(snapshot, "snapshot");
        }
    }

    private static String identifier(String value, String field) {
        String result = normalized(value);
        if (!IDENTIFIER.matcher(result).matches()) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return result;
    }

    private static String fingerprint(String value, String field) {
        String result = normalized(value);
        if (!FINGERPRINT.matcher(result).matches()) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return result;
    }

    private static String normalized(String value) {
        return Objects.requireNonNullElse(value, "").trim();
    }

    /** Shared bounded publisher and publication identity grammar. */
    Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");

    /** Shared lowercase SHA-256 protocol fingerprint grammar. */
    Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");
}
