package com.leanowtech.bloge.gateway.visual.model;

import java.util.Optional;

/**
 * Minimal semantic version core used by visual control-plane governance gates.
 *
 * <p>The parser intentionally compares only MAJOR.MINOR.PATCH core numbers and
 * tolerates prerelease/build suffixes for governance warnings. Full SemVer
 * precedence is unnecessary for the current gates and would make warning
 * behavior look more precise than it is.</p>
 *
 * @param major major version
 * @param minor minor version
 * @param patch patch version
 */
public record SemanticVersion(int major, int minor, int patch) {

    /**
     * Parses a semantic version core from {@code MAJOR.MINOR.PATCH}.
     *
     * @param value version string
     * @return parsed core version when valid
     */
    public static Optional<SemanticVersion> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String core = value.trim().split("[-+]", 2)[0];
        String[] parts = core.split("\\.");
        if (parts.length != 3) {
            return Optional.empty();
        }
        try {
            int major = Integer.parseInt(parts[0]);
            int minor = Integer.parseInt(parts[1]);
            int patch = Integer.parseInt(parts[2]);
            if (major < 0 || minor < 0 || patch < 0) {
                return Optional.empty();
            }
            return Optional.of(new SemanticVersion(major, minor, patch));
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    /**
     * Compares only MAJOR.MINOR.PATCH core values.
     *
     * @param other other semantic version
     * @return negative, zero, or positive comparison result
     */
    public int compareCore(SemanticVersion other) {
        int majorCompare = Integer.compare(major, other.major);
        if (majorCompare != 0) {
            return majorCompare;
        }
        int minorCompare = Integer.compare(minor, other.minor);
        if (minorCompare != 0) {
            return minorCompare;
        }
        return Integer.compare(patch, other.patch);
    }

    /**
     * @param previous previous version
     * @return true when this version bumps major or minor relative to previous
     */
    public boolean hasMinorOrMajorBumpFrom(SemanticVersion previous) {
        return major > previous.major || major == previous.major && minor > previous.minor;
    }
}
