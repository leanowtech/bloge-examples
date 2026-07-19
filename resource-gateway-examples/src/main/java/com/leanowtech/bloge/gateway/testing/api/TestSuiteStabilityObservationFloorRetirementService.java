package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityObservationFloorRetirementEvidence;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteStabilityObservationFloorRetirementAttestationService;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteStabilityObservationFloorRetirementIntegrity;

import java.time.Instant;
import java.util.Objects;

/**
 * Orchestrates prepare, sign, verify, and atomic commit for one observation-ledger floor move.
 *
 * <p>This service is deliberately not an HTTP capability. It establishes the durable retention
 * primitive needed by a future policy scheduler. External WORM acknowledgement, legal-hold
 * authorization, backup purge, and witnessed non-equivocation remain separate admission gates.</p>
 */
public final class TestSuiteStabilityObservationFloorRetirementService {
    private final ObjectMapper objectMapper;
    private final TestSuiteStabilityRunRepository repository;
    private final TestSuiteStabilityObservationFloorRetirementAttestationService attestations;

    /**
     * Creates an internal signed floor-retirement boundary.
     *
     * @param objectMapper canonical protocol mapper
     * @param repository database-authoritative stability store
     * @param attestations retirement-specific signing and verification service
     */
    public TestSuiteStabilityObservationFloorRetirementService(
            ObjectMapper objectMapper,
            TestSuiteStabilityRunRepository repository,
            TestSuiteStabilityObservationFloorRetirementAttestationService attestations) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.attestations = Objects.requireNonNull(attestations, "attestations");
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
     * @return committed, no-op, or signer-unavailable result
     */
    public Result retire(
            String tenantId,
            String environmentId,
            TestSuiteExecutionRequest.SuiteRef suiteRef,
            Instant cutoffExclusive,
            int minimumRetainedEntries,
            int maximumRetiredEntries,
            String retentionPolicyFingerprint) {
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
        TestSuiteStabilityObservationLedgerFloor successor =
                repository.commitObservationFloorRetirement(retirement);
        if (!successor.latestRetirementId().equals(evidence.retirementId())
                || !successor.latestRetirementFingerprint().equals(
                retirement.retirementFingerprint())
                || successor.retirementGeneration() != evidence.retirementGeneration()) {
            throw new IllegalStateException(
                    "Committed stability observation floor contradicts its retirement");
        }
        return Result.retired(retirement, successor);
    }

    /**
     * Closed orchestration result without payload or policy-authority material.
     *
     * @param status closed result status
     * @param retirement committed signed retirement; present only for {@code RETIRED}
     * @param successorFloor committed successor floor; present only for {@code RETIRED}
     * @param failureCode bounded stable reason; present only for {@code FAILED}
     */
    public record Result(
            Status status,
            TestSuiteStabilityObservationFloorRetirement retirement,
            TestSuiteStabilityObservationLedgerFloor successorFloor,
            String failureCode
    ) {
        /** Validates one exclusive orchestration outcome. */
        public Result {
            failureCode = failureCode == null ? "" : failureCode.trim();
            boolean retired = status == Status.RETIRED
                    && retirement != null && successorFloor != null && failureCode.isBlank();
            boolean noOp = status == Status.NO_ELIGIBLE_PREFIX
                    && retirement == null && successorFloor == null && failureCode.isBlank();
            boolean failed = status == Status.FAILED
                    && retirement == null && successorFloor == null && !failureCode.isBlank();
            if (!retired && !noOp && !failed) {
                throw new IllegalArgumentException(
                        "Floor retirement result must contain exactly one outcome");
            }
        }

        /**
         * Creates a successful committed result.
         *
         * @param retirement committed signed retirement
         * @param successorFloor exact committed successor floor
         * @return successful result
         */
        public static Result retired(
                TestSuiteStabilityObservationFloorRetirement retirement,
                TestSuiteStabilityObservationLedgerFloor successorFloor) {
            return new Result(Status.RETIRED,
                    Objects.requireNonNull(retirement, "retirement"),
                    Objects.requireNonNull(successorFloor, "successorFloor"), "");
        }

        /** @return no-op result when no bounded eligible prefix exists */
        public static Result noEligiblePrefix() {
            return new Result(Status.NO_ELIGIBLE_PREFIX, null, null, "");
        }

        /**
         * Creates a fail-closed result before persistence mutation.
         *
         * @param failureCode stable signing or verification reason
         * @return failed result
         */
        public static Result failed(String failureCode) {
            return new Result(Status.FAILED, null, null,
                    Objects.requireNonNull(failureCode, "failureCode"));
        }
    }

    /** Closed retirement orchestration states. */
    public enum Status {
        /** Exact prefix was archived, signed, and atomically retired. */
        RETIRED,
        /** Policy and minimum-suffix bounds selected no active prefix. */
        NO_ELIGIBLE_PREFIX,
        /** Signing or immediate verification failed before mutation. */
        FAILED
    }

    private static String zeroFingerprint() {
        return "sha256:" + "0".repeat(64);
    }
}
