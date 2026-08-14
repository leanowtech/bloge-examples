package com.leanowtech.bloge.gateway.businessmirror.evidence;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.mirror.ArtifactProvenance;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;
import com.leanowtech.bloge.gateway.integration.mirror.DomainFidelityProfile;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorArtifactRef;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Content-addressed, payload-free evidence index for one immutable Package snapshot.
 *
 * <p>The index deliberately has no aggregate score or package-level pass flag. Every conclusion
 * preserves its L0-L3/Calibration layer, proof strength, conservative state, and exact source
 * lineage. A resolved L0 dependency therefore cannot be interpreted as proof for a Scenario,
 * Carrier, Channel, or Outcome conclusion.</p>
 */
public record PackageEvidenceIndex(
        String schemaVersion,
        String indexFingerprint,
        CapabilitySnapshot.Scope scope,
        String packageId,
        long compilationRevision,
        long projectionRevision,
        EvidenceSource packageSnapshotSource,
        EvidenceSource readinessSource,
        EvidenceSource businessAssetClosureSource,
        String domainId,
        String problemCode,
        List<EvidenceLayer> layers,
        FidelityView fidelity,
        List<DriftSignal> driftSignals,
        Instant projectedAt,
        Instant validUntil
) {
    /** Current package evidence-index wire version. */
    public static final String SCHEMA_VERSION = "resourceGateway.packageEvidenceIndex.v1";
    /** Maximum canonical index bytes accepted for content addressing. */
    public static final int MAXIMUM_CANONICAL_BYTES = 16 * 1024 * 1024;
    /** Maximum conclusions admitted to one Package index. */
    public static final int MAXIMUM_CONCLUSIONS = 16_384;
    /** Maximum independently actionable drift signals admitted to one Package index. */
    public static final int MAXIMUM_DRIFT_SIGNALS = 500;

    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9@._:/-]{0,511}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    /** Validates canonical ordering, exact lineage, and the no-layer-collapse invariant. */
    public PackageEvidenceIndex {
        schemaVersion = version(schemaVersion);
        indexFingerprint = optionalFingerprint(indexFingerprint, "indexFingerprint");
        scope = Objects.requireNonNull(scope, "scope");
        packageId = identifier(packageId, "packageId");
        if (compilationRevision < 1 || projectionRevision < 1) {
            throw new IllegalArgumentException(
                    "compilationRevision and projectionRevision must be positive");
        }
        packageSnapshotSource = requireKind(
                packageSnapshotSource, "DOMAIN_CAPABILITY_PACKAGE", "packageSnapshotSource");
        readinessSource = requireKind(
                readinessSource, "PACKAGE_READINESS_REPORT", "readinessSource");
        businessAssetClosureSource = requireKind(
                businessAssetClosureSource,
                "BUSINESS_ASSET_LINK_CLOSURE",
                "businessAssetClosureSource");
        domainId = identifier(domainId, "domainId");
        problemCode = identifier(problemCode, "problemCode");
        layers = layers == null ? List.of() : List.copyOf(layers);
        if (layers.size() != EvidenceLayer.Layer.values().length
                || !layers.stream().map(EvidenceLayer::layer).toList()
                .equals(List.of(EvidenceLayer.Layer.values()))) {
            throw new IllegalArgumentException("evidence index must preserve every ordered layer");
        }
        int conclusionCount = layers.stream().mapToInt(value -> value.conclusions().size()).sum();
        if (conclusionCount < 1 || conclusionCount > MAXIMUM_CONCLUSIONS) {
            throw new IllegalArgumentException("evidence conclusion count is outside protocol bounds");
        }
        Set<String> conclusionIds = new HashSet<>();
        for (EvidenceLayer layer : layers) {
            for (EvidenceConclusion conclusion : layer.conclusions()) {
                if (conclusion.layer() != layer.layer()
                        || !conclusionIds.add(conclusion.conclusionId())) {
                    throw new IllegalArgumentException(
                            "evidence conclusions must be unique and remain in their declared layer");
                }
            }
        }
        fidelity = Objects.requireNonNull(fidelity, "fidelity");
        driftSignals = driftSignals == null ? List.of() : List.copyOf(driftSignals);
        List<String> orderedSignals = driftSignals.stream().map(DriftSignal::signalId).toList();
        if (driftSignals.size() > MAXIMUM_DRIFT_SIGNALS
                || !orderedSignals.equals(orderedSignals.stream().sorted().distinct().toList())) {
            throw new IllegalArgumentException("drift signals must be unique and ordered");
        }
        projectedAt = Objects.requireNonNull(projectedAt, "projectedAt");
        validUntil = Objects.requireNonNull(validUntil, "validUntil");
        if (validUntil.isBefore(projectedAt)) {
            throw new IllegalArgumentException("validUntil must not precede projectedAt");
        }
    }

    /** Returns this immutable projection with its canonical content address. */
    public PackageEvidenceIndex seal(ObjectMapper mapper) {
        if (!indexFingerprint.isBlank()) {
            verify(mapper);
            return this;
        }
        return withFingerprint(ProtocolFingerprint.ofBounded(
                Objects.requireNonNull(mapper, "mapper"),
                withFingerprint(""),
                MAXIMUM_CANONICAL_BYTES));
    }

    /** Recomputes and verifies the complete projection content address. */
    public void verify(ObjectMapper mapper) {
        if (indexFingerprint.isBlank()
                || !indexFingerprint.equals(ProtocolFingerprint.ofBounded(
                Objects.requireNonNull(mapper, "mapper"),
                withFingerprint(""),
                MAXIMUM_CANONICAL_BYTES))) {
            throw new IllegalArgumentException("Package evidence index fingerprint mismatch");
        }
    }

    /** Returns an identical index carrying the supplied fingerprint. */
    public PackageEvidenceIndex withFingerprint(String value) {
        return new PackageEvidenceIndex(schemaVersion, value, scope, packageId,
                compilationRevision, projectionRevision, packageSnapshotSource, readinessSource,
                businessAssetClosureSource, domainId, problemCode, layers, fidelity,
                driftSignals, projectedAt, validUntil);
    }

    /** @return exact immutable evidence-index reference used by registry and governance protocols */
    public MirrorArtifactRef artifactRef() {
        if (indexFingerprint.isBlank()) {
            throw new IllegalStateException("Package evidence index is not content-addressed");
        }
        return new MirrorArtifactRef(
                "PACKAGE_EVIDENCE_INDEX", packageId, projectionRevision, indexFingerprint);
    }

    /** One immutable source coordinate; supports revisioned artifacts and measured profile cuts. */
    public record EvidenceSource(
            String kind,
            String id,
            String coordinate,
            String fingerprint
    ) implements Comparable<EvidenceSource> {
        public EvidenceSource {
            kind = identifier(kind, "source.kind").toUpperCase(Locale.ROOT);
            id = identifier(id, "source.id");
            coordinate = required(coordinate, "source.coordinate");
            fingerprint = PackageEvidenceIndex.fingerprint(
                    fingerprint, "source.fingerprint");
        }

        /** Creates an exact source from the shared monotonic artifact reference. */
        public static EvidenceSource from(MirrorArtifactRef ref) {
            MirrorArtifactRef exact = Objects.requireNonNull(ref, "ref");
            return new EvidenceSource(exact.kind(), exact.id(),
                    "revision:" + exact.revision(), exact.fingerprint());
        }

        /** Creates an exact signed Fidelity profile source at its measurement cut. */
        public static EvidenceSource from(DomainFidelityProfile profile) {
            DomainFidelityProfile exact = Objects.requireNonNull(profile, "profile");
            return new EvidenceSource("DOMAIN_FIDELITY_PROFILE", exact.domainId(),
                    "measuredAt:" + exact.measuredAt(), exact.profileFingerprint());
        }

        @Override
        public int compareTo(EvidenceSource other) {
            return Comparator.comparing(EvidenceSource::kind)
                    .thenComparing(EvidenceSource::id)
                    .thenComparing(EvidenceSource::coordinate)
                    .thenComparing(EvidenceSource::fingerprint)
                    .compare(this, other);
        }
    }

    /** Ordered evidence conclusions for one semantic layer. */
    public record EvidenceLayer(Layer layer, List<EvidenceConclusion> conclusions) {
        public EvidenceLayer {
            layer = Objects.requireNonNull(layer, "layer");
            conclusions = conclusions == null ? List.of() : List.copyOf(conclusions);
            List<String> ordered = conclusions.stream()
                    .map(EvidenceConclusion::conclusionId).toList();
            if (!ordered.equals(ordered.stream().sorted().distinct().toList())) {
                throw new IllegalArgumentException(
                        "layer conclusions must be unique and ordered by conclusionId");
            }
        }

        /** Business proof layers that must remain independent. */
        public enum Layer {
            L0_RESOURCE,
            L1_SERVICE_DESIGN,
            L2_SERVICE_CARRIER,
            L3_APPLICATION,
            CALIBRATION
        }
    }

    /** One source-backed conclusion without business payload material. */
    public record EvidenceConclusion(
            String conclusionId,
            EvidenceLayer.Layer layer,
            EvidenceKind evidenceKind,
            ProofStrength proofStrength,
            ConclusionState state,
            EvidenceSource subject,
            List<EvidenceSource> sourceLineage,
            @JsonInclude(JsonInclude.Include.ALWAYS) Instant observedAt,
            @JsonInclude(JsonInclude.Include.ALWAYS) Instant validUntil,
            String limitationCode
    ) {
        public EvidenceConclusion {
            conclusionId = identifier(conclusionId, "conclusionId");
            layer = Objects.requireNonNull(layer, "layer");
            evidenceKind = Objects.requireNonNull(evidenceKind, "evidenceKind");
            proofStrength = Objects.requireNonNull(proofStrength, "proofStrength");
            state = Objects.requireNonNull(state, "state");
            subject = Objects.requireNonNull(subject, "subject");
            sourceLineage = canonicalSources(sourceLineage, "sourceLineage");
            if (sourceLineage.isEmpty() || !sourceLineage.contains(subject)) {
                throw new IllegalArgumentException(
                        "evidence conclusion lineage must include its exact subject");
            }
            limitationCode = optionalCode(limitationCode);
            if ((observedAt == null) != (validUntil == null)
                    || observedAt != null && validUntil.isBefore(observedAt)
                    || state == ConclusionState.AVAILABLE && !limitationCode.isBlank()
                    || state != ConclusionState.AVAILABLE && limitationCode.isBlank()) {
                throw new IllegalArgumentException("evidence conclusion state is inconsistent");
            }
        }
    }

    /** Closed evidence classification used by portfolio consumers. */
    public enum EvidenceKind {
        CONTRACT,
        EXECUTABLE,
        STATE_EFFECT,
        SCENARIO,
        SOLUTION,
        CARRIER,
        CHANNEL,
        FIDELITY_DENOMINATOR,
        FIDELITY_PROFILE,
        OUTCOME,
        OTHER
    }

    /** Strength of exactly this conclusion, never an aggregate Package grade. */
    public enum ProofStrength {
        DECLARED,
        COMPILED,
        OWNER_APPROVED_DENOMINATOR,
        EXECUTION_EVIDENCE,
        SIGNED_FIDELITY_PROFILE,
        INDEPENDENT_OUTCOME
    }

    /** Conservative conclusion state. */
    public enum ConclusionState {
        AVAILABLE,
        MISSING,
        STALE,
        DRIFTED,
        ABSTAINED,
        INSUFFICIENT
    }

    /**
     * Exact projection of the existing seven-dimensional Fidelity kernel.
     *
     * <p>There is intentionally no score field. The complete denominator, confidence interval,
     * freshness, source composition, and abstention debt remain independently visible.</p>
     */
    public record FidelityView(
            FidelityState state,
            EvidenceSource inventorySource,
            @JsonInclude(JsonInclude.Include.ALWAYS) EvidenceSource profileSource,
            @JsonInclude(JsonInclude.Include.ALWAYS) Instant measuredAt,
            @JsonInclude(JsonInclude.Include.ALWAYS) Instant validUntil,
            @JsonInclude(JsonInclude.Include.ALWAYS)
            DomainFidelityProfile.CoverageDenominator denominator,
            List<DimensionEvidence> dimensions,
            @JsonInclude(JsonInclude.Include.ALWAYS)
            DomainFidelityProfile.AbstentionDebt abstentionDebt,
            @JsonInclude(JsonInclude.Include.ALWAYS)
            DomainFidelityProfile.SourceComposition sourceComposition,
            @JsonInclude(JsonInclude.Include.ALWAYS)
            DomainFidelityProfile.Assessment assessment,
            List<DomainFidelityProfile.Limitation> limitations,
            List<EvidenceSource> sourceLineage
    ) {
        public FidelityView {
            state = Objects.requireNonNull(state, "state");
            inventorySource = Objects.requireNonNull(inventorySource, "inventorySource");
            dimensions = dimensions == null ? List.of() : List.copyOf(dimensions);
            limitations = limitations == null ? List.of() : List.copyOf(limitations);
            sourceLineage = canonicalSources(sourceLineage, "fidelity.sourceLineage");
            boolean profileMissing = profileSource == null;
            if (profileMissing != (measuredAt == null)
                    || profileMissing != (validUntil == null)
                    || profileMissing != (denominator == null)
                    || profileMissing != (abstentionDebt == null)
                    || profileMissing != (sourceComposition == null)
                    || profileMissing != (assessment == null)
                    || profileMissing != dimensions.isEmpty()
                    || profileMissing && state != FidelityState.MISSING
                    && state != FidelityState.INVENTORY_DRIFT
                    || !sourceLineage.contains(inventorySource)
                    || !profileMissing && !sourceLineage.contains(profileSource)) {
                throw new IllegalArgumentException("fidelity view shape is inconsistent");
            }
            if (!profileMissing) {
                List<DomainFidelityProfile.Dimension> expected =
                        List.of(DomainFidelityProfile.Dimension.values());
                List<DomainFidelityProfile.Dimension> actual = dimensions.stream()
                        .map(DimensionEvidence::dimension).toList();
                if (!actual.equals(expected)) {
                    throw new IllegalArgumentException(
                            "fidelity view must preserve all seven ordered dimensions");
                }
            }
        }
    }

    /** One dimension metric plus only the source cuts used for that dimension. */
    public record DimensionEvidence(
            DomainFidelityProfile.Dimension dimension,
            DimensionState state,
            @JsonInclude(JsonInclude.Include.ALWAYS)
            DomainFidelityProfile.DimensionMetric metric,
            List<EvidenceSource> sourceLineage
    ) {
        public DimensionEvidence {
            dimension = Objects.requireNonNull(dimension, "dimension");
            state = Objects.requireNonNull(state, "state");
            sourceLineage = canonicalSources(sourceLineage, "dimension.sourceLineage");
            if ((metric == null) != (state == DimensionState.NOT_IN_DENOMINATOR)
                    || metric != null && metric.dimension() != dimension
                    || sourceLineage.isEmpty()) {
                throw new IllegalArgumentException("dimension evidence is inconsistent");
            }
        }
    }

    /** Per-dimension state; absent denominator obligations remain visible instead of disappearing. */
    public enum DimensionState {
        MEASURED,
        PARTIAL,
        INSUFFICIENT,
        ABSTAINED,
        STALE,
        MISSING,
        NOT_IN_DENOMINATOR
    }

    /** Profile relationship/freshness state; it is not a publication decision. */
    public enum FidelityState {
        CURRENT,
        PARTIAL,
        INSUFFICIENT,
        ABSTAINED,
        STALE,
        MISSING,
        INVENTORY_DRIFT
    }

    /** Owner-addressable conservative signal, later materialized as a lifecycle task. */
    public record DriftSignal(
            String signalId,
            DriftReason reason,
            SignalSeverity severity,
            String owner,
            List<EvidenceSource> sourceLineage,
            Instant detectedAt,
            Instant dueAt
    ) {
        public DriftSignal {
            signalId = identifier(signalId, "signalId");
            reason = Objects.requireNonNull(reason, "reason");
            severity = Objects.requireNonNull(severity, "severity");
            owner = identifier(owner, "owner");
            sourceLineage = canonicalSources(sourceLineage, "drift.sourceLineage");
            detectedAt = Objects.requireNonNull(detectedAt, "detectedAt");
            dueAt = Objects.requireNonNull(dueAt, "dueAt");
            if (sourceLineage.isEmpty() || dueAt.isBefore(detectedAt)) {
                throw new IllegalArgumentException("drift signal requires lineage and a valid dueAt");
            }
        }
    }

    /** Closed package evidence debt vocabulary. */
    public enum DriftReason {
        FIDELITY_INVENTORY_UNRESOLVED,
        FIDELITY_INVENTORY_EXPIRED,
        FIDELITY_PROFILE_MISSING,
        FIDELITY_PROFILE_INVENTORY_DRIFT,
        FIDELITY_PROFILE_STALE,
        FIDELITY_EVIDENCE_INSUFFICIENT,
        FIDELITY_ABSTENTION_DEBT,
        OUTCOME_UNCALIBRATED
    }

    /** Owner task urgency derived from business risk and evidence debt. */
    public enum SignalSeverity {
        WARNING,
        ERROR,
        CRITICAL
    }

    private static List<EvidenceSource> canonicalSources(
            List<EvidenceSource> values, String field) {
        List<EvidenceSource> exact = values == null ? List.of() : values.stream()
                .map(value -> Objects.requireNonNull(value, field + " item"))
                .sorted()
                .distinct()
                .toList();
        if (exact.size() != (values == null ? 0 : values.size())) {
            throw new IllegalArgumentException(field + " must be unique and ordered");
        }
        return exact;
    }

    private static EvidenceSource requireKind(
            EvidenceSource value, String kind, String field) {
        EvidenceSource exact = Objects.requireNonNull(value, field);
        if (!kind.equals(exact.kind())) {
            throw new IllegalArgumentException(field + " must identify " + kind);
        }
        return exact;
    }

    private static String version(String value) {
        String exact = value == null || value.isBlank() ? SCHEMA_VERSION : value.trim();
        if (!SCHEMA_VERSION.equals(exact)) {
            throw new IllegalArgumentException("unsupported package evidence-index schemaVersion");
        }
        return exact;
    }

    private static String optionalFingerprint(String value, String field) {
        String exact = value == null ? "" : value.trim();
        if (!exact.isEmpty() && !FINGERPRINT.matcher(exact).matches()) {
            throw new IllegalArgumentException(field + " must be a canonical SHA-256 value");
        }
        return exact;
    }

    private static String fingerprint(String value, String field) {
        String exact = required(value, field);
        if (!FINGERPRINT.matcher(exact).matches()) {
            throw new IllegalArgumentException(field + " must be a canonical SHA-256 value");
        }
        return exact;
    }

    private static String identifier(String value, String field) {
        String exact = required(value, field);
        if (!IDENTIFIER.matcher(exact).matches()) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return exact;
    }

    private static String required(String value, String field) {
        String exact = value == null ? "" : value.trim();
        if (exact.isEmpty() || exact.length() > 1024) {
            throw new IllegalArgumentException(field + " must not be blank or oversized");
        }
        return exact;
    }

    private static String optionalCode(String value) {
        String exact = value == null ? "" : value.trim();
        if (!exact.isEmpty() && !IDENTIFIER.matcher(exact).matches()) {
            throw new IllegalArgumentException("limitationCode is invalid");
        }
        return exact;
    }
}
