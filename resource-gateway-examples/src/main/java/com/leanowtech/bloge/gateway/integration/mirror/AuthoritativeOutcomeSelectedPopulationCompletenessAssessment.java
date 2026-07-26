package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Signed denominator-preserving reconciliation of one selected population against current outcome
 * observations and governed legal dispositions.
 *
 * <p>The assessment distinguishes arrival completeness from terminal outcome completeness.
 * Pending observations count as arrived; missing members do not. Censored, conflicting, and
 * legally deleted members remain explicit debt and are never silently removed from the selected
 * denominator.</p>
 *
 * @param schemaVersion exact assessment protocol version
 * @param assessmentId stable assessment identity
 * @param revision positive immutable assessment revision
 * @param assessmentFingerprint canonical content address excluding this field and the seal
 * @param scope exact enterprise namespace
 * @param populationRef exact selected-population root
 * @param assessedAt trusted assessment cut
 * @param observationSetFingerprint exact ordered current-head observation closure
 * @param dispositionSetFingerprint exact ordered legal-disposition closure
 * @param strata complete canonical per-stratum counts
 * @param totals exact selected-population totals
 * @param submissionComplete whether no selected member is missing
 * @param terminalComplete whether no selected member is missing or pending
 * @param assessmentSeal detached Resource Gateway signature
 */
public record
AuthoritativeOutcomeSelectedPopulationCompletenessAssessment(
        String schemaVersion,
        String assessmentId,
        long revision,
        String assessmentFingerprint,
        CapabilitySnapshot.Scope scope,
        MirrorArtifactRef populationRef,
        Instant assessedAt,
        String observationSetFingerprint,
        String dispositionSetFingerprint,
        List<StratumAssessment> strata,
        Counts totals,
        boolean submissionComplete,
        boolean terminalComplete,
        VisualRunEvidenceSeal assessmentSeal
) {
    /** Current selected-population completeness wire version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.authoritativeOutcomeSelectedPopulationCompletenessAssessment.v1";
    /** Artifact kind consumed by governance and future calibration projection. */
    public static final String ARTIFACT_KIND =
            "AUTHORITATIVE_OUTCOME_SELECTED_POPULATION_COMPLETENESS";
    /** Maximum canonical assessment bytes. */
    public static final int MAXIMUM_CANONICAL_BYTES =
            8 * 1024 * 1024;
    /** Maximum domain-separated signing-material bytes. */
    public static final int MAXIMUM_ATTESTATION_BYTES =
            16 * 1024;
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9@._:/-]{0,511}");
    private static final Pattern FINGERPRINT =
            Pattern.compile("sha256:[a-f0-9]{64}");

    /** Enforces canonical strata and denominator-preserving arithmetic. */
    public AuthoritativeOutcomeSelectedPopulationCompletenessAssessment {
        schemaVersion = version(schemaVersion);
        assessmentId = identifier(
                assessmentId, "assessmentId");
        if (revision < 1) {
            throw new IllegalArgumentException(
                    "selected population completeness revision must be positive");
        }
        assessmentFingerprint = optionalFingerprint(
                assessmentFingerprint,
                "assessmentFingerprint");
        scope = Objects.requireNonNull(scope, "scope");
        populationRef = requireKind(
                populationRef,
                AuthoritativeOutcomeSelectedPopulationManifest
                        .ARTIFACT_KIND,
                "populationRef");
        assessedAt = Objects.requireNonNull(
                assessedAt, "assessedAt");
        observationSetFingerprint = fingerprint(
                observationSetFingerprint,
                "observationSetFingerprint");
        dispositionSetFingerprint = fingerprint(
                dispositionSetFingerprint,
                "dispositionSetFingerprint");
        strata = strata == null
                ? List.of() : List.copyOf(strata);
        if (strata.isEmpty()
                || strata.size()
                > AuthoritativeOutcomeSelectedPopulationManifest
                .MAXIMUM_STRATA) {
            throw new IllegalArgumentException(
                    "selected population completeness strata must be non-empty and bounded");
        }
        Counts derived = Counts.zero();
        StratumAssessment previous = null;
        for (StratumAssessment stratum : strata) {
            StratumAssessment exact = Objects.requireNonNull(
                    stratum, "stratumAssessment");
            if (previous != null
                    && compareStrata(previous, exact) >= 0) {
                throw new IllegalArgumentException(
                        "selected population completeness strata must be unique and ordered");
            }
            derived = derived.plus(exact.counts());
            previous = exact;
        }
        totals = Objects.requireNonNull(totals, "totals");
        if (!totals.equals(derived)
                || submissionComplete
                != (totals.missing() == 0)
                || terminalComplete
                != (totals.missing() == 0
                && totals.pending() == 0)
                || terminalComplete && !submissionComplete) {
            throw new IllegalArgumentException(
                    "selected population completeness flags or totals are not derived");
        }
        assessmentSeal = assessmentSeal == null
                ? VisualRunEvidenceSeal.unsigned()
                : assessmentSeal;
    }

    /**
     * Completeness result for one unit and stratum.
     *
     * @param unitId exact Fidelity inventory unit
     * @param stratumId exact owner-defined sampling stratum
     * @param counts complete denominator partition
     */
    public record StratumAssessment(
            String unitId,
            String stratumId,
            Counts counts
    ) {
        /** Validates one canonical non-empty stratum result. */
        public StratumAssessment {
            unitId = identifier(unitId, "unitId");
            stratumId = identifier(
                    stratumId, "stratumId");
            counts = Objects.requireNonNull(
                    counts, "counts");
            if (counts.expected() < 1) {
                throw new IllegalArgumentException(
                        "selected population completeness stratum cannot be empty");
            }
        }
    }

    /**
     * Exact partition of a selected denominator.
     *
     * @param expected selected members
     * @param matched arrived members reconciled as MATCH
     * @param mismatched arrived members reconciled as MISMATCH
     * @param pending arrived members awaiting closed authority watermarks
     * @param censored arrived members with closed windows and no fact
     * @param conflicting arrived members with conflicting authoritative facts
     * @param legallyDeleted independently authorized deleted members
     * @param missing selected members with neither an observation nor legal disposition
     */
    public record Counts(
            long expected,
            long matched,
            long mismatched,
            long pending,
            long censored,
            long conflicting,
            long legallyDeleted,
            long missing
    ) {
        /** Enforces a non-negative, denominator-preserving partition. */
        public Counts {
            if (expected < 0
                    || matched < 0
                    || mismatched < 0
                    || pending < 0
                    || censored < 0
                    || conflicting < 0
                    || legallyDeleted < 0
                    || missing < 0
                    || expected != Math.addExact(
                    Math.addExact(
                            Math.addExact(matched, mismatched),
                            Math.addExact(pending, censored)),
                    Math.addExact(
                            Math.addExact(
                                    conflicting,
                                    legallyDeleted),
                            missing))) {
                throw new IllegalArgumentException(
                        "selected population completeness counts do not preserve the denominator");
            }
        }

        /** @return zero-valued accumulation identity */
        public static Counts zero() {
            return new Counts(
                    0, 0, 0, 0, 0, 0, 0, 0);
        }

        /**
         * Adds another disjoint count partition.
         *
         * @param other disjoint counts
         * @return exact sum
         */
        public Counts plus(Counts other) {
            Counts value = Objects.requireNonNull(
                    other, "other");
            return new Counts(
                    Math.addExact(expected, value.expected),
                    Math.addExact(matched, value.matched),
                    Math.addExact(
                            mismatched, value.mismatched),
                    Math.addExact(pending, value.pending),
                    Math.addExact(censored, value.censored),
                    Math.addExact(
                            conflicting, value.conflicting),
                    Math.addExact(
                            legallyDeleted,
                            value.legallyDeleted),
                    Math.addExact(missing, value.missing));
        }
    }

    /**
     * Recomputes protocol semantics and content addressing.
     *
     * @param mapper canonical protocol mapper
     */
    public void verify(ObjectMapper mapper) {
        if (assessmentFingerprint.isBlank()
                || !assessmentFingerprint.equals(
                calculateFingerprint(mapper))) {
            throw new IllegalArgumentException(
                    "selected population completeness fingerprint mismatch");
        }
    }

    /**
     * Calculates the content address with address and seal blanked.
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
     * Returns domain-separated Resource Gateway signing material.
     *
     * @param mapper canonical protocol mapper
     * @return canonical SHA-256 attestation material
     */
    public String attestationMaterialFingerprint(
            ObjectMapper mapper) {
        if (assessmentFingerprint.isBlank()) {
            throw new IllegalStateException(
                    "selected population completeness must be content-addressed before signing");
        }
        return ProtocolFingerprint.ofBounded(
                Objects.requireNonNull(mapper, "mapper"),
                new AttestationMaterial(
                        "RESOURCE_GATEWAY_AUTHORITATIVE_OUTCOME_POPULATION_COMPLETENESS_V1",
                        schemaVersion,
                        assessmentId,
                        revision,
                        populationRef,
                        assessedAt,
                        observationSetFingerprint,
                        dispositionSetFingerprint,
                        assessmentFingerprint),
                MAXIMUM_ATTESTATION_BYTES);
    }

    /** @return exact completeness assessment reference after signing */
    public MirrorArtifactRef artifactRef() {
        if (assessmentFingerprint.isBlank()) {
            throw new IllegalStateException(
                    "selected population completeness is not content-addressed");
        }
        return new MirrorArtifactRef(
                ARTIFACT_KIND,
                assessmentId,
                revision,
                assessmentFingerprint);
    }

    /**
     * Attaches a detached Resource Gateway signature.
     *
     * @param seal governed producer seal
     * @return identical assessment carrying the seal
     */
    public
    AuthoritativeOutcomeSelectedPopulationCompletenessAssessment
    withAssessmentSeal(VisualRunEvidenceSeal seal) {
        return withFingerprintAndSeal(
                assessmentFingerprint,
                Objects.requireNonNull(seal, "seal"));
    }

    /** Keeps source-set details out of generic logs. */
    @Override
    public String toString() {
        return "AuthoritativeOutcomeSelectedPopulationCompletenessAssessment[assessmentId="
                + assessmentId + ", revision=" + revision
                + ", populationRef=" + populationRef
                + ", expected=" + totals.expected()
                + ", missing=" + totals.missing()
                + ", pending=" + totals.pending() + "]";
    }

    private
    AuthoritativeOutcomeSelectedPopulationCompletenessAssessment
    withFingerprintAndSeal(
            String fingerprint,
            VisualRunEvidenceSeal seal) {
        return new
                AuthoritativeOutcomeSelectedPopulationCompletenessAssessment(
                schemaVersion,
                assessmentId,
                revision,
                fingerprint,
                scope,
                populationRef,
                assessedAt,
                observationSetFingerprint,
                dispositionSetFingerprint,
                strata,
                totals,
                submissionComplete,
                terminalComplete,
                seal);
    }

    static
    AuthoritativeOutcomeSelectedPopulationCompletenessAssessment
    addressed(
            AuthoritativeOutcomeSelectedPopulationCompletenessAssessment
                    source,
            String fingerprint) {
        return source.withFingerprintAndSeal(
                fingerprint,
                VisualRunEvidenceSeal.unsigned());
    }

    private static int compareStrata(
            StratumAssessment left,
            StratumAssessment right) {
        return Comparator
                .comparing(StratumAssessment::unitId)
                .thenComparing(
                        StratumAssessment::stratumId)
                .compare(left, right);
    }

    private static String version(String value) {
        String normalized = value == null
                || value.isBlank()
                ? SCHEMA_VERSION : value.trim();
        if (!SCHEMA_VERSION.equals(normalized)) {
            throw new IllegalArgumentException(
                    "unsupported selected population completeness schemaVersion");
        }
        return normalized;
    }

    private static String identifier(
            String value, String field) {
        String normalized = value == null
                ? "" : value.trim();
        if (!IDENTIFIER.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    field + " must be a bounded identifier");
        }
        return normalized;
    }

    private static String fingerprint(
            String value, String field) {
        String normalized = value == null
                ? "" : value.trim();
        if (!FINGERPRINT.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    field + " must be a canonical SHA-256 fingerprint");
        }
        return normalized;
    }

    private static String optionalFingerprint(
            String value, String field) {
        String normalized = value == null
                ? "" : value.trim();
        if (!normalized.isBlank()
                && !FINGERPRINT.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    field + " must be blank or a canonical SHA-256 fingerprint");
        }
        return normalized;
    }

    private static MirrorArtifactRef requireKind(
            MirrorArtifactRef reference,
            String kind,
            String field) {
        MirrorArtifactRef exact =
                Objects.requireNonNull(reference, field);
        if (!kind.equals(exact.kind())) {
            throw new IllegalArgumentException(
                    field + " must reference " + kind);
        }
        return exact;
    }

    private record AttestationMaterial(
            String domain,
            String schemaVersion,
            String assessmentId,
            long revision,
            MirrorArtifactRef populationRef,
            Instant assessedAt,
            String observationSetFingerprint,
            String dispositionSetFingerprint,
            String assessmentFingerprint
    ) {
    }
}
