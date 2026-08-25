package com.leanowtech.bloge.gateway.gatewayverifier.archive;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable parsed packaging plan produced by {@link PackagingPlanParser}.
 *
 * <p>Represents the fully-validated contract between the gate and the packaging plan:
 * <ul>
 *   <li>{@code schemaVersion} — exact schema version identifier</li>
 *   <li>{@code exactArchiveEntries} — exactly 28 unique required archive entry paths</li>
 *   <li>{@code embeddedDependencies} — exactly 7 nested-JAR dependency declarations</li>
 *   <li>{@code artifactLimits} — 5-tier limit policy with exact key names</li>
 *   <li>{@code expectedSha256} — externally-supplied expected plan fingerprint</li>
 *   <li>{@code computedSha256} — SHA-256 of the raw plan bytes (computed before parse)</li>
 * </ul>
 *
 * <p>Construction validates all invariants: null checks, count constraints,
 * key constraints, and fingerprint format. Fail-closed on any violation.
 *
 * <p>This record is immutable and thread-safe.
 */
public final class PackagedPlan {

    // Required schema version
    public static final String REQUIRED_SCHEMA_VERSION = "v1";

    // Required counts
    public static final int REQUIRED_ARCHIVE_ENTRY_COUNT = 28;
    public static final int REQUIRED_DEPENDENCY_COUNT = 7;

    // Required artifact limits keys (exact field names from compiler/Authority)
    public static final String KEY_MAX_RAW_BYTES = "maxRawBytes";
    public static final String KEY_MAX_ZIP_ENTRIES = "maxZipEntries";
    public static final String KEY_MAX_SINGLE_ENTRY_BYTES = "maxSingleEntryBytes";
    public static final String KEY_MAX_TOTAL_UNCOMPRESSED_BYTES = "maxTotalUncompressedBytes";
    public static final String KEY_MAX_COMPRESSION_RATIO = "maxCompressionRatio";

    public static final Set<String> REQUIRED_LIMIT_KEYS = Set.of(
            KEY_MAX_RAW_BYTES,
            KEY_MAX_ZIP_ENTRIES,
            KEY_MAX_SINGLE_ENTRY_BYTES,
            KEY_MAX_TOTAL_UNCOMPRESSED_BYTES,
            KEY_MAX_COMPRESSION_RATIO
    );

    /**
     * Immutable embedded dependency descriptor.
     */
    public record Dependency(
            String lockId,
            String entryPath,
            String rawFingerprint
    ) {
        public Dependency {
            Objects.requireNonNull(lockId, "lockId must not be null");
            Objects.requireNonNull(entryPath, "entryPath must not be null");
            Objects.requireNonNull(rawFingerprint, "rawFingerprint must not be null");
            if (!PackagingPlanBinding.isValidSha256Fingerprint(rawFingerprint)) {
                throw new IllegalArgumentException(
                        "rawFingerprint must match sha256:<64 lowerhex>");
            }
        }
    }

    /**
     * Immutable artifact limits extracted from the plan.
     * Field names match the compiler/Authority's exact schema.
     */
    public record ArtifactLimitValues(
            long maxRawBytes,
            long maxZipEntries,
            long maxSingleEntryBytes,
            long maxTotalUncompressedBytes,
            long maxCompressionRatio
    ) {
        public ArtifactLimitValues {
            if (maxRawBytes < 0) throw new IllegalArgumentException("maxRawBytes must be non-negative");
            if (maxZipEntries < 0) throw new IllegalArgumentException("maxZipEntries must be non-negative");
            if (maxSingleEntryBytes < 0) throw new IllegalArgumentException("maxSingleEntryBytes must be non-negative");
            if (maxTotalUncompressedBytes < 0) throw new IllegalArgumentException("maxTotalUncompressedBytes must be non-negative");
            if (maxCompressionRatio < 0) throw new IllegalArgumentException("maxCompressionRatio must be non-negative");
        }
    }

    private final String schemaVersion;
    private final List<String> exactArchiveEntries;
    private final List<Dependency> embeddedDependencies;
    private final ArtifactLimitValues artifactLimits;
    private final String expectedSha256;
    private final String computedSha256;
    private final byte[] rawPlanBytes;

    /**
     * Canonical constructor with full validation.
     */
    public PackagedPlan(
            String schemaVersion,
            List<String> exactArchiveEntries,
            List<Dependency> embeddedDependencies,
            ArtifactLimitValues artifactLimits,
            String expectedSha256,
            String computedSha256,
            byte[] rawPlanBytes
    ) {
        // Schema version
        this.schemaVersion = Objects.requireNonNull(schemaVersion, "schemaVersion must not be null");
        if (!REQUIRED_SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException(
                    "schemaVersion must be '" + REQUIRED_SCHEMA_VERSION + "' but got: " + schemaVersion);
        }

        // Archive entries: exactly 28 unique entries
        Objects.requireNonNull(exactArchiveEntries, "exactArchiveEntries must not be null");
        if (exactArchiveEntries.size() != REQUIRED_ARCHIVE_ENTRY_COUNT) {
            throw new IllegalArgumentException(
                    "exactArchiveEntries must have exactly " + REQUIRED_ARCHIVE_ENTRY_COUNT +
                            " entries but got: " + exactArchiveEntries.size());
        }
        List<String> entriesCopy = List.copyOf(exactArchiveEntries);
        Set<String> uniqueEntries = Set.copyOf(entriesCopy);
        if (uniqueEntries.size() != REQUIRED_ARCHIVE_ENTRY_COUNT) {
            throw new IllegalArgumentException(
                    "exactArchiveEntries must have exactly " + REQUIRED_ARCHIVE_ENTRY_COUNT +
                            " unique entries (duplicates found)");
        }
        this.exactArchiveEntries = entriesCopy;

        // Embedded dependencies: exactly 7
        Objects.requireNonNull(embeddedDependencies, "embeddedDependencies must not be null");
        if (embeddedDependencies.size() != REQUIRED_DEPENDENCY_COUNT) {
            throw new IllegalArgumentException(
                    "embeddedDependencies must have exactly " + REQUIRED_DEPENDENCY_COUNT +
                            " entries but got: " + embeddedDependencies.size());
        }
        List<Dependency> depsCopy = List.copyOf(embeddedDependencies);
        this.embeddedDependencies = depsCopy;

        // Artifact limits
        this.artifactLimits = Objects.requireNonNull(artifactLimits, "artifactLimits must not be null");

        // SHA-256 fingerprints
        this.expectedSha256 = Objects.requireNonNull(expectedSha256, "expectedSha256 must not be null");
        if (!PackagingPlanBinding.isValidSha256Fingerprint(expectedSha256)) {
            throw new IllegalArgumentException(
                    "expectedSha256 must match sha256:<64 lowerhex>");
        }
        this.computedSha256 = Objects.requireNonNull(computedSha256, "computedSha256 must not be null");
        if (!PackagingPlanBinding.isValidSha256Fingerprint(computedSha256)) {
            throw new IllegalArgumentException(
                    "computedSha256 must match sha256:<64 lowerhex>");
        }

        // Raw bytes: defensive copy
        this.rawPlanBytes = Objects.requireNonNull(rawPlanBytes, "rawPlanBytes must not be null")
                .clone();
    }

    public String schemaVersion() { return schemaVersion; }
    public List<String> exactArchiveEntries() { return exactArchiveEntries; }
    public List<Dependency> embeddedDependencies() { return embeddedDependencies; }
    public ArtifactLimitValues artifactLimits() { return artifactLimits; }
    public String expectedSha256() { return expectedSha256; }
    public String computedSha256() { return computedSha256; }
    public byte[] rawPlanBytes() { return rawPlanBytes.clone(); }

    /** Returns true if the computed plan SHA-256 matches the expected value. */
    public boolean isHashValid() {
        return expectedSha256.equals(computedSha256);
    }

    /** Converts this plan to a {@link PackagingPlanBinding} for nested JAR binding. */
    public PackagingPlanBinding toBinding() {
        List<PackagingPlanBinding.Dependency> deps = embeddedDependencies.stream()
                .map(d -> new PackagingPlanBinding.Dependency(d.lockId(), d.entryPath(), d.rawFingerprint()))
                .toList();
        return new PackagingPlanBinding(rawPlanBytes, expectedSha256, deps);
    }

    /** Converts this plan's limits to an {@link ArtifactLimits} for limit checking. */
    public ArtifactLimits toArtifactLimits() {
        ArtifactLimitValues v = artifactLimits;
        return new ArtifactLimits(
                v.maxRawBytes(),
                v.maxZipEntries(),
                v.maxSingleEntryBytes(),
                v.maxTotalUncompressedBytes(),
                v.maxCompressionRatio()
        );
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PackagedPlan that = (PackagedPlan) o;
        return Objects.equals(schemaVersion, that.schemaVersion)
                && Objects.equals(exactArchiveEntries, that.exactArchiveEntries)
                && Objects.equals(embeddedDependencies, that.embeddedDependencies)
                && Objects.equals(artifactLimits, that.artifactLimits)
                && Objects.equals(expectedSha256, that.expectedSha256)
                && Objects.equals(computedSha256, that.computedSha256);
    }

    @Override
    public int hashCode() {
        return Objects.hash(schemaVersion, exactArchiveEntries, embeddedDependencies,
                artifactLimits, expectedSha256, computedSha256);
    }
}
