package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.runtime.ResolvedCorpusPayloads;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Fail-closed serving boundary from reviewed corpus publications to exact mirror outcomes and
 * owner-approved retry trajectories.
 *
 * <p>The service revalidates current publication heads, operator governance and retry policies,
 * exact source lineage, data-use grants, retention, classification, region, tombstone state, and
 * response content addresses before constructing an in-memory execution snapshot. It never
 * persists or returns request/response payloads through an HTTP contract. Standalone duplicate
 * request fingerprints are collapsed only when their normalized outcomes are identical; retry
 * attempts remain an explicitly reviewed ordered trajectory. Any conflict rejects the entire
 * generation rather than introducing nondeterministic business behavior.</p>
 */
@Service
@Profile("!production & (test | staging)")
@ConditionalOnProperty(
        prefix = "gateway.testing.mirror",
        name = "enabled",
        havingValue = "true")
public class CapabilityCorpusServingService {
    private final CapabilityCorpusRepository corpora;
    private final CapabilityObservationRepository observations;
    private final CapabilityCorpusTrajectoryRepository trajectories;
    private final CapabilityCorpusGovernancePolicyProvider policies;
    private final CapabilityRetryPolicyProvider retryPolicies;
    private final CapabilityCorpusSourceVerifier sourceVerifier;
    private final CapabilityCorpusPayloadAuthority payloadAuthority;
    private final CapabilityCorpusIntegrity integrity;
    private final ObjectMapper mapper;
    private final Clock clock;

    /**
     * Creates the serving boundary with the server UTC clock.
     *
     * @param corpora append-only corpus revision and publication store
     * @param observations exact observation and admission store
     * @param trajectories append-only reviewed trajectory store
     * @param policies current operator-owned corpus policy
     * @param retryPolicies current operator-owned retry policy
     * @param sourceVerifier external deletion, proof, retention, and grant verifier
     * @param payloadAuthority regional short-lived sanitized payload authority
     * @param integrity corpus content-address and trace integrity helper
     * @param mapper canonical protocol mapper
     */
    @Autowired
    public CapabilityCorpusServingService(
            CapabilityCorpusRepository corpora,
            CapabilityObservationRepository observations,
            CapabilityCorpusTrajectoryRepository trajectories,
            CapabilityCorpusGovernancePolicyProvider policies,
            CapabilityRetryPolicyProvider retryPolicies,
            CapabilityCorpusSourceVerifier sourceVerifier,
            CapabilityCorpusPayloadAuthority payloadAuthority,
            CapabilityCorpusIntegrity integrity,
            ObjectMapper mapper) {
        this(corpora, observations, trajectories, policies, retryPolicies,
                sourceVerifier, payloadAuthority, integrity, mapper,
                Clock.systemUTC());
    }

    /**
     * Full constructor for deterministic lifecycle and expiry tests.
     *
     * @param corpora append-only corpus revision and publication store
     * @param observations exact observation and admission store
     * @param trajectories append-only reviewed trajectory store
     * @param policies current operator-owned corpus policy
     * @param retryPolicies current operator-owned retry policy
     * @param sourceVerifier external deletion, proof, retention, and grant verifier
     * @param payloadAuthority regional short-lived sanitized payload authority
     * @param integrity corpus content-address and trace integrity helper
     * @param mapper canonical protocol mapper
     * @param clock trusted materialization clock
     */
    public CapabilityCorpusServingService(
            CapabilityCorpusRepository corpora,
            CapabilityObservationRepository observations,
            CapabilityCorpusTrajectoryRepository trajectories,
            CapabilityCorpusGovernancePolicyProvider policies,
            CapabilityRetryPolicyProvider retryPolicies,
            CapabilityCorpusSourceVerifier sourceVerifier,
            CapabilityCorpusPayloadAuthority payloadAuthority,
            CapabilityCorpusIntegrity integrity,
            ObjectMapper mapper,
            Clock clock) {
        this.corpora = Objects.requireNonNull(corpora, "corpora");
        this.observations = Objects.requireNonNull(observations, "observations");
        this.trajectories = Objects.requireNonNull(trajectories, "trajectories");
        this.policies = Objects.requireNonNull(policies, "policies");
        this.retryPolicies = Objects.requireNonNull(
                retryPolicies, "retryPolicies");
        this.sourceVerifier = Objects.requireNonNull(sourceVerifier, "sourceVerifier");
        this.payloadAuthority = Objects.requireNonNull(payloadAuthority, "payloadAuthority");
        this.integrity = Objects.requireNonNull(integrity, "integrity");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Resolves all corpus publications selected by an immutable fixture revision.
     *
     * @param fixture exact fixture carrying reserved corpus bindings
     * @param scope authenticated complete enterprise scope
     * @param policy server-minted mirror execution policy
     * @param requiredUntil hard plan expiry that every source must cover
     * @param identity authenticated workload identity used only for authorization and errors
     * @return empty or fully revalidated capability-keyed exact and trajectory outcomes
     */
    public ResolvedCorpusPayloads resolve(
            FixtureBundle fixture,
            CapabilitySnapshot.Scope scope,
            MirrorPlan.ExecutionPolicy policy,
            Instant requiredUntil,
            IntegrationRequestContext identity) {
        Objects.requireNonNull(fixture, "fixture");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(requiredUntil, "requiredUntil");
        Objects.requireNonNull(identity, "identity");

        FixtureMirrorCorpusBindings bindings;
        FixtureMirrorTrajectoryBindings trajectoryBindings;
        try {
            bindings = FixtureMirrorCorpusBindings.from(fixture);
            trajectoryBindings = FixtureMirrorTrajectoryBindings.from(
                    fixture, bindings);
        } catch (IllegalArgumentException malformed) {
            throw badRequest(identity, "RG.MIRROR.CORPUS_BINDING_INVALID",
                    "Fixture mirror-corpus bindings are invalid.");
        }
        if (!bindings.configured()) {
            return ResolvedCorpusPayloads.empty();
        }
        Instant now = clock.instant();
        if (!requiredUntil.isAfter(now)) {
            throw conflict(identity, "RG.MIRROR.CORPUS_HORIZON_INVALID",
                    "Mirror corpus materialization requires a future plan horizon.");
        }
        requireAuthorities(identity, trajectoryBindings.configured());

        List<ResolvedCorpusPayloads.CapabilityCorpus> resolved =
                new ArrayList<>(bindings.publications().size());
        Map<MirrorArtifactRef,
                List<FixtureMirrorTrajectoryBindings.TrajectoryBinding>>
                trajectoriesByCapability = new LinkedHashMap<>();
        for (FixtureMirrorTrajectoryBindings.TrajectoryBinding trajectory
                : trajectoryBindings.trajectories()) {
            trajectoriesByCapability.computeIfAbsent(
                    trajectory.capabilityRef(), ignored -> new ArrayList<>())
                    .add(trajectory);
        }
        long totalPayloadBytes = 0;
        for (FixtureMirrorCorpusBindings.PublicationBinding binding
                : bindings.publications()) {
            ResolvedCapability value = resolveCapability(
                    binding,
                    trajectoriesByCapability.getOrDefault(
                            binding.capabilityRef(), List.of()),
                    scope, policy, now, requiredUntil, identity);
            totalPayloadBytes += value.payloadBytes();
            if (totalPayloadBytes > ResolvedCorpusPayloads.MAXIMUM_TOTAL_BYTES) {
                throw conflict(identity, "RG.MIRROR.CORPUS_PAYLOAD_BUDGET_EXCEEDED",
                        "Resolved corpus payloads exceed the whole-generation memory budget.");
            }
            resolved.add(value.corpus());
        }
        try {
            return ResolvedCorpusPayloads.of(resolved);
        } catch (IllegalArgumentException invalid) {
            throw conflict(identity, "RG.MIRROR.CORPUS_GENERATION_INVALID",
                    "Resolved corpus publications cannot form one deterministic generation.");
        }
    }

    /** @return whether all dynamic serving authorities are currently usable */
    public boolean ready() {
        try {
            return policies.available() && sourceVerifier.available()
                    && payloadAuthority.available();
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    /**
     * Reports whether exact serving plus the additional retry-policy authority are usable.
     *
     * @return true only while reviewed trajectories can be revalidated and materialized
     */
    public boolean trajectoryReady() {
        try {
            return ready() && retryPolicies.available();
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private ResolvedCapability resolveCapability(
            FixtureMirrorCorpusBindings.PublicationBinding binding,
            List<FixtureMirrorTrajectoryBindings.TrajectoryBinding>
                    trajectoryBindings,
            CapabilitySnapshot.Scope scope,
            MirrorPlan.ExecutionPolicy executionPolicy,
            Instant now,
            Instant requiredUntil,
            IntegrationRequestContext identity) {
        CapabilityCorpusPublication publication = exactPublication(
                binding, scope, identity);
        requireHorizon(publication.usableUntil(), requiredUntil, identity,
                "RG.MIRROR.CORPUS_PUBLICATION_EXPIRES_EARLY");
        CapabilityCorpusRevision revision = exactRevision(
                publication, binding.capabilityRef(), scope, identity);
        requireHorizon(revision.usableUntil(), requiredUntil, identity,
                "RG.MIRROR.CORPUS_REVISION_EXPIRES_EARLY");
        CapabilityCorpusGovernancePolicyProvider.GovernancePolicy governance =
                currentPolicy(scope, binding.capabilityRef(), identity);
        if (!governance.governancePolicyRef().equals(revision.governancePolicyRef())
                || !governance.publicationPolicyRef().equals(
                publication.publicationPolicyRef())) {
            throw conflict(identity, "RG.MIRROR.CORPUS_POLICY_DRIFT",
                    "Corpus publication no longer matches current operator policy.");
        }
        if (revision.riskSummary().eligibility()
                != CapabilityCorpusRevision.Eligibility.ELIGIBLE) {
            throw conflict(identity, "RG.MIRROR.CORPUS_PUBLICATION_INELIGIBLE",
                    "Published corpus revision is not eligible for serving.");
        }

        Map<MirrorArtifactRef, CapabilityCorpusRevision.SourceObservation>
                members = corpusMembers(revision);
        List<ResolvedCorpusPayloads.Trajectory> resolvedTrajectories =
                new ArrayList<>(trajectoryBindings.size());
        Set<MirrorArtifactRef> trajectorySources = new LinkedHashSet<>();
        long payloadBytes = 0;
        Instant usableUntil = earliest(
                publication.usableUntil(), revision.usableUntil());
        for (FixtureMirrorTrajectoryBindings.TrajectoryBinding
                trajectoryBinding : trajectoryBindings) {
            ResolvedTrajectory resolvedTrajectory = resolveTrajectory(
                    trajectoryBinding, publication, revision, governance,
                    members, executionPolicy, scope, now, requiredUntil, identity);
            for (MirrorArtifactRef source : resolvedTrajectory.sourceRefs()) {
                if (!trajectorySources.add(source)) {
                    throw conflict(
                            identity,
                            "RG.MIRROR.CORPUS_TRAJECTORY_SOURCE_REUSED",
                            "One observation cannot serve more than one bound trajectory.");
                }
            }
            resolvedTrajectories.add(resolvedTrajectory.trajectory());
            payloadBytes += resolvedTrajectory.payloadBytes();
            usableUntil = earliest(
                    usableUntil, resolvedTrajectory.usableUntil());
        }

        Map<String, SampleAccumulator> samples = new LinkedHashMap<>();
        for (CapabilityCorpusRevision.SourceObservation source : revision.sources()) {
            if (trajectorySources.contains(source.observationRef())) {
                continue;
            }
            CapabilityObservationRepository.StoredObservation stored =
                    exactObservation(scope, binding.capabilityRef(), source, identity);
            verifyRuntimePolicy(
                    stored, executionPolicy, now, requiredUntil, false, identity);
            verifyExternalSource(stored, governance, now, identity);
            SourceOutcome outcome = sourceOutcome(
                    publication, revision, stored, source, executionPolicy,
                    now, requiredUntil, false, List.of(), List.of(), identity);
            SampleAccumulator previous = samples.get(outcome.requestFingerprint());
            if (previous == null) {
                samples.put(outcome.requestFingerprint(),
                        new SampleAccumulator(outcome));
                payloadBytes += outcome.responseJson().length;
            } else {
                previous.merge(outcome, identity);
            }
        }
        List<ResolvedCorpusPayloads.Sample> frozen = samples.values().stream()
                .map(SampleAccumulator::freeze).toList();
        return new ResolvedCapability(new ResolvedCorpusPayloads.CapabilityCorpus(
                binding.capabilityRef(), publication.artifactRef(),
                revision.artifactRef(), now,
                usableUntil, frozen, resolvedTrajectories), payloadBytes);
    }

    private CapabilityCorpusPublication exactPublication(
            FixtureMirrorCorpusBindings.PublicationBinding binding,
            CapabilitySnapshot.Scope scope,
            IntegrationRequestContext identity) {
        try {
            CapabilityCorpusPublication exact = corpora.findPublication(
                            scope, binding.publicationRef().id(),
                            binding.publicationRef().revision())
                    .filter(value -> value.artifactRef().equals(binding.publicationRef()))
                    .orElseThrow(() -> notFound(identity,
                            "RG.MIRROR.CORPUS_PUBLICATION_NOT_FOUND",
                            "Corpus publication was not found in the authorized scope."));
            CapabilityCorpusPublication latest = corpora.findLatestPublication(
                            scope, binding.publicationRef().id())
                    .orElseThrow(() -> notFound(identity,
                            "RG.MIRROR.CORPUS_PUBLICATION_NOT_FOUND",
                            "Corpus publication was not found in the authorized scope."));
            if (!latest.artifactRef().equals(exact.artifactRef())) {
                throw conflict(identity, "RG.MIRROR.CORPUS_PUBLICATION_STALE",
                        "Fixture corpus binding is not the latest reviewed publication.");
            }
            return exact;
        } catch (IntegrationProblemException expected) {
            throw expected;
        } catch (RuntimeException unavailable) {
            throw unavailable(identity, "RG.MIRROR.CORPUS_STORE_UNAVAILABLE",
                    "Capability corpus storage is unavailable.");
        }
    }

    private CapabilityCorpusRevision exactRevision(
            CapabilityCorpusPublication publication,
            MirrorArtifactRef capabilityRef,
            CapabilitySnapshot.Scope scope,
            IntegrationRequestContext identity) {
        MirrorArtifactRef ref = publication.corpusRevisionRef();
        try {
            CapabilityCorpusRevision revision = corpora.findRevision(
                            scope, ref.id(), ref.revision())
                    .filter(value -> value.artifactRef().equals(ref))
                    .orElseThrow(() -> unavailable(identity,
                            "RG.MIRROR.CORPUS_REVISION_INTEGRITY_INVALID",
                            "Published corpus revision is unavailable."));
            if (!scope.equals(publication.scope())
                    || !scope.equals(revision.scope())
                    || !capabilityRef.equals(revision.capabilityRef())
                    || !publication.corpusId().equals(revision.corpusId())) {
                throw unavailable(identity,
                        "RG.MIRROR.CORPUS_REVISION_INTEGRITY_INVALID",
                        "Published corpus revision failed exact identity checks.");
            }
            return revision;
        } catch (IntegrationProblemException expected) {
            throw expected;
        } catch (RuntimeException unavailable) {
            throw unavailable(identity, "RG.MIRROR.CORPUS_STORE_UNAVAILABLE",
                    "Capability corpus storage is unavailable.");
        }
    }

    private ResolvedTrajectory resolveTrajectory(
            FixtureMirrorTrajectoryBindings.TrajectoryBinding binding,
            CapabilityCorpusPublication corpusPublication,
            CapabilityCorpusRevision corpusRevision,
            CapabilityCorpusGovernancePolicyProvider.GovernancePolicy governance,
            Map<MirrorArtifactRef, CapabilityCorpusRevision.SourceObservation>
                    corpusMembers,
            MirrorPlan.ExecutionPolicy executionPolicy,
            CapabilitySnapshot.Scope scope,
            Instant now,
            Instant requiredUntil,
            IntegrationRequestContext identity) {
        CapabilityCorpusTrajectoryPublication publication = exactTrajectory(
                binding, scope, identity);
        if (!scope.equals(publication.scope())
                || !binding.capabilityRef().equals(publication.capabilityRef())
                || !binding.corpusPublicationRef().equals(
                publication.corpusPublicationRef())
                || !corpusPublication.artifactRef().equals(
                publication.corpusPublicationRef())
                || !corpusRevision.artifactRef().equals(
                publication.corpusRevisionRef())
                || !corpusPublication.publicationPolicyRef().equals(
                publication.publicationPolicyRef())
                || !governance.publicationPolicyRef().equals(
                publication.publicationPolicyRef())
                || publication.publishedAt().isAfter(now)) {
            throw unavailable(
                    identity,
                    "RG.MIRROR.CORPUS_TRAJECTORY_INTEGRITY_INVALID",
                    "Reviewed trajectory failed exact corpus and policy identity checks.");
        }
        requireHorizon(
                publication.usableUntil(),
                requiredUntil,
                identity,
                "RG.MIRROR.CORPUS_TRAJECTORY_EXPIRES_EARLY");
        CapabilityRetryPolicyProvider.RetryPolicy retryPolicy =
                currentRetryPolicy(scope, binding.capabilityRef(), identity);
        if (!retryPolicy.policyRef().equals(publication.retryPolicyRef())) {
            throw conflict(
                    identity,
                    "RG.MIRROR.CORPUS_TRAJECTORY_RETRY_POLICY_DRIFT",
                    "Reviewed trajectory no longer matches current retry policy.");
        }
        if (publication.attempts().size() > retryPolicy.maximumAttempts()) {
            throw conflict(
                    identity,
                    "RG.MIRROR.CORPUS_TRAJECTORY_ATTEMPT_LIMIT_EXCEEDED",
                    "Reviewed trajectory exceeds the current retry attempt limit.");
        }

        List<ResolvedCorpusPayloads.Sample> attempts =
                new ArrayList<>(publication.attempts().size());
        Set<MirrorArtifactRef> sourceRefs = new LinkedHashSet<>();
        Set<String> spanIds = new LinkedHashSet<>();
        String traceId = "";
        long previousSequence = -1;
        Instant previousOccurrence = null;
        long payloadBytes = 0;
        for (int index = 0; index < publication.attempts().size(); index++) {
            CapabilityCorpusTrajectoryPublishRequest.AttemptSource attempt =
                    publication.attempts().get(index);
            CapabilityCorpusRevision.SourceObservation member =
                    corpusMembers.get(attempt.observationRef());
            if (member == null
                    || !member.admissionRef().equals(attempt.admissionRef())) {
                throw unavailable(
                        identity,
                        "RG.MIRROR.CORPUS_TRAJECTORY_SOURCE_INTEGRITY_INVALID",
                        "Reviewed trajectory source is absent from its exact corpus revision.");
            }
            CapabilityObservationRepository.StoredObservation stored =
                    exactObservation(
                            scope, binding.capabilityRef(), member, identity);
            CapabilityObservationEnvelope.Material material =
                    stored.envelope().material();
            verifyRuntimePolicy(
                    stored,
                    executionPolicy,
                    now,
                    requiredUntil,
                    true,
                    identity);
            verifyExternalSource(stored, governance, now, identity);
            if (!publication.requestFingerprint().equals(
                    material.request().payloadRef().fingerprint())) {
                throw unavailable(
                        identity,
                        "RG.MIRROR.CORPUS_TRAJECTORY_REQUEST_INTEGRITY_INVALID",
                        "Reviewed trajectory request fingerprint has drifted.");
            }
            if (traceId.isEmpty()) {
                traceId = material.trace().traceId();
            }
            if (!traceId.equals(material.trace().traceId())
                    || !spanIds.add(material.trace().spanId())
                    || material.trace().sequence() <= previousSequence
                    || previousOccurrence != null
                    && material.occurredAt().isBefore(previousOccurrence)) {
                throw unavailable(
                        identity,
                        "RG.MIRROR.CORPUS_TRAJECTORY_ORDER_INTEGRITY_INVALID",
                        "Reviewed trajectory no longer forms one ordered trace.");
            }
            boolean finalAttempt =
                    index == publication.attempts().size() - 1;
            if (!finalAttempt && (material.error() == null
                    || !retryPolicy.permits(material.error()))) {
                throw conflict(
                        identity,
                        "RG.MIRROR.CORPUS_TRAJECTORY_RETRY_INVALID",
                        "An intermediate trajectory attempt is no longer retryable.");
            }
            if (finalAttempt && material.error() != null
                    && material.error().retryable()) {
                throw conflict(
                        identity,
                        "RG.MIRROR.CORPUS_TRAJECTORY_TERMINAL_INVALID",
                        "The final trajectory attempt is not terminal.");
            }
            String trajectoryRule = publication.trajectoryId()
                    + "@" + publication.revision()
                    + ":attempt:" + attempt.attempt();
            SourceOutcome outcome = sourceOutcome(
                    corpusPublication,
                    corpusRevision,
                    stored,
                    member,
                    executionPolicy,
                    now,
                    requiredUntil,
                    true,
                    List.of(
                            publication.artifactRef(),
                            publication.retryPolicyRef(),
                            publication.reviewTicketRef()),
                    List.of(trajectoryRule),
                    identity);
            attempts.add(new SampleAccumulator(outcome).freeze());
            payloadBytes += outcome.responseJson().length;
            sourceRefs.add(attempt.observationRef());
            previousSequence = material.trace().sequence();
            previousOccurrence = material.occurredAt();
        }
        return new ResolvedTrajectory(
                new ResolvedCorpusPayloads.Trajectory(
                        publication.requestFingerprint(),
                        publication.artifactRef(),
                        attempts),
                sourceRefs,
                payloadBytes,
                publication.usableUntil());
    }

    private CapabilityCorpusTrajectoryPublication exactTrajectory(
            FixtureMirrorTrajectoryBindings.TrajectoryBinding binding,
            CapabilitySnapshot.Scope scope,
            IntegrationRequestContext identity) {
        try {
            MirrorArtifactRef ref = binding.trajectoryPublicationRef();
            CapabilityCorpusTrajectoryPublication exact = trajectories.find(
                            scope, ref.id(), ref.revision())
                    .filter(value -> value.artifactRef().equals(ref))
                    .orElseThrow(() -> notFound(
                            identity,
                            "RG.MIRROR.CORPUS_TRAJECTORY_NOT_FOUND",
                            "Reviewed corpus trajectory was not found in the authorized scope."));
            CapabilityCorpusTrajectoryPublication latest =
                    trajectories.findLatest(scope, ref.id())
                            .orElseThrow(() -> notFound(
                                    identity,
                                    "RG.MIRROR.CORPUS_TRAJECTORY_NOT_FOUND",
                                    "Reviewed corpus trajectory was not found in the authorized scope."));
            if (!latest.artifactRef().equals(exact.artifactRef())) {
                throw conflict(
                        identity,
                        "RG.MIRROR.CORPUS_TRAJECTORY_STALE",
                        "Fixture trajectory binding is not the current reviewed head.");
            }
            return exact;
        } catch (IntegrationProblemException expected) {
            throw expected;
        } catch (RuntimeException unavailable) {
            throw unavailable(
                    identity,
                    "RG.MIRROR.CORPUS_TRAJECTORY_STORE_UNAVAILABLE",
                    "Capability trajectory storage is unavailable.");
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
                        "RG.MIRROR.CORPUS_TRAJECTORY_RETRY_POLICY_UNAVAILABLE",
                        "Retry policy authority is unavailable.");
            }
            return retryPolicies.resolve(scope, capabilityRef)
                    .filter(value -> scope.equals(value.scope())
                            && capabilityRef.equals(value.capabilityRef()))
                    .orElseThrow(() -> conflict(
                            identity,
                            "RG.MIRROR.CORPUS_TRAJECTORY_RETRY_POLICY_NOT_FOUND",
                            "No current retry policy authorizes this trajectory."));
        } catch (IntegrationProblemException expected) {
            throw expected;
        } catch (RuntimeException unavailable) {
            throw unavailable(
                    identity,
                    "RG.MIRROR.CORPUS_TRAJECTORY_RETRY_POLICY_UNAVAILABLE",
                    "Retry policy authority is unavailable.");
        }
    }

    private static Map<MirrorArtifactRef,
            CapabilityCorpusRevision.SourceObservation> corpusMembers(
            CapabilityCorpusRevision revision) {
        Map<MirrorArtifactRef, CapabilityCorpusRevision.SourceObservation>
                members = new LinkedHashMap<>();
        for (CapabilityCorpusRevision.SourceObservation source
                : revision.sources()) {
            members.put(source.observationRef(), source);
        }
        return Map.copyOf(members);
    }

    private CapabilityCorpusGovernancePolicyProvider.GovernancePolicy currentPolicy(
            CapabilitySnapshot.Scope scope,
            MirrorArtifactRef capabilityRef,
            IntegrationRequestContext identity) {
        try {
            if (!policies.available()) {
                throw unavailable(identity, "RG.MIRROR.CORPUS_POLICY_UNAVAILABLE",
                        "Corpus governance policy is unavailable.");
            }
            return policies.resolve(scope, capabilityRef)
                    .filter(value -> scope.equals(value.scope())
                            && capabilityRef.equals(value.capabilityRef()))
                    .orElseThrow(() -> conflict(identity,
                            "RG.MIRROR.CORPUS_POLICY_NOT_FOUND",
                            "No current operator policy authorizes this corpus."));
        } catch (IntegrationProblemException expected) {
            throw expected;
        } catch (RuntimeException unavailable) {
            throw unavailable(identity, "RG.MIRROR.CORPUS_POLICY_UNAVAILABLE",
                    "Corpus governance policy is unavailable.");
        }
    }

    private CapabilityObservationRepository.StoredObservation exactObservation(
            CapabilitySnapshot.Scope scope,
            MirrorArtifactRef capabilityRef,
            CapabilityCorpusRevision.SourceObservation source,
            IntegrationRequestContext identity) {
        try {
            CapabilityObservationRepository.StoredObservation stored = observations.find(
                            scope, source.observationRef().id())
                    .orElseThrow(() -> unavailable(identity,
                            "RG.MIRROR.CORPUS_SOURCE_INTEGRITY_INVALID",
                            "Published corpus source is unavailable."));
            CapabilityObservationEnvelope envelope = stored.envelope();
            CapabilityObservationAdmission admission = stored.admission();
            if (!envelope.artifactRef().equals(source.observationRef())
                    || !admission.artifactRef().equals(source.admissionRef())
                    || admission.state() != CapabilityObservationAdmission.State.ADMITTED
                    || !scope.equals(envelope.material().scope())
                    || !capabilityRef.equals(envelope.material().capabilityRef())
                    || !source.authorityKeyRef().equals(admission.authorityKeyRef())
                    || !source.occurredAt().equals(envelope.material().occurredAt())
                    || !source.usableUntil().equals(admission.usableUntil())
                    || !source.traceFingerprint().equals(
                    integrity.traceFingerprint(envelope.material().trace()))
                    || !payloadMatches(source, envelope.material())) {
                throw unavailable(identity,
                        "RG.MIRROR.CORPUS_SOURCE_INTEGRITY_INVALID",
                        "Published corpus source failed exact identity checks.");
            }
            return stored;
        } catch (IntegrationProblemException expected) {
            throw expected;
        } catch (RuntimeException unavailable) {
            throw unavailable(identity, "RG.MIRROR.CORPUS_SOURCE_STORE_UNAVAILABLE",
                    "Capability observation storage is unavailable.");
        }
    }

    private void verifyRuntimePolicy(
            CapabilityObservationRepository.StoredObservation stored,
            MirrorPlan.ExecutionPolicy policy,
            Instant now,
            Instant requiredUntil,
            boolean trajectory,
            IntegrationRequestContext identity) {
        CapabilityObservationEnvelope.Material material = stored.envelope().material();
        CapabilityObservationEnvelope.DataUseGrant grant = material.dataUseGrant();
        if (!grant.allowedUses().contains(
                CapabilityObservationEnvelope.AllowedUse.EXACT_REPLAY)
                || !grant.activeAt(now)
                || grant.expiresAt().isBefore(requiredUntil)) {
            throw conflict(identity, "RG.MIRROR.CORPUS_EXACT_REPLAY_NOT_AUTHORIZED",
                    "Corpus source is not authorized for exact replay over the plan horizon.");
        }
        if (trajectory && !grant.allowedUses().contains(
                CapabilityObservationEnvelope.AllowedUse.TRAJECTORY_MODELING)) {
            throw conflict(
                    identity,
                    "RG.MIRROR.CORPUS_TRAJECTORY_USE_NOT_AUTHORIZED",
                    "Trajectory source is not authorized for trajectory modeling.");
        }
        requirePayloadPolicy(material.request(), policy, requiredUntil, identity);
        if (material.response() != null) {
            requirePayloadPolicy(material.response(), policy, requiredUntil, identity);
        }
        requireHorizon(stored.admission().usableUntil(), requiredUntil, identity,
                "RG.MIRROR.CORPUS_SOURCE_EXPIRES_EARLY");
    }

    private void requirePayloadPolicy(
            CapabilityObservationEnvelope.PayloadReference payload,
            MirrorPlan.ExecutionPolicy policy,
            Instant requiredUntil,
            IntegrationRequestContext identity) {
        if (classificationRank(payload.classification())
                > classificationRank(policy.maximumClassification())) {
            throw forbidden(identity, "RG.MIRROR.CORPUS_CLEARANCE_REQUIRED",
                    "Workload clearance cannot use the selected corpus payload.");
        }
        if (!policy.allowedRegions().contains(payload.vaultRegion())) {
            throw forbidden(identity, "RG.MIRROR.CORPUS_REGION_FORBIDDEN",
                    "Selected corpus payload is outside the allowed regional boundary.");
        }
        requireHorizon(payload.retentionUntil(), requiredUntil, identity,
                "RG.MIRROR.CORPUS_PAYLOAD_EXPIRES_EARLY");
    }

    private void verifyExternalSource(
            CapabilityObservationRepository.StoredObservation stored,
            CapabilityCorpusGovernancePolicyProvider.GovernancePolicy policy,
            Instant now,
            IntegrationRequestContext identity) {
        CapabilityCorpusSourceVerifier.VerificationResult result;
        try {
            result = sourceVerifier.verify(stored, policy, now);
        } catch (RuntimeException failure) {
            throw unavailable(identity, "RG.MIRROR.CORPUS_SOURCE_AUTHORITY_UNAVAILABLE",
                    "Corpus source lifecycle authority is unavailable.");
        }
        if (result == null
                || result.outcome() == CapabilityCorpusSourceVerifier.Outcome.UNAVAILABLE) {
            throw unavailable(identity, "RG.MIRROR.CORPUS_SOURCE_AUTHORITY_UNAVAILABLE",
                    "Corpus source lifecycle authority is unavailable.");
        }
        if (result.outcome() == CapabilityCorpusSourceVerifier.Outcome.REJECTED) {
            throw conflict(identity, "RG.MIRROR.CORPUS_SOURCE_UNUSABLE",
                    "Corpus source was deleted, revoked, expired, or rejected.");
        }
    }

    private SourceOutcome sourceOutcome(
            CapabilityCorpusPublication publication,
            CapabilityCorpusRevision revision,
            CapabilityObservationRepository.StoredObservation stored,
            CapabilityCorpusRevision.SourceObservation source,
            MirrorPlan.ExecutionPolicy policy,
            Instant now,
            Instant requiredUntil,
            boolean allowRetryableError,
            List<MirrorArtifactRef> additionalArtifactRefs,
            List<String> additionalRuleRefs,
            IntegrationRequestContext identity) {
        CapabilityObservationEnvelope.Material material = stored.envelope().material();
        String requestFingerprint = source.requestPayloadRef().fingerprint();
        byte[] responseJson = new byte[0];
        if (!allowRetryableError
                && material.error() != null
                && material.error().retryable()) {
            throw conflict(identity, "RG.MIRROR.CORPUS_RETRYABLE_ERROR_UNSUPPORTED",
                    "Retryable observations require a governed attempt-trajectory corpus.");
        }
        if (material.response() != null) {
            CapabilityCorpusPayloadAuthority.Materialization result;
            try {
                CapabilityObservationEnvelope.PayloadReference response = material.response();
                result = payloadAuthority.materialize(
                        new CapabilityCorpusPayloadAuthority.MaterializationRequest(
                                material.scope(), material.capabilityRef(),
                                publication.artifactRef(), stored.envelope().artifactRef(),
                                response.payloadRef(), response.sanitizationProofRef(),
                                response.schemaRef(), response.classification(),
                                response.vaultRegion(), response.sizeBytes(),
                                material.dataUseGrant().grantRef(),
                                policy.authorizedPurpose(), now, requiredUntil));
            } catch (RuntimeException failure) {
                throw unavailable(identity, "RG.MIRROR.CORPUS_PAYLOAD_AUTHORITY_UNAVAILABLE",
                        "Corpus payload authority is unavailable.");
            }
            if (result == null
                    || result.outcome()
                    == CapabilityCorpusPayloadAuthority.Outcome.UNAVAILABLE) {
                throw unavailable(identity, "RG.MIRROR.CORPUS_PAYLOAD_AUTHORITY_UNAVAILABLE",
                        "Corpus payload authority is unavailable.");
            }
            if (result.outcome() == CapabilityCorpusPayloadAuthority.Outcome.REJECTED) {
                throw conflict(identity, "RG.MIRROR.CORPUS_PAYLOAD_UNUSABLE",
                        "Corpus response payload was deleted, revoked, expired, or rejected.");
            }
            responseJson = verifiedJson(result.canonicalJson(), material.response(), identity);
        }
        LinkedHashSet<MirrorArtifactRef> artifactRefs =
                new LinkedHashSet<>(artifacts(
                        publication, revision, stored, source));
        artifactRefs.addAll(additionalArtifactRefs);
        LinkedHashSet<String> ruleRefs = new LinkedHashSet<>();
        ruleRefs.add(source.observationRef().id());
        ruleRefs.addAll(additionalRuleRefs);
        return new SourceOutcome(
                requestFingerprint,
                outcomeKey(material),
                responseJson,
                material.error(),
                List.copyOf(artifactRefs),
                List.copyOf(ruleRefs),
                freshness(material.occurredAt(), source.usableUntil(), now));
    }

    private byte[] verifiedJson(
            byte[] value,
            CapabilityObservationEnvelope.PayloadReference expected,
            IntegrationRequestContext identity) {
        if (value == null || value.length == 0
                || value.length != expected.sizeBytes()
                || value.length > MirrorResolutionIntegrity.MAXIMUM_OUTPUT_BYTES) {
            throw unavailable(identity, "RG.MIRROR.CORPUS_PAYLOAD_INTEGRITY_INVALID",
                    "Materialized corpus response failed immutable size verification.");
        }
        try {
            try (JsonParser parser = mapper.getFactory().createParser(value)) {
                JsonNode json = mapper.readTree(parser);
                if (json == null || parser.nextToken() != null
                        || !expected.payloadRef().fingerprint().equals(
                        ProtocolFingerprint.ofBounded(
                                mapper, json,
                                MirrorResolutionIntegrity.MAXIMUM_OUTPUT_BYTES))) {
                    throw unavailable(identity,
                            "RG.MIRROR.CORPUS_PAYLOAD_INTEGRITY_INVALID",
                            "Materialized corpus response failed content-address verification.");
                }
            }
            return Arrays.copyOf(value, value.length);
        } catch (IOException invalid) {
            throw unavailable(identity, "RG.MIRROR.CORPUS_PAYLOAD_INTEGRITY_INVALID",
                    "Materialized corpus response is not canonical JSON.");
        }
    }

    private static List<MirrorArtifactRef> artifacts(
            CapabilityCorpusPublication publication,
            CapabilityCorpusRevision revision,
            CapabilityObservationRepository.StoredObservation stored,
            CapabilityCorpusRevision.SourceObservation source) {
        CapabilityObservationEnvelope.Material material = stored.envelope().material();
        LinkedHashSet<MirrorArtifactRef> refs = new LinkedHashSet<>();
        refs.add(publication.artifactRef());
        refs.add(revision.artifactRef());
        refs.add(revision.governancePolicyRef());
        refs.add(publication.publicationPolicyRef());
        refs.add(publication.reviewTicketRef());
        refs.add(source.observationRef());
        refs.add(source.admissionRef());
        refs.add(source.requestPayloadRef());
        refs.add(source.requestProofRef());
        refs.add(source.requestSchemaRef());
        refs.add(source.authorityKeyRef());
        refs.add(material.dataUseGrant().grantRef());
        if (source.responsePayloadRef() != null) {
            refs.add(source.responsePayloadRef());
            refs.add(source.responseProofRef());
            refs.add(source.responseSchemaRef());
        }
        if (material.outcomeCorrelationRef() != null) {
            refs.add(material.outcomeCorrelationRef());
        }
        return List.copyOf(refs);
    }

    private static boolean payloadMatches(
            CapabilityCorpusRevision.SourceObservation source,
            CapabilityObservationEnvelope.Material material) {
        CapabilityObservationEnvelope.PayloadReference request = material.request();
        CapabilityObservationEnvelope.PayloadReference response = material.response();
        if (!source.requestPayloadRef().equals(request.payloadRef())
                || !source.requestProofRef().equals(request.sanitizationProofRef())
                || !source.requestSchemaRef().equals(request.schemaRef())) {
            return false;
        }
        if (response == null) {
            return source.responsePayloadRef() == null
                    && source.responseProofRef() == null
                    && source.responseSchemaRef() == null
                    && material.error() != null
                    && source.normalizedErrorCode().equals(
                    material.error().errorCode());
        }
        return source.normalizedErrorCode().isBlank()
                && source.responsePayloadRef().equals(response.payloadRef())
                && source.responseProofRef().equals(response.sanitizationProofRef())
                && source.responseSchemaRef().equals(response.schemaRef());
    }

    private static String outcomeKey(CapabilityObservationEnvelope.Material material) {
        if (material.response() != null) {
            return "RESPONSE:" + material.response().payloadRef().fingerprint();
        }
        CapabilityObservationEnvelope.NormalizedError error = material.error();
        return "ERROR:" + error.errorClass() + ":" + error.errorCode() + ":"
                + error.retryable() + ":" + error.detailsFingerprint();
    }

    private static double freshness(Instant occurredAt, Instant usableUntil, Instant now) {
        long total = Math.max(1, Duration.between(occurredAt, usableUntil).toMillis());
        long remaining = Math.max(0, Duration.between(now, usableUntil).toMillis());
        double value = Math.min(1, (double) remaining / total);
        return Math.round(value * 1_000_000d) / 1_000_000d;
    }

    private static int classificationRank(
            CapabilityObservationEnvelope.Classification classification) {
        return switch (classification) {
            case PUBLIC -> 0;
            case INTERNAL -> 1;
            case CONFIDENTIAL -> 2;
            case RESTRICTED -> 3;
        };
    }

    private static int classificationRank(
            CapabilityContract.DataClassification classification) {
        return switch (classification) {
            case PUBLIC -> 0;
            case INTERNAL -> 1;
            case CONFIDENTIAL -> 2;
            case RESTRICTED -> 3;
        };
    }

    private void requireAuthorities(
            IntegrationRequestContext identity,
            boolean trajectoryServing) {
        try {
            if (!policies.available()) {
                throw unavailable(identity, "RG.MIRROR.CORPUS_POLICY_UNAVAILABLE",
                        "Corpus governance policy is unavailable.");
            }
            if (!sourceVerifier.available()) {
                throw unavailable(identity, "RG.MIRROR.CORPUS_SOURCE_AUTHORITY_UNAVAILABLE",
                        "Corpus source lifecycle authority is unavailable.");
            }
            if (!payloadAuthority.available()) {
                throw unavailable(identity, "RG.MIRROR.CORPUS_PAYLOAD_AUTHORITY_UNAVAILABLE",
                        "Corpus payload authority is unavailable.");
            }
            if (trajectoryServing && !retryPolicies.available()) {
                throw unavailable(
                        identity,
                        "RG.MIRROR.CORPUS_TRAJECTORY_RETRY_POLICY_UNAVAILABLE",
                        "Retry policy authority is unavailable.");
            }
        } catch (IntegrationProblemException expected) {
            throw expected;
        } catch (RuntimeException unavailable) {
            throw unavailable(identity, "RG.MIRROR.CORPUS_SERVING_UNAVAILABLE",
                    "Corpus serving authorities are unavailable.");
        }
    }

    private static void requireHorizon(
            Instant actual,
            Instant requiredUntil,
            IntegrationRequestContext identity,
            String code) {
        if (actual == null || actual.isBefore(requiredUntil)) {
            throw conflict(identity, code,
                    "Corpus source does not cover the complete mirror-plan horizon.");
        }
    }

    private static Instant earliest(Instant left, Instant right) {
        return left.isBefore(right) ? left : right;
    }

    private static IntegrationProblemException badRequest(
            IntegrationRequestContext identity, String code, String title) {
        return new IntegrationProblemException(IntegrationProblem.badRequest(
                code, title, identity.correlationId(), Map.of()));
    }

    private static IntegrationProblemException notFound(
            IntegrationRequestContext identity, String code, String title) {
        return new IntegrationProblemException(IntegrationProblem.notFound(
                code, title, identity.correlationId(), Map.of()));
    }

    private static IntegrationProblemException forbidden(
            IntegrationRequestContext identity, String code, String title) {
        return new IntegrationProblemException(IntegrationProblem.forbidden(
                code, title, identity.correlationId(), Map.of()));
    }

    private static IntegrationProblemException conflict(
            IntegrationRequestContext identity, String code, String title) {
        return new IntegrationProblemException(IntegrationProblem.conflict(
                code, title, identity.correlationId(), Map.of()));
    }

    private static IntegrationProblemException unavailable(
            IntegrationRequestContext identity, String code, String title) {
        return new IntegrationProblemException(IntegrationProblem.serviceUnavailable(
                code, title, identity.correlationId(), Map.of()));
    }

    private record ResolvedCapability(
            ResolvedCorpusPayloads.CapabilityCorpus corpus,
            long payloadBytes
    ) {
    }

    private record ResolvedTrajectory(
            ResolvedCorpusPayloads.Trajectory trajectory,
            Set<MirrorArtifactRef> sourceRefs,
            long payloadBytes,
            Instant usableUntil
    ) {
        private ResolvedTrajectory {
            trajectory = Objects.requireNonNull(trajectory, "trajectory");
            sourceRefs = Set.copyOf(sourceRefs);
            if (payloadBytes < 0) {
                throw new IllegalArgumentException(
                        "trajectory payloadBytes must not be negative");
            }
            usableUntil = Objects.requireNonNull(
                    usableUntil, "usableUntil");
        }
    }

    private record SourceOutcome(
            String requestFingerprint,
            String outcomeKey,
            byte[] responseJson,
            CapabilityObservationEnvelope.NormalizedError error,
            List<MirrorArtifactRef> artifactRefs,
            List<String> ruleRefs,
            double freshness
    ) {
        private SourceOutcome {
            responseJson = responseJson == null
                    ? new byte[0] : Arrays.copyOf(responseJson, responseJson.length);
            artifactRefs = List.copyOf(artifactRefs);
            ruleRefs = List.copyOf(ruleRefs);
        }

        @Override
        public byte[] responseJson() {
            return Arrays.copyOf(responseJson, responseJson.length);
        }
    }

    private static final class SampleAccumulator {
        private final String requestFingerprint;
        private final String outcomeKey;
        private final byte[] responseJson;
        private final CapabilityObservationEnvelope.NormalizedError error;
        private final Set<MirrorArtifactRef> artifactRefs = new LinkedHashSet<>();
        private final Set<String> ruleRefs = new LinkedHashSet<>();
        private double freshness;
        private int count;

        private SampleAccumulator(SourceOutcome source) {
            this.requestFingerprint = source.requestFingerprint();
            this.outcomeKey = source.outcomeKey();
            this.responseJson = source.responseJson();
            this.error = source.error();
            this.freshness = source.freshness();
            mergeProvenance(source);
        }

        private void merge(
                SourceOutcome source,
                IntegrationRequestContext identity) {
            if (!outcomeKey.equals(source.outcomeKey())) {
                throw conflict(identity, "RG.MIRROR.CORPUS_EXACT_CONFLICT",
                        "One exact request fingerprint has conflicting corpus outcomes.");
            }
            freshness = Math.min(freshness, source.freshness());
            mergeProvenance(source);
        }

        private void mergeProvenance(SourceOutcome source) {
            artifactRefs.addAll(source.artifactRefs());
            ruleRefs.addAll(source.ruleRefs());
            count++;
        }

        private ResolvedCorpusPayloads.Sample freeze() {
            List<String> limitations = count > 1
                    ? List.of("IDENTICAL_EXACT_SOURCES_COLLAPSED:" + count) : List.of();
            if (error == null) {
                return ResolvedCorpusPayloads.Sample.response(
                        requestFingerprint, responseJson,
                        List.copyOf(artifactRefs), List.copyOf(ruleRefs),
                        freshness, limitations);
            }
            return ResolvedCorpusPayloads.Sample.error(
                    requestFingerprint, error.errorCode(), error.errorClass(),
                    error.retryable(), error.detailsFingerprint(),
                    List.copyOf(artifactRefs), List.copyOf(ruleRefs),
                    freshness, limitations);
        }
    }
}
