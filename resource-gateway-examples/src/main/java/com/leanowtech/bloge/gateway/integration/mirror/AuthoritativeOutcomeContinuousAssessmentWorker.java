package com.leanowtech.bloge.gateway.integration.mirror;

import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * One-step owner/epoch-fenced continuous selected-population assessment worker.
 *
 * <p>Each turn freezes a database-coherent current source cut. An unchanged cut only renews the
 * bounded freshness window; a changed cut traverses the existing audited three-authority
 * assessment admission path. Before allocating the next revision the worker reads the immutable
 * assessment head and adopts any revision committed by a previous owner that lost its response or
 * lease before advancing the rebuildable projection cursor.</p>
 */
public final class AuthoritativeOutcomeContinuousAssessmentWorker {
    private static final String AUTHORITY_UNAVAILABLE =
            "RG.MIRROR.OUTCOME.CONTINUOUS_ASSESSMENT_AUTHORITY_UNAVAILABLE";
    private static final String CUT_BUSY =
            "RG.MIRROR.OUTCOME.CONTINUOUS_ASSESSMENT_CUT_BUSY";
    private static final String STATE_INVALID =
            "RG.MIRROR.OUTCOME.CONTINUOUS_ASSESSMENT_STATE_INVALID";
    private static final String UNEXPECTED_FAILURE =
            "RG.MIRROR.OUTCOME.CONTINUOUS_ASSESSMENT_UNEXPECTED";
    private static final String ACTOR_ID =
            "resource-gateway-continuous-assessment";

    private final AuthoritativeOutcomeContinuousAssessmentRepository
            projections;
    private final AuthoritativeOutcomeSelectedPopulationRepository
            populations;
    private final AuthoritativeOutcomeSelectedPopulationApplicationService
            assessmentService;
    private final AuthoritativeOutcomeContinuousAssessmentPolicy
            policy;

    /**
     * Creates one deterministic continuous-assessment worker.
     *
     * @param projections durable scheduling and freshness authority
     * @param populations coherent source-cut and immutable assessment registry
     * @param assessmentService audited three-authority assessment boundary
     * @param policy server-owned lease, polling, and retry controls
     */
    public AuthoritativeOutcomeContinuousAssessmentWorker(
            AuthoritativeOutcomeContinuousAssessmentRepository
                    projections,
            AuthoritativeOutcomeSelectedPopulationRepository
                    populations,
            AuthoritativeOutcomeSelectedPopulationApplicationService
                    assessmentService,
            AuthoritativeOutcomeContinuousAssessmentPolicy
                    policy) {
        this.projections = Objects.requireNonNull(
                projections, "projections");
        this.populations = Objects.requireNonNull(
                populations, "populations");
        this.assessmentService = Objects.requireNonNull(
                assessmentService, "assessmentService");
        this.policy = Objects.requireNonNull(
                policy, "policy");
    }

    /**
     * Claims and evaluates at most one due projection in an exact partition.
     *
     * @return acquired pre-execution claim or a database-clock no-work observation
     */
    public AuthoritativeOutcomeContinuousAssessmentRepository.Claim
    runOne(
            String region,
            String environmentId,
            String ownerId) {
        AuthoritativeOutcomeContinuousAssessmentRepository.Claim
                claim = projections.claimNext(
                region,
                environmentId,
                ownerId,
                policy);
        if (claim.outcome()
                == AuthoritativeOutcomeContinuousAssessmentRepository
                .Claim.Outcome.NO_WORK) {
            return claim;
        }
        AuthoritativeOutcomeContinuousAssessmentRepository.Lease
                lease = claim.lease();
        try {
            if (!assessmentService.available()) {
                fail(
                        lease,
                        AUTHORITY_UNAVAILABLE,
                        true);
                return claim;
            }
            AuthoritativeOutcomeContinuousAssessmentProjection
                    projection = claim.projection();
            AuthoritativeOutcomeSelectedPopulationRepository
                    .AssessmentCut cut =
                    populations.prepareAssessment(
                            projection.scope(),
                            projection.populationRef().id(),
                            projection.populationRef()
                                    .revision());
            if (!cut.population().manifest()
                    .artifactRef().equals(
                            projection.populationRef())) {
                fail(lease, STATE_INVALID, false);
                return claim;
            }
            Optional<AuthoritativeOutcomeSelectedPopulationCompletenessAssessment>
                    currentHead =
                    populations.findLatestAssessment(
                            projection.scope(),
                            projection.assessmentId());
            validateHead(projection, currentHead);
            if (currentHead.isPresent()
                    && sameSource(
                    currentHead.orElseThrow(), cut)) {
                AuthoritativeOutcomeSelectedPopulationCompletenessAssessment
                        current = currentHead.orElseThrow();
                if (projection.lastAssessmentRef() != null
                        && projection.lastAssessmentRef()
                        .equals(current.artifactRef())
                        && projection
                        .observationSetFingerprint()
                        .equals(
                                cut.observationSetFingerprint())
                        && projection
                        .dispositionSetFingerprint()
                        .equals(
                                cut.dispositionSetFingerprint())) {
                    projections.unchanged(
                            lease, policy);
                } else {
                    projections.publish(
                            lease,
                            current.artifactRef(),
                            current.observationSetFingerprint(),
                            current.dispositionSetFingerprint(),
                            policy);
                }
                return claim;
            }
            long revision = currentHead
                    .map(value -> Math.addExact(
                            value.revision(), 1L))
                    .orElse(1L);
            String predecessor = currentHead
                    .map(AuthoritativeOutcomeSelectedPopulationCompletenessAssessment
                            ::assessmentFingerprint)
                    .orElse("");
            AuthoritativeOutcomeSelectedPopulationAssessmentAdmission
                    admission = assessmentService
                    .assessContinuous(
                            projection.populationRef().id(),
                            new AuthoritativeOutcomeSelectedPopulationAssessmentRequest(
                                    "",
                                    projection.populationRef()
                                            .revision(),
                                    projection.assessmentId(),
                                    revision,
                                    predecessor),
                            workerIdentity(projection));
            AuthoritativeOutcomeSelectedPopulationCompletenessAssessment
                    assessment = admission.assessment();
            if (!assessment.populationRef().equals(
                    projection.populationRef())
                    || !assessment.assessmentId().equals(
                    projection.assessmentId())) {
                fail(lease, STATE_INVALID, false);
                return claim;
            }
            projections.publish(
                    lease,
                    assessment.artifactRef(),
                    assessment.observationSetFingerprint(),
                    assessment.dispositionSetFingerprint(),
                    policy);
        } catch (IntegrationProblemException failure) {
            fail(
                    lease,
                    failure.problem().retryable()
                            ? AUTHORITY_UNAVAILABLE
                            : STATE_INVALID,
                    failure.problem().retryable());
        } catch (AuthoritativeOutcomeSelectedPopulationRepository
                 .Violation failure) {
            boolean retryable =
                    failure.reason()
                            == AuthoritativeOutcomeSelectedPopulationRepository
                            .Reason.CUT_STALE;
            fail(
                    lease,
                    retryable ? CUT_BUSY : STATE_INVALID,
                    retryable);
        } catch (AuthoritativeOutcomeContinuousAssessmentRepository
                 .Violation failure) {
            if (failure.reason()
                    != AuthoritativeOutcomeContinuousAssessmentRepository
                    .Reason.LEASE_LOST) {
                fail(
                        lease,
                        STATE_INVALID,
                        false);
            }
        } catch (IllegalArgumentException invalid) {
            fail(lease, STATE_INVALID, false);
        } catch (RuntimeException unexpected) {
            fail(
                    lease,
                    UNEXPECTED_FAILURE,
                    true);
        }
        return claim;
    }

    /** @return whether all assessment trust boundaries are currently usable */
    public boolean ready() {
        try {
            return assessmentService.available();
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private static void validateHead(
            AuthoritativeOutcomeContinuousAssessmentProjection
                    projection,
            Optional<AuthoritativeOutcomeSelectedPopulationCompletenessAssessment>
                    currentHead) {
        MirrorArtifactRef cursor =
                projection.lastAssessmentRef();
        if (currentHead.isEmpty()) {
            if (cursor != null) {
                throw new IllegalArgumentException(
                        "continuous assessment evidence head disappeared");
            }
            return;
        }
        AuthoritativeOutcomeSelectedPopulationCompletenessAssessment
                head = currentHead.orElseThrow();
        if (!head.scope().equals(projection.scope())
                || !head.populationRef().equals(
                projection.populationRef())
                || !head.assessmentId().equals(
                projection.assessmentId())
                || cursor != null
                && (head.revision() < cursor.revision()
                || head.revision() == cursor.revision()
                && !head.artifactRef().equals(cursor))) {
            throw new IllegalArgumentException(
                    "continuous assessment evidence head is inconsistent");
        }
    }

    private static boolean sameSource(
            AuthoritativeOutcomeSelectedPopulationCompletenessAssessment
                    assessment,
            AuthoritativeOutcomeSelectedPopulationRepository
                    .AssessmentCut cut) {
        return assessment.populationRef().equals(
                cut.population().manifest().artifactRef())
                && assessment.observationSetFingerprint()
                .equals(cut.observationSetFingerprint())
                && assessment.dispositionSetFingerprint()
                .equals(cut.dispositionSetFingerprint());
    }

    private void fail(
            AuthoritativeOutcomeContinuousAssessmentRepository.Lease
                    lease,
            String failureCode,
            boolean retryable) {
        try {
            projections.fail(
                    lease,
                    failureCode,
                    retryable,
                    policy);
        } catch (AuthoritativeOutcomeContinuousAssessmentRepository
                 .Violation ignored) {
            // A replacement owner or completed turn owns the current projection.
        }
    }

    private static IntegrationRequestContext workerIdentity(
            AuthoritativeOutcomeContinuousAssessmentProjection
                    projection) {
        CapabilitySnapshot.Scope scope = projection.scope();
        return new IntegrationRequestContext(
                scope.tenantId(),
                scope.organizationId(),
                scope.projectId(),
                scope.environmentId(),
                scope.region(),
                "SERVICE",
                ACTOR_ID,
                "",
                AuthoritativeOutcomeSelectedPopulationAccessPolicy
                        .ASSESSMENT_PURPOSE,
                "continuous-assessment/"
                        + projection.projectionId(),
                Set.of(
                        AuthoritativeOutcomeSelectedPopulationAccessPolicy
                                .DEFAULT_ASSESSMENT_GROUP),
                "INTERNAL",
                "");
    }
}
