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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Protected application boundary for owner-reviewed recorded retry trajectories.
 *
 * <p>The service never infers retries from nearby observations. It verifies one explicit ordered
 * sequence against the current corpus publication, current corpus and retry policies, exact
 * observation/admission lineage, trace ordering, data-use grants, and external source authority.
 * Intermediate attempts must be policy-permitted retryable errors and the final attempt must be
 * terminal. Exact command retries are recovered before mutable authorities are consulted.</p>
 */
@Service
@Profile("!production & (test | staging)")
@ConditionalOnProperty(
        prefix = "gateway.testing.mirror",
        name = "enabled",
        havingValue = "true")
public class CapabilityCorpusTrajectoryGovernanceService {
    private static final String ZERO_FINGERPRINT =
            "sha256:" + "0".repeat(64);

    private final CapabilityObservationRepository observations;
    private final CapabilityCorpusRepository corpora;
    private final CapabilityCorpusTrajectoryRepository trajectories;
    private final CapabilityCorpusGovernancePolicyProvider corpusPolicies;
    private final CapabilityRetryPolicyProvider retryPolicies;
    private final CapabilityCorpusSourceVerifier sourceVerifier;
    private final CapabilityCorpusIntegrity integrity;
    private final MirrorOperationObservability observability;
    private final Clock clock;

    /**
     * Creates the protected trajectory publisher using the server UTC clock.
     *
     * @param observations exact observation/admission store
     * @param corpora immutable corpus revision/publication store
     * @param trajectories append-only trajectory publication store
     * @param corpusPolicies current operator-owned corpus policy authority
     * @param retryPolicies current operator-owned retry policy authority
     * @param sourceVerifier external source lifecycle authority
     * @param integrity canonical command and artifact integrity boundary
     * @param observability mandatory payload-free audit and metrics
     */
    @Autowired
    public CapabilityCorpusTrajectoryGovernanceService(
            CapabilityObservationRepository observations,
            CapabilityCorpusRepository corpora,
            CapabilityCorpusTrajectoryRepository trajectories,
            CapabilityCorpusGovernancePolicyProvider corpusPolicies,
            CapabilityRetryPolicyProvider retryPolicies,
            CapabilityCorpusSourceVerifier sourceVerifier,
            CapabilityCorpusIntegrity integrity,
            MirrorOperationObservability observability) {
        this(observations, corpora, trajectories, corpusPolicies, retryPolicies,
                sourceVerifier, integrity, observability, Clock.systemUTC());
    }

    /**
     * Full constructor for deterministic authority, expiry, and lineage tests.
     *
     * @param observations exact observation/admission store
     * @param corpora immutable corpus revision/publication store
     * @param trajectories append-only trajectory publication store
     * @param corpusPolicies current operator-owned corpus policy authority
     * @param retryPolicies current operator-owned retry policy authority
     * @param sourceVerifier external source lifecycle authority
     * @param integrity canonical command and artifact integrity boundary
     * @param observability mandatory payload-free audit and metrics
     * @param clock trusted governance clock
     */
    public CapabilityCorpusTrajectoryGovernanceService(
            CapabilityObservationRepository observations,
            CapabilityCorpusRepository corpora,
            CapabilityCorpusTrajectoryRepository trajectories,
            CapabilityCorpusGovernancePolicyProvider corpusPolicies,
            CapabilityRetryPolicyProvider retryPolicies,
            CapabilityCorpusSourceVerifier sourceVerifier,
            CapabilityCorpusIntegrity integrity,
            MirrorOperationObservability observability,
            Clock clock) {
        this.observations = Objects.requireNonNull(observations, "observations");
        this.corpora = Objects.requireNonNull(corpora, "corpora");
        this.trajectories = Objects.requireNonNull(
                trajectories, "trajectories");
        this.corpusPolicies = Objects.requireNonNull(
                corpusPolicies, "corpusPolicies");
        this.retryPolicies = Objects.requireNonNull(
                retryPolicies, "retryPolicies");
        this.sourceVerifier = Objects.requireNonNull(
                sourceVerifier, "sourceVerifier");
        this.integrity = Objects.requireNonNull(integrity, "integrity");
        this.observability = Objects.requireNonNull(
                observability, "observability");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Publishes one exact, current, owner-reviewed retry trajectory.
     *
     * @param request explicit payload-free trajectory command
     * @param identity authenticated corpus publisher
     * @return committed or idempotently recovered trajectory publication
     */
    @Transactional
    public CapabilityCorpusTrajectoryPublication publish(
            CapabilityCorpusTrajectoryPublishRequest request,
            IntegrationRequestContext identity) {
        var operation = observability.start(
                MirrorOperationAuditEvent.Operation.CORPUS_TRAJECTORY_PUBLISH,
                identity,
                request == null ? "" : request.trajectoryId(),
                request == null ? "" : request.capabilityRef().id(),
                request == null
                        ? "" : request.corpusPublicationRef().fingerprint());
        try {
            CapabilitySnapshot.Scope scope = requireIdentity(identity);
            CapabilityCorpusTrajectoryPublishRequest exact =
                    Objects.requireNonNull(request, "request");
            String commandFingerprint =
                    integrity.trajectoryCommandFingerprint(exact);
            Optional<CapabilityCorpusTrajectoryPublication> existing =
                    findTrajectory(
                            scope, exact.trajectoryId(), exact.revision(), identity);
            if (existing.isPresent()) {
                CapabilityCorpusTrajectoryPublication stored = existing.get();
                if (!stored.sourceCommandFingerprint().equals(
                        commandFingerprint)) {
                    throw conflict(
                            identity,
                            "RG.MIRROR.CORPUS_TRAJECTORY_CONFLICT",
                            "The trajectory revision is already bound to another command.");
                }
                operation.succeeded(stored.trajectoryFingerprint());
                return stored;
            }
            requireHead(exact, scope, identity);
            CapabilityCorpusPublication publication = exactPublication(
                    exact, scope, identity);
            CapabilityCorpusRevision revision = exactRevision(
                    publication, exact.capabilityRef(), scope, identity);
            CapabilityCorpusGovernancePolicyProvider.GovernancePolicy
                    corpusPolicy = currentCorpusPolicy(
                    scope, exact.capabilityRef(), identity);
            if (!corpusPolicy.publicationPolicyRef().equals(
                    publication.publicationPolicyRef())) {
                throw conflict(
                        identity,
                        "RG.MIRROR.CORPUS_TRAJECTORY_POLICY_DRIFTED",
                        "The corpus publication no longer matches current policy.");
            }
            if (!corpusPolicy.mayPublish(identity)) {
                throw forbidden(
                        identity,
                        "RG.MIRROR.CORPUS_TRAJECTORY_PUBLISH_FORBIDDEN",
                        "The authenticated actor is not an authorized corpus publisher.");
            }
            CapabilityRetryPolicyProvider.RetryPolicy retryPolicy =
                    currentRetryPolicy(
                            scope, exact.capabilityRef(), identity);
            if (!retryPolicy.policyRef().equals(exact.retryPolicyRef())) {
                throw conflict(
                        identity,
                        "RG.MIRROR.CORPUS_TRAJECTORY_RETRY_POLICY_DRIFTED",
                        "The requested retry policy is not the current generation.");
            }
            if (exact.attempts().size() > retryPolicy.maximumAttempts()) {
                throw conflict(
                        identity,
                        "RG.MIRROR.CORPUS_TRAJECTORY_ATTEMPT_LIMIT_EXCEEDED",
                        "The recorded sequence exceeds the current retry policy.");
            }
            Instant now = clock.instant();
            requireSourceAuthority(identity);
            Map<MirrorArtifactRef, CapabilityCorpusRevision.SourceObservation>
                    members = corpusMembers(revision);
            String requestFingerprint = "";
            String traceId = "";
            long previousSequence = -1;
            Instant previousOccurrence = null;
            Instant usableUntil = earliest(
                    publication.usableUntil(), revision.usableUntil());
            Set<String> spanIds = new HashSet<>();
            for (int index = 0; index < exact.attempts().size(); index++) {
                CapabilityCorpusTrajectoryPublishRequest.AttemptSource
                        attempt = exact.attempts().get(index);
                CapabilityCorpusRevision.SourceObservation member =
                        members.get(attempt.observationRef());
                if (member == null
                        || !member.admissionRef().equals(
                        attempt.admissionRef())) {
                    throw conflict(
                            identity,
                            "RG.MIRROR.CORPUS_TRAJECTORY_SOURCE_NOT_PUBLISHED",
                            "Every attempt must belong to the exact published corpus revision.");
                }
                CapabilityObservationRepository.StoredObservation stored =
                        exactObservation(
                                scope, exact.capabilityRef(), attempt, identity);
                CapabilityObservationEnvelope.Material material =
                        stored.envelope().material();
                requireGrant(material.dataUseGrant(), now, identity);
                verifySource(stored, corpusPolicy, now, identity);
                String currentRequest =
                        material.request().payloadRef().fingerprint();
                if (requestFingerprint.isEmpty()) {
                    requestFingerprint = currentRequest;
                    traceId = material.trace().traceId();
                } else if (!requestFingerprint.equals(currentRequest)) {
                    throw conflict(
                            identity,
                            "RG.MIRROR.CORPUS_TRAJECTORY_REQUEST_CHANGED",
                            "All retry attempts must use one canonical request.");
                }
                if (!traceId.equals(material.trace().traceId())
                        || !spanIds.add(material.trace().spanId())
                        || material.trace().sequence() <= previousSequence
                        || previousOccurrence != null
                        && material.occurredAt().isBefore(
                        previousOccurrence)) {
                    throw conflict(
                            identity,
                            "RG.MIRROR.CORPUS_TRAJECTORY_ORDER_INVALID",
                            "Attempts must use one trace with distinct ordered spans.");
                }
                boolean finalAttempt = index == exact.attempts().size() - 1;
                if (!finalAttempt && (material.error() == null
                        || !retryPolicy.permits(material.error()))) {
                    throw conflict(
                            identity,
                            "RG.MIRROR.CORPUS_TRAJECTORY_RETRY_INVALID",
                            "Every intermediate attempt must be a policy-permitted retryable error.");
                }
                if (finalAttempt && material.error() != null
                        && material.error().retryable()) {
                    throw conflict(
                            identity,
                            "RG.MIRROR.CORPUS_TRAJECTORY_TERMINAL_INVALID",
                            "The final recorded attempt must be terminal.");
                }
                previousSequence = material.trace().sequence();
                previousOccurrence = material.occurredAt();
                usableUntil = earliest(
                        usableUntil,
                        stored.admission().usableUntil(),
                        material.dataUseGrant().expiresAt(),
                        material.request().retentionUntil(),
                        material.response() == null ? usableUntil
                                : material.response().retentionUntil());
            }
            if (!usableUntil.isAfter(now)) {
                throw conflict(
                        identity,
                        "RG.MIRROR.CORPUS_TRAJECTORY_EXPIRED",
                        "The trajectory has no remaining authorized serving horizon.");
            }
            CapabilityCorpusTrajectoryPublication candidate =
                    integrity.sealTrajectory(
                            new CapabilityCorpusTrajectoryPublication(
                                    "",
                                    ZERO_FINGERPRINT,
                                    commandFingerprint,
                                    scope,
                                    exact.trajectoryId(),
                                    exact.revision(),
                                    exact.expectedPredecessorRef(),
                                    exact.capabilityRef(),
                                    publication.artifactRef(),
                                    revision.artifactRef(),
                                    corpusPolicy.publicationPolicyRef(),
                                    retryPolicy.policyRef(),
                                    requestFingerprint,
                                    exact.attempts(),
                                    exact.reviewTicketRef(),
                                    exact.reasonCode(),
                                    identity.actorId(),
                                    now,
                                    usableUntil));
            CapabilityCorpusTrajectoryPublication stored =
                    appendTrajectory(candidate, identity);
            operation.succeeded(stored.trajectoryFingerprint());
            return stored;
        } catch (RuntimeException failure) {
            throw operation.failed(failure);
        }
    }

    private void requireHead(
            CapabilityCorpusTrajectoryPublishRequest request,
            CapabilitySnapshot.Scope scope,
            IntegrationRequestContext identity) {
        Optional<CapabilityCorpusTrajectoryPublication> latest =
                findLatestTrajectory(
                        scope, request.trajectoryId(), identity);
        if (latest.isEmpty() && request.expectedPredecessorRef() != null
                || latest.isPresent() && !latest.get().artifactRef().equals(
                request.expectedPredecessorRef())) {
            throw conflict(
                    identity,
                    "RG.MIRROR.CORPUS_TRAJECTORY_HEAD_CONFLICT",
                    "The expected trajectory publication head is stale.");
        }
    }

    private CapabilityCorpusPublication exactPublication(
            CapabilityCorpusTrajectoryPublishRequest request,
            CapabilitySnapshot.Scope scope,
            IntegrationRequestContext identity) {
        try {
            MirrorArtifactRef ref = request.corpusPublicationRef();
            CapabilityCorpusPublication exact = corpora.findPublication(
                            scope, ref.id(), ref.revision())
                    .filter(value -> value.artifactRef().equals(ref))
                    .orElseThrow(() -> notFound(
                            identity,
                            "RG.MIRROR.CORPUS_PUBLICATION_NOT_FOUND",
                            "The exact corpus publication was not found."));
            CapabilityCorpusPublication latest =
                    corpora.findLatestPublication(scope, ref.id())
                            .orElseThrow(() -> notFound(
                                    identity,
                                    "RG.MIRROR.CORPUS_PUBLICATION_NOT_FOUND",
                                    "The exact corpus publication was not found."));
            if (!latest.artifactRef().equals(exact.artifactRef())) {
                throw conflict(
                        identity,
                        "RG.MIRROR.CORPUS_PUBLICATION_STALE",
                        "A trajectory can bind only the current corpus publication.");
            }
            return exact;
        } catch (IntegrationProblemException expected) {
            throw expected;
        } catch (RuntimeException unavailable) {
            throw unavailable(
                    identity,
                    "RG.MIRROR.CORPUS_STORE_UNAVAILABLE",
                    "The capability corpus store is unavailable.");
        }
    }

    private CapabilityCorpusRevision exactRevision(
            CapabilityCorpusPublication publication,
            MirrorArtifactRef capabilityRef,
            CapabilitySnapshot.Scope scope,
            IntegrationRequestContext identity) {
        try {
            MirrorArtifactRef ref = publication.corpusRevisionRef();
            CapabilityCorpusRevision revision = corpora.findRevision(
                            scope, ref.id(), ref.revision())
                    .filter(value -> value.artifactRef().equals(ref))
                    .orElseThrow(() -> unavailable(
                            identity,
                            "RG.MIRROR.CORPUS_REVISION_INTEGRITY_INVALID",
                            "The published corpus revision is unavailable."));
            if (!scope.equals(revision.scope())
                    || !capabilityRef.equals(revision.capabilityRef())
                    || !publication.corpusId().equals(revision.corpusId())
                    || revision.riskSummary().eligibility()
                    != CapabilityCorpusRevision.Eligibility.ELIGIBLE) {
                throw conflict(
                        identity,
                        "RG.MIRROR.CORPUS_TRAJECTORY_CORPUS_INELIGIBLE",
                        "The published corpus revision is not eligible.");
            }
            return revision;
        } catch (IntegrationProblemException expected) {
            throw expected;
        } catch (RuntimeException unavailable) {
            throw unavailable(
                    identity,
                    "RG.MIRROR.CORPUS_STORE_UNAVAILABLE",
                    "The capability corpus store is unavailable.");
        }
    }

    private static Map<MirrorArtifactRef,
            CapabilityCorpusRevision.SourceObservation> corpusMembers(
            CapabilityCorpusRevision revision) {
        Map<MirrorArtifactRef, CapabilityCorpusRevision.SourceObservation>
                members = new HashMap<>();
        for (CapabilityCorpusRevision.SourceObservation source
                : revision.sources()) {
            members.put(source.observationRef(), source);
        }
        return Map.copyOf(members);
    }

    private CapabilityObservationRepository.StoredObservation exactObservation(
            CapabilitySnapshot.Scope scope,
            MirrorArtifactRef capabilityRef,
            CapabilityCorpusTrajectoryPublishRequest.AttemptSource attempt,
            IntegrationRequestContext identity) {
        try {
            CapabilityObservationRepository.StoredObservation source =
                    observations.find(scope, attempt.observationRef().id())
                            .orElseThrow(() -> notFound(
                                    identity,
                                    "RG.MIRROR.CORPUS_TRAJECTORY_SOURCE_NOT_FOUND",
                                    "An exact trajectory source was not found."));
            if (!source.envelope().artifactRef().equals(
                    attempt.observationRef())
                    || !source.admission().artifactRef().equals(
                    attempt.admissionRef())
                    || source.admission().state()
                    != CapabilityObservationAdmission.State.ADMITTED
                    || !capabilityRef.equals(
                    source.envelope().material().capabilityRef())) {
                throw notFound(
                        identity,
                        "RG.MIRROR.CORPUS_TRAJECTORY_SOURCE_NOT_FOUND",
                        "An exact trajectory source was not found.");
            }
            return source;
        } catch (IntegrationProblemException expected) {
            throw expected;
        } catch (RuntimeException unavailable) {
            throw unavailable(
                    identity,
                    "RG.MIRROR.OBSERVATION_STORE_UNAVAILABLE",
                    "The capability observation store is unavailable.");
        }
    }

    private CapabilityCorpusGovernancePolicyProvider.GovernancePolicy
            currentCorpusPolicy(
            CapabilitySnapshot.Scope scope,
            MirrorArtifactRef capabilityRef,
            IntegrationRequestContext identity) {
        try {
            if (!corpusPolicies.available()) {
                throw unavailable(
                        identity,
                        "RG.MIRROR.CORPUS_POLICY_UNAVAILABLE",
                        "The corpus governance policy is unavailable.");
            }
            return corpusPolicies.resolve(scope, capabilityRef)
                    .filter(policy -> scope.equals(policy.scope())
                            && capabilityRef.equals(policy.capabilityRef()))
                    .orElseThrow(() -> conflict(
                            identity,
                            "RG.MIRROR.CORPUS_POLICY_NOT_FOUND",
                            "No current corpus policy covers the exact capability."));
        } catch (IntegrationProblemException expected) {
            throw expected;
        } catch (RuntimeException unavailable) {
            throw unavailable(
                    identity,
                    "RG.MIRROR.CORPUS_POLICY_UNAVAILABLE",
                    "The corpus governance policy is unavailable.");
        }
    }

    private CapabilityRetryPolicyProvider.RetryPolicy currentRetryPolicy(
            CapabilitySnapshot.Scope scope,
            MirrorArtifactRef capabilityRef,
            IntegrationRequestContext identity) {
        try {
            if (!retryPolicies.available()) {
                throw unavailable(
                        identity,
                        "RG.MIRROR.RETRY_POLICY_UNAVAILABLE",
                        "The retry policy authority is unavailable.");
            }
            return retryPolicies.resolve(scope, capabilityRef)
                    .filter(policy -> scope.equals(policy.scope())
                            && capabilityRef.equals(policy.capabilityRef()))
                    .orElseThrow(() -> conflict(
                            identity,
                            "RG.MIRROR.RETRY_POLICY_NOT_FOUND",
                            "No current retry policy covers the exact capability."));
        } catch (IntegrationProblemException expected) {
            throw expected;
        } catch (RuntimeException unavailable) {
            throw unavailable(
                    identity,
                    "RG.MIRROR.RETRY_POLICY_UNAVAILABLE",
                    "The retry policy authority is unavailable.");
        }
    }

    private static void requireGrant(
            CapabilityObservationEnvelope.DataUseGrant grant,
            Instant now,
            IntegrationRequestContext identity) {
        if (!grant.activeAt(now)
                || !grant.allowedUses().contains(
                CapabilityObservationEnvelope.AllowedUse.EXACT_REPLAY)
                || !grant.allowedUses().contains(
                CapabilityObservationEnvelope.AllowedUse.TRAJECTORY_MODELING)) {
            throw conflict(
                    identity,
                    "RG.MIRROR.CORPUS_TRAJECTORY_USE_NOT_AUTHORIZED",
                    "Every source grant must authorize exact replay and trajectory modeling.");
        }
    }

    private void requireSourceAuthority(
            IntegrationRequestContext identity) {
        try {
            if (!sourceVerifier.available()) {
                throw unavailable(
                        identity,
                        "RG.MIRROR.CORPUS_SOURCE_AUTHORITY_UNAVAILABLE",
                        "The external corpus source authority is unavailable.");
            }
        } catch (IntegrationProblemException expected) {
            throw expected;
        } catch (RuntimeException unavailable) {
            throw unavailable(
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
            if (result == null || result.outcome()
                    == CapabilityCorpusSourceVerifier.Outcome.UNAVAILABLE) {
                throw unavailable(
                        identity,
                        "RG.MIRROR.CORPUS_SOURCE_AUTHORITY_UNAVAILABLE",
                        "The external corpus source authority is unavailable.");
            }
            if (result.outcome()
                    == CapabilityCorpusSourceVerifier.Outcome.REJECTED) {
                throw conflict(
                        identity,
                        "RG.MIRROR.CORPUS_TRAJECTORY_SOURCE_REJECTED",
                        "A trajectory source is no longer eligible.");
            }
        } catch (IntegrationProblemException expected) {
            throw expected;
        } catch (RuntimeException unavailable) {
            throw unavailable(
                    identity,
                    "RG.MIRROR.CORPUS_SOURCE_AUTHORITY_UNAVAILABLE",
                    "The external corpus source authority is unavailable.");
        }
    }

    private Optional<CapabilityCorpusTrajectoryPublication> findTrajectory(
            CapabilitySnapshot.Scope scope,
            String trajectoryId,
            long revision,
            IntegrationRequestContext identity) {
        try {
            return trajectories.find(scope, trajectoryId, revision);
        } catch (RuntimeException unavailable) {
            throw trajectoryStoreUnavailable(identity);
        }
    }

    private Optional<CapabilityCorpusTrajectoryPublication> findLatestTrajectory(
            CapabilitySnapshot.Scope scope,
            String trajectoryId,
            IntegrationRequestContext identity) {
        try {
            return trajectories.findLatest(scope, trajectoryId);
        } catch (RuntimeException unavailable) {
            throw trajectoryStoreUnavailable(identity);
        }
    }

    private CapabilityCorpusTrajectoryPublication appendTrajectory(
            CapabilityCorpusTrajectoryPublication publication,
            IntegrationRequestContext identity) {
        try {
            return trajectories.append(publication);
        } catch (CapabilityCorpusTrajectoryRepository.Violation rejected) {
            if (rejected.reason()
                    == CapabilityCorpusTrajectoryRepository.Reason.LINEAGE_CONFLICT
                    || rejected.reason()
                    == CapabilityCorpusTrajectoryRepository.Reason.CONTENT_CONFLICT) {
                throw conflict(
                        identity,
                        "RG.MIRROR.CORPUS_TRAJECTORY_CONFLICT",
                        "The trajectory publication lineage changed concurrently.");
            }
            throw trajectoryStoreUnavailable(identity);
        } catch (RuntimeException unavailable) {
            throw trajectoryStoreUnavailable(identity);
        }
    }

    private static CapabilitySnapshot.Scope requireIdentity(
            IntegrationRequestContext identity) {
        IntegrationRequestContext exact =
                Objects.requireNonNull(identity, "identity");
        exact.requireComplete();
        if (!CapabilityCorpusGovernanceService.AUTHORIZED_PURPOSE.equals(
                exact.purpose())) {
            throw forbidden(
                    exact,
                    "RG.MIRROR.CORPUS_GOVERNANCE_PURPOSE_REQUIRED",
                    "Trajectory governance requires its dedicated purpose.");
        }
        if (exact.projectId().isBlank() || exact.region().isBlank()) {
            throw badRequest(
                    exact,
                    "RG.MIRROR.CORPUS_SCOPE_INCOMPLETE",
                    "Trajectory governance requires complete enterprise scope.");
        }
        if (!("test".equalsIgnoreCase(exact.environmentId())
                || "staging".equalsIgnoreCase(exact.environmentId()))) {
            throw forbidden(
                    exact,
                    "RG.MIRROR.CORPUS_ENVIRONMENT_FORBIDDEN",
                    "Trajectory governance is restricted to test and staging.");
        }
        return new CapabilitySnapshot.Scope(
                exact.tenantId(),
                exact.organizationId(),
                exact.projectId(),
                exact.environmentId(),
                exact.region());
    }

    private static Instant earliest(Instant first, Instant... rest) {
        Instant result = Objects.requireNonNull(first, "first");
        for (Instant candidate : rest) {
            if (Objects.requireNonNull(candidate, "candidate").isBefore(result)) {
                result = candidate;
            }
        }
        return result;
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

    private static IntegrationProblemException unavailable(
            IntegrationRequestContext identity, String code, String title) {
        return new IntegrationProblemException(
                IntegrationProblem.serviceUnavailable(
                        code, title, identity.correlationId(), Map.of()));
    }

    private static IntegrationProblemException trajectoryStoreUnavailable(
            IntegrationRequestContext identity) {
        return unavailable(
                identity,
                "RG.MIRROR.CORPUS_TRAJECTORY_STORE_UNAVAILABLE",
                "The capability corpus trajectory store is unavailable.");
    }
}
