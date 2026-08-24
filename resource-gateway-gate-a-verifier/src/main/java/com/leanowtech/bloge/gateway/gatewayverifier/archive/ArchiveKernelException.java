package com.leanowtech.bloge.gateway.gatewayverifier.archive;

import java.util.Map;
import java.util.Objects;

/**
 * Root exception for all Archive Kernel rejection events.
 * Carries a structured reason code and optional args so T2/T3 consumers
 * can inspect the failure without parsing free-text messages.
 *
 * <p>All public reason codes are documented AK-* codes from the specification:
 * <ul>
 *   <li>AK-ENTRY-DIRECTORY: entry name ends with '/'</li>
 *   <li>AK-EXTRA-FIELD: local or central directory contains extra field</li>
 *   <li>AK-ENCRYPTED: encryption bit (GPB bit 0) is set</li>
 *   <li>AK-EXTERNAL-SYMLINK: external attributes declare Unix symlink</li>
 *   <li>AK-EXTERNAL-SPECIAL: external attributes declare special file</li>
 *   <li>AK-MULTI-RELEASE: name starts with "META-INF/versions/"</li>
 *   <li>AK-DD-UNVERIFIABLE: data descriptor present with zero central size and CRC</li>
 *   <li>AK-UNKNOWN-COMPRESSION: compression method is not 0 or 8</li>
 *   <li>AK-CRC-MISMATCH: recomputed CRC-32 does not match central directory value</li>
 *   <li>AK-SIZE-MISMATCH: recomputed uncompressed size does not match central directory value</li>
 *   <li>AK-LIMIT-RAW-BYTES: raw archive bytes exceed limit</li>
 *   <li>AK-LIMIT-SINGLE-ENTRY: any uncompressed entry exceeds single entry limit</li>
 *   <li>AK-LIMIT-TOTAL-UNCOMPRESSED: total uncompressed size exceeds limit</li>
 *   <li>AK-LIMIT-COMPRESSION-RATIO: compression ratio exceeds limit</li>
 *   <li>AK-ZIP-STRUCTURE: ZIP structure violation (EOCD, CD, local header)</li>
 *   <li>AK-ARCHIVE-IO: I/O error during archive processing</li>
 * </ul>
 */
public final class ArchiveKernelException extends Exception {

    private final String reasonCode;
    // T1-5: Typed as Map<String, Object> with nullability — never Object.
    // Defensively copied at construction to prevent caller mutation.
    private final Map<String, Object> reasonArgs;

    public ArchiveKernelException(String reasonCode, Map<String, Object> reasonArgs) {
        super(reasonCode);
        this.reasonCode = Objects.requireNonNull(reasonCode);
        this.reasonArgs = reasonArgs != null ? Map.copyOf(reasonArgs) : null;
    }

    public ArchiveKernelException(String reasonCode, Map<String, Object> reasonArgs, Throwable cause) {
        super(reasonCode, cause);
        this.reasonCode = Objects.requireNonNull(reasonCode);
        this.reasonArgs = reasonArgs != null ? Map.copyOf(reasonArgs) : null;
    }

    /** Machine-readable reason code per AK-* specification. */
    public String reasonCode() {
        return reasonCode;
    }

    /**
     * Structured args associated with this rejection; may be null.
     * Returns a defensive copy to prevent mutation of internal state.
     */
    public Map<String, Object> reasonArgs() {
        return reasonArgs != null ? Map.copyOf(reasonArgs) : null;
    }
}
