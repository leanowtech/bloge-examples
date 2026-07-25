package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Owner-approved, content-addressed denominator for one business domain's fidelity profile.
 *
 * <p>The inventory answers the question that a pass-rate cannot: which business scenarios and
 * fidelity dimensions were expected in the first place. It contains no test input, fixture value,
 * response, or customer payload. Each unit points to one exact ScenarioCase and capability
 * generation, while an independently governed taxonomy reference freezes the meaning of the
 * required dimensions. A profile may measure this inventory, but it must never repair or shrink
 * the denominator from the evidence that happened to be available.</p>
 *
 * @param schemaVersion exact inventory protocol version
 * @param inventoryId stable inventory identity inside the enterprise scope
 * @param revision positive immutable revision
 * @param fingerprint canonical content address with this field blanked
 * @param scope complete enterprise namespace
 * @param domainId stable customer-business domain identity
 * @param taxonomyRef exact owner-governed fidelity taxonomy
 * @param units complete ordered Scenario coverage denominator
 * @param provenance owner approval and source lineage
 * @param lifecycle governed lifecycle; v1 projection requires {@code ACTIVE}
 * @param effectiveAt inclusive inventory applicability time
 * @param expiresAt exclusive review horizon
 */
public record DomainFidelityInventory(
        String schemaVersion,
        String inventoryId,
        long revision,
        String fingerprint,
        CapabilitySnapshot.Scope scope,
        String domainId,
        MirrorArtifactRef taxonomyRef,
        List<CoverageUnit> units,
        ArtifactProvenance provenance,
        CapabilitySnapshot.Lifecycle lifecycle,
        Instant effectiveAt,
        Instant expiresAt
) {
    /** Current owner-approved denominator protocol version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.domainFidelityInventory.v1";
    /** Exact artifact kind used by profile lineage. */
    public static final String ARTIFACT_KIND =
            "DOMAIN_FIDELITY_INVENTORY";
    /** Largest domain inventory admitted by the first bounded protocol. */
    public static final int MAXIMUM_UNITS = 4_096;
    /** Maximum canonical inventory bytes admitted to fingerprinting. */
    public static final int MAXIMUM_CANONICAL_BYTES =
            8 * 1024 * 1024;

    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9@._:/-]{0,511}");

    /** Enforces a non-empty, canonical, owner-approved denominator. */
    public DomainFidelityInventory {
        schemaVersion = version(schemaVersion);
        inventoryId = identifier(inventoryId, "inventoryId");
        if (revision < 1) {
            throw new IllegalArgumentException(
                    "fidelity inventory revision must be positive");
        }
        fingerprint = MirrorStateProtocolSupport.optionalFingerprint(
                fingerprint, "fingerprint");
        scope = Objects.requireNonNull(scope, "scope");
        domainId = identifier(domainId, "domainId");
        taxonomyRef = requireKind(
                taxonomyRef,
                "DOMAIN_FIDELITY_TAXONOMY",
                "taxonomyRef");
        units = units == null ? List.of() : List.copyOf(units);
        if (units.isEmpty() || units.size() > MAXIMUM_UNITS) {
            throw new IllegalArgumentException(
                    "fidelity inventory requires between 1 and 4096 units");
        }
        Set<String> unitIds = new HashSet<>();
        Set<MirrorArtifactRef> scenarioCases = new HashSet<>();
        String previousUnitId = "";
        for (CoverageUnit unit : units) {
            CoverageUnit exact = Objects.requireNonNull(
                    unit, "coverageUnit");
            if (!unitIds.add(exact.unitId())
                    || !scenarioCases.add(exact.scenarioCaseRef())
                    || exact.unitId().compareTo(previousUnitId) <= 0) {
                throw new IllegalArgumentException(
                        "coverage units must be unique and ordered by unitId");
            }
            previousUnitId = exact.unitId();
        }
        provenance = Objects.requireNonNull(
                provenance, "provenance");
        lifecycle = Objects.requireNonNull(
                lifecycle, "lifecycle");
        effectiveAt = Objects.requireNonNull(
                effectiveAt, "effectiveAt");
        expiresAt = Objects.requireNonNull(
                expiresAt, "expiresAt");
        if (provenance.sourceType()
                != ArtifactProvenance.SourceType.OWNER
                || provenance.approvedBy().isBlank()
                || provenance.approvedAt() == null
                || !provenance.tenantId().equals(scope.tenantId())
                || !provenance.revocationRef().isBlank()
                || lifecycle != CapabilitySnapshot.Lifecycle.ACTIVE
                || effectiveAt.isBefore(provenance.approvedAt())
                || !expiresAt.isAfter(effectiveAt)
                || provenance.expiresAt() == null
                || !expiresAt.equals(provenance.expiresAt())) {
            throw new IllegalArgumentException(
                    "fidelity inventory requires active, unrevoked owner approval");
        }
    }

    /**
     * One business coverage obligation.
     *
     * @param unitId stable domain-local reporting identity
     * @param scenarioCaseRef exact governed ScenarioCase revision
     * @param targetCapabilityRef exact capability exercised by the case
     * @param caseType business scenario intent
     * @param requiredDimensions complete sorted fidelity obligations for this unit
     */
    public record CoverageUnit(
            String unitId,
            MirrorArtifactRef scenarioCaseRef,
            MirrorArtifactRef targetCapabilityRef,
            ScenarioCase.CaseType caseType,
            List<DomainFidelityProfile.Dimension>
                    requiredDimensions
    ) {
        /** Rejects weak taxonomies that omit baseline or case-specific obligations. */
        public CoverageUnit {
            unitId = identifier(unitId, "unitId");
            scenarioCaseRef = requireKind(
                    scenarioCaseRef,
                    "SCENARIO_CASE",
                    "scenarioCaseRef");
            targetCapabilityRef = requireKind(
                    targetCapabilityRef,
                    "CAPABILITY",
                    "targetCapabilityRef");
            caseType = Objects.requireNonNull(
                    caseType, "caseType");
            requiredDimensions =
                    requiredDimensions == null
                            ? List.of()
                            : List.copyOf(requiredDimensions);
            List<DomainFidelityProfile.Dimension> canonical =
                    requiredDimensions.stream()
                            .map(value -> Objects.requireNonNull(
                                    value, "requiredDimension"))
                            .distinct()
                            .sorted(Comparator.comparing(Enum::name))
                            .toList();
            if (canonical.isEmpty()
                    || !canonical.equals(requiredDimensions)
                    || !canonical.contains(
                    DomainFidelityProfile.Dimension.CONTRACT)
                    || !canonical.contains(
                    DomainFidelityProfile.Dimension.BEHAVIOR)
                    || caseType
                    == ScenarioCase.CaseType.STATE_TRANSITION
                    && !canonical.contains(
                    DomainFidelityProfile.Dimension.STATE_TRANSITION)
                    || caseType
                    == ScenarioCase.CaseType.FAULT
                    && !canonical.contains(
                    DomainFidelityProfile.Dimension.ERROR_DISTRIBUTION)) {
                throw new IllegalArgumentException(
                        "coverage dimensions are incomplete or non-canonical");
            }
            requiredDimensions = List.copyOf(canonical);
        }
    }

    /** @return exact content-addressed inventory reference */
    public MirrorArtifactRef artifactRef() {
        if (fingerprint.isBlank()) {
            throw new IllegalStateException(
                    "fidelity inventory is not content-addressed");
        }
        return new MirrorArtifactRef(
                ARTIFACT_KIND,
                inventoryId,
                revision,
                fingerprint);
    }

    /**
     * Seals an owner-approved inventory with its deterministic content address.
     *
     * @param mapper canonical protocol mapper
     * @return identical inventory carrying its canonical fingerprint
     */
    public DomainFidelityInventory seal(ObjectMapper mapper) {
        if (!fingerprint.isBlank()) {
            verify(mapper);
            return this;
        }
        return withFingerprint(
                ProtocolFingerprint.ofBounded(
                        Objects.requireNonNull(mapper, "mapper"),
                        withFingerprint(""),
                        MAXIMUM_CANONICAL_BYTES));
    }

    /**
     * Recomputes the immutable denominator content address.
     *
     * @param mapper canonical protocol mapper
     */
    public void verify(ObjectMapper mapper) {
        if (fingerprint.isBlank()
                || !fingerprint.equals(
                ProtocolFingerprint.ofBounded(
                        Objects.requireNonNull(mapper, "mapper"),
                        withFingerprint(""),
                        MAXIMUM_CANONICAL_BYTES))) {
            throw new IllegalArgumentException(
                    "Domain fidelity inventory fingerprint mismatch");
        }
    }

    /** @return identical inventory carrying the supplied fingerprint */
    public DomainFidelityInventory withFingerprint(
            String value) {
        return new DomainFidelityInventory(
                schemaVersion,
                inventoryId,
                revision,
                value,
                scope,
                domainId,
                taxonomyRef,
                units,
                provenance,
                lifecycle,
                effectiveAt,
                expiresAt);
    }

    /** Keeps the full business coverage inventory out of generic logs. */
    @Override
    public String toString() {
        return "DomainFidelityInventory[domainId="
                + domainId + ", revision=" + revision
                + ", units=" + units.size()
                + ", lifecycle=" + lifecycle + "]";
    }

    private static String version(String value) {
        String exact = value == null || value.isBlank()
                ? SCHEMA_VERSION : value.trim();
        if (!SCHEMA_VERSION.equals(exact)) {
            throw new IllegalArgumentException(
                    "unsupported Domain fidelity inventory schemaVersion");
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
