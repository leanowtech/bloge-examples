package com.leanowtech.bloge.gateway.businessmirror.authoring;

import com.leanowtech.bloge.gateway.businessmirror.domain.DomainCapabilityPackageDraft;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;

/** Canonical material bound to one idempotent Package create or save command. */
public record DomainCapabilityPackageSaveCommand(
        String schemaVersion,
        Operation operation,
        CapabilitySnapshot.Scope scope,
        String packageId,
        long expectedRevision,
        DomainCapabilityPackageDraft draft,
        String actorId
) {
    public static final String SCHEMA_VERSION = "resourceGateway.domainCapabilityPackageSaveCommand.v1";

    public enum Operation {
        CREATE,
        SAVE
    }

    public DomainCapabilityPackageSaveCommand {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        operation = java.util.Objects.requireNonNull(operation, "operation");
        scope = java.util.Objects.requireNonNull(scope, "scope");
        packageId = packageId == null ? "" : packageId.trim();
        draft = java.util.Objects.requireNonNull(draft, "draft");
        actorId = actorId == null ? "" : actorId.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion) || packageId.isBlank() || expectedRevision < 0
                || actorId.isBlank() || !scope.equals(draft.scope())
                || !packageId.equals(draft.packageId()) || draft.revision() != expectedRevision
                || (operation == Operation.CREATE) != (expectedRevision == 0)) {
            throw new IllegalArgumentException("Package save command is incomplete or inconsistent");
        }
    }
}
