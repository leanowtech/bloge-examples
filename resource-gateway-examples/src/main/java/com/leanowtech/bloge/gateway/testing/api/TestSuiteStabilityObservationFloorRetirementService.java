package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityObservationFloorRetirementEvidence;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteStabilityObservationExternalArchiveIntegrity;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteStabilityObservationFloorRetirementAttestationService;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteStabilityObservationFloorRetirementIntegrity;

import java.time.Instant;
import java.util.Objects;

/**
 * Orchestrates prepare, sign, verify, and atomic commit for one observation-ledger floor move.
 *
 * <p>This service is deliberately not an HTTP capability. It obtains independently verified
 * external immutable-archive receipts before invoking the local transaction, so no supported
 * retirement path can delete active rows using same-database durability alone. Legal-hold
 * authorization, backup purge, and witnessed non-equivocation remain separate admission gates.</p>
 */
public final class TestSuiteStabilityObservationFloorRetirementService {
    private final ObjectMapper objectMapper;
    private final TestSuiteStabilityRunRepository repository;
    private final TestSuiteStabilityObservationFloorRetirementAttestationService attestations;
    private final TestSuiteStabilityObservationExternalArchiveAuthority archiveAuthority;

    /**
     * Creates an external-first signed floor-retirement boundary.
     *
     * @param objectMapper canonical protocol mapper
     * @param repository database-authoritative stability store
     * @param attestations retirement-specific signing and verification service
     * @param archiveAuthority independently trusted external immutable-archive authority
     */
    public TestSuiteStabilityObservationFloorRetirementService(
            ObjectMapper objectMapper,
            TestSuiteStabilityRunRepository repository,
            TestSuiteStabilityObservationFloorRetirementAttestationService attestations,
            TestSuiteStabilityObservationExternalArchiveAuthority archiveAuthority) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.attestations = Objects.requireNonNull(attestations, "attestations");
        this.archiveAuthority = Objects.requireNonNull(
                archiveAuthority, "archiveAuthority");
    }

    /**
     * Retires at most one bounded eligible prefix without exposing unsigned deletion authority.
     *
     * @param tenantId verified tenant scope
     * @param environmentId verified non-production environment
     * @param suiteRef exact immutable suite revision
     * @param cutoffExclusive exclusive database append-time retention boundary
     * @param minimumRetainedEntries minimum active suffix that must remain
     * @param maximumRetiredEntries maximum entries in one atomic archive segment
     * @param retentionPolicyFingerprint immutable external policy identity
     * @param retainUntil minimum external compliance-retention deadline
     * @return committed, no-op, or fail-closed result
     */
    public Result retire(
            String tenantId,
            String environmentId,
            TestSuiteExecutionRequest.SuiteRef suiteRef,
            Instant cutoffExclusive,
            int minimumRetainedEntries,
            int maximumRetiredEntries,
            String retentionPolicyFingerprint,
            Instant retainUntil) {
        var planned = repository.planObservationFloorRetirement(
                tenantId, environmentId, suiteRef, cutoffExclusive,
                minimumRetainedEntries, maximumRetiredEntries,
                retentionPolicyFingerprint);
        if (planned.isEmpty()) {
            return Result.noEligiblePrefix();
        }
        TestSuiteStabilityObservationFloorRetirementEvidence evidence = planned.get();
        var sealed = attestations.seal(evidence);
        if (!sealed.verified()) {
            return Result.failed(sealed.failureCode());
        }
        String evidenceFingerprint = ProtocolFingerprint.of(objectMapper, evidence);
        String attestationFingerprint = ProtocolFingerprint.of(
                objectMapper, sealed.attestation());
        TestSuiteStabilityObservationFloorRetirement unsigned =
                new TestSuiteStabilityObservationFloorRetirement(
                        evidenceFingerprint, evidence, attestationFingerprint,
                        sealed.attestation(), zeroFingerprint());
        TestSuiteStabilityObservationFloorRetirement retirement =
                new TestSuiteStabilityObservationFloorRetirement(
                        unsigned.evidenceFingerprint(), unsigned.evidence(),
                        unsigned.attestationFingerprint(), unsigned.attestation(),
                        TestSuiteStabilityObservationFloorRetirementIntegrity
                                .retirementFingerprint(objectMapper, unsigned));
        if (attestations.verify(evidence, retirement.attestation())
                != TestSuiteStabilityObservationFloorRetirementAttestationService.Verification
                .VERIFIED
                || !TestSuiteStabilityObservationFloorRetirementIntegrity.valid(
                objectMapper, retirement)) {
            return Result.failed(
                    TestSuiteStabilityObservationFloorRetirementAttestationService
                            .SIGNATURE_INVALID);
        }
        if (retainUntil == null || !retainUntil.isAfter(evidence.retiredAt())) {
            return Result.failed(
                    TestSuiteStabilityObservationExternalArchiveAuthority
                            .ARCHIVE_RECEIPT_INVALID);
        }
        TestSuiteStabilityObservationExternalArchiveReceiptSet receiptSet;
        try {
            receiptSet = archiveAuthority.archive(retirement, retainUntil);
        } catch (TestSuiteStabilityObservationExternalArchiveAuthority
                 .ExternalArchiveException failure) {
            return Result.failed(archiveFailureCode(failure.reason()));
        } catch (RuntimeException unavailable) {
            return Result.failed(
                    TestSuiteStabilityObservationExternalArchiveAuthority.ARCHIVE_UNAVAILABLE);
        }
        TestSuiteStabilityObservationExternalArchiveAuthority.Verification archiveVerification;
        try {
            archiveVerification = archiveAuthority.verify(receiptSet);
        } catch (RuntimeException unavailable) {
            archiveVerification =
                    TestSuiteStabilityObservationExternalArchiveAuthority.Verification.UNAVAILABLE;
        }
        if (archiveVerification
                != TestSuiteStabilityObservationExternalArchiveAuthority.Verification.VERIFIED
                || !TestSuiteStabilityObservationExternalArchiveIntegrity.valid(
                objectMapper, receiptSet)
                || !retirement.equals(receiptSet.request().retirement())
                || receiptSet.request().retainUntil().isBefore(retainUntil)) {
            return Result.failed(archiveVerification
                    == TestSuiteStabilityObservationExternalArchiveAuthority.Verification.UNAVAILABLE
                    ? TestSuiteStabilityObservationExternalArchiveAuthority.ARCHIVE_UNAVAILABLE
                    : TestSuiteStabilityObservationExternalArchiveAuthority
                            .ARCHIVE_RECEIPT_INVALID);
        }
        TestSuiteStabilityObservationLedgerFloor successor =
                repository.commitObservationFloorRetirement(retirement, receiptSet);
        if (!successor.latestRetirementId().equals(evidence.retirementId())
                || !successor.latestRetirementFingerprint().equals(
                retirement.retirementFingerprint())
                || successor.retirementGeneration() != evidence.retirementGeneration()) {
            throw new IllegalStateException(
                    "Committed stability observation floor contradicts its retirement");
        }
        return Result.retired(retirement, receiptSet, successor);
    }

    /**
     * Closed orchestration result without business payload or remote diagnostic material.
     *
     * @param status closed result status
     * @param retirement committed signed retirement; present only for {@code RETIRED}
     * @param archiveReceiptSet external receipts committed with the retirement
     * @param successorFloor committed successor floor; present only for {@code RETIRED}
     * @param failureCode bounded stable reason; present only for {@code FAILED}
     */
    public record Result(
            Status status,
            TestSuiteStabilityObservationFloorRetirement retirement,
            TestSuiteStabilityObservationExternalArchiveReceiptSet archiveReceiptSet,
            TestSuiteStabilityObservationLedgerFloor successorFloor,
            String failureCode
    ) {
        /** Validates one exclusive orchestration outcome. */
        public Result {
            failureCode = failureCode == null ? "" : failureCode.trim();
            boolean retired = status == Status.RETIRED
                    && retirement != null && archiveReceiptSet != null
                    && successorFloor != null && failureCode.isBlank();
            boolean noOp = status == Status.NO_ELIGIBLE_PREFIX
                    && retirement == null && archiveReceiptSet == null
                    && successorFloor == null && failureCode.isBlank();
            boolean failed = status == Status.FAILED
                    && retirement == null && archiveReceiptSet == null
                    && successorFloor == null && !failureCode.isBlank();
            if (!retired && !noOp && !failed) {
                throw new IllegalArgumentException(
                        "Floor retirement result must contain exactly one outcome");
            }
        }

        /**
         * Creates a successful committed result.
         *
         * @param retirement committed signed retirement
         * @param archiveReceiptSet independently verified committed external receipts
         * @param successorFloor exact committed successor floor
         * @return successful result
         */
        public static Result retired(
                TestSuiteStabilityObservationFloorRetirement retirement,
                TestSuiteStabilityObservationExternalArchiveReceiptSet archiveReceiptSet,
                TestSuiteStabilityObservationLedgerFloor successorFloor) {
            return new Result(Status.RETIRED,
                    Objects.requireNonNull(retirement, "retirement"),
                    Objects.requireNonNull(archiveReceiptSet, "archiveReceiptSet"),
                    Objects.requireNonNull(successorFloor, "successorFloor"), "");
        }

        /** @return no-op result when no bounded eligible prefix exists */
        public static Result noEligiblePrefix() {
            return new Result(Status.NO_ELIGIBLE_PREFIX, null, null, null, "");
        }

        /**
         * Creates a fail-closed result before persistence mutation.
         *
         * @param failureCode stable signing or verification reason
         * @return failed result
         */
        public static Result failed(String failureCode) {
            return new Result(Status.FAILED, null, null, null,
                    Objects.requireNonNull(failureCode, "failureCode"));
        }
    }

    /** Closed retirement orchestration states. */
    public enum Status {
        /** Exact prefix was archived, signed, and atomically retired. */
        RETIRED,
        /** Policy and minimum-suffix bounds selected no active prefix. */
        NO_ELIGIBLE_PREFIX,
        /** Signing, external archive, or immediate verification failed before mutation. */
        FAILED
    }

    private static String zeroFingerprint() {
        return "sha256:" + "0".repeat(64);
    }

    private static String archiveFailureCode(
            TestSuiteStabilityObservationExternalArchiveAuthority.ExternalArchiveException.Reason
                    reason) {
        return switch (reason) {
            case AUTHENTICATED_CONFLICT ->
                    TestSuiteStabilityObservationExternalArchiveAuthority.ARCHIVE_CONFLICT;
            case INVALID_RECEIPT ->
                    TestSuiteStabilityObservationExternalArchiveAuthority.ARCHIVE_RECEIPT_INVALID;
            case UNAVAILABLE, CLOSED ->
                    TestSuiteStabilityObservationExternalArchiveAuthority.ARCHIVE_UNAVAILABLE;
        };
    }
}
