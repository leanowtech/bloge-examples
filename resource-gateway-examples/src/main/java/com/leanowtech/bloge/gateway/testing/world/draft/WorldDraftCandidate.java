package com.leanowtech.bloge.gateway.testing.world.draft;

import java.util.regex.Pattern;

/** Immutable payload-free candidate projection retained by the CAS repository. */
public record WorldDraftCandidate(
        String candidateId,
        long revision,
        WorldDraftState state,
        String tenantId,
        WorldDraftSourceRef source,
        String sourceMetadataFingerprint,
        String schemaFingerprint,
        String redactionPolicyFingerprint,
        String requestFingerprint,
        String responseFingerprint,
        WorldDraftRedactedPayloadRef redactedPayloadRef,
        String redactionReportFingerprint,
        WorldDraftRedactionReport redactionReport,
        String approvalFingerprint,
        String materializationFingerprint
) {
    private static final Pattern FP = Pattern.compile("sha256:[a-f0-9]{64}");

    public WorldDraftCandidate {
        candidateId = text(candidateId, 255);
        tenantId = text(tenantId, 255);
        if (revision < 1 || state == null || source == null
                || (revision == 1 && state != WorldDraftState.CAPTURED)
                || (state == WorldDraftState.CAPTURED && revision != 1)
                || !valid(sourceMetadataFingerprint) || !valid(schemaFingerprint)
                || !valid(redactionPolicyFingerprint) || !valid(requestFingerprint)
                || !valid(responseFingerprint)
                || redactedPayloadRef != null && (!tenantId.equals(redactedPayloadRef.tenantId())
                || !candidateId.equals(redactedPayloadRef.candidateId())
                || redactedPayloadRef.artifactRevision() > revision)
                || !optional(redactionReportFingerprint)
                || redactionReport == null || !optional(approvalFingerprint)
                || !optional(materializationFingerprint)
                || requiresApproval(state) && !valid(approvalFingerprint)
                || requiresMaterialization(state) && !valid(materializationFingerprint)) {
            throw invalid();
        }
    }

    WorldDraftCandidate next(WorldDraftState next, String approval, String materialization,
                             WorldDraftRedactedPayloadRef redactedPayload,
                             String reportFingerprint, WorldDraftRedactionReport report) {
        if (!state.mayAdvanceTo(next)) throw transition();
        if (state != WorldDraftState.CAPTURED && redactedPayloadRef == null) throw transition();
        if (redactedPayloadRef != null && !redactedPayloadRef.equals(redactedPayload)) throw transition();
        return new WorldDraftCandidate(candidateId, revision + 1, next, tenantId, source,
                sourceMetadataFingerprint, schemaFingerprint, redactionPolicyFingerprint,
                requestFingerprint, responseFingerprint, redactedPayload, reportFingerprint,
                report, approval, materialization);
    }

    String effectiveRequestFingerprint() {
        return redactedPayloadRef == null ? requestFingerprint : redactedPayloadRef.requestFingerprint();
    }

    String effectiveResponseFingerprint() {
        return redactedPayloadRef == null ? responseFingerprint : redactedPayloadRef.responseFingerprint();
    }

    String redactedRequestFingerprint() {
        return redactedPayloadRef == null ? "" : redactedPayloadRef.requestFingerprint();
    }

    String redactedResponseFingerprint() {
        return redactedPayloadRef == null ? "" : redactedPayloadRef.responseFingerprint();
    }

    String redactedPayloadFingerprint() {
        return redactedPayloadRef == null ? "" : redactedPayloadRef.pairFingerprint();
    }

    private static String text(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max
                || value.chars().anyMatch(Character::isISOControl)) throw invalid();
        return value.trim();
    }

    private static boolean valid(String value) { return value != null && FP.matcher(value).matches(); }
    private static boolean optional(String value) { return value == null || value.isBlank() || valid(value); }
    private static boolean requiresApproval(WorldDraftState state) {
        return state == WorldDraftState.APPROVED || state == WorldDraftState.MATERIALIZED_DRAFT
                || state == WorldDraftState.PUBLISHED;
    }
    private static boolean requiresMaterialization(WorldDraftState state) {
        return state == WorldDraftState.MATERIALIZED_DRAFT || state == WorldDraftState.PUBLISHED;
    }
    private static WorldDraftCandidateException invalid() {
        return new WorldDraftCandidateException(WorldDraftCandidateException.Code.INVALID_INPUT);
    }
    private static WorldDraftCandidateException transition() {
        return new WorldDraftCandidateException(WorldDraftCandidateException.Code.STATE_TRANSITION_INVALID);
    }
}
