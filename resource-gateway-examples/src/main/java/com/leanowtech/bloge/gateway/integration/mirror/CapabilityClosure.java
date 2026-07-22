package com.leanowtech.bloge.gateway.integration.mirror;

import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Immutable, content-addressed transitive closure for one composed capability.
 *
 * <p>The closure is the planning hand-off unit: it contains the exact root reference and every
 * reachable capability snapshot needed to compile a mirror plan without consulting mutable
 * registries. Structural and cryptographic validation is performed by
 * {@link CapabilityClosureIntegrity}.</p>
 *
 * @param schemaVersion closure protocol version
 * @param rootRef exact root capability snapshot
 * @param snapshots root and all transitively reachable child snapshots
 * @param fingerprint canonical closure fingerprint with this field blanked
 */
public record CapabilityClosure(
        String schemaVersion,
        MirrorArtifactRef rootRef,
        List<CapabilitySnapshot> snapshots,
        String fingerprint
) {
    /** Current closure protocol version. */
    public static final String SCHEMA_VERSION = "resourceGateway.capabilityClosure.v1";
    /** Maximum root-plus-dependency snapshots admitted by the v1 wire contract. */
    public static final int MAXIMUM_SNAPSHOTS = 10_001;
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    /** Normalizes deterministic ordering and rejects incomplete root coordinates. */
    public CapabilityClosure {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("unsupported capability closure schemaVersion");
        }
        rootRef = java.util.Objects.requireNonNull(rootRef, "rootRef");
        if (!"CAPABILITY".equals(rootRef.kind())) {
            throw new IllegalArgumentException("rootRef must reference a CAPABILITY artifact");
        }
        snapshots = snapshots == null ? List.of() : snapshots.stream()
                .sorted(Comparator.comparing(CapabilitySnapshot::capabilityId)
                        .thenComparingLong(CapabilitySnapshot::revision)
                        .thenComparing(CapabilitySnapshot::fingerprint))
                .toList();
        if (snapshots.isEmpty()) {
            throw new IllegalArgumentException("capability closure requires at least one snapshot");
        }
        if (snapshots.size() > MAXIMUM_SNAPSHOTS) {
            throw new IllegalArgumentException("capability closure exceeds its snapshot limit");
        }
        fingerprint = fingerprint == null ? "" : fingerprint.trim();
        if (!fingerprint.isBlank() && !FINGERPRINT.matcher(fingerprint).matches()) {
            throw new IllegalArgumentException("fingerprint must be blank or canonical SHA-256");
        }
    }

    /**
     * Returns the closure with a replacement canonical fingerprint.
     *
     * @param value canonical fingerprint, or blank fingerprint material
     * @return copied closure
     */
    public CapabilityClosure withFingerprint(String value) {
        return new CapabilityClosure(schemaVersion, rootRef, snapshots, value);
    }
}
