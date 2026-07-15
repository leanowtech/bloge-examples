package com.leanowtech.bloge.gateway.testing.domain;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Exact content-addressed reference to one governed replay payload revision.
 *
 * <p>The wire form is {@code bloge-replay:&lt;id&gt;@&lt;revision&gt;#&lt;sha256 fingerprint&gt;}.
 * It deliberately has no {@code latest} form: fixture compilation must resolve one immutable
 * payload or fail before graph execution starts.</p>
 *
 * @param replayPayloadId stable payload id
 * @param revision positive immutable revision
 * @param fingerprint canonical payload fingerprint
 */
public record ReplayPayloadRef(String replayPayloadId, long revision, String fingerprint) {

    private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,254}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Pattern REFERENCE = Pattern.compile(
            "bloge-replay:([A-Za-z0-9][A-Za-z0-9._-]{0,254})@([1-9][0-9]*)#(sha256:[a-f0-9]{64})");

    /** Validates all immutable identity components. */
    public ReplayPayloadRef {
        replayPayloadId = normalized(replayPayloadId);
        fingerprint = normalized(fingerprint);
        if (!ID.matcher(replayPayloadId).matches()) {
            throw new IllegalArgumentException(
                    "replayPayloadId must match " + ID.pattern());
        }
        if (revision <= 0) {
            throw new IllegalArgumentException("replay payload revision must be positive");
        }
        if (!FINGERPRINT.matcher(fingerprint).matches()) {
            throw new IllegalArgumentException("replay payload fingerprint must be canonical sha256");
        }
    }

    /**
     * Parses and validates one canonical replay reference.
     *
     * @param value canonical wire reference
     * @return exact replay payload reference
     * @throws IllegalArgumentException when the reference is not canonical
     */
    public static ReplayPayloadRef parse(String value) {
        Matcher matcher = REFERENCE.matcher(normalized(value));
        if (!matcher.matches()) {
            throw new IllegalArgumentException(
                    "replayRef must use bloge-replay:<id>@<revision>#<sha256 fingerprint>");
        }
        try {
            return new ReplayPayloadRef(matcher.group(1), Long.parseLong(matcher.group(2)), matcher.group(3));
        } catch (NumberFormatException overflow) {
            throw new IllegalArgumentException("replay payload revision exceeds the supported range", overflow);
        }
    }

    /**
     * Encodes this exact identity for storage in {@code FixtureRule.behavior.replayRef}.
     *
     * @return canonical replay reference
     */
    public String canonical() {
        return "bloge-replay:" + replayPayloadId + "@" + revision + "#" + fingerprint;
    }

    private static String normalized(String value) {
        return Objects.requireNonNullElse(value, "").trim();
    }
}
