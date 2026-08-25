package com.leanowtech.bloge.gateway.gatewayverifier.archive;

import java.util.Map;
import java.util.Objects;

/**
 * Immutable value type for A1.3-R03 DEVELOPMENT predecessor fingerprint binding.
 * Produced by Python orchestrator; consumed by DevelopmentPredecessorBindingVerifier.
 *
 * @param bindingFingerprint        sha256:<64 hex> - domain+NUL+canonical JSON excl. this field
 * @param authorityRawFingerprint    sha256:<64 hex> - raw Authority bytes
 * @param messageVersion            fixed "1.0.0"
 * @param sourceSliceId             fixed "A1.2"
 * @param targetSliceId            fixed "A1.3"
 * @param providerArtifact          immutable ProviderArtifact
 * @param providerEntryPath         derived from INDEPENDENT_VERIFIER role contract
 * @param providerBytes            verified raw bytes - defensive copy on access
 */
public final class DevelopmentPredecessorBinding {

    public static final String MESSAGE_VERSION = "1.0.0";
    public static final String SOURCE_SLICE_ID  = "A1.2";
    public static final String TARGET_SLICE_ID  = "A1.3";

    public record ProviderArtifact(
            String coordinate,    // groupId:artifactId:version
            String path,         // repo-relative POSIX path
            long byteLength,     // 1..16MiB
            String rawFingerprint // sha256:<64 hex>
    ) {
        public ProviderArtifact {
            Objects.requireNonNull(coordinate, "coordinate");
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(rawFingerprint, "rawFingerprint");
            if (byteLength < 1 || byteLength > 16 * 1024 * 1024)
                throw new IllegalArgumentException("byteLength out of range: " + byteLength);
            if (!rawFingerprint.matches("^sha256:[0-9a-f]{64}$"))
                throw new IllegalArgumentException("invalid rawFingerprint: " + rawFingerprint);
        }
    }

    private final String bindingFingerprint;
    private final String authorityRawFingerprint;
    private final String messageVersion;
    private final String sourceSliceId;
    private final String targetSliceId;
    private final ProviderArtifact providerArtifact;
    private final String providerEntryPath;
    private final byte[] providerBytes;

    public DevelopmentPredecessorBinding(
            String bindingFingerprint,
            String authorityRawFingerprint,
            String messageVersion,
            String sourceSliceId,
            String targetSliceId,
            ProviderArtifact providerArtifact,
            String providerEntryPath,
            byte[] providerBytes) {
        this.bindingFingerprint      = Objects.requireNonNull(bindingFingerprint);
        this.authorityRawFingerprint = Objects.requireNonNull(authorityRawFingerprint);
        this.messageVersion         = Objects.requireNonNull(messageVersion);
        this.sourceSliceId          = Objects.requireNonNull(sourceSliceId);
        this.targetSliceId          = Objects.requireNonNull(targetSliceId);
        this.providerArtifact        = Objects.requireNonNull(providerArtifact);
        this.providerEntryPath      = Objects.requireNonNull(providerEntryPath);
        this.providerBytes          = providerBytes == null ? null : providerBytes.clone();

        for (var fp : new String[]{bindingFingerprint, authorityRawFingerprint, providerArtifact.rawFingerprint()})
            if (!fp.matches("^sha256:[0-9a-f]{64}$"))
                throw new IllegalArgumentException("invalid fingerprint: " + fp);
    }

    public String bindingFingerprint()            { return bindingFingerprint; }
    public String authorityRawFingerprint()       { return authorityRawFingerprint; }
    public String messageVersion()               { return messageVersion; }
    public String sourceSliceId()                { return sourceSliceId; }
    public String targetSliceId()                { return targetSliceId; }
    public ProviderArtifact providerArtifact()   { return providerArtifact; }
    public String providerEntryPath()            { return providerEntryPath; }

    /** Defensive copy - never expose mutable internal array. */
    public byte[] providerBytes()               { return providerBytes == null ? null : providerBytes.clone(); }
    public String providerCoordinate()           { return providerArtifact.coordinate(); }
    public String providerPath()                { return providerArtifact.path(); }

    @Override public String toString() {
        return "DevelopmentPredecessorBinding{fingerprint=" + bindingFingerprint +
                ", authorityFp=" + authorityRawFingerprint +
                ", provider=" + providerArtifact + "}";
    }

    @Override public boolean equals(Object o) {
        return o instanceof DevelopmentPredecessorBinding b
                && Objects.equals(bindingFingerprint, b.bindingFingerprint)
                && Objects.equals(authorityRawFingerprint, b.authorityRawFingerprint)
                && Objects.equals(messageVersion, b.messageVersion)
                && Objects.equals(sourceSliceId, b.sourceSliceId)
                && Objects.equals(targetSliceId, b.targetSliceId)
                && Objects.equals(providerArtifact, b.providerArtifact)
                && Objects.equals(providerEntryPath, b.providerEntryPath);
    }

    @Override public int hashCode() {
        return Objects.hash(bindingFingerprint, authorityRawFingerprint, messageVersion,
                sourceSliceId, targetSliceId, providerArtifact, providerEntryPath);
    }
}
