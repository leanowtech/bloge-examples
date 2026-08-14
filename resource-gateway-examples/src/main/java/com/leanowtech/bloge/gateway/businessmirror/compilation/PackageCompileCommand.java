package com.leanowtech.bloge.gateway.businessmirror.compilation;

import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;

import java.util.regex.Pattern;

/** Canonical material bound to one idempotent Package compilation command. */
public record PackageCompileCommand(
        String schemaVersion,
        CapabilitySnapshot.Scope scope,
        String packageId,
        long sourceDraftRevision,
        String sourceDraftFingerprint,
        String actorId
) {
    /** Current internal command protocol. */
    public static final String SCHEMA_VERSION = "resourceGateway.packageCompileCommand.v1";
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    /** Enforces exact source identity and trusted actor attribution. */
    public PackageCompileCommand {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        scope = java.util.Objects.requireNonNull(scope, "scope");
        packageId = normalized(packageId);
        sourceDraftFingerprint = normalized(sourceDraftFingerprint);
        actorId = normalized(actorId);
        if (!SCHEMA_VERSION.equals(schemaVersion) || packageId.isBlank()
                || sourceDraftRevision < 1 || !FINGERPRINT.matcher(sourceDraftFingerprint).matches()
                || actorId.isBlank()) {
            throw new IllegalArgumentException("Package compile command is incomplete or inconsistent");
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
