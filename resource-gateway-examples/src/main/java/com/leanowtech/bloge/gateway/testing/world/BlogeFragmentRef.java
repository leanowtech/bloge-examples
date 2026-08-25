package com.leanowtech.bloge.gateway.testing.world;

import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;

import java.util.Map;
import java.util.Objects;

/** Frozen, content-addressed reference to one .bloge world fragment artifact. */
public final class BlogeFragmentRef {
    private final String artifactId;
    private final long revision;
    private final String source;
    private final String outputNodeId;
    private final String fingerprint;

    private BlogeFragmentRef(String artifactId, long revision, String source, String outputNodeId) {
        this.artifactId = requireId(artifactId);
        if (revision <= 0) {
            throw new WorldModelException(WorldModelException.Code.FRAGMENT_INVALID);
        }
        this.revision = revision;
        this.source = requireSource(source);
        this.outputNodeId = outputNodeId == null ? "" : outputNodeId.trim();
        if (this.outputNodeId.length() > 256) {
            throw new WorldModelException(WorldModelException.Code.FRAGMENT_INVALID);
        }
        this.fingerprint = VisualBundleFingerprint.fromMaterial(Map.of(
                "artifactId", this.artifactId,
                "revision", this.revision,
                "source", this.source,
                "outputNodeId", this.outputNodeId));
    }

    public static BlogeFragmentRef frozen(String artifactId, String source) {
        return frozen(artifactId, 1, source, "");
    }

    public static BlogeFragmentRef frozen(String artifactId, long revision, String source) {
        return frozen(artifactId, revision, source, "");
    }

    public static BlogeFragmentRef frozen(String artifactId, String source, String outputNodeId) {
        return frozen(artifactId, 1, source, outputNodeId);
    }

    public static BlogeFragmentRef frozen(String artifactId, long revision,
                                          String source, String outputNodeId) {
        return new BlogeFragmentRef(artifactId, revision, source, outputNodeId);
    }

    public static BlogeFragmentRef freeze(String artifactId,
                                          String source,
                                          String outputNodeId,
                                          PureBlogeFragmentValidator validator) {
        return freeze(artifactId, 1, source, outputNodeId, validator);
    }

    public static BlogeFragmentRef freeze(String artifactId,
                                          long revision,
                                          String source,
                                          String outputNodeId,
                                          PureBlogeFragmentValidator validator) {
        BlogeFragmentRef fragment = frozen(artifactId, revision, source, outputNodeId);
        if (validator == null) {
            throw new WorldModelException(WorldModelException.Code.FRAGMENT_INVALID);
        }
        validator.validate(fragment);
        return fragment;
    }

    public String artifactId() {
        return artifactId;
    }

    public long revision() {
        return revision;
    }

    public String source() {
        return source;
    }

    public String outputNodeId() {
        return outputNodeId;
    }

    public String fingerprint() {
        return fingerprint;
    }

    private static String requireId(String value) {
        if (value == null || value.isBlank() || value.length() > 256 || !value.endsWith(".bloge")) {
            throw new WorldModelException(WorldModelException.Code.FRAGMENT_INVALID);
        }
        return value.trim();
    }

    private static String requireSource(String value) {
        if (value == null || value.isBlank() || value.length() > 1_000_000) {
            throw new WorldModelException(WorldModelException.Code.FRAGMENT_INVALID);
        }
        return value.replace("\r\n", "\n").replace('\r', '\n').strip();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof BlogeFragmentRef ref && fingerprint.equals(ref.fingerprint);
    }

    @Override
    public int hashCode() {
        return Objects.hash(fingerprint);
    }
}
