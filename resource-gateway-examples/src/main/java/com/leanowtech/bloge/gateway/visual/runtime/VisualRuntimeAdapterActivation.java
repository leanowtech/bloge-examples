package com.leanowtech.bloge.gateway.visual.runtime;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * Persisted runtime-plane adapter activation fact for one bound implementation.
 *
 * <p>An activation proves that a runtime owner has exposed the bound adapter in a
 * concrete environment. It is still a control-plane fact: it does not mutate the
 * imported operator definition and does not by itself make a design-only operator
 * executable by the current BLOGE runtime.</p>
 *
 * @param schemaVersion activation record contract version
 * @param activationId stable activation id
 * @param revision monotonically increasing record revision
 * @param state active, inactive, or failed
 * @param level UI/control-plane severity
 * @param bindingId active implementation binding id being activated
 * @param bindingRevision implementation binding revision observed at activation time
 * @param operatorRef operator being activated
 * @param operatorFingerprint operator fingerprint observed at activation time
 * @param adapterKind runtime adapter kind from the bound implementation
 * @param entrypoint executable adapter entrypoint from the bound implementation
 * @param runtimeOwner owning runtime team or service
 * @param runtimeEnvironment concrete runtime environment
 * @param healthState healthy runtime adapter health state required for activation
 * @param activatedBy principal or service that confirmed the activation
 * @param changeSource source system or workflow that submitted the activation
 * @param reason human-readable activation reason
 * @param evidence external activation evidence
 * @param createdAt first persistence timestamp
 * @param updatedAt latest persistence timestamp
 */
public record VisualRuntimeAdapterActivation(
        String schemaVersion,
        String activationId,
        long revision,
        String state,
        String level,
        String bindingId,
        long bindingRevision,
        String operatorRef,
        String operatorFingerprint,
        String adapterKind,
        String entrypoint,
        String runtimeOwner,
        String runtimeEnvironment,
        String healthState,
        String activatedBy,
        String changeSource,
        String reason,
        List<Evidence> evidence,
        Instant createdAt,
        Instant updatedAt
) {
    public static final String SCHEMA_VERSION = "bloge.visualRuntimeAdapterActivation.v1";
    public static final String STATE_ACTIVE = "active";
    public static final String STATE_INACTIVE = "inactive";
    public static final String STATE_FAILED = "failed";
    public static final String HEALTH_HEALTHY = "healthy";

    /**
     * Creates a normalized activation record.
     */
    public VisualRuntimeAdapterActivation {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        activationId = activationId == null ? "" : activationId.trim();
        revision = Math.max(0, revision);
        state = normalizeState(state, STATE_ACTIVE);
        level = level == null || level.isBlank() ? "success" : level.trim().toLowerCase(Locale.ROOT);
        bindingId = bindingId == null ? "" : bindingId.trim();
        bindingRevision = Math.max(0, bindingRevision);
        operatorRef = operatorRef == null ? "" : operatorRef.trim();
        operatorFingerprint = operatorFingerprint == null ? "" : operatorFingerprint.trim();
        adapterKind = adapterKind == null ? "" : adapterKind.trim().toLowerCase(Locale.ROOT);
        entrypoint = entrypoint == null ? "" : entrypoint.trim();
        runtimeOwner = runtimeOwner == null ? "" : runtimeOwner.trim();
        runtimeEnvironment = runtimeEnvironment == null ? "" : runtimeEnvironment.trim();
        healthState = normalizeState(healthState, HEALTH_HEALTHY);
        activatedBy = activatedBy == null ? "" : activatedBy.trim();
        changeSource = changeSource == null ? "" : changeSource.trim();
        reason = reason == null ? "" : reason.trim();
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        createdAt = createdAt == null ? Instant.EPOCH : createdAt;
        updatedAt = updatedAt == null ? createdAt : updatedAt;
    }

    /**
     * External proof attached to an adapter activation.
     *
     * @param kind evidence kind such as health-check, deployment, certification, or approval
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
     * Creates an activation fact from a successful validation result.
     *
     * @param request submitted activation request
     * @param validation validation snapshot
     * @return activation record without repository-assigned timestamps
     */
    public static VisualRuntimeAdapterActivation from(VisualRuntimeAdapterActivationValidation.Request request,
                                                      VisualRuntimeAdapterActivationValidation validation) {
        return new VisualRuntimeAdapterActivation(
                SCHEMA_VERSION,
                request == null ? "" : request.activationId(),
                0,
                STATE_ACTIVE,
                "success",
                validation == null ? "" : validation.bindingId(),
                validation == null ? 0 : validation.bindingRevision(),
                validation == null ? "" : validation.operatorRef(),
                validation == null ? "" : validation.operatorFingerprint(),
                validation == null ? "" : validation.adapterKind(),
                validation == null ? "" : validation.entrypoint(),
                validation == null ? "" : validation.runtimeOwner(),
                validation == null ? "" : validation.runtimeEnvironment(),
                validation == null ? HEALTH_HEALTHY : validation.healthState(),
                validation == null ? "" : validation.activatedBy(),
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
     * @param id activation id
     * @param nextRevision record revision
     * @param created timestamp
     * @param updated timestamp
     * @return activation record with identity
     */
    public VisualRuntimeAdapterActivation withIdentity(String id,
                                                       long nextRevision,
                                                       Instant created,
                                                       Instant updated) {
        return new VisualRuntimeAdapterActivation(
                schemaVersion,
                id,
                nextRevision,
                state,
                level,
                bindingId,
                bindingRevision,
                operatorRef,
                operatorFingerprint,
                adapterKind,
                entrypoint,
                runtimeOwner,
                runtimeEnvironment,
                healthState,
                activatedBy,
                changeSource,
                reason,
                evidence,
                created,
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
