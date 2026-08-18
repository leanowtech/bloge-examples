package com.leanowtech.bloge.gateway.capabilitystudio;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Deployment-owned source for the immutable Resource Gateway candidate running the demo.
 *
 * <p>The authority is assembled once at application startup. Capability Studio requests cannot
 * provide or replace these coordinates. An entirely absent configuration is represented as an
 * unbound candidate; a partially configured candidate fails startup instead of silently
 * weakening release evidence.</p>
 */
public final class CapabilityStudioDeploymentCandidateAuthority {

    private static final Pattern SAFE_REF = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");
    private static final Pattern SOURCE_COMMIT = Pattern.compile("[a-f0-9]{7,64}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    private final Optional<Binding> binding;

    /** Creates an authority from deployment-owned startup values. */
    public CapabilityStudioDeploymentCandidateAuthority(
            String authority,
            String instanceId,
            String buildRef,
            String revision,
            String sourceCommit,
            String sourceTreeStatus,
            String artifactFingerprint) {
        List<String> values = List.of(
                normalized(authority),
                normalized(instanceId),
                normalized(buildRef),
                normalized(revision),
                normalized(sourceCommit),
                normalized(sourceTreeStatus),
                normalized(artifactFingerprint));
        long configured = values.stream().filter(value -> !value.isBlank()).count();
        if (configured == 0) {
            binding = Optional.empty();
            return;
        }
        if (configured != values.size()) {
            throw new IllegalArgumentException(
                    "deployment candidate binding must be completely configured or absent");
        }
        binding = Optional.of(new Binding(
                values.get(0), values.get(1), values.get(2), values.get(3), values.get(4),
                values.get(5), values.get(6)));
    }

    /** Creates an explicitly unbound authority for tests and non-release local composition. */
    public static CapabilityStudioDeploymentCandidateAuthority unbound() {
        return new CapabilityStudioDeploymentCandidateAuthority(
                "", "", "", "", "", "", "");
    }

    /** Returns the startup-frozen candidate coordinates when the deployment supplied all fields. */
    public Optional<Binding> current() {
        return binding;
    }

    /** Payload-free exact build identity owned by the deployment launcher. */
    public record Binding(
            String authority,
            String instanceId,
            String buildRef,
            String revision,
            String sourceCommit,
            String sourceTreeStatus,
            String artifactFingerprint) {

        /** Validates canonical, immutable build coordinates. */
        public Binding {
            authority = safe(authority, "authority");
            instanceId = safe(instanceId, "instanceId");
            buildRef = safe(buildRef, "buildRef");
            revision = safe(revision, "revision");
            sourceCommit = normalized(sourceCommit).toLowerCase(Locale.ROOT);
            sourceTreeStatus = normalized(sourceTreeStatus);
            artifactFingerprint = normalized(artifactFingerprint);
            if (!SOURCE_COMMIT.matcher(sourceCommit).matches()) {
                throw new IllegalArgumentException("sourceCommit must be a hexadecimal commit id");
            }
            if (!FINGERPRINT.matcher(artifactFingerprint).matches()) {
                throw new IllegalArgumentException(
                        "artifactFingerprint must use canonical sha256:<lowercase-hex>");
            }
            if (!"CLEAN".equals(sourceTreeStatus)) {
                throw new IllegalArgumentException(
                        "sourceTreeStatus must be CLEAN for an immutable candidate");
            }
        }
    }

    private static String safe(String value, String field) {
        String normalized = normalized(value);
        if (!SAFE_REF.matcher(normalized).matches()) {
            throw new IllegalArgumentException(field + " is not a safe deployment reference");
        }
        return normalized;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
