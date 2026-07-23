package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Immutable payload-free corpus revision derived from admitted capability observations.
 *
 * <p>A revision is only a governance candidate. Runtime serving requires a separate
 * {@link CapabilityCorpusPublication}; this prevents candidate creation, risk analysis, or a
 * repository bug from silently changing runtime behavior. Every source binds exact observation,
 * admission, payload, proof, schema, producer-key, trace, and use-horizon metadata without storing
 * request or response bytes.</p>
 *
 * @param schemaVersion corpus revision wire version
 * @param revisionFingerprint canonical revision fingerprint
 * @param sourceCommandFingerprint canonical candidate-command fingerprint
 * @param scope complete enterprise scope
 * @param corpusId stable corpus identity
 * @param revision positive append-only revision
 * @param predecessorRef exact previous revision, absent for revision one
 * @param capabilityRef exact capability shared by all sources
 * @param governancePolicyRef exact operator-owned candidate policy
 * @param sources immutable source observations
 * @param riskSummary deterministic metadata-only risk assessment
 * @param createdBy authenticated creator identity
 * @param createdAt trusted local creation time
 * @param usableUntil earliest exclusive source-use horizon
 */
public record CapabilityCorpusRevision(
        String schemaVersion,
        String revisionFingerprint,
        String sourceCommandFingerprint,
        CapabilitySnapshot.Scope scope,
        String corpusId,
        long revision,
        MirrorArtifactRef predecessorRef,
        MirrorArtifactRef capabilityRef,
        MirrorArtifactRef governancePolicyRef,
        List<SourceObservation> sources,
        RiskSummary riskSummary,
        String createdBy,
        Instant createdAt,
        Instant usableUntil
) {
    /** Current corpus revision version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.capabilityCorpusRevision.v1";
    /** Artifact kind used by exact corpus revision references. */
    public static final String ARTIFACT_KIND = "CAPABILITY_CORPUS_REVISION";
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    /** Validates revision lineage, source ordering, and use-horizon invariants. */
    public CapabilityCorpusRevision {
        schemaVersion = version(schemaVersion);
        revisionFingerprint = fingerprint(
                revisionFingerprint, "revisionFingerprint");
        sourceCommandFingerprint = fingerprint(
                sourceCommandFingerprint, "sourceCommandFingerprint");
        scope = Objects.requireNonNull(scope, "scope");
        corpusId = identifier(corpusId, "corpusId");
        if (revision < 1) {
            throw new IllegalArgumentException("revision must be positive");
        }
        if (predecessorRef != null) {
            predecessorRef = ref(
                    predecessorRef, ARTIFACT_KIND, "predecessorRef");
        }
        if (revision == 1 && predecessorRef != null
                || revision > 1 && (predecessorRef == null
                || !predecessorRef.id().equals(corpusId)
                || predecessorRef.revision() != revision - 1)) {
            throw new IllegalArgumentException(
                    "predecessorRef does not describe the previous corpus revision");
        }
        capabilityRef = ref(capabilityRef, "CAPABILITY", "capabilityRef");
        governancePolicyRef = ref(
                governancePolicyRef,
                "CORPUS_GOVERNANCE_POLICY",
                "governancePolicyRef");
        if (sources == null || sources.isEmpty()
                || sources.size() > CapabilityCorpusCandidateRequest.MAXIMUM_SOURCES) {
            throw new IllegalArgumentException("sources size is invalid");
        }
        sources = List.copyOf(sources);
        String previous = "";
        for (SourceObservation source : sources) {
            SourceObservation exact = Objects.requireNonNull(source, "source");
            String current = exact.observationRef().id();
            if (current.compareTo(previous) <= 0) {
                throw new IllegalArgumentException(
                        "sources must be strictly ordered by observation id");
            }
            previous = current;
        }
        riskSummary = Objects.requireNonNull(riskSummary, "riskSummary");
        if (riskSummary.sampleCount() != sources.size()) {
            throw new IllegalArgumentException(
                    "riskSummary sample count does not match sources");
        }
        createdBy = identifier(createdBy, "createdBy");
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        usableUntil = Objects.requireNonNull(usableUntil, "usableUntil");
        if (!usableUntil.isAfter(createdAt)) {
            throw new IllegalArgumentException(
                    "usableUntil exceeds one or more source horizons");
        }
        for (SourceObservation source : sources) {
            if (source.usableUntil().isBefore(usableUntil)) {
                throw new IllegalArgumentException(
                        "usableUntil exceeds one or more source horizons");
            }
        }
    }

    /**
     * Returns the exact revision reference.
     *
     * @return immutable corpus revision reference
     */
    public MirrorArtifactRef artifactRef() {
        return new MirrorArtifactRef(
                ARTIFACT_KIND, corpusId, revision, revisionFingerprint);
    }

    /**
     * Payload-free projection of one admitted observation.
     *
     * @param observationRef exact signed observation
     * @param admissionRef exact admitted decision
     * @param requestPayloadRef sanitized request payload content address
     * @param requestProofRef exact request sanitization proof
     * @param requestSchemaRef exact request JSON Schema
     * @param responsePayloadRef sanitized response payload, absent on error
     * @param responseProofRef response sanitization proof, absent on error
     * @param responseSchemaRef response JSON Schema, absent on error
     * @param normalizedErrorCode payload-free error code, blank on response
     * @param traceFingerprint one-way trace coordinate fingerprint
     * @param authorityKeyRef exact producer verification key
     * @param occurredAt source occurrence time
     * @param usableUntil exclusive source-use horizon
     */
    public record SourceObservation(
            MirrorArtifactRef observationRef,
            MirrorArtifactRef admissionRef,
            MirrorArtifactRef requestPayloadRef,
            MirrorArtifactRef requestProofRef,
            MirrorArtifactRef requestSchemaRef,
            MirrorArtifactRef responsePayloadRef,
            MirrorArtifactRef responseProofRef,
            MirrorArtifactRef responseSchemaRef,
            String normalizedErrorCode,
            String traceFingerprint,
            MirrorArtifactRef authorityKeyRef,
            Instant occurredAt,
            Instant usableUntil
    ) {
        /** Validates exact payload-free source coordinates. */
        public SourceObservation {
            observationRef = ref(
                    observationRef,
                    CapabilityObservationEnvelope.ARTIFACT_KIND,
                    "observationRef");
            admissionRef = ref(
                    admissionRef,
                    CapabilityObservationAdmission.ARTIFACT_KIND,
                    "admissionRef");
            if (!admissionRef.id().equals(observationRef.id() + ":admission")) {
                throw new IllegalArgumentException(
                        "admissionRef must belong to observationRef");
            }
            requestPayloadRef = ref(
                    requestPayloadRef, "SANITIZED_PAYLOAD", "requestPayloadRef");
            requestProofRef = ref(
                    requestProofRef, "PAYLOAD_SANITIZATION_PROOF", "requestProofRef");
            requestSchemaRef = ref(
                    requestSchemaRef, "JSON_SCHEMA", "requestSchemaRef");
            boolean response = responsePayloadRef != null
                    || responseProofRef != null || responseSchemaRef != null;
            normalizedErrorCode = normalizedErrorCode == null
                    ? "" : normalizedErrorCode.trim();
            if (response) {
                responsePayloadRef = ref(
                        responsePayloadRef,
                        "SANITIZED_PAYLOAD",
                        "responsePayloadRef");
                responseProofRef = ref(
                        responseProofRef,
                        "PAYLOAD_SANITIZATION_PROOF",
                        "responseProofRef");
                responseSchemaRef = ref(
                        responseSchemaRef, "JSON_SCHEMA", "responseSchemaRef");
            }
            if (response == !normalizedErrorCode.isBlank()
                    || !normalizedErrorCode.isBlank()
                    && !normalizedErrorCode.matches("[A-Z][A-Z0-9_.-]{0,127}")) {
                throw new IllegalArgumentException(
                        "source must contain exactly one response or normalized error");
            }
            traceFingerprint = fingerprint(
                    traceFingerprint, "traceFingerprint");
            authorityKeyRef = ref(
                    authorityKeyRef,
                    "OBSERVATION_AUTHORITY_KEY",
                    "authorityKeyRef");
            occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
            usableUntil = Objects.requireNonNull(usableUntil, "usableUntil");
            if (!usableUntil.isAfter(occurredAt)) {
                throw new IllegalArgumentException(
                        "source usableUntil must follow occurredAt");
            }
        }
    }

    /**
     * Deterministic metadata-only candidate risk assessment.
     *
     * @param sampleCount total source count
     * @param uniqueRequestCount unique sanitized request content addresses
     * @param duplicateRequestCount repeated request addresses
     * @param maximumRequestMultiplicity largest request-address multiplicity
     * @param producerKeyCount distinct trusted producer keys
     * @param duplicateBasisPoints duplicate share in basis points
     * @param eligibility publication eligibility
     * @param reasons closed risk reasons
     */
    public record RiskSummary(
            int sampleCount,
            int uniqueRequestCount,
            int duplicateRequestCount,
            int maximumRequestMultiplicity,
            int producerKeyCount,
            int duplicateBasisPoints,
            Eligibility eligibility,
            Set<RiskReason> reasons
    ) {
        /** Validates bounded internally consistent risk statistics. */
        public RiskSummary {
            if (sampleCount < 1
                    || uniqueRequestCount < 1
                    || uniqueRequestCount > sampleCount
                    || duplicateRequestCount != sampleCount - uniqueRequestCount
                    || maximumRequestMultiplicity < 1
                    || maximumRequestMultiplicity > sampleCount
                    || producerKeyCount < 1
                    || producerKeyCount > sampleCount
                    || duplicateBasisPoints < 0
                    || duplicateBasisPoints > 10_000) {
                throw new IllegalArgumentException("risk summary is inconsistent");
            }
            eligibility = Objects.requireNonNull(eligibility, "eligibility");
            reasons = reasons == null ? Set.of() : Set.copyOf(reasons);
            if (eligibility == Eligibility.ELIGIBLE && !reasons.isEmpty()
                    || eligibility == Eligibility.BLOCKED && reasons.isEmpty()) {
                throw new IllegalArgumentException(
                        "risk eligibility and reasons are inconsistent");
            }
        }
    }

    /** Candidate publication eligibility. */
    public enum Eligibility {
        /** Deterministic policy gates passed; owner review is still mandatory. */
        ELIGIBLE,
        /** One or more hard metadata risk gates failed. */
        BLOCKED
    }

    /** Closed deterministic metadata risk reasons. */
    public enum RiskReason {
        /** Too few observations exist for the operator-owned policy. */
        INSUFFICIENT_SAMPLE_COUNT,
        /** The candidate exceeds its bounded source count. */
        EXCESSIVE_SAMPLE_COUNT,
        /** Repeated sanitized requests exceed the allowed ratio. */
        DUPLICATE_REQUEST_RATIO_EXCEEDED,
        /** Too few independent producer keys contributed observations. */
        PRODUCER_DIVERSITY_INSUFFICIENT,
        /** Source retention is too short for the required serving horizon. */
        SERVING_HORIZON_INSUFFICIENT
    }

    private static MirrorArtifactRef ref(
            MirrorArtifactRef value, String kind, String field) {
        MirrorArtifactRef exact = Objects.requireNonNull(value, field);
        if (!kind.equals(exact.kind())) {
            throw new IllegalArgumentException(field + " must reference " + kind);
        }
        return exact;
    }

    private static String version(String value) {
        String exact = value == null || value.isBlank()
                ? SCHEMA_VERSION : value.trim();
        if (!SCHEMA_VERSION.equals(exact)) {
            throw new IllegalArgumentException(
                    "unsupported capability corpus revision schemaVersion");
        }
        return exact;
    }

    private static String fingerprint(String value, String field) {
        String exact = value == null ? "" : value.trim();
        if (!FINGERPRINT.matcher(exact).matches()) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return exact;
    }

    private static String identifier(String value, String field) {
        String exact = value == null ? "" : value.trim();
        if (!exact.matches("[A-Za-z0-9][A-Za-z0-9@._:/#-]{0,511}")) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return exact;
    }
}
