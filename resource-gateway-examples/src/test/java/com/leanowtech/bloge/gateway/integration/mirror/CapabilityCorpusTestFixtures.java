package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

final class CapabilityCorpusTestFixtures {
    private static final String ZERO_FINGERPRINT =
            "sha256:" + "0".repeat(64);

    private CapabilityCorpusTestFixtures() {
    }

    static IntegrationRequestContext identity(
            String organization, Set<String> groups) {
        return new IntegrationRequestContext(
                "tenant-a",
                organization,
                "support",
                "test",
                "sg",
                "SERVICE",
                "corpus-curator",
                "",
                CapabilityCorpusGovernanceService.AUTHORIZED_PURPOSE,
                "corr-corpus",
                groups,
                "CONFIDENTIAL",
                "");
    }

    static CapabilityObservationRepository.StoredObservation admitted(
            ObjectMapper mapper,
            CapabilitySnapshot.Scope scope,
            String observationId) {
        CapabilitySnapshot capability =
                CapabilityObservationTestFixtures.capability(mapper, scope);
        InMemoryVisualEvidenceSigner signer = new InMemoryVisualEvidenceSigner();
        CapabilityObservationEnvelope envelope =
                CapabilityObservationTestFixtures.envelope(
                        mapper, signer, capability, observationId);
        CapabilityObservationAdmissionIntegrity admissions =
                new CapabilityObservationAdmissionIntegrity(mapper);
        Instant decidedAt = envelope.seal().signedAt().plusSeconds(1);
        CapabilityObservationAdmission admission = admissions.admitted(
                envelope,
                CapabilityObservationTestFixtures.ref(
                        "OBSERVATION_ADMISSION_POLICY",
                        "support-admission-policy",
                        3,
                        'f'),
                CapabilityObservationTestFixtures.authorityKey(
                        envelope,
                        signer,
                        CapabilityObservationIntegrity.KeyState.ACTIVE).keyRef(),
                decidedAt,
                decidedAt.plus(Duration.ofDays(10)));
        return new CapabilityObservationRepository.StoredObservation(
                envelope, admission);
    }

    static CapabilityObservationRepository.StoredObservation quarantined(
            ObjectMapper mapper,
            CapabilitySnapshot.Scope scope,
            String observationId) {
        CapabilitySnapshot capability =
                CapabilityObservationTestFixtures.capability(mapper, scope);
        InMemoryVisualEvidenceSigner signer = new InMemoryVisualEvidenceSigner();
        CapabilityObservationEnvelope envelope =
                CapabilityObservationTestFixtures.envelope(
                        mapper, signer, capability, observationId);
        Instant decidedAt = envelope.seal().signedAt().plusSeconds(1);
        CapabilityObservationAdmission admission =
                new CapabilityObservationAdmissionIntegrity(mapper).quarantined(
                        envelope,
                        CapabilityObservationTestFixtures.ref(
                                "OBSERVATION_ADMISSION_POLICY",
                                "support-admission-policy",
                                3,
                                'f'),
                        CapabilityObservationTestFixtures.authorityKey(
                                envelope,
                                signer,
                                CapabilityObservationIntegrity.KeyState.ACTIVE)
                                .keyRef(),
                        CapabilityObservationAdmission.Reason.INTEGRITY_REJECTED,
                        decidedAt);
        return new CapabilityObservationRepository.StoredObservation(
                envelope, admission);
    }

    static CapabilityObservationRepository.StoredObservation trajectoryObservation(
            ObjectMapper mapper,
            InMemoryVisualEvidenceSigner signer,
            CapabilitySnapshot capability,
            String observationId,
            Instant occurredAt,
            long sequence,
            boolean retryableError,
            boolean trajectoryUse) {
        CapabilityObservationEnvelope.DataUseGrant grant =
                new CapabilityObservationEnvelope.DataUseGrant(
                        CapabilityObservationTestFixtures.ref(
                                "DATA_USE_GRANT", "grant-trajectory", 1, '9'),
                        CapabilityObservationAdmissionService.AUTHORIZED_PURPOSE,
                        trajectoryUse
                                ? List.of(
                                CapabilityObservationEnvelope.AllowedUse
                                        .CORPUS_CURATION,
                                CapabilityObservationEnvelope.AllowedUse
                                        .EXACT_REPLAY,
                                CapabilityObservationEnvelope.AllowedUse
                                        .TRAJECTORY_MODELING)
                                : List.of(
                                CapabilityObservationEnvelope.AllowedUse
                                        .CORPUS_CURATION,
                                CapabilityObservationEnvelope.AllowedUse
                                        .EXACT_REPLAY),
                        occurredAt.minus(Duration.ofDays(1)),
                        occurredAt.plus(Duration.ofDays(20)));
        CapabilityObservationEnvelope.PayloadReference request =
                CapabilityObservationTestFixtures.payload(
                        "trajectory-request",
                        '1',
                        occurredAt.plus(Duration.ofDays(30)));
        CapabilityObservationEnvelope.PayloadReference response =
                retryableError ? null
                        : CapabilityObservationTestFixtures.payload(
                        "trajectory-response",
                        '5',
                        occurredAt.plus(Duration.ofDays(30)));
        CapabilityObservationEnvelope.NormalizedError error =
                retryableError
                        ? new CapabilityObservationEnvelope.NormalizedError(
                        "TRANSIENT_UPSTREAM",
                        "UPSTREAM_TIMEOUT",
                        true,
                        CapabilityObservationTestFixtures.fingerprint('8'))
                        : null;
        CapabilityObservationEnvelope.Material material =
                new CapabilityObservationEnvelope.Material(
                        observationId,
                        capability.scope(),
                        new MirrorArtifactRef(
                                "CAPABILITY",
                                capability.capabilityId(),
                                capability.revision(),
                                capability.fingerprint()),
                        occurredAt,
                        new CapabilityObservationEnvelope.TraceCoordinates(
                                "trace-trajectory",
                                "span-" + observationId,
                                sequence),
                        request,
                        response,
                        error,
                        42,
                        null,
                        null,
                        grant);
        CapabilityObservationEnvelope envelope =
                new CapabilityObservationIntegrity(mapper).seal(
                        material,
                        signer,
                        CapabilityObservationTestFixtures.ISSUER);
        CapabilityObservationAdmissionIntegrity admissions =
                new CapabilityObservationAdmissionIntegrity(mapper);
        Instant decidedAt = envelope.seal().signedAt().plusSeconds(1);
        CapabilityObservationAdmission admission = admissions.admitted(
                envelope,
                CapabilityObservationTestFixtures.ref(
                        "OBSERVATION_ADMISSION_POLICY",
                        "support-admission-policy",
                        3,
                        'f'),
                CapabilityObservationTestFixtures.authorityKey(
                        envelope,
                        signer,
                        CapabilityObservationIntegrity.KeyState.ACTIVE).keyRef(),
                decidedAt,
                decidedAt.plus(Duration.ofDays(10)));
        return new CapabilityObservationRepository.StoredObservation(
                envelope, admission);
    }

    static CapabilityCorpusGovernancePolicyProvider.GovernancePolicy policy(
            CapabilityObservationRepository.StoredObservation source,
            int minimumSamples,
            int maximumDuplicateBasisPoints,
            int minimumProducerKeys) {
        return new CapabilityCorpusGovernancePolicyProvider.GovernancePolicy(
                source.envelope().material().scope(),
                source.envelope().material().capabilityRef(),
                CapabilityObservationTestFixtures.ref(
                        "CORPUS_GOVERNANCE_POLICY",
                        "support-corpus-policy",
                        2,
                        '1'),
                CapabilityObservationTestFixtures.ref(
                        "CORPUS_PUBLICATION_POLICY",
                        "support-publication-policy",
                        4,
                        '2'),
                Set.of("corpus-reviewers"),
                Set.of("corpus-publishers"),
                minimumSamples,
                1_000,
                maximumDuplicateBasisPoints,
                minimumProducerKeys,
                Duration.ofHours(1));
    }

    static CapabilityObservationReviewRequest reviewRequest(
            CapabilityObservationRepository.StoredObservation source) {
        return new CapabilityObservationReviewRequest(
                "",
                source.envelope().artifactRef(),
                source.admission().artifactRef(),
                CapabilityObservationReviewRequest.Disposition
                        .PRODUCER_REMEDIATION_REQUIRED,
                CapabilityObservationTestFixtures.ref(
                        "GOVERNANCE_REVIEW_TICKET",
                        "ticket-" + source.envelope().material().observationId(),
                        1,
                        '3'),
                "PRODUCER_SIGNATURE_REMEDIATION");
    }

    static CapabilityCorpusCandidateRequest candidateRequest(
            String corpusId,
            long revision,
            MirrorArtifactRef predecessor,
            List<CapabilityObservationRepository.StoredObservation> sources) {
        List<CapabilityCorpusCandidateRequest.SourceCoordinate> coordinates =
                sources.stream()
                        .sorted((left, right) -> left.envelope().material()
                                .observationId().compareTo(
                                        right.envelope().material()
                                                .observationId()))
                        .map(source ->
                                new CapabilityCorpusCandidateRequest.SourceCoordinate(
                                        source.envelope().artifactRef(),
                                        source.admission().artifactRef()))
                        .toList();
        return new CapabilityCorpusCandidateRequest(
                "",
                corpusId,
                revision,
                predecessor,
                sources.getFirst().envelope().material().capabilityRef(),
                coordinates);
    }

    static CapabilityCorpusRevision revision(
            ObjectMapper mapper,
            CapabilityObservationRepository.StoredObservation source,
            String corpusId,
            long revision,
            MirrorArtifactRef predecessor,
            Instant createdAt) {
        CapabilityCorpusIntegrity integrity = new CapabilityCorpusIntegrity(mapper);
        CapabilityCorpusCandidateRequest request = candidateRequest(
                corpusId, revision, predecessor, List.of(source));
        CapabilityObservationEnvelope.PayloadReference requestPayload =
                source.envelope().material().request();
        CapabilityObservationEnvelope.PayloadReference responsePayload =
                source.envelope().material().response();
        CapabilityCorpusRevision.SourceObservation projection =
                new CapabilityCorpusRevision.SourceObservation(
                        source.envelope().artifactRef(),
                        source.admission().artifactRef(),
                        requestPayload.payloadRef(),
                        requestPayload.sanitizationProofRef(),
                        requestPayload.schemaRef(),
                        responsePayload.payloadRef(),
                        responsePayload.sanitizationProofRef(),
                        responsePayload.schemaRef(),
                        "",
                        integrity.traceFingerprint(
                                source.envelope().material().trace()),
                        source.admission().authorityKeyRef(),
                        source.envelope().material().occurredAt(),
                        source.admission().usableUntil());
        return integrity.sealRevision(new CapabilityCorpusRevision(
                "",
                ZERO_FINGERPRINT,
                integrity.candidateCommandFingerprint(request),
                source.envelope().material().scope(),
                corpusId,
                revision,
                predecessor,
                source.envelope().material().capabilityRef(),
                policy(source, 1, 10_000, 1).governancePolicyRef(),
                List.of(projection),
                new CapabilityCorpusRevision.RiskSummary(
                        1,
                        1,
                        0,
                        1,
                        1,
                        0,
                        CapabilityCorpusRevision.Eligibility.ELIGIBLE,
                        Set.of()),
                "corpus-curator",
                createdAt,
                source.admission().usableUntil()));
    }

    static CapabilityCorpusRevision revision(
            ObjectMapper mapper,
            List<CapabilityObservationRepository.StoredObservation> sources,
            String corpusId,
            Instant createdAt) {
        CapabilityCorpusIntegrity integrity =
                new CapabilityCorpusIntegrity(mapper);
        CapabilityCorpusCandidateRequest request = candidateRequest(
                corpusId, 1, null, sources);
        List<CapabilityCorpusRevision.SourceObservation> projections =
                sources.stream().map(source -> {
                    CapabilityObservationEnvelope.Material material =
                            source.envelope().material();
                    CapabilityObservationEnvelope.PayloadReference response =
                            material.response();
                    return new CapabilityCorpusRevision.SourceObservation(
                            source.envelope().artifactRef(),
                            source.admission().artifactRef(),
                            material.request().payloadRef(),
                            material.request().sanitizationProofRef(),
                            material.request().schemaRef(),
                            response == null ? null : response.payloadRef(),
                            response == null
                                    ? null : response.sanitizationProofRef(),
                            response == null ? null : response.schemaRef(),
                            material.error() == null
                                    ? "" : material.error().errorCode(),
                            integrity.traceFingerprint(material.trace()),
                            source.admission().authorityKeyRef(),
                            material.occurredAt(),
                            source.admission().usableUntil());
                }).toList();
        Instant usableUntil = sources.stream()
                .map(source -> source.admission().usableUntil())
                .min(Instant::compareTo)
                .orElseThrow();
        return integrity.sealRevision(new CapabilityCorpusRevision(
                "",
                ZERO_FINGERPRINT,
                integrity.candidateCommandFingerprint(request),
                sources.getFirst().envelope().material().scope(),
                corpusId,
                1,
                null,
                sources.getFirst().envelope().material().capabilityRef(),
                policy(sources.getFirst(), 1, 10_000, 1)
                        .governancePolicyRef(),
                projections,
                new CapabilityCorpusRevision.RiskSummary(
                        sources.size(),
                        1,
                        sources.size() - 1,
                        sources.size(),
                        1,
                        (sources.size() - 1) * 10_000 / sources.size(),
                        CapabilityCorpusRevision.Eligibility.ELIGIBLE,
                        Set.of()),
                "corpus-curator",
                createdAt,
                usableUntil));
    }

    static CapabilityCorpusTrajectoryPublishRequest trajectoryRequest(
            CapabilityCorpusPublication publication,
            List<CapabilityObservationRepository.StoredObservation> sources,
            MirrorArtifactRef retryPolicyRef) {
        List<CapabilityCorpusTrajectoryPublishRequest.AttemptSource> attempts =
                java.util.stream.IntStream.range(0, sources.size())
                        .mapToObj(index ->
                                new CapabilityCorpusTrajectoryPublishRequest
                                        .AttemptSource(
                                        index + 1,
                                        sources.get(index).envelope().artifactRef(),
                                        sources.get(index).admission().artifactRef()))
                        .toList();
        return new CapabilityCorpusTrajectoryPublishRequest(
                "",
                "support-timeout-trajectory",
                1,
                null,
                sources.getFirst().envelope().material().capabilityRef(),
                publication.artifactRef(),
                retryPolicyRef,
                attempts,
                CapabilityObservationTestFixtures.ref(
                        "GOVERNANCE_REVIEW_TICKET",
                        "ticket-trajectory",
                        1,
                        '6'),
                "OWNER_APPROVED_RETRY_TRAJECTORY");
    }

    static CapabilityCorpusTrajectoryPublication trajectoryPublication(
            ObjectMapper mapper,
            CapabilityCorpusPublication corpusPublication,
            CapabilityCorpusRevision corpusRevision,
            CapabilityCorpusTrajectoryPublishRequest request,
            MirrorArtifactRef predecessor,
            Instant publishedAt) {
        CapabilityCorpusIntegrity integrity =
                new CapabilityCorpusIntegrity(mapper);
        return integrity.sealTrajectory(
                new CapabilityCorpusTrajectoryPublication(
                        "",
                        ZERO_FINGERPRINT,
                        integrity.trajectoryCommandFingerprint(request),
                        corpusPublication.scope(),
                        request.trajectoryId(),
                        request.revision(),
                        predecessor,
                        request.capabilityRef(),
                        corpusPublication.artifactRef(),
                        corpusRevision.artifactRef(),
                        corpusPublication.publicationPolicyRef(),
                        request.retryPolicyRef(),
                        corpusRevision.sources().getFirst()
                                .requestPayloadRef().fingerprint(),
                        request.attempts(),
                        request.reviewTicketRef(),
                        request.reasonCode(),
                        "corpus-curator",
                        publishedAt,
                        corpusPublication.usableUntil()));
    }

    static CapabilityCorpusPublication publication(
            ObjectMapper mapper,
            CapabilityCorpusRevision revision,
            long publicationRevision,
            MirrorArtifactRef predecessor,
            Instant publishedAt) {
        CapabilityCorpusIntegrity integrity = new CapabilityCorpusIntegrity(mapper);
        CapabilityCorpusPublishRequest request = new CapabilityCorpusPublishRequest(
                "",
                revision.corpusId(),
                publicationRevision,
                predecessor,
                revision.artifactRef(),
                CapabilityObservationTestFixtures.ref(
                        "GOVERNANCE_REVIEW_TICKET",
                        "ticket-publish-" + publicationRevision,
                        1,
                        '4'),
                "OWNER_APPROVED");
        return integrity.sealPublication(new CapabilityCorpusPublication(
                "",
                ZERO_FINGERPRINT,
                integrity.publishCommandFingerprint(request),
                revision.scope(),
                revision.corpusId(),
                publicationRevision,
                predecessor,
                revision.artifactRef(),
                CapabilityObservationTestFixtures.ref(
                        "CORPUS_PUBLICATION_POLICY",
                        "support-publication-policy",
                        4,
                        '2'),
                request.reviewTicketRef(),
                request.reasonCode(),
                "corpus-curator",
                publishedAt,
                revision.usableUntil()));
    }
}
