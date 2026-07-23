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
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Protected application boundary for owner-reviewed recorded-cluster publication.
 *
 * <p>The service never clusters payloads in the control plane. It resolves one exact validation
 * from the trusted data-plane authority, recomputes its content address, and proves current corpus,
 * policy, source, grant, retention, holdout, confidence, and identity-projection invariants before
 * appending a payload-free publication. Exact command retries are recovered before mutable
 * authorities are consulted.</p>
 */
@Service
@Profile("!production & (test | staging)")
@ConditionalOnProperty(
        prefix = "gateway.testing.mirror",
        name = "enabled",
        havingValue = "true")
public class CapabilityCorpusClusterGovernanceService {
    private static final String ZERO_FINGERPRINT =
            "sha256:" + "0".repeat(64);

    private final CapabilityObservationRepository observations;
    private final CapabilityCorpusRepository corpora;
    private final CapabilityCorpusClusterRepository clusters;
    private final CapabilityCorpusGovernancePolicyProvider corpusPolicies;
    private final CapabilityCorpusClusterPolicyProvider clusterPolicies;
    private final CapabilityCorpusClusterValidationAuthority validations;
    private final CapabilityCorpusSourceVerifier sourceVerifier;
    private final CapabilityCorpusIntegrity integrity;
    private final MirrorOperationObservability observability;
    private final Clock clock;

    /**
     * Creates the protected cluster publisher using the server UTC clock.
     *
     * @param observations exact observation/admission store
     * @param corpora immutable corpus revision/publication store
     * @param clusters append-only cluster publication store
     * @param corpusPolicies current operator-owned corpus policy authority
     * @param clusterPolicies current operator-owned cluster policy authority
     * @param validations trusted data-plane validation authority
     * @param sourceVerifier external source lifecycle authority
     * @param integrity canonical command and artifact integrity boundary
     * @param observability mandatory payload-free audit and metrics
     */
    @Autowired
    public CapabilityCorpusClusterGovernanceService(
            CapabilityObservationRepository observations,
            CapabilityCorpusRepository corpora,
            CapabilityCorpusClusterRepository clusters,
            CapabilityCorpusGovernancePolicyProvider corpusPolicies,
            CapabilityCorpusClusterPolicyProvider clusterPolicies,
            CapabilityCorpusClusterValidationAuthority validations,
            CapabilityCorpusSourceVerifier sourceVerifier,
            CapabilityCorpusIntegrity integrity,
            MirrorOperationObservability observability) {
        this(observations, corpora, clusters, corpusPolicies, clusterPolicies,
                validations, sourceVerifier, integrity, observability,
                Clock.systemUTC());
    }

    /**
     * Full constructor for deterministic authority, expiry, and lineage tests.
     *
     * @param observations exact observation/admission store
     * @param corpora immutable corpus revision/publication store
     * @param clusters append-only cluster publication store
     * @param corpusPolicies current operator-owned corpus policy authority
     * @param clusterPolicies current operator-owned cluster policy authority
     * @param validations trusted data-plane validation authority
     * @param sourceVerifier external source lifecycle authority
     * @param integrity canonical command and artifact integrity boundary
     * @param observability mandatory payload-free audit and metrics
     * @param clock trusted governance clock
     */
    public CapabilityCorpusClusterGovernanceService(
            CapabilityObservationRepository observations,
            CapabilityCorpusRepository corpora,
            CapabilityCorpusClusterRepository clusters,
            CapabilityCorpusGovernancePolicyProvider corpusPolicies,
            CapabilityCorpusClusterPolicyProvider clusterPolicies,
            CapabilityCorpusClusterValidationAuthority validations,
            CapabilityCorpusSourceVerifier sourceVerifier,
            CapabilityCorpusIntegrity integrity,
            MirrorOperationObservability observability,
            Clock clock) {
        this.observations = Objects.requireNonNull(observations, "observations");
        this.corpora = Objects.requireNonNull(corpora, "corpora");
        this.clusters = Objects.requireNonNull(clusters, "clusters");
        this.corpusPolicies = Objects.requireNonNull(
                corpusPolicies, "corpusPolicies");
        this.clusterPolicies = Objects.requireNonNull(
                clusterPolicies, "clusterPolicies");
        this.validations = Objects.requireNonNull(validations, "validations");
        this.sourceVerifier = Objects.requireNonNull(
                sourceVerifier, "sourceVerifier");
        this.integrity = Objects.requireNonNull(integrity, "integrity");
        this.observability = Objects.requireNonNull(
                observability, "observability");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Publishes one exact, current, owner-reviewed recorded cluster.
     *
     * @param request payload-free cluster publication command
     * @param identity authenticated corpus publisher
     * @return committed or idempotently recovered cluster publication
     */
    @Transactional
    public CapabilityCorpusClusterPublication publish(
            CapabilityCorpusClusterPublishRequest request,
            IntegrationRequestContext identity) {
        var operation = observability.start(
                MirrorOperationAuditEvent.Operation.CORPUS_CLUSTER_PUBLISH,
                identity,
                request == null ? "" : request.clusterId(),
                request == null ? "" : request.capabilityRef().id(),
                request == null
                        ? "" : request.corpusPublicationRef().fingerprint());
        try {
            CapabilitySnapshot.Scope scope = requireIdentity(identity);
            CapabilityCorpusClusterPublishRequest exact =
                    Objects.requireNonNull(request, "request");
            String commandFingerprint =
                    integrity.clusterCommandFingerprint(exact);
            Optional<CapabilityCorpusClusterPublication> existing = findCluster(
                    scope, exact.clusterId(), exact.revision(), identity);
            if (existing.isPresent()) {
                CapabilityCorpusClusterPublication stored = existing.get();
                if (!stored.sourceCommandFingerprint().equals(
                        commandFingerprint)) {
                    throw conflict(
                            identity,
                            "RG.MIRROR.CORPUS_CLUSTER_CONFLICT",
                            "The cluster revision is already bound to another command.");
                }
                operation.succeeded(stored.clusterFingerprint());
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
                        "RG.MIRROR.CORPUS_CLUSTER_POLICY_DRIFTED",
                        "The corpus publication no longer matches current policy.");
            }
            CapabilityCorpusClusterPolicyProvider.ClusterPolicy clusterPolicy =
                    currentClusterPolicy(
                            scope, exact.capabilityRef(), identity);
            if (!clusterPolicy.policyRef().equals(exact.clusterPolicyRef())) {
                throw conflict(
                        identity,
                        "RG.MIRROR.CORPUS_CLUSTER_POLICY_DRIFTED",
                        "The requested cluster policy is not the current generation.");
            }
            if (!corpusPolicy.mayPublish(identity)
                    || !clusterPolicy.mayPublish(identity)) {
                throw forbidden(
                        identity,
                        "RG.MIRROR.CORPUS_CLUSTER_PUBLISH_FORBIDDEN",
                        "The authenticated actor is not an authorized cluster publisher.");
            }
            CapabilityCorpusClusterValidation validation = exactValidation(
                    exact.validationRef(), scope, identity);
            requireValidationBinding(
                    validation, exact, publication, revision, identity);
            requireThresholds(validation, clusterPolicy, identity);
            if (!clusterPolicy.permits(validation)) {
                throw conflict(
                        identity,
                        "RG.MIRROR.CORPUS_CLUSTER_PATH_POLICY_REJECTED",
                        "The owner policy rejects the validated match or projection paths.");
            }
            Instant now = clock.instant();
            if (!validation.expiresAt().isAfter(now)) {
                throw conflict(
                        identity,
                        "RG.MIRROR.CORPUS_CLUSTER_VALIDATION_EXPIRED",
                        "The external cluster validation has expired.");
            }
            requireSourceAuthority(identity);
            Map<MirrorArtifactRef, CapabilityCorpusRevision.SourceObservation>
                    corpusMembers = corpusMembers(revision);
            Instant usableUntil = earliest(
                    publication.usableUntil(),
                    revision.usableUntil(),
                    validation.expiresAt(),
                    now.plus(clusterPolicy.maximumUsableHorizon()));
            MirrorArtifactRef responseSchemaRef = null;
            for (CapabilityCorpusClusterValidation.SourceCoordinate source
                    : validation.members()) {
                CapabilityCorpusRevision.SourceObservation member =
                        corpusMembers.get(source.observationRef());
                if (member == null
                        || !member.admissionRef().equals(
                        source.admissionRef())) {
                    throw conflict(
                            identity,
                            "RG.MIRROR.CORPUS_CLUSTER_SOURCE_NOT_PUBLISHED",
                            "Every cluster member must belong to the exact published corpus.");
                }
                CapabilityObservationRepository.StoredObservation stored =
                        exactObservation(
                                scope, exact.capabilityRef(), source, identity);
                CapabilityObservationEnvelope.Material material =
                        stored.envelope().material();
                requireGrant(material.dataUseGrant(), now, identity);
                verifySource(stored, corpusPolicy, now, identity);
                if (material.response() == null
                        || member.responsePayloadRef() == null
                        || member.responseSchemaRef() == null) {
                    throw conflict(
                            identity,
                            "RG.MIRROR.CORPUS_CLUSTER_RESPONSE_REQUIRED",
                            "Recorded cluster v1 supports successful response members only.");
                }
                if (responseSchemaRef == null) {
                    responseSchemaRef = member.responseSchemaRef();
                } else if (!responseSchemaRef.equals(
                        member.responseSchemaRef())) {
                    throw conflict(
                            identity,
                            "RG.MIRROR.CORPUS_CLUSTER_SCHEMA_MISMATCH",
                            "Every cluster member must use one exact response schema.");
                }
                usableUntil = earliest(
                        usableUntil,
                        member.usableUntil(),
                        stored.admission().usableUntil(),
                        material.dataUseGrant().expiresAt(),
                        material.request().retentionUntil(),
                        material.response().retentionUntil());
            }
            if (!usableUntil.isAfter(now)) {
                throw conflict(
                        identity,
                        "RG.MIRROR.CORPUS_CLUSTER_EXPIRED",
                        "The cluster has no remaining authorized serving horizon.");
            }
            CapabilityCorpusClusterPublication candidate =
                    integrity.sealCluster(
                            new CapabilityCorpusClusterPublication(
                                    "",
                                    ZERO_FINGERPRINT,
                                    commandFingerprint,
                                    scope,
                                    exact.clusterId(),
                                    exact.revision(),
                                    exact.expectedPredecessorRef(),
                                    exact.capabilityRef(),
                                    publication.artifactRef(),
                                    revision.artifactRef(),
                                    corpusPolicy.publicationPolicyRef(),
                                    clusterPolicy.policyRef(),
                                    validation.artifactRef(),
                                    validation.representativeSource(),
                                    validation.members(),
                                    validation.matchRequestPointers(),
                                    validation.identityMode(),
                                    validation.identityProjections(),
                                    validation.distinctIdentityCount(),
                                    validation.holdout(),
                                    validation.confidence(),
                                    exact.reviewTicketRef(),
                                    exact.reasonCode(),
                                    identity.actorId(),
                                    now,
                                    usableUntil));
            CapabilityCorpusClusterPublication stored =
                    appendCluster(candidate, identity);
            operation.succeeded(stored.clusterFingerprint());
            return stored;
        } catch (RuntimeException failure) {
            throw operation.failed(failure);
        }
    }

    private void requireHead(
            CapabilityCorpusClusterPublishRequest request,
            CapabilitySnapshot.Scope scope,
            IntegrationRequestContext identity) {
        Optional<CapabilityCorpusClusterPublication> latest =
                findLatestCluster(scope, request.clusterId(), identity);
        if (latest.isEmpty() && request.expectedPredecessorRef() != null
                || latest.isPresent() && !latest.get().artifactRef().equals(
                request.expectedPredecessorRef())
                || latest.isPresent() && !latest.get().capabilityRef().equals(
                request.capabilityRef())) {
            throw conflict(
                    identity,
                    "RG.MIRROR.CORPUS_CLUSTER_HEAD_CONFLICT",
                    "The expected cluster publication head is stale or belongs to another capability.");
        }
    }

    private CapabilityCorpusPublication exactPublication(
            CapabilityCorpusClusterPublishRequest request,
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
                        "A cluster can bind only the current corpus publication.");
            }
            return exact;
        } catch (IntegrationProblemException expected) {
            throw expected;
        } catch (RuntimeException unavailable) {
            throw corpusStoreUnavailable(identity);
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
                        "RG.MIRROR.CORPUS_CLUSTER_CORPUS_INELIGIBLE",
                        "The published corpus revision is not eligible.");
            }
            return revision;
        } catch (IntegrationProblemException expected) {
            throw expected;
        } catch (RuntimeException unavailable) {
            throw corpusStoreUnavailable(identity);
        }
    }

    private CapabilityCorpusClusterValidation exactValidation(
            MirrorArtifactRef validationRef,
            CapabilitySnapshot.Scope scope,
            IntegrationRequestContext identity) {
        try {
            if (!validations.available()) {
                throw validationUnavailable(identity);
            }
            CapabilityCorpusClusterValidation validation = validations.resolve(
                            scope, validationRef)
                    .filter(value -> scope.equals(value.scope())
                            && value.artifactRef().equals(validationRef))
                    .orElseThrow(() -> notFound(
                            identity,
                            "RG.MIRROR.CORPUS_CLUSTER_VALIDATION_NOT_FOUND",
                            "The exact current cluster validation was not found."));
            if (!integrity.clusterValidationVerified(validation)) {
                throw unavailable(
                        identity,
                        "RG.MIRROR.CORPUS_CLUSTER_VALIDATION_INTEGRITY_INVALID",
                        "The cluster validation content address is invalid.");
            }
            return validation;
        } catch (IntegrationProblemException expected) {
            throw expected;
        } catch (RuntimeException unavailable) {
            throw validationUnavailable(identity);
        }
    }

    private static void requireValidationBinding(
            CapabilityCorpusClusterValidation validation,
            CapabilityCorpusClusterPublishRequest request,
            CapabilityCorpusPublication publication,
            CapabilityCorpusRevision revision,
            IntegrationRequestContext identity) {
        if (!validation.capabilityRef().equals(request.capabilityRef())
                || !validation.corpusPublicationRef().equals(
                publication.artifactRef())
                || !validation.corpusRevisionRef().equals(
                revision.artifactRef())) {
            throw conflict(
                    identity,
                    "RG.MIRROR.CORPUS_CLUSTER_VALIDATION_BINDING_INVALID",
                    "The validation does not bind the exact capability and corpus.");
        }
    }

    private static void requireThresholds(
            CapabilityCorpusClusterValidation validation,
            CapabilityCorpusClusterPolicyProvider.ClusterPolicy policy,
            IntegrationRequestContext identity) {
        if (validation.members().size() < policy.minimumSupport()
                || validation.distinctIdentityCount()
                < policy.minimumDistinctIdentities()
                || validation.holdout().acceptedCount()
                < policy.minimumHoldoutAccepted()
                || validation.holdout().falsePositiveBasisPoints()
                > policy.maximumFalsePositiveBasisPoints()
                || validation.confidence().lowerBound()
                < policy.minimumConfidenceLowerBound()) {
            throw conflict(
                    identity,
                    "RG.MIRROR.CORPUS_CLUSTER_CONFIDENCE_REJECTED",
                    "The validation does not satisfy current cluster risk thresholds.");
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
            CapabilityCorpusClusterValidation.SourceCoordinate source,
            IntegrationRequestContext identity) {
        try {
            CapabilityObservationRepository.StoredObservation stored =
                    observations.find(scope, source.observationRef().id())
                            .orElseThrow(() -> notFound(
                                    identity,
                                    "RG.MIRROR.CORPUS_CLUSTER_SOURCE_NOT_FOUND",
                                    "An exact cluster source was not found."));
            if (!stored.envelope().artifactRef().equals(
                    source.observationRef())
                    || !stored.admission().artifactRef().equals(
                    source.admissionRef())
                    || stored.admission().state()
                    != CapabilityObservationAdmission.State.ADMITTED
                    || !capabilityRef.equals(
                    stored.envelope().material().capabilityRef())) {
                throw notFound(
                        identity,
                        "RG.MIRROR.CORPUS_CLUSTER_SOURCE_NOT_FOUND",
                        "An exact cluster source was not found.");
            }
            return stored;
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

    private CapabilityCorpusClusterPolicyProvider.ClusterPolicy
            currentClusterPolicy(
            CapabilitySnapshot.Scope scope,
            MirrorArtifactRef capabilityRef,
            IntegrationRequestContext identity) {
        try {
            if (!clusterPolicies.available()) {
                throw unavailable(
                        identity,
                        "RG.MIRROR.CORPUS_CLUSTER_POLICY_UNAVAILABLE",
                        "The cluster policy authority is unavailable.");
            }
            return clusterPolicies.resolve(scope, capabilityRef)
                    .filter(policy -> scope.equals(policy.scope())
                            && capabilityRef.equals(policy.capabilityRef()))
                    .orElseThrow(() -> conflict(
                            identity,
                            "RG.MIRROR.CORPUS_CLUSTER_POLICY_NOT_FOUND",
                            "No current cluster policy covers the exact capability."));
        } catch (IntegrationProblemException expected) {
            throw expected;
        } catch (RuntimeException unavailable) {
            throw unavailable(
                    identity,
                    "RG.MIRROR.CORPUS_CLUSTER_POLICY_UNAVAILABLE",
                    "The cluster policy authority is unavailable.");
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
                CapabilityObservationEnvelope.AllowedUse.CLUSTER_MODELING)) {
            throw conflict(
                    identity,
                    "RG.MIRROR.CORPUS_CLUSTER_USE_NOT_AUTHORIZED",
                    "Every source grant must authorize exact replay and cluster modeling.");
        }
    }

    private void requireSourceAuthority(IntegrationRequestContext identity) {
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
                        "RG.MIRROR.CORPUS_CLUSTER_SOURCE_REJECTED",
                        "A cluster source is no longer eligible.");
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

    private Optional<CapabilityCorpusClusterPublication> findCluster(
            CapabilitySnapshot.Scope scope,
            String clusterId,
            long revision,
            IntegrationRequestContext identity) {
        try {
            return clusters.find(scope, clusterId, revision);
        } catch (RuntimeException unavailable) {
            throw clusterStoreUnavailable(identity);
        }
    }

    private Optional<CapabilityCorpusClusterPublication> findLatestCluster(
            CapabilitySnapshot.Scope scope,
            String clusterId,
            IntegrationRequestContext identity) {
        try {
            return clusters.findLatest(scope, clusterId);
        } catch (RuntimeException unavailable) {
            throw clusterStoreUnavailable(identity);
        }
    }

    private CapabilityCorpusClusterPublication appendCluster(
            CapabilityCorpusClusterPublication publication,
            IntegrationRequestContext identity) {
        try {
            return clusters.append(publication);
        } catch (CapabilityCorpusClusterRepository.Violation rejected) {
            if (rejected.reason()
                    == CapabilityCorpusClusterRepository.Reason.LINEAGE_CONFLICT
                    || rejected.reason()
                    == CapabilityCorpusClusterRepository.Reason.CONTENT_CONFLICT) {
                throw conflict(
                        identity,
                        "RG.MIRROR.CORPUS_CLUSTER_CONFLICT",
                        "The cluster publication lineage changed concurrently.");
            }
            throw clusterStoreUnavailable(identity);
        } catch (RuntimeException unavailable) {
            throw clusterStoreUnavailable(identity);
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
                    "Cluster governance requires its dedicated purpose.");
        }
        if (exact.projectId().isBlank() || exact.region().isBlank()) {
            throw badRequest(
                    exact,
                    "RG.MIRROR.CORPUS_SCOPE_INCOMPLETE",
                    "Cluster governance requires complete enterprise scope.");
        }
        if (!("test".equalsIgnoreCase(exact.environmentId())
                || "staging".equalsIgnoreCase(exact.environmentId()))) {
            throw forbidden(
                    exact,
                    "RG.MIRROR.CORPUS_ENVIRONMENT_FORBIDDEN",
                    "Cluster governance is restricted to test and staging.");
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

    private static IntegrationProblemException corpusStoreUnavailable(
            IntegrationRequestContext identity) {
        return unavailable(
                identity,
                "RG.MIRROR.CORPUS_STORE_UNAVAILABLE",
                "The capability corpus store is unavailable.");
    }

    private static IntegrationProblemException clusterStoreUnavailable(
            IntegrationRequestContext identity) {
        return unavailable(
                identity,
                "RG.MIRROR.CORPUS_CLUSTER_STORE_UNAVAILABLE",
                "The capability corpus cluster store is unavailable.");
    }

    private static IntegrationProblemException validationUnavailable(
            IntegrationRequestContext identity) {
        return unavailable(
                identity,
                "RG.MIRROR.CORPUS_CLUSTER_VALIDATION_UNAVAILABLE",
                "The cluster validation authority is unavailable.");
    }
}
