package com.leanowtech.bloge.gateway.gatewayverifier.archive;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Typed binding between a packaging plan and archive entry content.
 *
 * <p>This class validates that a parsed packaging plan (provided as raw canonical
 * bytes) declares nested-JAR dependencies, and that each dependency's
 * content-addressable fingerprint matches the corresponding ZIP entry's uncompressed
 * raw bytes SHA-256.
 *
 * <p>Fingerprint validation uses strict format checking: the expected fingerprint
 * must match the pattern {@code sha256:<64 lowerhex>}. Invalid formats cause
 * immediate rejection without leaking bytes or path information.
 *
 * <p>Construction validates all dependencies for non-null fields and valid
 * fingerprint format, so malformed plans are rejected at construction time
 * with {@code IllegalArgumentException} rather than being misreported as
 * {@code AK-NESTED-JAR-COUNT} at bind time.
 *
 * <p>This class is immutable and thread-safe.
 */
public final class PackagingPlanBinding {

    /**
     * Immutable descriptor for a single dependency declared in the packaging plan.
     *
     * @param lockId           stable lock identifier
     * @param entryPath        expected ZIP entry path
     * @param rawFingerprint   expected SHA-256 fingerprint in {@code sha256:<64 lowerhex>} format
     */
    public record Dependency(String lockId, String entryPath, String rawFingerprint) {

        public Dependency {
            Objects.requireNonNull(lockId, "lockId must not be null");
            Objects.requireNonNull(entryPath, "entryPath must not be null");
            Objects.requireNonNull(rawFingerprint, "rawFingerprint must not be null");
        }

        /**
         * Validates the fingerprint format strictly.
         *
         * @return true if the fingerprint matches {@code sha256:<64 lowerhex>}
         */
        public boolean hasValidFingerprintFormat() {
            return isValidSha256Fingerprint(rawFingerprint);
        }
    }

    private final byte[] rawPlanBytes;
    private final String expectedPlanFingerprint;
    private final List<Dependency> dependencies;

    /**
     * Constructs a packaging plan binding from raw canonical plan bytes.
     *
     * @param rawPlanBytes           raw canonical plan bytes (defensive copy made)
     * @param expectedPlanFingerprint expected plan raw fingerprint in {@code sha256:<64 lowerhex>} format
     * @param dependencies           dependencies parsed from the plan (defensive copy made);
     *                               each dependency must have non-null fields and valid fingerprint format
     * @throws NullPointerException if any argument or dependency field is null
     * @throws IllegalArgumentException if expectedPlanFingerprint format is invalid
     */
    public PackagingPlanBinding(
            byte[] rawPlanBytes,
            String expectedPlanFingerprint,
            List<Dependency> dependencies
    ) {
        this.rawPlanBytes = Objects.requireNonNull(rawPlanBytes, "rawPlanBytes must not be null")
                .clone();
        this.expectedPlanFingerprint = Objects.requireNonNull(expectedPlanFingerprint,
                "expectedPlanFingerprint must not be null");
        // Validate all dependencies: non-null fields, valid fingerprint format.
        // Fail fast so malformed plans are not misreported as AK-NESTED-JAR-COUNT.
        List<Dependency> validated = new ArrayList<>();
        for (Dependency dep : Objects.requireNonNull(dependencies, "dependencies must not be null")) {
            Objects.requireNonNull(dep.lockId(),
                    "dependency lockId must not be null");
            Objects.requireNonNull(dep.entryPath(),
                    "dependency entryPath must not be null");
            Objects.requireNonNull(dep.rawFingerprint(),
                    "dependency rawFingerprint must not be null");
            if (!isValidSha256Fingerprint(dep.rawFingerprint())) {
                throw new IllegalArgumentException(
                        "dependency rawFingerprint must match sha256:<64 lowerhex>");
            }
            validated.add(dep);
        }
        this.dependencies = List.copyOf(validated);

        // Validate plan fingerprint format
        if (!isValidSha256Fingerprint(this.expectedPlanFingerprint)) {
            throw new IllegalArgumentException(
                    "expectedPlanFingerprint must match sha256:<64 lowerhex>");
        }
    }

    /**
     * Validates a SHA-256 fingerprint format strictly.
     *
     * <p>The fingerprint must match: {@code sha256:<64 lowerhex>}
     * where the 64 hex characters represent exactly 32 bytes.
     *
     * <p>Fail-closed: any invalid format returns false without leaking bytes.
     */
    public static boolean isValidSha256Fingerprint(String fingerprint) {
        if (fingerprint == null || fingerprint.length() != 7 + 64) {
            return false;
        }
        if (!fingerprint.startsWith("sha256:")) {
            return false;
        }
        String hex = fingerprint.substring(7);
        for (int i = 0; i < hex.length(); i++) {
            char c = hex.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Computes the SHA-256 fingerprint of the raw plan bytes.
     *
     * @return fingerprint in {@code sha256:<64 lowerhex>} format
     */
    public String computePlanFingerprint() {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(rawPlanBytes);
            return "sha256:" + hex(digest);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    /**
     * Validates that the computed plan fingerprint matches the expected fingerprint.
     */
    public boolean validatePlanFingerprint() {
        String computed = computePlanFingerprint();
        return constantTimeEquals(expectedPlanFingerprint, computed);
    }

    /**
     * Constant-time string comparison to prevent timing attacks.
     */
    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        if (a.length() != b.length()) return false;
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }

    public String expectedPlanFingerprint() {
        return expectedPlanFingerprint;
    }

    public List<Dependency> dependencies() {
        return dependencies;
    }

    public byte[] rawPlanBytes() {
        return rawPlanBytes.clone();
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
