package com.leanowtech.bloge.gateway.visual.authoring.fixture;

import com.fasterxml.jackson.core.JsonPointer;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Frozen author command for deriving a governed Fixture Set revision. */
public record FixtureShareCommand(String schemaVersion, Source source, Policy policy) {
    public static final String SCHEMA_VERSION = "bloge.fixtureShareCommand.v1";
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[0-9a-f]{64}");
    private static final Set<String> CLASSIFICATIONS =
            Set.of("INTERNAL", "CONFIDENTIAL", "RESTRICTED");

    public FixtureShareCommand {
        schemaVersion = schemaVersion == null ? SCHEMA_VERSION : schemaVersion;
        if (!SCHEMA_VERSION.equals(schemaVersion) || source == null || policy == null) {
            throw new IllegalArgumentException("Fixture share command is invalid");
        }
    }

    /** Exact private source guarded by content and lifecycle CAS. */
    public record Source(String fixtureSetId, int revision, String fingerprint, int statusRevision) {
        public Source {
            if (fixtureSetId == null || !IDENTIFIER.matcher(fixtureSetId).matches()
                    || revision < 1 || fingerprint == null
                    || !FINGERPRINT.matcher(fingerprint).matches() || statusRevision < 1) {
                throw new IllegalArgumentException("Fixture share source is invalid");
            }
        }
    }

    /** Requested governance policy; the server still applies its stricter policy guard. */
    public record Policy(String classification, int retentionDays, Redaction redaction) {
        public Policy {
            classification = classification == null ? "" : classification.trim().toUpperCase(Locale.ROOT);
            if (!CLASSIFICATIONS.contains(classification) || retentionDays < 1 || redaction == null) {
                throw new IllegalArgumentException("Fixture share policy is invalid");
            }
        }
    }

    /** Bounded redaction profile and non-root JSON Pointer list. */
    public record Redaction(String profileVersion, List<String> paths) {
        public Redaction {
            paths = paths == null ? List.of() : paths.stream().map(value -> value == null ? "" : value.trim())
                    .distinct().sorted().toList();
            if (profileVersion == null || !IDENTIFIER.matcher(profileVersion.trim()).matches()
                    || paths.size() > 64 || paths.stream().anyMatch(value -> value.isBlank()
                    || value.length() > 512 || !validRedactionPath(value))) {
                throw new IllegalArgumentException("Fixture share redaction is invalid");
            }
            profileVersion = profileVersion.trim();
        }

        @Override public List<String> paths() { return List.copyOf(paths); }

        private static boolean validRedactionPath(String value) {
            if (!value.startsWith("/") || "/".equals(value)) return false;
            try {
                JsonPointer.compile(value);
                return true;
            } catch (IllegalArgumentException invalidPointer) {
                return false;
            }
        }
    }
}
