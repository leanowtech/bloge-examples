package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Content-addressed, payload-free fidelity vector for one owner-approved business-domain inventory.
 *
 * <p>The profile deliberately has no overall score. It preserves the complete denominator,
 * per-unit measurement outcomes, per-dimension coverage and Wilson confidence, freshness,
 * abstention debt, source composition, and exact source lineage. Governance consumers can apply
 * their own risk policy to individual dimensions, but cannot turn missing, stale, abstained, or
 * low-sample obligations into a high-confidence pass.</p>
 *
 * @param schemaVersion exact profile protocol version
 * @param profileFingerprint canonical content address with this field and the seal blanked
 * @param scope complete enterprise namespace
 * @param domainId exact customer-business domain
 * @param inventoryRef exact owner-approved coverage denominator
 * @param taxonomyRef exact dimension taxonomy used by the denominator
 * @param policy deterministic projection policy
 * @param measuredAt projection time and evidence cut
 * @param validUntil earliest exclusive freshness boundary among admitted fresh measurements
 * @param denominator immutable domain and dimension obligation counts
 * @param unitAssessments complete ordered projection for every denominator unit
 * @param dimensions complete ordered dimension metrics
 * @param abstentionDebt explicit unresolved fresh-evidence obligations
 * @param sourceComposition evidence-source composition over the full unit denominator
 * @param assessment conservative aggregate completeness state, not a publication gate
 * @param limitations closed, sorted reasons that constrain interpretation
 * @param profileSeal optional detached producer signature over the profile content address
 */
public record DomainFidelityProfile(
        String schemaVersion,
        String profileFingerprint,
        CapabilitySnapshot.Scope scope,
        String domainId,
        MirrorArtifactRef inventoryRef,
        MirrorArtifactRef taxonomyRef,
        ProjectionPolicy policy,
        Instant measuredAt,
        Instant validUntil,
        CoverageDenominator denominator,
        List<UnitAssessment> unitAssessments,
        List<DimensionMetric> dimensions,
        AbstentionDebt abstentionDebt,
        SourceComposition sourceComposition,
        Assessment assessment,
        List<Limitation> limitations,
        VisualRunEvidenceSeal profileSeal
) {
    /** Current fidelity-vector wire version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.domainFidelityProfile.v1";
    /** Maximum canonical profile bytes admitted to fingerprinting. */
    public static final int MAXIMUM_CANONICAL_BYTES =
            16 * 1024 * 1024;
    /** Maximum signing-material bytes. */
    public static final int MAXIMUM_ATTESTATION_BYTES =
            16 * 1024;
    /** Exact v1 confidence algorithm. */
    public static final String CONFIDENCE_METHOD =
            "WILSON_95_V1";
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9@._:/-]{0,511}");

    /** Validates the bounded protocol shape before derived arithmetic is checked. */
    public DomainFidelityProfile {
        schemaVersion = version(schemaVersion);
        profileFingerprint =
                MirrorStateProtocolSupport.optionalFingerprint(
                        profileFingerprint,
                        "profileFingerprint");
        scope = Objects.requireNonNull(scope, "scope");
        domainId = identifier(domainId, "domainId");
        inventoryRef = requireKind(
                inventoryRef,
                DomainFidelityInventory.ARTIFACT_KIND,
                "inventoryRef");
        taxonomyRef = requireKind(
                taxonomyRef,
                "DOMAIN_FIDELITY_TAXONOMY",
                "taxonomyRef");
        policy = Objects.requireNonNull(policy, "policy");
        measuredAt = Objects.requireNonNull(
                measuredAt, "measuredAt");
        validUntil = Objects.requireNonNull(
                validUntil, "validUntil");
        if (validUntil.isBefore(measuredAt)) {
            throw new IllegalArgumentException(
                    "profile validUntil must not precede measuredAt");
        }
        denominator = Objects.requireNonNull(
                denominator, "denominator");
        unitAssessments = unitAssessments == null
                ? List.of() : List.copyOf(unitAssessments);
        dimensions = dimensions == null
                ? List.of() : List.copyOf(dimensions);
        abstentionDebt = Objects.requireNonNull(
                abstentionDebt, "abstentionDebt");
        sourceComposition = Objects.requireNonNull(
                sourceComposition, "sourceComposition");
        assessment = Objects.requireNonNull(
                assessment, "assessment");
        limitations = limitations == null
                ? List.of() : List.copyOf(limitations);
        List<Limitation> canonicalLimitations =
                limitations.stream()
                        .map(value -> Objects.requireNonNull(
                                value, "limitation"))
                        .distinct()
                        .sorted(Comparator.comparing(Enum::name))
                        .toList();
        if (!canonicalLimitations.equals(limitations)) {
            throw new IllegalArgumentException(
                    "profile limitations must be unique and ordered");
        }
        profileSeal = profileSeal == null
                ? VisualRunEvidenceSeal.unsigned()
                : profileSeal;
    }

    /**
     * Policy frozen into each deterministic profile projection.
     *
     * @param minimumAssessedUnits minimum PASS plus FAIL sample for a dimension to be measured
     * @param freshnessWindow maximum admitted age of a measurement
     * @param certifiableEvidenceRequired whether exploratory evidence must abstain
     * @param confidenceMethod exact interval algorithm
     */
    public record ProjectionPolicy(
            int minimumAssessedUnits,
            Duration freshnessWindow,
            boolean certifiableEvidenceRequired,
            String confidenceMethod
    ) {
        /** Enforces conservative bounded v1 projection semantics. */
        public ProjectionPolicy {
            freshnessWindow = Objects.requireNonNull(
                    freshnessWindow, "freshnessWindow");
            confidenceMethod = confidenceMethod == null
                    ? "" : confidenceMethod.trim();
            if (minimumAssessedUnits < 1
                    || minimumAssessedUnits
                    > DomainFidelityInventory.MAXIMUM_UNITS
                    || freshnessWindow.compareTo(
                    Duration.ofHours(1)) < 0
                    || freshnessWindow.compareTo(
                    Duration.ofDays(365)) > 0
                    || !certifiableEvidenceRequired
                    || !CONFIDENCE_METHOD.equals(
                    confidenceMethod)) {
                throw new IllegalArgumentException(
                        "fidelity projection policy violates v1 bounds");
            }
        }
    }

    /**
     * Frozen denominator arithmetic.
     *
     * @param totalUnits number of owner-approved Scenario units
     * @param totalObligations sum of every required unit-dimension pair
     * @param dimensions required units for each dimension
     */
    public record CoverageDenominator(
            int totalUnits,
            int totalObligations,
            List<DimensionDenominator> dimensions
    ) {
        /** Validates positive bounded, ordered denominator counts. */
        public CoverageDenominator {
            dimensions = dimensions == null
                    ? List.of() : List.copyOf(dimensions);
            if (totalUnits < 1
                    || totalUnits
                    > DomainFidelityInventory.MAXIMUM_UNITS
                    || totalObligations < totalUnits
                    || dimensions.isEmpty()
                    || dimensions.size()
                    > Dimension.values().length
                    || dimensions.stream()
                    .mapToInt(DimensionDenominator::requiredUnits)
                    .sum() != totalObligations) {
                throw new IllegalArgumentException(
                        "fidelity denominator arithmetic is invalid");
            }
            requireDimensionOrder(
                    dimensions.stream()
                            .map(DimensionDenominator::dimension)
                            .toList(),
                    "denominator dimensions");
        }
    }

    /**
     * Required unit count for one fidelity dimension.
     *
     * @param dimension closed fidelity dimension
     * @param requiredUnits positive denominator count
     */
    public record DimensionDenominator(
            Dimension dimension,
            int requiredUnits
    ) {
        /** Validates one positive dimension denominator. */
        public DimensionDenominator {
            dimension = Objects.requireNonNull(
                    dimension, "dimension");
            if (requiredUnits < 1
                    || requiredUnits
                    > DomainFidelityInventory.MAXIMUM_UNITS) {
                throw new IllegalArgumentException(
                        "dimension requiredUnits is outside the inventory bound");
            }
        }
    }

    /**
     * Complete evidence decision for one denominator unit.
     *
     * @param unitId exact inventory unit
     * @param scenarioCaseRef exact ScenarioCase revision
     * @param sourceRef exact source artifact, or {@code null} when missing
     * @param observedAt source observation time, or {@code null} when missing
     * @param expiresAt exclusive source freshness boundary, or {@code null} when missing
     * @param sourceMode source provenance class
     * @param results complete ordered outcomes for the unit's required dimensions
     */
    public record UnitAssessment(
            String unitId,
            MirrorArtifactRef scenarioCaseRef,
            @JsonInclude(JsonInclude.Include.ALWAYS)
            MirrorArtifactRef sourceRef,
            @JsonInclude(JsonInclude.Include.ALWAYS)
            Instant observedAt,
            @JsonInclude(JsonInclude.Include.ALWAYS)
            Instant expiresAt,
            SourceMode sourceMode,
            List<DimensionResult> results
    ) {
        /** Enforces exact missing/source correspondence and canonical dimension order. */
        public UnitAssessment {
            unitId = identifier(unitId, "unitId");
            scenarioCaseRef = requireKind(
                    scenarioCaseRef,
                    "SCENARIO_CASE",
                    "scenarioCaseRef");
            sourceMode = Objects.requireNonNull(
                    sourceMode, "sourceMode");
            results = results == null
                    ? List.of() : List.copyOf(results);
            if (results.isEmpty()) {
                throw new IllegalArgumentException(
                        "unit assessment requires dimension results");
            }
            requireDimensionOrder(
                    results.stream()
                            .map(DimensionResult::dimension)
                            .toList(),
                    "unit result dimensions");
            boolean missing = sourceRef == null;
            if (missing != (observedAt == null)
                    || missing != (expiresAt == null)
                    || missing != (sourceMode
                    == SourceMode.UNKNOWN)
                    || !missing
                    && (!expiresAt.isAfter(observedAt)
                    || sourceRef.kind().isBlank())
                    || missing
                    && results.stream().anyMatch(
                    result -> result.outcome()
                            != MeasurementOutcome.MISSING)
                    || !missing
                    && results.stream().anyMatch(
                    result -> result.outcome()
                            == MeasurementOutcome.MISSING)) {
                throw new IllegalArgumentException(
                        "unit assessment source and result shape are inconsistent");
            }
        }
    }

    /**
     * One source-derived fidelity obligation outcome.
     *
     * @param dimension required dimension
     * @param outcome conservative measured state
     * @param reason closed derivation reason
     */
    public record DimensionResult(
            Dimension dimension,
            MeasurementOutcome outcome,
            MeasurementReason reason
    ) {
        /** Validates reason and outcome correspondence. */
        public DimensionResult {
            dimension = Objects.requireNonNull(
                    dimension, "dimension");
            outcome = Objects.requireNonNull(
                    outcome, "outcome");
            reason = Objects.requireNonNull(
                    reason, "reason");
            boolean valid = switch (outcome) {
                case PASS -> reason
                        == MeasurementReason.ASSERTIONS_PASSED;
                case FAIL -> reason
                        == MeasurementReason.ASSERTION_FAILED;
                case ABSTAINED -> switch (reason) {
                    case ASSERTION_EVIDENCE_INDETERMINATE,
                            DIMENSION_ASSERTION_ABSENT,
                            EVIDENCE_NOT_CERTIFIABLE,
                            OUTCOME_CENSORED,
                            OUTCOME_CONFLICTING,
                            OUTCOME_PENDING,
                            OUTCOME_AUTHORITY_UNAVAILABLE,
                            REQUEST_SPACE_EVIDENCE_UNAVAILABLE,
                            SOURCE_EVIDENCE_INCOMPLETE -> true;
                    default -> false;
                };
                case STALE -> reason
                        == MeasurementReason.EVIDENCE_STALE;
                case MISSING -> reason
                        == MeasurementReason.NO_ELIGIBLE_EVIDENCE;
            };
            if (!valid) {
                throw new IllegalArgumentException(
                        "dimension result outcome and reason are inconsistent");
            }
        }
    }

    /**
     * Independently recomputable metric for one dimension.
     *
     * @param dimension exact denominator dimension
     * @param requiredUnits complete denominator
     * @param freshEvidenceUnits PASS, FAIL, and ABSTAINED units
     * @param assessedUnits PASS plus FAIL units
     * @param passedUnits PASS units
     * @param failedUnits FAIL units
     * @param abstainedUnits ABSTAINED units
     * @param staleUnits STALE units
     * @param missingUnits MISSING units
     * @param coverageRatio fresh evidence divided by required units
     * @param abstentionRatio abstained divided by required units
     * @param confidence Wilson interval over PASS plus FAIL, absent with no assessed units
     * @param sufficiency conservative interpretation state
     */
    public record DimensionMetric(
            Dimension dimension,
            int requiredUnits,
            int freshEvidenceUnits,
            int assessedUnits,
            int passedUnits,
            int failedUnits,
            int abstainedUnits,
            int staleUnits,
            int missingUnits,
            double coverageRatio,
            double abstentionRatio,
            @JsonInclude(JsonInclude.Include.ALWAYS)
            ArtifactProvenance.Confidence confidence,
            Sufficiency sufficiency
    ) {
        /** Validates bounded local arithmetic before full profile reconstruction. */
        public DimensionMetric {
            dimension = Objects.requireNonNull(
                    dimension, "dimension");
            sufficiency = Objects.requireNonNull(
                    sufficiency, "sufficiency");
            if (requiredUnits < 1
                    || freshEvidenceUnits < 0
                    || assessedUnits < 0
                    || passedUnits < 0
                    || failedUnits < 0
                    || abstainedUnits < 0
                    || staleUnits < 0
                    || missingUnits < 0
                    || freshEvidenceUnits
                    != assessedUnits + abstainedUnits
                    || assessedUnits
                    != passedUnits + failedUnits
                    || requiredUnits
                    != freshEvidenceUnits
                    + staleUnits + missingUnits
                    || !ratio(coverageRatio)
                    || !ratio(abstentionRatio)
                    || (assessedUnits == 0)
                    != (confidence == null)
                    || confidence != null
                    && !CONFIDENCE_METHOD.equals(
                    confidence.method())) {
                throw new IllegalArgumentException(
                        "dimension metric arithmetic is invalid");
            }
        }
    }

    /**
     * Explicit debt caused by fresh evidence that could not answer a required obligation.
     *
     * @param totalObligations complete unit-dimension denominator
     * @param abstainedObligations unresolved fresh-evidence obligations
     * @param ratio abstained obligations divided by total obligations
     * @param reasons complete ordered abstention-reason counts
     */
    public record AbstentionDebt(
            int totalObligations,
            int abstainedObligations,
            double ratio,
            List<ReasonCount> reasons
    ) {
        /** Validates bounded debt arithmetic and canonical reason ordering. */
        public AbstentionDebt {
            reasons = reasons == null
                    ? List.of() : List.copyOf(reasons);
            if (totalObligations < 1
                    || abstainedObligations < 0
                    || abstainedObligations > totalObligations
                    || !DomainFidelityProfile.ratio(ratio)
                    || reasons.stream()
                    .mapToInt(ReasonCount::count)
                    .sum() != abstainedObligations) {
                throw new IllegalArgumentException(
                        "abstention debt arithmetic is invalid");
            }
            List<MeasurementReason> ordered =
                    reasons.stream()
                            .map(ReasonCount::reason)
                            .toList();
            if (!ordered.stream()
                    .sorted(Comparator.comparing(Enum::name))
                    .toList().equals(ordered)
                    || ordered.stream().distinct().count()
                    != ordered.size()) {
                throw new IllegalArgumentException(
                        "abstention debt reasons must be unique and ordered");
            }
        }
    }

    /**
     * Count for one closed abstention reason.
     *
     * @param reason exact reason
     * @param count positive obligation count
     */
    public record ReasonCount(
            MeasurementReason reason,
            int count
    ) {
        /** Rejects non-abstention reasons and empty counts. */
        public ReasonCount {
            reason = Objects.requireNonNull(reason, "reason");
            if (count < 1 || switch (reason) {
                case ASSERTION_EVIDENCE_INDETERMINATE,
                        DIMENSION_ASSERTION_ABSENT,
                        EVIDENCE_NOT_CERTIFIABLE,
                        OUTCOME_CENSORED,
                        OUTCOME_CONFLICTING,
                        OUTCOME_PENDING,
                        OUTCOME_AUTHORITY_UNAVAILABLE,
                        REQUEST_SPACE_EVIDENCE_UNAVAILABLE,
                        SOURCE_EVIDENCE_INCOMPLETE -> false;
                default -> true;
            }) {
                throw new IllegalArgumentException(
                        "abstention reason count is invalid");
            }
        }
    }

    /**
     * Evidence-source composition over the whole unit denominator.
     *
     * @param totalUnits complete inventory size
     * @param recordedUnits units measured from recorded evidence
     * @param synthesizedUnits units measured from synthesized evidence
     * @param ownerDeclaredUnits units measured from owner-declared evidence
     * @param authoritativeUnits units measured from independent authoritative outcome evidence
     * @param unknownUnits missing or unclassified units
     * @param synthesizedRatio synthesized units divided by total units
     * @param unknownRatio unknown units divided by total units
     */
    public record SourceComposition(
            int totalUnits,
            int recordedUnits,
            int synthesizedUnits,
            int ownerDeclaredUnits,
            int authoritativeUnits,
            int unknownUnits,
            double synthesizedRatio,
            double unknownRatio
    ) {
        /** Validates a denominator-preserving source partition. */
        public SourceComposition {
            if (totalUnits < 1
                    || recordedUnits < 0
                    || synthesizedUnits < 0
                    || ownerDeclaredUnits < 0
                    || authoritativeUnits < 0
                    || unknownUnits < 0
                    || totalUnits != recordedUnits
                    + synthesizedUnits
                    + ownerDeclaredUnits
                    + authoritativeUnits
                    + unknownUnits
                    || !DomainFidelityProfile.ratio(
                    synthesizedRatio)
                    || !DomainFidelityProfile.ratio(
                    unknownRatio)) {
                throw new IllegalArgumentException(
                        "source composition arithmetic is invalid");
            }
        }
    }

    /** Business fidelity dimensions that remain independent in v1. */
    public enum Dimension {
        BEHAVIOR,
        CONTRACT,
        EFFECT,
        ERROR_DISTRIBUTION,
        OUTCOME,
        REQUEST_SPACE,
        STATE_TRANSITION
    }

    /** Per-obligation measurement outcome. */
    public enum MeasurementOutcome {
        PASS,
        FAIL,
        ABSTAINED,
        STALE,
        MISSING
    }

    /** Closed derivation reasons; free-form producer explanations are not trusted. */
    public enum MeasurementReason {
        ASSERTIONS_PASSED,
        ASSERTION_FAILED,
        ASSERTION_EVIDENCE_INDETERMINATE,
        DIMENSION_ASSERTION_ABSENT,
        EVIDENCE_NOT_CERTIFIABLE,
        OUTCOME_CENSORED,
        OUTCOME_CONFLICTING,
        OUTCOME_PENDING,
        OUTCOME_AUTHORITY_UNAVAILABLE,
        REQUEST_SPACE_EVIDENCE_UNAVAILABLE,
        SOURCE_EVIDENCE_INCOMPLETE,
        EVIDENCE_STALE,
        NO_ELIGIBLE_EVIDENCE
    }

    /** Source provenance used to expose synthesized and unknown debt. */
    public enum SourceMode {
        RECORDED,
        SYNTHESIZED,
        OWNER_DECLARED,
        AUTHORITATIVE,
        UNKNOWN
    }

    /** Per-dimension interpretation state. */
    public enum Sufficiency {
        MEASURED,
        PARTIAL_COVERAGE,
        BELOW_MINIMUM_SAMPLE,
        NO_ASSESSED_EVIDENCE
    }

    /** Conservative profile completeness state; never a release decision. */
    public enum Assessment {
        COMPLETE,
        PARTIAL,
        INSUFFICIENT_EVIDENCE,
        STALE
    }

    /** Closed limitations exposed to governance consumers. */
    public enum Limitation {
        ABSTENTION_PRESENT,
        COVERAGE_INCOMPLETE,
        EVIDENCE_STALE,
        LOW_SAMPLE,
        OUTCOME_UNCALIBRATED,
        REQUEST_SPACE_UNMEASURED,
        SOURCE_MODE_UNKNOWN,
        SYNTHESIZED_SOURCE_PRESENT
    }

    /**
     * Recomputes every denominator, measurement, metric, debt, source, and content-address fact.
     *
     * @param mapper canonical protocol mapper
     */
    public void verify(ObjectMapper mapper) {
        DomainFidelityProfileProjector.verify(this);
        if (profileFingerprint.isBlank()
                || !profileFingerprint.equals(
                ProtocolFingerprint.ofBounded(
                        Objects.requireNonNull(mapper, "mapper"),
                        withFingerprintAndSeal(
                                "",
                                VisualRunEvidenceSeal.unsigned()),
                        MAXIMUM_CANONICAL_BYTES))) {
            throw new IllegalArgumentException(
                    "Domain fidelity profile fingerprint mismatch");
        }
    }

    /**
     * Returns the exact domain-separated profile material signed by the producer.
     *
     * @param mapper canonical protocol mapper
     * @return canonical SHA-256 attestation material
     */
    public String attestationMaterialFingerprint(
            ObjectMapper mapper) {
        if (profileFingerprint.isBlank()) {
            throw new IllegalStateException(
                    "fidelity profile must be content-addressed before signing");
        }
        return ProtocolFingerprint.ofBounded(
                Objects.requireNonNull(mapper, "mapper"),
                new AttestationMaterial(
                        "RESOURCE_GATEWAY_DOMAIN_FIDELITY_PROFILE_V1",
                        schemaVersion,
                        domainId,
                        inventoryRef,
                        measuredAt,
                        profileFingerprint),
                MAXIMUM_ATTESTATION_BYTES);
    }

    /**
     * Attaches a detached signature without changing the profile content address.
     *
     * @param seal producer signature over {@link #attestationMaterialFingerprint(ObjectMapper)}
     * @return identical profile carrying the signature
     */
    public DomainFidelityProfile withProfileSeal(
            VisualRunEvidenceSeal seal) {
        return withFingerprintAndSeal(
                profileFingerprint,
                Objects.requireNonNull(seal, "seal"));
    }

    /** @return identical profile carrying a replacement content address */
    DomainFidelityProfile withFingerprint(
            String value) {
        return withFingerprintAndSeal(
                value, VisualRunEvidenceSeal.unsigned());
    }

    /** Keeps exact unit and source lineage out of generic logs. */
    @Override
    public String toString() {
        return "DomainFidelityProfile[domainId="
                + domainId + ", assessment=" + assessment
                + ", units=" + denominator.totalUnits()
                + ", obligations="
                + denominator.totalObligations() + "]";
    }

    private DomainFidelityProfile withFingerprintAndSeal(
            String fingerprint,
            VisualRunEvidenceSeal seal) {
        return new DomainFidelityProfile(
                schemaVersion,
                fingerprint,
                scope,
                domainId,
                inventoryRef,
                taxonomyRef,
                policy,
                measuredAt,
                validUntil,
                denominator,
                unitAssessments,
                dimensions,
                abstentionDebt,
                sourceComposition,
                assessment,
                limitations,
                seal);
    }

    private record AttestationMaterial(
            String domain,
            String schemaVersion,
            String domainId,
            MirrorArtifactRef inventoryRef,
            Instant measuredAt,
            String profileFingerprint
    ) {
    }

    private static void requireDimensionOrder(
            List<Dimension> dimensions,
            String field) {
        List<Dimension> canonical = dimensions.stream()
                .map(value -> Objects.requireNonNull(
                        value, field + " item"))
                .distinct()
                .sorted(Comparator.comparing(Enum::name))
                .toList();
        if (!canonical.equals(dimensions)) {
            throw new IllegalArgumentException(
                    field + " must be unique and ordered");
        }
    }

    private static boolean ratio(double value) {
        return Double.isFinite(value)
                && value >= 0.0d
                && value <= 1.0d;
    }

    private static String version(String value) {
        String exact = value == null || value.isBlank()
                ? SCHEMA_VERSION : value.trim();
        if (!SCHEMA_VERSION.equals(exact)) {
            throw new IllegalArgumentException(
                    "unsupported Domain fidelity profile schemaVersion");
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
