package com.leanowtech.bloge.gateway.integration.mirror;

import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Protected application boundary for quarantine review and corpus publication.
 *
 * <p>The service preserves three distinct immutable facts: the original observation admission,
 * its optional terminal quarantine review, and an independently owner-published corpus revision.
 * Candidate creation accepts only admitted, unexpired, exact-scope sources and revalidates every
 * external payload/proof reference. It computes deterministic metadata risk gates but never reads
 * payload bytes. Publication requires an eligible current candidate, a current operator policy,
 * an authorized publisher group, a fresh external source recheck, and an optimistic publication
 * predecessor. Provider uncertainty fails with 503 and creates no false governance decision.</p>
 */
@Service
@Profile("!production & (test | staging)")
@ConditionalOnProperty(
        prefix = "gateway.testing.mirror",
        name = "enabled",
        havingValue = "true")
public class CapabilityCorpusGovernanceService {
    /** Dedicated purpose required by all corpus-governance operations. */
    public static final String AUTHORIZED_PURPOSE = "MIRROR_CORPUS_GOVERNANCE";
    private static final String ZERO_FINGERPRINT = "sha256:" + "0".repeat(64);

    private final CapabilityObservationRepository observations;
    private final CapabilityObservationReviewRepository reviews;
    private final CapabilityCorpusRepository corpora;
    private final CapabilityCorpusGovernancePolicyProvider policies;
    private final CapabilityCorpusSourceVerifier sourceVerifier;
    private final CapabilityCorpusIntegrity integrity;
    private final MirrorOperationObservability observability;
    private final Clock clock;

    /**
     * Creates the protected service using the server UTC clock.
     *
     * @param observations exact admitted or quarantined observation store
     * @param reviews terminal quarantine-review store
     * @param corpora append-only candidate and publication store
     * @param policies operator-owned governance policy provider
     * @param sourceVerifier external metadata-only source verifier
     * @param integrity corpus command and artifact integrity boundary
     * @param observability mandatory payload-free audit and metrics
     */
    @Autowired
    public CapabilityCorpusGovernanceService(
            CapabilityObservationRepository observations,
            CapabilityObservationReviewRepository reviews,
            CapabilityCorpusRepository corpora,
            CapabilityCorpusGovernancePolicyProvider policies,
            CapabilityCorpusSourceVerifier sourceVerifier,
            CapabilityCorpusIntegrity integrity,
            MirrorOperationObservability observability) {
        this(
                observations,
                reviews,
                corpora,
                policies,
                sourceVerifier,
                integrity,
                observability,
                Clock.systemUTC());
    }

    /**
     * Full constructor for deterministic lifecycle and expiry tests.
     *
     * @param observations exact admitted or quarantined observation store
     * @param reviews terminal quarantine-review store
     * @param corpora append-only candidate and publication store
     * @param policies operator-owned governance policy provider
     * @param sourceVerifier external metadata-only source verifier
     * @param integrity corpus command and artifact integrity boundary
     * @param observability mandatory payload-free audit and metrics
     * @param clock trusted governance clock
     */
    public CapabilityCorpusGovernanceService(
            CapabilityObservationRepository observations,
            CapabilityObservationReviewRepository reviews,
            CapabilityCorpusRepository corpora,
            CapabilityCorpusGovernancePolicyProvider policies,
            CapabilityCorpusSourceVerifier sourceVerifier,
            CapabilityCorpusIntegrity integrity,
            MirrorOperationObservability observability,
            Clock clock) {
        this.observations = Objects.requireNonNull(observations, "observations");
        this.reviews = Objects.requireNonNull(reviews, "reviews");
        this.corpora = Objects.requireNonNull(corpora, "corpora");
        this.policies = Objects.requireNonNull(policies, "policies");
        this.sourceVerifier = Objects.requireNonNull(
                sourceVerifier, "sourceVerifier");
        this.integrity = Objects.requireNonNull(integrity, "integrity");
        this.observability = Objects.requireNonNull(
                observability, "observability");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Records one terminal governance review without changing the quarantine admission.
     *
     * @param request exact payload-free review command
     * @param identity authenticated reviewer
     * @return committed or idempotently recovered review
     */
    @Transactional
    public CapabilityObservationReview reviewQuarantine(
            CapabilityObservationReviewRequest request,
            IntegrationRequestContext identity) {
        var operation = observability.start(
                MirrorOperationAuditEvent.Operation.OBSERVATION_REVIEW,
                identity,
                request == null ? "" : request.observationRef().id(),
                "",
                "");
        try {
            CapabilitySnapshot.Scope scope = requireIdentity(identity);
            CapabilityObservationReviewRequest exact =
                    Objects.requireNonNull(request, "request");
            String commandFingerprint =
                    integrity.reviewCommandFingerprint(exact);
            Optional<CapabilityObservationReview> existing =
                    findReview(scope, exact.observationRef().id(), identity);
            if (existing.isPresent()) {
                CapabilityObservationReview stored = existing.get();
                if (!stored.sourceCommandFingerprint().equals(
                        commandFingerprint)) {
                    throw conflict(
                            identity,
                            "RG.MIRROR.OBSERVATION_REVIEW_CONFLICT",
                            "The observation already has a different terminal review.");
                }
                operation.succeeded(stored.reviewFingerprint());
                return stored;
            }
            CapabilityObservationRepository.StoredObservation source =
                    requireObservation(
                            scope, exact.observationRef().id(), identity);
            requireExactSource(source, exact.observationRef(),
                    exact.admissionRef(), identity);
            if (source.admission().state()
                    != CapabilityObservationAdmission.State.QUARANTINED) {
                throw conflict(
                        identity,
                        "RG.MIRROR.OBSERVATION_NOT_QUARANTINED",
                        "Only a terminal quarantined observation may be reviewed.");
            }
            CapabilityCorpusGovernancePolicyProvider.GovernancePolicy policy =
                    requirePolicy(scope, source.envelope().material().capabilityRef(),
                            identity);
            if (!policy.mayReview(identity)) {
                throw forbidden(
                        identity,
                        "RG.MIRROR.OBSERVATION_REVIEW_FORBIDDEN",
                        "The authenticated actor is not an authorized quarantine reviewer.");
            }
            CapabilityObservationReview candidate =
                    integrity.sealReview(new CapabilityObservationReview(
                            "",
                            ZERO_FINGERPRINT,
                            commandFingerprint,
                            scope,
                            exact.observationRef(),
                            exact.admissionRef(),
                            exact.disposition(),
                            exact.reviewTicketRef(),
                            exact.reasonCode(),
                            identity.actorId(),
                            clock.instant()));
            CapabilityObservationReview stored = appendReview(
                    candidate, identity);
            operation.succeeded(stored.reviewFingerprint());
            return stored;
        } catch (RuntimeException failure) {
            throw operation.failed(failure);
        }
    }

    /**
     * Freezes exact admitted observations into an immutable corpus revision candidate.
     *
     * @param request exact payload-free candidate command
     * @param identity authenticated corpus curator
     * @return committed or idempotently recovered candidate revision
     */
    @Transactional
    public CapabilityCorpusRevision createCandidate(
            CapabilityCorpusCandidateRequest request,
            IntegrationRequestContext identity) {
        var operation = observability.start(
                MirrorOperationAuditEvent.Operation.CORPUS_CANDIDATE_CREATE,
                identity,
                request == null ? "" : request.corpusId(),
                request == null ? "" : request.capabilityRef().id(),
                "");
        try {
            CapabilitySnapshot.Scope scope = requireIdentity(identity);
            CapabilityCorpusCandidateRequest exact =
                    Objects.requireNonNull(request, "request");
            String commandFingerprint =
                    integrity.candidateCommandFingerprint(exact);
            Optional<CapabilityCorpusRevision> existing = findRevision(
                    scope, exact.corpusId(), exact.revision(), identity);
            if (existing.isPresent()) {
                CapabilityCorpusRevision stored = existing.get();
                if (!stored.sourceCommandFingerprint().equals(
                        commandFingerprint)) {
                    throw conflict(
                            identity,
                            "RG.MIRROR.CORPUS_REVISION_CONFLICT",
                            "The corpus revision is already bound to another command.");
                }
                operation.succeeded(stored.revisionFingerprint());
                return stored;
            }
            requireCandidateHead(exact, scope, identity);
            CapabilityCorpusGovernancePolicyProvider.GovernancePolicy policy =
                    requirePolicy(scope, exact.capabilityRef(), identity);
            requireSourceAuthority(identity);
            Instant now = clock.instant();
            var projected = new ArrayList<
                    CapabilityCorpusRevision.SourceObservation>(
                    exact.sources().size());
            Instant usableUntil = null;
            for (CapabilityCorpusCandidateRequest.SourceCoordinate coordinate
                    : exact.sources()) {
                CapabilityObservationRepository.StoredObservation source =
                        requireObservation(
                                scope, coordinate.observationRef().id(), identity);
                requireExactSource(
                        source,
                        coordinate.observationRef(),
                        coordinate.admissionRef(),
                        identity);
                requireAdmittedSource(source, exact.capabilityRef(), now, identity);
                verifySource(source, policy, now, identity);
                CapabilityCorpusRevision.SourceObservation projection =
                        sourceProjection(source);
                projected.add(projection);
                if (usableUntil == null
                        || projection.usableUntil().isBefore(usableUntil)) {
                    usableUntil = projection.usableUntil();
                }
            }
            CapabilityCorpusRevision.RiskSummary risk = riskSummary(
                    projected, policy, now, usableUntil);
            CapabilityCorpusRevision candidate =
                    integrity.sealRevision(new CapabilityCorpusRevision(
                            "",
                            ZERO_FINGERPRINT,
                            commandFingerprint,
                            scope,
                            exact.corpusId(),
                            exact.revision(),
                            exact.expectedPredecessorRef(),
                            exact.capabilityRef(),
                            policy.governancePolicyRef(),
                            projected,
                            risk,
                            identity.actorId(),
                            now,
                            Objects.requireNonNull(usableUntil, "usableUntil")));
            CapabilityCorpusRevision stored = appendRevision(
                    candidate, identity);
            operation.succeeded(stored.revisionFingerprint());
            return stored;
        } catch (RuntimeException failure) {
            throw operation.failed(failure);
        }
    }

    /**
     * Publishes one exact eligible current corpus revision as a serving head.
     *
     * @param request exact owner-reviewed publication command
     * @param identity authenticated corpus publisher
     * @return committed or idempotently recovered serving publication
     */
    @Transactional
    public CapabilityCorpusPublication publish(
            CapabilityCorpusPublishRequest request,
            IntegrationRequestContext identity) {
        var operation = observability.start(
                MirrorOperationAuditEvent.Operation.CORPUS_PUBLISH,
                identity,
                request == null ? "" : request.corpusId(),
                "",
                request == null ? "" : request.corpusRevisionRef().fingerprint());
        try {
            CapabilitySnapshot.Scope scope = requireIdentity(identity);
            CapabilityCorpusPublishRequest exact =
                    Objects.requireNonNull(request, "request");
            String commandFingerprint =
                    integrity.publishCommandFingerprint(exact);
            Optional<CapabilityCorpusPublication> existing = findPublication(
                    scope, exact.corpusId(), exact.publicationRevision(), identity);
            if (existing.isPresent()) {
                CapabilityCorpusPublication stored = existing.get();
                if (!stored.sourceCommandFingerprint().equals(
                        commandFingerprint)) {
                    throw conflict(
                            identity,
                            "RG.MIRROR.CORPUS_PUBLICATION_CONFLICT",
                            "The publication revision is already bound to another command.");
                }
                operation.succeeded(stored.publicationFingerprint());
                return stored;
            }
            requirePublicationHead(exact, scope, identity);
            CapabilityCorpusRevision revision = findRevision(
                    scope,
                    exact.corpusId(),
                    exact.corpusRevisionRef().revision(),
                    identity).filter(value -> value.artifactRef().equals(
                    exact.corpusRevisionRef())).orElseThrow(() -> notFound(
                            identity,
                            "RG.MIRROR.CORPUS_REVISION_NOT_FOUND",
                            "The exact corpus revision was not found."));
            CapabilityCorpusRevision latest = findLatestRevision(
                    scope, exact.corpusId(), identity).orElseThrow(() -> notFound(
                            identity,
                            "RG.MIRROR.CORPUS_REVISION_NOT_FOUND",
                            "The exact corpus revision was not found."));
            if (!latest.artifactRef().equals(revision.artifactRef())) {
                throw conflict(
                        identity,
                        "RG.MIRROR.CORPUS_CANDIDATE_HEAD_CHANGED",
                        "A newer corpus candidate exists and requires owner review.");
            }
            CapabilityCorpusGovernancePolicyProvider.GovernancePolicy policy =
                    requirePolicy(scope, revision.capabilityRef(), identity);
            if (!policy.governancePolicyRef().equals(
                    revision.governancePolicyRef())) {
                throw conflict(
                        identity,
                        "RG.MIRROR.CORPUS_POLICY_DRIFTED",
                        "The candidate policy is no longer current.");
            }
            if (!policy.mayPublish(identity)) {
                throw forbidden(
                        identity,
                        "RG.MIRROR.CORPUS_PUBLICATION_FORBIDDEN",
                        "The authenticated actor is not an authorized corpus publisher.");
            }
            Instant now = clock.instant();
            if (revision.riskSummary().eligibility()
                    != CapabilityCorpusRevision.Eligibility.ELIGIBLE
                    || revision.usableUntil().isBefore(
                    now.plus(policy.minimumServingHorizon()))) {
                throw conflict(
                        identity,
                        "RG.MIRROR.CORPUS_CANDIDATE_INELIGIBLE",
                        "The corpus candidate does not satisfy publication gates.");
            }
            requireSourceAuthority(identity);
            for (CapabilityCorpusRevision.SourceObservation coordinate
                    : revision.sources()) {
                CapabilityObservationRepository.StoredObservation source =
                        requireObservation(
                                scope, coordinate.observationRef().id(), identity);
                requireExactSource(
                        source,
                        coordinate.observationRef(),
                        coordinate.admissionRef(),
                        identity);
                requireAdmittedSource(
                        source, revision.capabilityRef(), now, identity);
                verifySource(source, policy, now, identity);
            }
            CapabilityCorpusPublication candidate =
                    integrity.sealPublication(new CapabilityCorpusPublication(
                            "",
                            ZERO_FINGERPRINT,
                            commandFingerprint,
                            scope,
                            exact.corpusId(),
                            exact.publicationRevision(),
                            exact.expectedPublicationRef(),
                            exact.corpusRevisionRef(),
                            policy.publicationPolicyRef(),
                            exact.reviewTicketRef(),
                            exact.reasonCode(),
                            identity.actorId(),
                            now,
                            revision.usableUntil()));
            CapabilityCorpusPublication stored = appendPublication(
                    candidate, identity);
            operation.succeeded(stored.publicationFingerprint());
            return stored;
        } catch (RuntimeException failure) {
            throw operation.failed(failure);
        }
    }

    private CapabilityCorpusRevision.SourceObservation sourceProjection(
            CapabilityObservationRepository.StoredObservation stored) {
        CapabilityObservationEnvelope envelope = stored.envelope();
        CapabilityObservationEnvelope.PayloadReference request =
                envelope.material().request();
        CapabilityObservationEnvelope.PayloadReference response =
                envelope.material().response();
        return new CapabilityCorpusRevision.SourceObservation(
                envelope.artifactRef(),
                stored.admission().artifactRef(),
                request.payloadRef(),
                request.sanitizationProofRef(),
                request.schemaRef(),
                response == null ? null : response.payloadRef(),
                response == null ? null : response.sanitizationProofRef(),
                response == null ? null : response.schemaRef(),
                envelope.material().error() == null
                        ? "" : envelope.material().error().errorCode(),
                integrity.traceFingerprint(envelope.material().trace()),
                stored.admission().authorityKeyRef(),
                envelope.material().occurredAt(),
                stored.admission().usableUntil());
    }

    private static CapabilityCorpusRevision.RiskSummary riskSummary(
            ArrayList<CapabilityCorpusRevision.SourceObservation> sources,
            CapabilityCorpusGovernancePolicyProvider.GovernancePolicy policy,
            Instant now,
            Instant usableUntil) {
        Map<String, Integer> requestMultiplicity = new HashMap<>();
        HashSet<String> producerKeys = new HashSet<>();
        for (CapabilityCorpusRevision.SourceObservation source : sources) {
            requestMultiplicity.merge(
                    source.requestPayloadRef().fingerprint(), 1, Integer::sum);
            producerKeys.add(source.authorityKeyRef().fingerprint());
        }
        int sampleCount = sources.size();
        int uniqueRequests = requestMultiplicity.size();
        int duplicates = sampleCount - uniqueRequests;
        int maximumMultiplicity = requestMultiplicity.values().stream()
                .mapToInt(Integer::intValue)
                .max()
                .orElse(1);
        int duplicateBasisPoints = (int) (((long) duplicates * 10_000L)
                / sampleCount);
        EnumSet<CapabilityCorpusRevision.RiskReason> reasons =
                EnumSet.noneOf(CapabilityCorpusRevision.RiskReason.class);
        if (sampleCount < policy.minimumSamples()) {
            reasons.add(
                    CapabilityCorpusRevision.RiskReason.INSUFFICIENT_SAMPLE_COUNT);
        }
        if (sampleCount > policy.maximumSamples()) {
            reasons.add(
                    CapabilityCorpusRevision.RiskReason.EXCESSIVE_SAMPLE_COUNT);
        }
        if (duplicateBasisPoints > policy.maximumDuplicateBasisPoints()) {
            reasons.add(
                    CapabilityCorpusRevision.RiskReason
                            .DUPLICATE_REQUEST_RATIO_EXCEEDED);
        }
        if (producerKeys.size() < policy.minimumProducerKeys()) {
            reasons.add(
                    CapabilityCorpusRevision.RiskReason
                            .PRODUCER_DIVERSITY_INSUFFICIENT);
        }
        if (usableUntil == null
                || usableUntil.isBefore(now.plus(policy.minimumServingHorizon()))) {
            reasons.add(
                    CapabilityCorpusRevision.RiskReason
                            .SERVING_HORIZON_INSUFFICIENT);
        }
        return new CapabilityCorpusRevision.RiskSummary(
                sampleCount,
                uniqueRequests,
                duplicates,
                maximumMultiplicity,
                producerKeys.size(),
                duplicateBasisPoints,
                reasons.isEmpty()
                        ? CapabilityCorpusRevision.Eligibility.ELIGIBLE
                        : CapabilityCorpusRevision.Eligibility.BLOCKED,
                reasons);
    }

    private void requireCandidateHead(
            CapabilityCorpusCandidateRequest request,
            CapabilitySnapshot.Scope scope,
            IntegrationRequestContext identity) {
        Optional<CapabilityCorpusRevision> latest = findLatestRevision(
                scope, request.corpusId(), identity);
        if (latest.isEmpty() && request.expectedPredecessorRef() != null
                || latest.isPresent() && !latest.get().artifactRef().equals(
                request.expectedPredecessorRef())) {
            throw conflict(
                    identity,
                    "RG.MIRROR.CORPUS_REVISION_HEAD_CONFLICT",
                    "The expected corpus revision head is stale.");
        }
    }

    private void requirePublicationHead(
            CapabilityCorpusPublishRequest request,
            CapabilitySnapshot.Scope scope,
            IntegrationRequestContext identity) {
        Optional<CapabilityCorpusPublication> latest = findLatestPublication(
                scope, request.corpusId(), identity);
        if (latest.isEmpty() && request.expectedPublicationRef() != null
                || latest.isPresent() && !latest.get().artifactRef().equals(
                request.expectedPublicationRef())) {
            throw conflict(
                    identity,
                    "RG.MIRROR.CORPUS_PUBLICATION_HEAD_CONFLICT",
                    "The expected corpus publication head is stale.");
        }
    }

    private CapabilityCorpusGovernancePolicyProvider.GovernancePolicy
            requirePolicy(
            CapabilitySnapshot.Scope scope,
            MirrorArtifactRef capabilityRef,
            IntegrationRequestContext identity) {
        try {
            if (!policies.available()) {
                throw serviceUnavailable(
                        identity,
                        "RG.MIRROR.CORPUS_POLICY_UNAVAILABLE",
                        "The corpus governance policy provider is unavailable.");
            }
            CapabilityCorpusGovernancePolicyProvider.GovernancePolicy policy =
                    policies.resolve(scope, capabilityRef).orElseThrow(() -> conflict(
                            identity,
                            "RG.MIRROR.CORPUS_POLICY_NOT_FOUND",
                            "No corpus governance policy covers the exact capability."));
            if (!scope.equals(policy.scope())
                    || !capabilityRef.equals(policy.capabilityRef())) {
                throw serviceUnavailable(
                        identity,
                        "RG.MIRROR.CORPUS_POLICY_INVALID",
                        "The corpus governance policy is inconsistent.");
            }
            return policy;
        } catch (IntegrationProblemException expected) {
            throw expected;
        } catch (RuntimeException unavailable) {
            throw serviceUnavailable(
                    identity,
                    "RG.MIRROR.CORPUS_POLICY_UNAVAILABLE",
                    "The corpus governance policy provider is unavailable.");
        }
    }

    private void requireSourceAuthority(IntegrationRequestContext identity) {
        try {
            if (!sourceVerifier.available()) {
                throw serviceUnavailable(
                        identity,
                        "RG.MIRROR.CORPUS_SOURCE_AUTHORITY_UNAVAILABLE",
                        "The external corpus source authority is unavailable.");
            }
        } catch (IntegrationProblemException expected) {
            throw expected;
        } catch (RuntimeException unavailable) {
            throw serviceUnavailable(
                    identity,
                    "RG.MIRROR.CORPUS_SOURCE_AUTHORITY_UNAVAILABLE",
                    "The external corpus source authority is unavailable.");
        }
    }

    private void verifySource(
            CapabilityObservationRepository.StoredObservation source,
            CapabilityCorpusGovernancePolicyProvider.GovernancePolicy policy,
            Instant now,
            IntegrationRequestContext identity) {
        try {
            CapabilityCorpusSourceVerifier.VerificationResult result =
                    sourceVerifier.verify(source, policy, now);
            if (result == null
                    || result.outcome()
                    == CapabilityCorpusSourceVerifier.Outcome.UNAVAILABLE) {
                throw serviceUnavailable(
                        identity,
                        "RG.MIRROR.CORPUS_SOURCE_AUTHORITY_UNAVAILABLE",
                        "The external corpus source authority is unavailable.");
            }
            if (result.outcome()
                    == CapabilityCorpusSourceVerifier.Outcome.REJECTED) {
                throw conflict(
                        identity,
                        "RG.MIRROR.CORPUS_SOURCE_REJECTED",
                        "An exact corpus source is no longer eligible for use.");
            }
        } catch (IntegrationProblemException expected) {
            throw expected;
        } catch (RuntimeException unavailable) {
            throw serviceUnavailable(
                    identity,
                    "RG.MIRROR.CORPUS_SOURCE_AUTHORITY_UNAVAILABLE",
                    "The external corpus source authority is unavailable.");
        }
    }

    private static void requireAdmittedSource(
            CapabilityObservationRepository.StoredObservation source,
            MirrorArtifactRef capabilityRef,
            Instant now,
            IntegrationRequestContext identity) {
        if (source.admission().state()
                != CapabilityObservationAdmission.State.ADMITTED
                || !capabilityRef.equals(source.admission().capabilityRef())
                || !source.admission().usableUntil().isAfter(now)) {
            throw conflict(
                    identity,
                    "RG.MIRROR.CORPUS_SOURCE_INELIGIBLE",
                    "A source is quarantined, expired, or belongs to another capability.");
        }
    }

    private static void requireExactSource(
            CapabilityObservationRepository.StoredObservation source,
            MirrorArtifactRef observationRef,
            MirrorArtifactRef admissionRef,
            IntegrationRequestContext identity) {
        if (!source.envelope().artifactRef().equals(observationRef)
                || !source.admission().artifactRef().equals(admissionRef)) {
            throw notFound(
                    identity,
                    "RG.MIRROR.CORPUS_SOURCE_NOT_FOUND",
                    "The exact observation and admission source was not found.");
        }
    }

    private CapabilityObservationRepository.StoredObservation
            requireObservation(
            CapabilitySnapshot.Scope scope,
            String observationId,
            IntegrationRequestContext identity) {
        try {
            return observations.find(scope, observationId).orElseThrow(() ->
                    notFound(
                            identity,
                            "RG.MIRROR.CORPUS_SOURCE_NOT_FOUND",
                            "The exact observation and admission source was not found."));
        } catch (IntegrationProblemException expected) {
            throw expected;
        } catch (RuntimeException unavailable) {
            throw serviceUnavailable(
                    identity,
                    "RG.MIRROR.OBSERVATION_STORE_UNAVAILABLE",
                    "The capability observation store is unavailable.");
        }
    }

    private Optional<CapabilityObservationReview> findReview(
            CapabilitySnapshot.Scope scope,
            String observationId,
            IntegrationRequestContext identity) {
        try {
            return reviews.find(scope, observationId);
        } catch (RuntimeException unavailable) {
            throw serviceUnavailable(
                    identity,
                    "RG.MIRROR.OBSERVATION_REVIEW_STORE_UNAVAILABLE",
                    "The capability observation review store is unavailable.");
        }
    }

    private CapabilityObservationReview appendReview(
            CapabilityObservationReview review,
            IntegrationRequestContext identity) {
        try {
            return reviews.append(review);
        } catch (CapabilityObservationReviewRepository.Violation rejected) {
            if (rejected.reason()
                    == CapabilityObservationReviewRepository.Reason.REVIEW_CONFLICT) {
                throw conflict(
                        identity,
                        "RG.MIRROR.OBSERVATION_REVIEW_CONFLICT",
                        "The observation already has a different terminal review.");
            }
            throw serviceUnavailable(
                    identity,
                    "RG.MIRROR.OBSERVATION_REVIEW_STORE_UNAVAILABLE",
                    "The capability observation review store failed integrity validation.");
        } catch (RuntimeException unavailable) {
            throw serviceUnavailable(
                    identity,
                    "RG.MIRROR.OBSERVATION_REVIEW_STORE_UNAVAILABLE",
                    "The capability observation review store is unavailable.");
        }
    }

    private Optional<CapabilityCorpusRevision> findRevision(
            CapabilitySnapshot.Scope scope,
            String corpusId,
            long revision,
            IntegrationRequestContext identity) {
        try {
            return corpora.findRevision(scope, corpusId, revision);
        } catch (RuntimeException unavailable) {
            throw corpusStoreUnavailable(identity);
        }
    }

    private Optional<CapabilityCorpusRevision> findLatestRevision(
            CapabilitySnapshot.Scope scope,
            String corpusId,
            IntegrationRequestContext identity) {
        try {
            return corpora.findLatestRevision(scope, corpusId);
        } catch (RuntimeException unavailable) {
            throw corpusStoreUnavailable(identity);
        }
    }

    private CapabilityCorpusRevision appendRevision(
            CapabilityCorpusRevision revision,
            IntegrationRequestContext identity) {
        try {
            return corpora.appendRevision(revision);
        } catch (CapabilityCorpusRepository.Violation rejected) {
            if (rejected.reason() == CapabilityCorpusRepository.Reason.LINEAGE_CONFLICT
                    || rejected.reason()
                    == CapabilityCorpusRepository.Reason.CONTENT_CONFLICT) {
                throw conflict(
                        identity,
                        "RG.MIRROR.CORPUS_REVISION_CONFLICT",
                        "The corpus revision lineage changed concurrently.");
            }
            throw corpusStoreUnavailable(identity);
        } catch (RuntimeException unavailable) {
            throw corpusStoreUnavailable(identity);
        }
    }

    private Optional<CapabilityCorpusPublication> findPublication(
            CapabilitySnapshot.Scope scope,
            String corpusId,
            long revision,
            IntegrationRequestContext identity) {
        try {
            return corpora.findPublication(scope, corpusId, revision);
        } catch (RuntimeException unavailable) {
            throw corpusStoreUnavailable(identity);
        }
    }

    private Optional<CapabilityCorpusPublication> findLatestPublication(
            CapabilitySnapshot.Scope scope,
            String corpusId,
            IntegrationRequestContext identity) {
        try {
            return corpora.findLatestPublication(scope, corpusId);
        } catch (RuntimeException unavailable) {
            throw corpusStoreUnavailable(identity);
        }
    }

    private CapabilityCorpusPublication appendPublication(
            CapabilityCorpusPublication publication,
            IntegrationRequestContext identity) {
        try {
            return corpora.appendPublication(publication);
        } catch (CapabilityCorpusRepository.Violation rejected) {
            if (rejected.reason() == CapabilityCorpusRepository.Reason.LINEAGE_CONFLICT
                    || rejected.reason()
                    == CapabilityCorpusRepository.Reason.CONTENT_CONFLICT) {
                throw conflict(
                        identity,
                        "RG.MIRROR.CORPUS_PUBLICATION_CONFLICT",
                        "The corpus publication lineage changed concurrently.");
            }
            throw corpusStoreUnavailable(identity);
        } catch (RuntimeException unavailable) {
            throw corpusStoreUnavailable(identity);
        }
    }

    private static CapabilitySnapshot.Scope requireIdentity(
            IntegrationRequestContext identity) {
        IntegrationRequestContext exact = Objects.requireNonNull(identity, "identity");
        exact.requireComplete();
        if (!AUTHORIZED_PURPOSE.equals(exact.purpose())) {
            throw forbidden(
                    exact,
                    "RG.MIRROR.CORPUS_GOVERNANCE_PURPOSE_REQUIRED",
                    "Corpus governance requires its dedicated purpose.");
        }
        if (exact.projectId().isBlank() || exact.region().isBlank()) {
            throw badRequest(
                    exact,
                    "RG.MIRROR.CORPUS_SCOPE_INCOMPLETE",
                    "Corpus governance requires complete enterprise scope.");
        }
        if (!("test".equalsIgnoreCase(exact.environmentId())
                || "staging".equalsIgnoreCase(exact.environmentId()))) {
            throw forbidden(
                    exact,
                    "RG.MIRROR.CORPUS_ENVIRONMENT_FORBIDDEN",
                    "Corpus governance is restricted to test and staging.");
        }
        return new CapabilitySnapshot.Scope(
                exact.tenantId(),
                exact.organizationId(),
                exact.projectId(),
                exact.environmentId(),
                exact.region());
    }

    private static IntegrationProblemException badRequest(
            IntegrationRequestContext identity, String code, String title) {
        return new IntegrationProblemException(IntegrationProblem.badRequest(
                code, title, identity.correlationId(), Map.of()));
    }

    private static IntegrationProblemException forbidden(
            IntegrationRequestContext identity, String code, String title) {
        return new IntegrationProblemException(IntegrationProblem.forbidden(
                code, title, identity.correlationId(), Map.of()));
    }

    private static IntegrationProblemException notFound(
            IntegrationRequestContext identity, String code, String title) {
        return new IntegrationProblemException(IntegrationProblem.notFound(
                code, title, identity.correlationId(), Map.of()));
    }

    private static IntegrationProblemException conflict(
            IntegrationRequestContext identity, String code, String title) {
        return new IntegrationProblemException(IntegrationProblem.conflict(
                code, title, identity.correlationId(), Map.of()));
    }

    private static IntegrationProblemException serviceUnavailable(
            IntegrationRequestContext identity, String code, String title) {
        return new IntegrationProblemException(IntegrationProblem.serviceUnavailable(
                code, title, identity.correlationId(), Map.of()));
    }

    private static IntegrationProblemException corpusStoreUnavailable(
            IntegrationRequestContext identity) {
        return serviceUnavailable(
                identity,
                "RG.MIRROR.CORPUS_STORE_UNAVAILABLE",
                "The capability corpus store is unavailable.");
    }
}
