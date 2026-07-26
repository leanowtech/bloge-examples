package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Signed, payload-free reconciliation between one simulated business outcome and independent
 * authoritative business facts.
 *
 * <p>The protocol never trusts a producer-supplied pass/fail flag. It freezes the exact Fidelity
 * inventory unit, versioned outcome definition and attribution policy, pre-treatment cohort
 * selection, subject and attribution identities, attribution window, complete authority-set
 * watermarks, and every admitted outcome fact. {@link Reconciliation} is derived from those facts:
 * an open authority watermark is pending, a closed window without facts is censored, one closed
 * semantic value is matched or mismatched, and multiple values conflict. Business payload values
 * never enter the artifact.</p>
 *
 * @param schemaVersion exact outcome-observation protocol version
 * @param observationId stable observation identity
 * @param revision positive immutable revision
 * @param observationFingerprint canonical content address excluding this field and the seal
 * @param scope exact enterprise namespace
 * @param inventoryRef exact owner-approved Fidelity inventory
 * @param unitId exact inventory coverage unit
 * @param scenarioCaseRef exact Scenario case represented by the unit
 * @param targetCapabilityRef exact simulated capability revision
 * @param outcomeDefinitionRef exact owner-versioned business outcome definition
 * @param attributionPolicyRef exact owner-versioned attribution policy
 * @param authoritySetRef exact independently governed authority membership
 * @param selectionProof pre-treatment cohort inclusion proof
 * @param subjectFingerprint domain-separated subject identity without the business identifier
 * @param attributionKeyFingerprint domain-separated action-to-outcome correlation identity
 * @param modelOutcomeFingerprint normalized simulated business outcome
 * @param attributionWindow exact action and eligible outcome interval
 * @param reconciledAt trusted reconciliation cut
 * @param attestedAt Resource Gateway attestation time bound into the content address and signature
 * @param authorityWatermarks complete canonical authority-set watermarks at the cut
 * @param authorityFacts canonical independently sourced business outcome facts
 * @param reconciliation fact-derived reconciliation state
 * @param evidenceComplete whether every admitted fact exposes its complete claimed evidence
 * @param observationSeal detached Resource Gateway attestation over the content address
 */
public record AuthoritativeOutcomeObservation(
        String schemaVersion,
        String observationId,
        long revision,
        String observationFingerprint,
        CapabilitySnapshot.Scope scope,
        MirrorArtifactRef inventoryRef,
        String unitId,
        MirrorArtifactRef scenarioCaseRef,
        MirrorArtifactRef targetCapabilityRef,
        MirrorArtifactRef outcomeDefinitionRef,
        MirrorArtifactRef attributionPolicyRef,
        MirrorArtifactRef authoritySetRef,
        SelectionProof selectionProof,
        String subjectFingerprint,
        String attributionKeyFingerprint,
        String modelOutcomeFingerprint,
        AttributionWindow attributionWindow,
        Instant reconciledAt,
        Instant attestedAt,
        List<AuthorityWatermark> authorityWatermarks,
        List<AuthorityFact> authorityFacts,
        Reconciliation reconciliation,
        boolean evidenceComplete,
        VisualRunEvidenceSeal observationSeal
) {
    /** Current authoritative outcome observation wire version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.authoritativeOutcomeObservation.v1";
    /** Artifact kind admitted by the Domain Fidelity projection kernel. */
    public static final String ARTIFACT_KIND =
            "AUTHORITATIVE_OUTCOME_OBSERVATION";
    /** Maximum authority members represented by one observation. */
    public static final int MAXIMUM_AUTHORITIES = 64;
    /** Maximum independently sourced facts represented by one observation. */
    public static final int MAXIMUM_FACTS = 1_024;
    /** Maximum canonical observation bytes admitted to content addressing. */
    public static final int MAXIMUM_CANONICAL_BYTES =
            4 * 1024 * 1024;
    /** Maximum domain-separated signing-material bytes. */
    public static final int MAXIMUM_ATTESTATION_BYTES =
            16 * 1024;
    private static final Duration MAXIMUM_ATTRIBUTION_WINDOW =
            Duration.ofDays(365);
    private static final Pattern IDENTIFIER =
            Pattern.compile(
                    "[A-Za-z0-9][A-Za-z0-9@._:/-]{0,511}");
    private static final Pattern FINGERPRINT =
            Pattern.compile("sha256:[a-f0-9]{64}");

    /** Validates exact lineage, pre-treatment selection, temporal closure, and derived outcome. */
    public AuthoritativeOutcomeObservation {
        schemaVersion = version(schemaVersion);
        observationId = identifier(
                observationId, "observationId");
        if (revision < 1) {
            throw new IllegalArgumentException(
                    "outcome observation revision must be positive");
        }
        observationFingerprint = optionalFingerprint(
                observationFingerprint,
                "observationFingerprint");
        scope = Objects.requireNonNull(scope, "scope");
        inventoryRef = requireKind(
                inventoryRef,
                DomainFidelityInventory.ARTIFACT_KIND,
                "inventoryRef");
        unitId = identifier(unitId, "unitId");
        scenarioCaseRef = requireKind(
                scenarioCaseRef,
                "SCENARIO_CASE",
                "scenarioCaseRef");
        targetCapabilityRef = requireKind(
                targetCapabilityRef,
                "CAPABILITY",
                "targetCapabilityRef");
        outcomeDefinitionRef = requireKind(
                outcomeDefinitionRef,
                "OUTCOME_DEFINITION",
                "outcomeDefinitionRef");
        attributionPolicyRef = requireKind(
                attributionPolicyRef,
                "OUTCOME_ATTRIBUTION_POLICY",
                "attributionPolicyRef");
        authoritySetRef = requireKind(
                authoritySetRef,
                "OUTCOME_AUTHORITY_SET",
                "authoritySetRef");
        selectionProof = Objects.requireNonNull(
                selectionProof, "selectionProof");
        subjectFingerprint = fingerprint(
                subjectFingerprint,
                "subjectFingerprint");
        attributionKeyFingerprint = fingerprint(
                attributionKeyFingerprint,
                "attributionKeyFingerprint");
        modelOutcomeFingerprint = fingerprint(
                modelOutcomeFingerprint,
                "modelOutcomeFingerprint");
        attributionWindow = Objects.requireNonNull(
                attributionWindow, "attributionWindow");
        if (!selectionProof.selectedAt().isBefore(
                attributionWindow.actionOccurredAt())) {
            throw new IllegalArgumentException(
                    "outcome cohort selection must precede the business action");
        }
        reconciledAt = Objects.requireNonNull(
                reconciledAt, "reconciledAt");
        if (reconciledAt.isBefore(
                attributionWindow.actionOccurredAt())) {
            throw new IllegalArgumentException(
                    "outcome reconciliation cannot precede the business action");
        }
        attestedAt = Objects.requireNonNull(
                attestedAt, "attestedAt");
        if (attestedAt.isBefore(reconciledAt)) {
            throw new IllegalArgumentException(
                    "outcome attestation cannot precede reconciliation");
        }
        authorityWatermarks = authorityWatermarks == null
                ? List.of() : List.copyOf(authorityWatermarks);
        authorityFacts = authorityFacts == null
                ? List.of() : List.copyOf(authorityFacts);
        validateWatermarks(
                authorityWatermarks, reconciledAt);
        validateFacts(
                authorityFacts,
                authorityWatermarks,
                subjectFingerprint,
                attributionKeyFingerprint,
                attributionWindow,
                reconciledAt);
        Reconciliation derived = derive(
                modelOutcomeFingerprint,
                attributionWindow,
                authorityWatermarks,
                authorityFacts);
        reconciliation = Objects.requireNonNull(
                reconciliation, "reconciliation");
        if (reconciliation != derived) {
            throw new IllegalArgumentException(
                    "outcome reconciliation is not derived from the authority closure");
        }
        boolean complete = authorityFacts.stream()
                .allMatch(AuthorityFact::evidenceComplete);
        if (evidenceComplete != complete) {
            throw new IllegalArgumentException(
                    "outcome evidenceComplete is not derived from authority facts");
        }
        observationSeal = observationSeal == null
                ? VisualRunEvidenceSeal.unsigned()
                : observationSeal;
    }

    /**
     * Pre-treatment calibration-cohort selection proof.
     *
     * @param cohortRef exact immutable calibration cohort
     * @param samplingFrameRef exact eligible-population snapshot
     * @param stratumId exact owner-defined sampling stratum
     * @param inclusionFingerprint domain-separated deterministic inclusion material
     * @param selectedAt selection time, checked against the business action by the outer protocol
     * @param eligiblePopulationSize complete stratum population
     * @param selectedPopulationSize admitted stratum sample
     * @param sampleOrdinal one-based deterministic sample position
     * @param selectionMode closed pre-treatment selection algorithm
     */
    public record SelectionProof(
            MirrorArtifactRef cohortRef,
            MirrorArtifactRef samplingFrameRef,
            String stratumId,
            String inclusionFingerprint,
            Instant selectedAt,
            long eligiblePopulationSize,
            long selectedPopulationSize,
            long sampleOrdinal,
            SelectionMode selectionMode
    ) {
        /** Enforces a bounded, immutable, non-post-treatment sample coordinate. */
        public SelectionProof {
            cohortRef = requireKind(
                    cohortRef,
                    "OUTCOME_CALIBRATION_COHORT",
                    "cohortRef");
            samplingFrameRef = requireKind(
                    samplingFrameRef,
                    "OUTCOME_SAMPLING_FRAME",
                    "samplingFrameRef");
            stratumId = identifier(stratumId, "stratumId");
            inclusionFingerprint = fingerprint(
                    inclusionFingerprint,
                    "inclusionFingerprint");
            selectedAt = Objects.requireNonNull(
                    selectedAt, "selectedAt");
            selectionMode = Objects.requireNonNull(
                    selectionMode, "selectionMode");
            if (eligiblePopulationSize < 1
                    || selectedPopulationSize < 1
                    || selectedPopulationSize
                    > eligiblePopulationSize
                    || sampleOrdinal < 1
                    || sampleOrdinal > selectedPopulationSize
                    || selectionMode == SelectionMode.CENSUS
                    && selectedPopulationSize
                    != eligiblePopulationSize) {
                throw new IllegalArgumentException(
                        "outcome cohort selection arithmetic is invalid");
            }
        }
    }

    /**
     * Exact action-to-outcome attribution interval.
     *
     * @param actionOccurredAt simulated business action time
     * @param opensAt inclusive earliest attributable outcome event time
     * @param closesAt inclusive latest attributable outcome event time
     */
    public record AttributionWindow(
            Instant actionOccurredAt,
            Instant opensAt,
            Instant closesAt
    ) {
        /** Requires an ordered, positive, bounded attribution interval. */
        public AttributionWindow {
            actionOccurredAt = Objects.requireNonNull(
                    actionOccurredAt, "actionOccurredAt");
            opensAt = Objects.requireNonNull(
                    opensAt, "opensAt");
            closesAt = Objects.requireNonNull(
                    closesAt, "closesAt");
            Duration duration = Duration.between(
                    opensAt, closesAt);
            if (opensAt.isBefore(actionOccurredAt)
                    || !closesAt.isAfter(opensAt)
                    || duration.compareTo(
                    MAXIMUM_ATTRIBUTION_WINDOW) > 0) {
                throw new IllegalArgumentException(
                        "outcome attribution window is invalid");
            }
        }
    }

    /**
     * Independently published event-time progress for one required outcome authority.
     *
     * @param authorityId exact authority-set member
     * @param watermarkRef exact signed source watermark
     * @param eventTimeThrough inclusive event-time coverage
     * @param publishedAt authority publication time
     */
    public record AuthorityWatermark(
            String authorityId,
            MirrorArtifactRef watermarkRef,
            Instant eventTimeThrough,
            Instant publishedAt
    ) {
        /** Validates one bounded authority progress coordinate. */
        public AuthorityWatermark {
            authorityId = identifier(
                    authorityId, "authorityId");
            watermarkRef = requireKind(
                    watermarkRef,
                    "AUTHORITATIVE_OUTCOME_SOURCE_WATERMARK",
                    "watermarkRef");
            eventTimeThrough = Objects.requireNonNull(
                    eventTimeThrough, "eventTimeThrough");
            publishedAt = Objects.requireNonNull(
                    publishedAt, "publishedAt");
            if (eventTimeThrough.isAfter(publishedAt)) {
                throw new IllegalArgumentException(
                        "outcome authority watermark cannot cover future event time");
            }
        }
    }

    /**
     * One payload-free independently governed business outcome fact.
     *
     * @param authorityId exact authority-set member
     * @param sourceRef exact signed source record
     * @param subjectFingerprint exact observation subject identity
     * @param attributionKeyFingerprint exact action correlation identity
     * @param outcomeFingerprint normalized business outcome under the exact definition
     * @param occurredAt authoritative business event time
     * @param recordedAt authoritative source publication time
     * @param evidenceComplete whether the source exposed every fact it claimed to expose
     */
    public record AuthorityFact(
            String authorityId,
            MirrorArtifactRef sourceRef,
            String subjectFingerprint,
            String attributionKeyFingerprint,
            String outcomeFingerprint,
            Instant occurredAt,
            Instant recordedAt,
            boolean evidenceComplete
    ) {
        /** Validates one payload-free authority fact before outer closure checks. */
        public AuthorityFact {
            authorityId = identifier(
                    authorityId, "authorityId");
            sourceRef = requireKind(
                    sourceRef,
                    "AUTHORITATIVE_OUTCOME_SOURCE_RECORD",
                    "sourceRef");
            subjectFingerprint = fingerprint(
                    subjectFingerprint,
                    "subjectFingerprint");
            attributionKeyFingerprint = fingerprint(
                    attributionKeyFingerprint,
                    "attributionKeyFingerprint");
            outcomeFingerprint = fingerprint(
                    outcomeFingerprint,
                    "outcomeFingerprint");
            occurredAt = Objects.requireNonNull(
                    occurredAt, "occurredAt");
            recordedAt = Objects.requireNonNull(
                    recordedAt, "recordedAt");
            if (recordedAt.isBefore(occurredAt)) {
                throw new IllegalArgumentException(
                        "outcome authority fact cannot be recorded before it occurred");
            }
        }
    }

    /** Closed pre-treatment calibration-cohort selection algorithms. */
    public enum SelectionMode {
        CENSUS,
        HASH_PARTITION,
        STRATIFIED_RANDOM
    }

    /** Deterministically derived outcome reconciliation states. */
    public enum Reconciliation {
        MATCH,
        MISMATCH,
        PENDING,
        CENSORED,
        CONFLICT
    }

    /**
     * Recomputes protocol semantics and content addressing.
     *
     * @param mapper canonical protocol mapper
     */
    public void verify(ObjectMapper mapper) {
        if (observationFingerprint.isBlank()
                || !observationFingerprint.equals(
                calculateFingerprint(mapper))) {
            throw new IllegalArgumentException(
                    "Authoritative outcome observation fingerprint mismatch");
        }
    }

    /**
     * Calculates the content address with address and detached seal blanked.
     *
     * @param mapper canonical protocol mapper
     * @return canonical SHA-256 content address
     */
    public String calculateFingerprint(ObjectMapper mapper) {
        return ProtocolFingerprint.ofBounded(
                Objects.requireNonNull(mapper, "mapper"),
                withFingerprintAndSeal(
                        "",
                        VisualRunEvidenceSeal.unsigned()),
                MAXIMUM_CANONICAL_BYTES);
    }

    /**
     * Returns the domain-separated producer signing material.
     *
     * @param mapper canonical protocol mapper
     * @return canonical SHA-256 attestation material
     */
    public String attestationMaterialFingerprint(
            ObjectMapper mapper) {
        if (observationFingerprint.isBlank()) {
            throw new IllegalStateException(
                    "outcome observation must be content-addressed before signing");
        }
        return ProtocolFingerprint.ofBounded(
                Objects.requireNonNull(mapper, "mapper"),
                new AttestationMaterial(
                        "RESOURCE_GATEWAY_AUTHORITATIVE_OUTCOME_OBSERVATION_V1",
                        schemaVersion,
                        observationId,
                        revision,
                        inventoryRef,
                        unitId,
                        reconciledAt,
                        attestedAt,
                        observationFingerprint),
                MAXIMUM_ATTESTATION_BYTES);
    }

    /** @return exact content-addressed artifact reference */
    public MirrorArtifactRef artifactRef() {
        if (observationFingerprint.isBlank()) {
            throw new IllegalStateException(
                    "outcome observation is not content-addressed");
        }
        return new MirrorArtifactRef(
                ARTIFACT_KIND,
                observationId,
                revision,
                observationFingerprint);
    }

    /**
     * Attaches a detached signature without changing the content address.
     *
     * @param seal producer signature
     * @return identical observation carrying the seal
     */
    public AuthoritativeOutcomeObservation withObservationSeal(
            VisualRunEvidenceSeal seal) {
        return withFingerprintAndSeal(
                observationFingerprint,
                Objects.requireNonNull(seal, "seal"));
    }

    /** @return identical observation carrying a replacement content address */
    AuthoritativeOutcomeObservation withFingerprint(
            String value) {
        return withFingerprintAndSeal(
                value, VisualRunEvidenceSeal.unsigned());
    }

    /**
     * Replaces the provisional attestation time before content addressing.
     *
     * @param value trusted Resource Gateway signing-intent time
     * @return unsigned observation carrying the exact attestation time
     */
    AuthoritativeOutcomeObservation withAttestedAt(
            Instant value) {
        return new AuthoritativeOutcomeObservation(
                schemaVersion,
                observationId,
                revision,
                "",
                scope,
                inventoryRef,
                unitId,
                scenarioCaseRef,
                targetCapabilityRef,
                outcomeDefinitionRef,
                attributionPolicyRef,
                authoritySetRef,
                selectionProof,
                subjectFingerprint,
                attributionKeyFingerprint,
                modelOutcomeFingerprint,
                attributionWindow,
                reconciledAt,
                Objects.requireNonNull(value, "value"),
                authorityWatermarks,
                authorityFacts,
                reconciliation,
                evidenceComplete,
                VisualRunEvidenceSeal.unsigned());
    }

    /** Keeps subject, attribution, and source identities out of generic logs. */
    @Override
    public String toString() {
        return "AuthoritativeOutcomeObservation[observationId="
                + observationId + ", revision=" + revision
                + ", unitId=" + unitId
                + ", reconciliation=" + reconciliation + "]";
    }

    private AuthoritativeOutcomeObservation withFingerprintAndSeal(
            String fingerprint,
            VisualRunEvidenceSeal seal) {
        return new AuthoritativeOutcomeObservation(
                schemaVersion,
                observationId,
                revision,
                fingerprint,
                scope,
                inventoryRef,
                unitId,
                scenarioCaseRef,
                targetCapabilityRef,
                outcomeDefinitionRef,
                attributionPolicyRef,
                authoritySetRef,
                selectionProof,
                subjectFingerprint,
                attributionKeyFingerprint,
                modelOutcomeFingerprint,
                attributionWindow,
                reconciledAt,
                attestedAt,
                authorityWatermarks,
                authorityFacts,
                reconciliation,
                evidenceComplete,
                seal);
    }

    private static void validateWatermarks(
            List<AuthorityWatermark> watermarks,
            Instant reconciledAt) {
        if (watermarks.isEmpty()
                || watermarks.size() > MAXIMUM_AUTHORITIES) {
            throw new IllegalArgumentException(
                    "outcome authority watermark closure is empty or too large");
        }
        String previous = "";
        Set<String> authorities = new HashSet<>();
        Set<MirrorArtifactRef> refs = new HashSet<>();
        for (AuthorityWatermark watermark : watermarks) {
            AuthorityWatermark exact = Objects.requireNonNull(
                    watermark, "authorityWatermark");
            if (!authorities.add(exact.authorityId())
                    || !refs.add(exact.watermarkRef())
                    || exact.authorityId()
                    .compareTo(previous) <= 0
                    || exact.publishedAt().isAfter(reconciledAt)) {
                throw new IllegalArgumentException(
                        "outcome authority watermarks must be unique, ordered, and visible at the cut");
            }
            previous = exact.authorityId();
        }
    }

    private static void validateFacts(
            List<AuthorityFact> facts,
            List<AuthorityWatermark> watermarks,
            String subjectFingerprint,
            String attributionKeyFingerprint,
            AttributionWindow window,
            Instant reconciledAt) {
        if (facts.size() > MAXIMUM_FACTS) {
            throw new IllegalArgumentException(
                    "outcome authority fact closure is too large");
        }
        Set<String> authorityIds = watermarks.stream()
                .map(AuthorityWatermark::authorityId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Set<MirrorArtifactRef> refs = new HashSet<>();
        AuthorityFact previous = null;
        for (AuthorityFact fact : facts) {
            AuthorityFact exact = Objects.requireNonNull(
                    fact, "authorityFact");
            if (!authorityIds.contains(exact.authorityId())
                    || !refs.add(exact.sourceRef())
                    || !subjectFingerprint.equals(
                    exact.subjectFingerprint())
                    || !attributionKeyFingerprint.equals(
                    exact.attributionKeyFingerprint())
                    || exact.occurredAt().isBefore(
                    window.opensAt())
                    || exact.occurredAt().isAfter(
                    window.closesAt())
                    || exact.recordedAt().isAfter(reconciledAt)
                    || previous != null
                    && compareFact(previous, exact) >= 0) {
                throw new IllegalArgumentException(
                        "outcome authority facts do not form one unique ordered attribution closure");
            }
            previous = exact;
        }
    }

    private static int compareFact(
            AuthorityFact left,
            AuthorityFact right) {
        int authority = left.authorityId()
                .compareTo(right.authorityId());
        if (authority != 0) {
            return authority;
        }
        int occurred = left.occurredAt()
                .compareTo(right.occurredAt());
        if (occurred != 0) {
            return occurred;
        }
        return left.sourceRef().fingerprint()
                .compareTo(right.sourceRef().fingerprint());
    }

    private static Reconciliation derive(
            String modelOutcomeFingerprint,
            AttributionWindow window,
            List<AuthorityWatermark> watermarks,
            List<AuthorityFact> facts) {
        boolean closed = watermarks.stream()
                .map(AuthorityWatermark::eventTimeThrough)
                .min(Comparator.naturalOrder())
                .orElseThrow()
                .compareTo(window.closesAt()) >= 0;
        if (!closed) {
            return Reconciliation.PENDING;
        }
        if (facts.isEmpty()) {
            return Reconciliation.CENSORED;
        }
        Set<String> outcomes = facts.stream()
                .map(AuthorityFact::outcomeFingerprint)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (outcomes.size() > 1) {
            return Reconciliation.CONFLICT;
        }
        return outcomes.contains(modelOutcomeFingerprint)
                ? Reconciliation.MATCH
                : Reconciliation.MISMATCH;
    }

    private record AttestationMaterial(
            String domain,
            String schemaVersion,
            String observationId,
            long revision,
            MirrorArtifactRef inventoryRef,
            String unitId,
            Instant reconciledAt,
            Instant attestedAt,
            String observationFingerprint
    ) {
    }

    private static String version(String value) {
        String exact = value == null || value.isBlank()
                ? SCHEMA_VERSION : value.trim();
        if (!SCHEMA_VERSION.equals(exact)) {
            throw new IllegalArgumentException(
                    "unsupported authoritative outcome observation schemaVersion");
        }
        return exact;
    }

    private static String identifier(
            String value, String field) {
        String exact = MirrorStateProtocolSupport.required(
                value, field);
        if (!IDENTIFIER.matcher(exact).matches()) {
            throw new IllegalArgumentException(
                    field + " is invalid");
        }
        return exact;
    }

    private static String fingerprint(
            String value, String field) {
        String exact = MirrorStateProtocolSupport.required(
                value, field);
        if (!FINGERPRINT.matcher(exact).matches()) {
            throw new IllegalArgumentException(
                    field + " must be a canonical SHA-256 fingerprint");
        }
        return exact;
    }

    private static String optionalFingerprint(
            String value, String field) {
        String exact = value == null ? "" : value.trim();
        if (!exact.isEmpty()
                && !FINGERPRINT.matcher(exact).matches()) {
            throw new IllegalArgumentException(
                    field + " must be blank or a canonical SHA-256 fingerprint");
        }
        return exact;
    }

    private static MirrorArtifactRef requireKind(
            MirrorArtifactRef value,
            String kind,
            String field) {
        if (value == null || !kind.equals(value.kind())) {
            throw new IllegalArgumentException(
                    field + " must be an exact " + kind + " ref");
        }
        return value;
    }
}
