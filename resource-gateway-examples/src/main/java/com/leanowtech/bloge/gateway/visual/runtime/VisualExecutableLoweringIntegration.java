package com.leanowtech.bloge.gateway.visual.runtime;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * Persisted proof that a runtime adapter activation has a BLOGE lowering/executor bridge.
 *
 * <p>This is still a control-plane fact. It does not rewrite imported operator
 * definitions and does not by itself make a design-only operator executable by
 * the current request-response runtime.</p>
 *
 * @param schemaVersion integration record contract version
 * @param integrationId stable integration id
 * @param revision monotonically increasing record revision
 * @param state active, inactive, or failed
 * @param level UI/control-plane severity
 * @param activationId adapter activation id being integrated
 * @param activationRevision adapter activation revision observed by the executor platform
 * @param bindingId implementation binding id
 * @param bindingRevision implementation binding revision
 * @param operatorRef operator being integrated
 * @param operatorFingerprint operator fingerprint observed at integration time
 * @param adapterKind runtime adapter kind
 * @param entrypoint runtime adapter entrypoint
 * @param runtimeEnvironment runtime environment
 * @param loweringMode executable lowering mode exposed to BLOGE
 * @param executorKind executor integration kind
 * @param executorEntrypoint executable BLOGE lowering/executor entrypoint
 * @param executorOwner owning executor platform team or service
 * @param integratedBy principal or service that confirmed integration
 * @param changeSource source system or workflow that submitted the latest integration lifecycle transition
 * @param reason human-readable latest integration lifecycle reason
 * @param evidence external integration evidence
 * @param createdAt first persistence timestamp
 * @param updatedAt latest persistence timestamp
 */
public record VisualExecutableLoweringIntegration(
        String schemaVersion,
        String integrationId,
        long revision,
        String state,
        String level,
        String activationId,
        long activationRevision,
        String bindingId,
        long bindingRevision,
        String operatorRef,
        String operatorFingerprint,
        String adapterKind,
        String entrypoint,
        String runtimeEnvironment,
        String loweringMode,
        String executorKind,
        String executorEntrypoint,
        String executorOwner,
        String integratedBy,
        String changeSource,
        String reason,
        List<Evidence> evidence,
        Instant createdAt,
        Instant updatedAt
) {
    public static final String SCHEMA_VERSION = "bloge.visualExecutableLoweringIntegration.v1";
    public static final String STATE_ACTIVE = "active";
    public static final String STATE_INACTIVE = "inactive";
    public static final String STATE_FAILED = "failed";

    /**
     * Creates a normalized integration record.
     */
    public VisualExecutableLoweringIntegration {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        integrationId = integrationId == null ? "" : integrationId.trim();
        revision = Math.max(0, revision);
        state = normalizeState(state, STATE_ACTIVE);
        level = level == null || level.isBlank() ? "success" : level.trim().toLowerCase(Locale.ROOT);
        activationId = activationId == null ? "" : activationId.trim();
        activationRevision = Math.max(0, activationRevision);
        bindingId = bindingId == null ? "" : bindingId.trim();
        bindingRevision = Math.max(0, bindingRevision);
        operatorRef = operatorRef == null ? "" : operatorRef.trim();
        operatorFingerprint = operatorFingerprint == null ? "" : operatorFingerprint.trim();
        adapterKind = adapterKind == null ? "" : adapterKind.trim().toLowerCase(Locale.ROOT);
        entrypoint = entrypoint == null ? "" : entrypoint.trim();
        runtimeEnvironment = runtimeEnvironment == null ? "" : runtimeEnvironment.trim();
        loweringMode = normalizeState(loweringMode, "");
        executorKind = executorKind == null ? "" : executorKind.trim().toLowerCase(Locale.ROOT);
        executorEntrypoint = executorEntrypoint == null ? "" : executorEntrypoint.trim();
        executorOwner = executorOwner == null ? "" : executorOwner.trim();
        integratedBy = integratedBy == null ? "" : integratedBy.trim();
        changeSource = changeSource == null ? "" : changeSource.trim();
        reason = reason == null ? "" : reason.trim();
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        createdAt = createdAt == null ? Instant.EPOCH : createdAt;
        updatedAt = updatedAt == null ? createdAt : updatedAt;
    }

    /**
     * External proof attached to an executable lowering integration.
     *
     * @param kind evidence kind such as executor-test, lowering-bridge, certification, or approval
     * @param ref external evidence identifier or URL
     * @param summary human-readable evidence summary
     */
    public record Evidence(String kind, String ref, String summary) {
        public Evidence {
            kind = kind == null ? "" : kind.trim().toLowerCase(Locale.ROOT);
            ref = ref == null ? "" : ref.trim();
            summary = summary == null ? "" : summary.trim();
        }
    }

    /**
     * Creates an integration fact from a successful validation result.
     *
     * @param request submitted integration request
     * @param validation validation snapshot
     * @return integration record without repository-assigned timestamps
     */
    public static VisualExecutableLoweringIntegration from(
            VisualExecutableLoweringIntegrationValidation.Request request,
            VisualExecutableLoweringIntegrationValidation validation) {
        return new VisualExecutableLoweringIntegration(
                SCHEMA_VERSION,
                request == null ? "" : request.integrationId(),
                0,
                STATE_ACTIVE,
                "success",
                validation == null ? "" : validation.activationId(),
                validation == null ? 0 : validation.activationRevision(),
                validation == null ? "" : validation.bindingId(),
                validation == null ? 0 : validation.bindingRevision(),
                validation == null ? "" : validation.operatorRef(),
                validation == null ? "" : validation.operatorFingerprint(),
                validation == null ? "" : validation.adapterKind(),
                validation == null ? "" : validation.entrypoint(),
                validation == null ? "" : validation.runtimeEnvironment(),
                validation == null ? "" : validation.loweringMode(),
                validation == null ? "" : validation.executorKind(),
                validation == null ? "" : validation.executorEntrypoint(),
                validation == null ? "" : validation.executorOwner(),
                validation == null ? "" : validation.integratedBy(),
                request == null ? "" : request.changeSource(),
                validation == null ? "" : validation.reason(),
                request == null ? List.of() : request.evidence(),
                Instant.EPOCH,
                Instant.EPOCH
        );
    }

    /**
     * Assigns repository identity and timestamps.
     *
     * @param id integration id
     * @param nextRevision record revision
     * @param created timestamp
     * @param updated timestamp
     * @return integration record with identity
     */
    public VisualExecutableLoweringIntegration withIdentity(String id,
                                                            long nextRevision,
                                                            Instant created,
                                                            Instant updated) {
        return new VisualExecutableLoweringIntegration(
                schemaVersion,
                id,
                nextRevision,
                state,
                level,
                activationId,
                activationRevision,
                bindingId,
                bindingRevision,
                operatorRef,
                operatorFingerprint,
                adapterKind,
                entrypoint,
                runtimeEnvironment,
                loweringMode,
                executorKind,
                executorEntrypoint,
                executorOwner,
                integratedBy,
                changeSource,
                reason,
                evidence,
                created,
                updated
        );
    }

    /**
     * Applies a lifecycle state transition while retaining prior integration evidence.
     *
     * @param nextState next integration state
     * @param nextLevel UI/control-plane level
     * @param nextChangeSource source workflow for the transition
     * @param nextReason transition reason
     * @param transitionEvidence persisted evidence for the transition
     * @param updated transition timestamp
     * @return integration record ready for repository update
     */
    public VisualExecutableLoweringIntegration withStateTransition(String nextState,
                                                                   String nextLevel,
                                                                   String nextChangeSource,
                                                                   String nextReason,
                                                                   Evidence transitionEvidence,
                                                                   Instant updated) {
        java.util.ArrayList<Evidence> nextEvidence = new java.util.ArrayList<>(evidence);
        if (transitionEvidence != null) {
            nextEvidence.add(transitionEvidence);
        }
        return new VisualExecutableLoweringIntegration(
                schemaVersion,
                integrationId,
                revision,
                nextState,
                nextLevel,
                activationId,
                activationRevision,
                bindingId,
                bindingRevision,
                operatorRef,
                operatorFingerprint,
                adapterKind,
                entrypoint,
                runtimeEnvironment,
                loweringMode,
                executorKind,
                executorEntrypoint,
                executorOwner,
                integratedBy,
                nextChangeSource,
                nextReason,
                nextEvidence,
                createdAt,
                updated
        );
    }

    public boolean active() {
        return STATE_ACTIVE.equals(state);
    }

    private static String normalizeState(String value, String fallback) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        return normalized.isBlank() ? fallback : normalized;
    }
}
