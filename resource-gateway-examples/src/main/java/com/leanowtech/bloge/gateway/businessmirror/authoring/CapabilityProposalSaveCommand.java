package com.leanowtech.bloge.gateway.businessmirror.authoring;

import com.leanowtech.bloge.gateway.businessmirror.domain.CapabilityProposalDraft;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;

/** Canonical material bound to one idempotent Capability Proposal create or save command. */
public record CapabilityProposalSaveCommand(
        String schemaVersion,
        Operation operation,
        CapabilitySnapshot.Scope scope,
        String proposalId,
        long expectedRevision,
        CapabilityProposalDraft draft,
        String actorId
) {
    public static final String SCHEMA_VERSION = "resourceGateway.capabilityProposalSaveCommand.v1";

    public enum Operation {
        CREATE,
        SAVE
    }

    public CapabilityProposalSaveCommand {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        operation = java.util.Objects.requireNonNull(operation, "operation");
        scope = java.util.Objects.requireNonNull(scope, "scope");
        proposalId = proposalId == null ? "" : proposalId.trim();
        draft = java.util.Objects.requireNonNull(draft, "draft");
        actorId = actorId == null ? "" : actorId.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion) || proposalId.isBlank()
                || expectedRevision < 0 || actorId.isBlank() || !scope.equals(draft.scope())
                || !proposalId.equals(draft.proposalId()) || draft.revision() != expectedRevision
                || (operation == Operation.CREATE) != (expectedRevision == 0)) {
            throw new IllegalArgumentException("Capability Proposal save command is incomplete or inconsistent");
        }
    }
}
