package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Content-addressed page of exact sources behind one signed completeness assessment.
 *
 * <p>A page does not independently claim that the complete source set was returned. Consumers
 * follow the exclusive ordinal cursor until {@code complete=true}, then recompute the assessment's
 * observation and disposition set fingerprints from all entries. Any omitted, duplicated,
 * reordered, or substituted source therefore fails the root assessment closure.</p>
 *
 * @param schemaVersion exact source-page protocol version
 * @param pageFingerprint canonical page address excluding this field
 * @param scope exact enterprise namespace
 * @param assessmentRef exact signed completeness assessment
 * @param populationRef exact selected-population denominator
 * @param afterGlobalOrdinal exclusive request cursor
 * @param nextGlobalOrdinal exclusive cursor for the next page
 * @param complete whether no persisted source follows this page
 * @param entries unique member-ordered source references
 */
public record
AuthoritativeOutcomeSelectedPopulationAssessmentSourcePage(
        String schemaVersion,
        String pageFingerprint,
        CapabilitySnapshot.Scope scope,
        MirrorArtifactRef assessmentRef,
        MirrorArtifactRef populationRef,
        long afterGlobalOrdinal,
        long nextGlobalOrdinal,
        boolean complete,
        List<Entry> entries
) {
    /** Current assessment-source page wire version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.authoritativeOutcomeSelectedPopulationAssessmentSourcePage.v1";
    /** Maximum page entries exposed by the repository and public API. */
    public static final int MAXIMUM_ENTRIES = 1_000;
    /** Maximum canonical page bytes. */
    public static final int MAXIMUM_CANONICAL_BYTES =
            2 * 1024 * 1024;
    private static final Pattern FINGERPRINT =
            Pattern.compile("sha256:[a-f0-9]{64}");

    /** Enforces exact cursor continuity and unique ascending member ordinals. */
    public AuthoritativeOutcomeSelectedPopulationAssessmentSourcePage {
        schemaVersion = schemaVersion == null
                || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException(
                    "unsupported assessment source page schemaVersion");
        }
        pageFingerprint = optionalFingerprint(
                pageFingerprint);
        scope = Objects.requireNonNull(scope, "scope");
        assessmentRef = requireKind(
                assessmentRef,
                AuthoritativeOutcomeSelectedPopulationCompletenessAssessment
                        .ARTIFACT_KIND,
                "assessmentRef");
        populationRef = requireKind(
                populationRef,
                AuthoritativeOutcomeSelectedPopulationManifest
                        .ARTIFACT_KIND,
                "populationRef");
        if (afterGlobalOrdinal < 0
                || nextGlobalOrdinal < afterGlobalOrdinal) {
            throw new IllegalArgumentException(
                    "assessment source page cursor is invalid");
        }
        entries = entries == null
                ? List.of() : List.copyOf(entries);
        if (entries.size() > MAXIMUM_ENTRIES) {
            throw new IllegalArgumentException(
                    "assessment source page is too large");
        }
        long previous = afterGlobalOrdinal;
        for (Entry entry : entries) {
            Entry exact = Objects.requireNonNull(
                    entry, "entry");
            if (exact.globalOrdinal() <= previous) {
                throw new IllegalArgumentException(
                        "assessment sources must be unique and member ordered");
            }
            previous = exact.globalOrdinal();
        }
        long derivedNext = entries.isEmpty()
                ? afterGlobalOrdinal
                : entries.getLast().globalOrdinal();
        if (nextGlobalOrdinal != derivedNext
                || !complete && entries.isEmpty()) {
            throw new IllegalArgumentException(
                    "assessment source page next cursor is not derived");
        }
    }

    /** Exact source kind admitted by the completeness projector. */
    public enum SourceKind {
        OBSERVATION,
        LEGAL_DISPOSITION
    }

    /**
     * One selected-member position resolved by one exact immutable source.
     *
     * @param globalOrdinal selected population member ordinal
     * @param sourceKind observation or independently authorized legal disposition
     * @param sourceRef exact immutable source artifact
     */
    public record Entry(
            long globalOrdinal,
            SourceKind sourceKind,
            MirrorArtifactRef sourceRef
    ) {
        /** Enforces source-kind-to-artifact correspondence. */
        public Entry {
            if (globalOrdinal < 1) {
                throw new IllegalArgumentException(
                        "assessment source globalOrdinal must be positive");
            }
            sourceKind = Objects.requireNonNull(
                    sourceKind, "sourceKind");
            sourceRef = requireKind(
                    sourceRef,
                    sourceKind == SourceKind.OBSERVATION
                            ? AuthoritativeOutcomeObservation
                            .ARTIFACT_KIND
                            : AuthoritativeOutcomeSelectedPopulationDisposition
                            .ARTIFACT_KIND,
                    "sourceRef");
        }
    }

    /**
     * Seals one repository-derived page with a canonical content address.
     *
     * @param mapper canonical protocol mapper
     * @return identical addressed page
     */
    public AuthoritativeOutcomeSelectedPopulationAssessmentSourcePage
    seal(ObjectMapper mapper) {
        AuthoritativeOutcomeSelectedPopulationAssessmentSourcePage
                material = withFingerprint("");
        return material.withFingerprint(
                ProtocolFingerprint.ofBounded(
                        Objects.requireNonNull(
                                mapper, "mapper"),
                        material,
                        MAXIMUM_CANONICAL_BYTES));
    }

    /**
     * Recomputes page structure and content address.
     *
     * @param mapper canonical protocol mapper
     */
    public void verify(ObjectMapper mapper) {
        if (pageFingerprint.isBlank()
                || !pageFingerprint.equals(
                withFingerprint("")
                        .seal(mapper)
                        .pageFingerprint())) {
            throw new IllegalArgumentException(
                    "assessment source page fingerprint mismatch");
        }
    }

    /** @return entries ordered by global member ordinal */
    public List<Entry> orderedEntries() {
        return entries.stream()
                .sorted(Comparator.comparingLong(
                        Entry::globalOrdinal))
                .toList();
    }

    private
    AuthoritativeOutcomeSelectedPopulationAssessmentSourcePage
    withFingerprint(String fingerprint) {
        return new
                AuthoritativeOutcomeSelectedPopulationAssessmentSourcePage(
                schemaVersion,
                fingerprint,
                scope,
                assessmentRef,
                populationRef,
                afterGlobalOrdinal,
                nextGlobalOrdinal,
                complete,
                entries);
    }

    private static String optionalFingerprint(
            String value) {
        String exact = value == null
                ? "" : value.trim();
        if (!exact.isBlank()
                && !FINGERPRINT.matcher(exact).matches()) {
            throw new IllegalArgumentException(
                    "pageFingerprint is invalid");
        }
        return exact;
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
}
