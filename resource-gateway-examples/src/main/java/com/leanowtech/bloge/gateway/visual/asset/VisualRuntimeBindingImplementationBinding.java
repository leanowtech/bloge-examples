package com.leanowtech.bloge.gateway.visual.asset;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * Persisted runtime implementation proposal created after a handoff contract is validated.
 *
 * <p>This is a control-plane record, not executable graph state. A stored
 * proposal proves that a runtime team submitted implementation evidence against
 * a specific handoff operator contract; future bind/supersede mutations can use
 * this record as their auditable source.</p>
 *
 * @param schemaVersion binding record contract version
 * @param bindingId stable binding proposal id
 * @param revision monotonically increasing record revision
 * @param state ready-to-bind or requires-review
 * @param level UI/control-plane severity
 * @param operatorRef operator being implemented
 * @param operatorFingerprint fingerprint of the submitted operator contract
 * @param sourceHandoffBundleFingerprint source handoff bundle fingerprint
 * @param sourceRequirementKeys requirement keys covered by the proposal
 * @param operatorContract submitted handoff operator contract snapshot
 * @param implementation submitted implementation metadata
 * @param validation validation snapshot used to accept the proposal
 * @param createdAt first persistence timestamp
 * @param updatedAt latest persistence timestamp
 */
public record VisualRuntimeBindingImplementationBinding(
        String schemaVersion,
        String bindingId,
        long revision,
        String state,
        String level,
        String operatorRef,
        String operatorFingerprint,
        String sourceHandoffBundleFingerprint,
        List<String> sourceRequirementKeys,
        VisualRuntimeBindingHandoffBundle.OperatorContractSnapshot operatorContract,
        VisualRuntimeBindingImplementationValidation.ImplementationMetadata implementation,
        VisualRuntimeBindingImplementationValidation validation,
        Instant createdAt,
        Instant updatedAt
) {
    public static final String SCHEMA_VERSION = "bloge.visualRuntimeBindingImplementationBindingRecord.v1";

    /**
     * Creates a normalized binding record.
     */
    public VisualRuntimeBindingImplementationBinding {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        bindingId = bindingId == null ? "" : bindingId.trim();
        revision = Math.max(0, revision);
        state = state == null || state.isBlank() ? "requires-review" : state.trim().toLowerCase(Locale.ROOT);
        level = level == null || level.isBlank() ? "warning" : level.trim().toLowerCase(Locale.ROOT);
        operatorRef = operatorRef == null ? "" : operatorRef.trim();
        operatorFingerprint = operatorFingerprint == null ? "" : operatorFingerprint.trim();
        sourceHandoffBundleFingerprint = sourceHandoffBundleFingerprint == null
                ? ""
                : sourceHandoffBundleFingerprint.trim();
        sourceRequirementKeys = sourceRequirementKeys == null ? List.of() : List.copyOf(sourceRequirementKeys);
        createdAt = createdAt == null ? Instant.EPOCH : createdAt;
        updatedAt = updatedAt == null ? createdAt : updatedAt;
    }

    /**
     * Creates a pending binding record from a successful validation.
     *
     * @param request submitted implementation request
     * @param validation validation result with no blocking errors
     * @return binding record without repository-assigned identity
     */
    public static VisualRuntimeBindingImplementationBinding from(
            VisualRuntimeBindingImplementationValidation.Request request,
            VisualRuntimeBindingImplementationValidation validation) {
        VisualRuntimeBindingImplementationValidation.ImplementationMetadata implementation =
                request == null ? null : request.implementation();
        String bindingId = implementation == null ? "" : implementation.bindingId();
        String state = validation == null ? "requires-review" : validation.state();
        String level = validation == null ? "warning" : validation.level();
        return new VisualRuntimeBindingImplementationBinding(
                SCHEMA_VERSION,
                bindingId,
                0,
                state,
                level,
                request == null ? "" : request.operatorRef(),
                request == null ? "" : request.operatorFingerprint(),
                request == null ? "" : request.sourceHandoffBundleFingerprint(),
                request == null ? List.of() : request.sourceRequirementKeys(),
                request == null ? null : request.operatorContract(),
                implementation,
                validation,
                Instant.EPOCH,
                Instant.EPOCH
        );
    }

    /**
     * Assigns repository identity and timestamps.
     *
     * @param id binding id
     * @param nextRevision record revision
     * @param created timestamp
     * @param updated timestamp
     * @return binding record with identity
     */
    public VisualRuntimeBindingImplementationBinding withIdentity(String id,
                                                                  long nextRevision,
                                                                  Instant created,
                                                                  Instant updated) {
        return new VisualRuntimeBindingImplementationBinding(
                schemaVersion,
                id,
                nextRevision,
                state,
                level,
                operatorRef,
                operatorFingerprint,
                sourceHandoffBundleFingerprint,
                sourceRequirementKeys,
                operatorContract,
                implementation,
                validation,
                created,
                updated
        );
    }
}
