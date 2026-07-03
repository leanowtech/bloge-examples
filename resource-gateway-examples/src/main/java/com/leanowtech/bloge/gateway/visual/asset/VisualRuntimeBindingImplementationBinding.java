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
 * @param state ready-to-bind, requires-review, bound, unbound, superseded, or failed
 * @param level UI/control-plane severity
 * @param operatorRef operator being implemented
 * @param operatorFingerprint fingerprint of the submitted operator contract
 * @param sourceHandoffBundleFingerprint source handoff bundle fingerprint
 * @param sourceRequirementKeys requirement keys covered by the proposal
 * @param operatorContract submitted handoff operator contract snapshot
 * @param implementation submitted implementation metadata
 * @param validation validation snapshot used to accept the proposal
 * @param supersedesBindingId previous binding replaced by this binding
 * @param supersededByBindingId replacement binding that superseded this binding
 * @param lifecycleEvents auditable lifecycle transition events
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
        String supersedesBindingId,
        String supersededByBindingId,
        List<LifecycleEvent> lifecycleEvents,
        Instant createdAt,
        Instant updatedAt
) {
    public static final String SCHEMA_VERSION = "bloge.visualRuntimeBindingImplementationBindingRecord.v1";
    public static final String STATE_READY_TO_BIND = "ready-to-bind";
    public static final String STATE_REQUIRES_REVIEW = "requires-review";
    public static final String STATE_BOUND = "bound";
    public static final String STATE_UNBOUND = "unbound";
    public static final String STATE_SUPERSEDED = "superseded";
    public static final String STATE_FAILED = "failed";

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
        supersedesBindingId = supersedesBindingId == null ? "" : supersedesBindingId.trim();
        supersededByBindingId = supersededByBindingId == null ? "" : supersededByBindingId.trim();
        lifecycleEvents = lifecycleEvents == null ? List.of() : List.copyOf(lifecycleEvents);
        createdAt = createdAt == null ? Instant.EPOCH : createdAt;
        updatedAt = updatedAt == null ? createdAt : updatedAt;
    }

    /**
     * Audit event for proposal lifecycle transitions.
     *
     * @param eventType submitted, bound, unbound, superseded, failed, or restored
     * @param fromState previous lifecycle state
     * @param toState next lifecycle state
     * @param actor principal or team that approved the transition
     * @param changeSource client/source system that performed the transition
     * @param reason required human-readable reason
     * @param summary optional short summary
     * @param relatedBindingId replacement or previous binding id when applicable
     * @param occurredAt transition timestamp
     */
    public record LifecycleEvent(
            String eventType,
            String fromState,
            String toState,
            String actor,
            String changeSource,
            String reason,
            String summary,
            String relatedBindingId,
            Instant occurredAt
    ) {
        public LifecycleEvent {
            eventType = eventType == null ? "" : eventType.trim().toLowerCase(Locale.ROOT);
            fromState = fromState == null ? "" : fromState.trim().toLowerCase(Locale.ROOT);
            toState = toState == null ? "" : toState.trim().toLowerCase(Locale.ROOT);
            actor = actor == null ? "" : actor.trim();
            changeSource = changeSource == null ? "" : changeSource.trim();
            reason = reason == null ? "" : reason.trim();
            summary = summary == null ? "" : summary.trim();
            relatedBindingId = relatedBindingId == null ? "" : relatedBindingId.trim();
            occurredAt = occurredAt == null ? Instant.now() : occurredAt;
        }
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
                "",
                "",
                List.of(),
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
                supersedesBindingId,
                supersededByBindingId,
                lifecycleEvents,
                created,
                updated
        );
    }

    /**
     * Applies one auditable lifecycle transition.
     *
     * @param nextState next state
     * @param nextLevel next UI/control-plane level
     * @param supersedes replacement lineage when this binding supersedes another
     * @param supersededBy replacement lineage when this binding is superseded
     * @param event audit event
     * @param updated timestamp
     * @return updated binding record
     */
    public VisualRuntimeBindingImplementationBinding withLifecycleTransition(String nextState,
                                                                             String nextLevel,
                                                                             String supersedes,
                                                                             String supersededBy,
                                                                             LifecycleEvent event,
                                                                             Instant updated) {
        List<LifecycleEvent> events;
        if (event == null) {
            events = lifecycleEvents;
        } else {
            events = new java.util.ArrayList<>(lifecycleEvents);
            events.add(event);
        }
        return new VisualRuntimeBindingImplementationBinding(
                schemaVersion,
                bindingId,
                revision + 1,
                nextState,
                nextLevel,
                operatorRef,
                operatorFingerprint,
                sourceHandoffBundleFingerprint,
                sourceRequirementKeys,
                operatorContract,
                implementation,
                validation,
                supersedes == null ? supersedesBindingId : supersedes,
                supersededBy == null ? supersededByBindingId : supersededBy,
                events,
                createdAt,
                updated
        );
    }

    public boolean readyToBind() {
        return STATE_READY_TO_BIND.equals(state);
    }

    public boolean requiresReview() {
        return STATE_REQUIRES_REVIEW.equals(state);
    }

    public boolean bound() {
        return STATE_BOUND.equals(state);
    }

    public boolean unbound() {
        return STATE_UNBOUND.equals(state);
    }

    public boolean superseded() {
        return STATE_SUPERSEDED.equals(state);
    }

    public boolean failed() {
        return STATE_FAILED.equals(state);
    }
}
