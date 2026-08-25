package com.leanowtech.bloge.gateway.gatewayverifier.archive;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable snapshot produced by {@link ArchiveKernel} verification.
 *
 * <p>Contains the complete verification outcome:
 * <ul>
 *   <li>Plan hash validation result</li>
 *   <li>ZIP structural verification result</li>
 *   <li>Exact closure verification result</li>
 *   <li>Artifact limits verification result</li>
 *   <li>Nested JAR binding result</li>
 *   <li>Entry metadata for accepted archives</li>
 *   <li>Rejection info if verification failed</li>
 * </ul>
 *
 * <p>Field order is fixed for deterministic JSON serialization:
 * <ol>
 *   <li>planHashValid</li>
 *   <li>planExpectedHash</li>
 *   <li>planActualHash</li>
 *   <li>rejected</li>
 *   <li>rejectionCode</li>
 *   <li>rejectionArgs</li>
 *   <li>entryCount</li>
 *   <li>entries (sorted by name)</li>
 *   <li>dependencyCount</li>
 *   <li>dependencies (sorted by sha256Key)</li>
 * </ol>
 *
 * <p>Protocol fields use stable naming — no paths or system exception text.
 *
 * <p>This record is immutable and thread-safe.
 */
public final class ArchiveKernelSnapshot {

    // Phase result records

    /**
     * Immutable result of the ZIP structural verification phase.
     */
    public record ZipVerifierResult(
            boolean passed,
            int entryCount,
            List<String> entryNames,
            boolean rawBytesLimitExceeded,
            boolean zipEntriesLimitExceeded
    ) {
        public ZipVerifierResult {
            Objects.requireNonNull(entryNames, "entryNames must not be null");
            entryNames = List.copyOf(entryNames);
        }
    }

    /**
     * Immutable result of the exact closure verification phase.
     */
    public record ClosureResult(
            boolean passed,
            String reasonCode,
            Map<String, Object> reasonArgs
    ) {
        public ClosureResult {
            reasonArgs = reasonArgs != null ? Map.copyOf(reasonArgs) : null;
        }
    }

    /**
     * Immutable result of the artifact limits verification phase.
     */
    public record LimitsResult(
            boolean passed,
            String reasonCode,
            Map<String, Object> reasonArgs
    ) {
        public LimitsResult {
            reasonArgs = reasonArgs != null ? Map.copyOf(reasonArgs) : null;
        }
    }

    /**
     * Immutable result of the nested JAR binding phase.
     */
    public record BindingResult(
            boolean passed,
            boolean planMismatch,
            boolean countMismatch,
            String reasonCode,
            Map<String, Object> reasonArgs
    ) {
        public BindingResult {
            reasonArgs = reasonArgs != null ? Map.copyOf(reasonArgs) : null;
        }
    }

    /**
     * Immutable entry descriptor in the snapshot.
     */
    public record Entry(
            String name,
            String sha256,
            long uncompressedSize
    ) {
        public Entry {
            Objects.requireNonNull(name, "name must not be null");
            Objects.requireNonNull(sha256, "sha256 must not be null");
        }
    }

    /**
     * Immutable dependency descriptor in the snapshot.
     */
    public record Dependency(
            String lockId,
            String entryPath,
            String expectedFingerprint,
            String actualFingerprint,
            boolean bound,
            String sha256Key
    ) {
        public Dependency {
            Objects.requireNonNull(lockId, "lockId must not be null");
            Objects.requireNonNull(entryPath, "entryPath must not be null");
            Objects.requireNonNull(expectedFingerprint, "expectedFingerprint must not be null");
            Objects.requireNonNull(actualFingerprint, "actualFingerprint must not be null");
            Objects.requireNonNull(sha256Key, "sha256Key must not be null");
        }
    }

    // Snapshot fields (fixed order for determinism)

    private final boolean planHashValid;
    private final String planExpectedHash;
    private final String planActualHash;
    private final boolean rejected;
    private final String rejectionCode;
    private final Map<String, Object> rejectionArgs;
    private final int entryCount;
    private final List<Entry> entries;
    private final int dependencyCount;
    private final List<Dependency> dependencies;
    private final ZipVerifierResult zipVerifierResult;
    private final ClosureResult closureResult;
    private final LimitsResult limitsResult;
    private final BindingResult bindingResult;

    /**
     * Canonical constructor with full validation and defensive copies.
     */
    public ArchiveKernelSnapshot(
            boolean planHashValid,
            String planExpectedHash,
            String planActualHash,
            boolean rejected,
            String rejectionCode,
            Map<String, Object> rejectionArgs,
            int entryCount,
            List<Entry> entries,
            int dependencyCount,
            List<Dependency> dependencies,
            ZipVerifierResult zipVerifierResult,
            ClosureResult closureResult,
            LimitsResult limitsResult,
            BindingResult bindingResult
    ) {
        this.planHashValid = planHashValid;
        this.planExpectedHash = Objects.requireNonNull(planExpectedHash, "planExpectedHash must not be null");
        this.planActualHash = Objects.requireNonNull(planActualHash, "planActualHash must not be null");
        this.rejected = rejected;
        this.rejectionCode = rejectionCode; // null if not rejected
        this.rejectionArgs = rejectionArgs != null ? Map.copyOf(rejectionArgs) : null;
        this.entryCount = entryCount;
        this.entries = entries != null ? List.copyOf(entries) : List.of();
        this.dependencyCount = dependencyCount;
        this.dependencies = dependencies != null ? List.copyOf(dependencies) : List.of();
        this.zipVerifierResult = Objects.requireNonNull(zipVerifierResult, "zipVerifierResult must not be null");
        this.closureResult = Objects.requireNonNull(closureResult, "closureResult must not be null");
        this.limitsResult = Objects.requireNonNull(limitsResult, "limitsResult must not be null");
        this.bindingResult = Objects.requireNonNull(bindingResult, "bindingResult must not be null");
    }

    // Factory methods

    /**
     * Creates a successful snapshot.
     */
    public static ArchiveKernelSnapshot success(
            String planExpectedHash,
            String planActualHash,
            int entryCount,
            List<Entry> entries,
            int dependencyCount,
            List<Dependency> dependencies,
            ZipVerifierResult zipVerifierResult,
            ClosureResult closureResult,
            LimitsResult limitsResult,
            BindingResult bindingResult
    ) {
        return new ArchiveKernelSnapshot(
                true,
                planExpectedHash,
                planActualHash,
                false,
                null,
                null,
                entryCount,
                entries,
                dependencyCount,
                dependencies,
                zipVerifierResult,
                closureResult,
                limitsResult,
                bindingResult
        );
    }

    /**
     * Creates a rejected snapshot.
     */
    public static ArchiveKernelSnapshot rejected(
            String planExpectedHash,
            String planActualHash,
            String rejectionCode,
            Map<String, Object> rejectionArgs,
            ZipVerifierResult zipVerifierResult,
            ClosureResult closureResult,
            LimitsResult limitsResult,
            BindingResult bindingResult
    ) {
        return new ArchiveKernelSnapshot(
                planExpectedHash.equals(planActualHash),
                planExpectedHash,
                planActualHash,
                true,
                Objects.requireNonNull(rejectionCode, "rejectionCode must not be null"),
                rejectionArgs,
                0,
                List.of(),
                0,
                List.of(),
                zipVerifierResult,
                closureResult,
                limitsResult,
                bindingResult
        );
    }

    // Accessors

    public boolean planHashValid() { return planHashValid; }
    public String planExpectedHash() { return planExpectedHash; }
    public String planActualHash() { return planActualHash; }
    public boolean rejected() { return rejected; }
    public String rejectionCode() { return rejectionCode; }
    public Map<String, Object> rejectionArgs() { return rejectionArgs; }
    public int entryCount() { return entryCount; }
    public List<Entry> entries() { return entries; }
    public int dependencyCount() { return dependencyCount; }
    public List<Dependency> dependencies() { return dependencies; }
    public ZipVerifierResult zipVerifierResult() { return zipVerifierResult; }
    public ClosureResult closureResult() { return closureResult; }
    public LimitsResult limitsResult() { return limitsResult; }
    public BindingResult bindingResult() { return bindingResult; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ArchiveKernelSnapshot that = (ArchiveKernelSnapshot) o;
        return planHashValid == that.planHashValid
                && rejected == that.rejected
                && entryCount == that.entryCount
                && dependencyCount == that.dependencyCount
                && Objects.equals(planExpectedHash, that.planExpectedHash)
                && Objects.equals(planActualHash, that.planActualHash)
                && Objects.equals(rejectionCode, that.rejectionCode)
                && Objects.equals(rejectionArgs, that.rejectionArgs)
                && Objects.equals(entries, that.entries)
                && Objects.equals(dependencies, that.dependencies)
                && Objects.equals(zipVerifierResult, that.zipVerifierResult)
                && Objects.equals(closureResult, that.closureResult)
                && Objects.equals(limitsResult, that.limitsResult)
                && Objects.equals(bindingResult, that.bindingResult);
    }

    @Override
    public int hashCode() {
        return Objects.hash(planHashValid, planExpectedHash, planActualHash,
                rejected, rejectionCode, rejectionArgs, entryCount, entries,
                dependencyCount, dependencies, zipVerifierResult, closureResult,
                limitsResult, bindingResult);
    }
}
