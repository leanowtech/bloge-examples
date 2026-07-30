package com.leanowtech.bloge.gateway.visual.authoring.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * Deterministic, payload-free projection of one multi-sample inference request.
 */
public record SampleInferenceResult(
        String schemaVersion,
        String draftId,
        long authoringRevision,
        SampleInferenceRequest.Target target,
        String evidenceFingerprint,
        String inferencerVersion,
        String redactionProfileVersion,
        int sampleCount,
        JsonNode candidate,
        List<FieldObservation> observations,
        List<InferenceConfirmation> confirmationRequests,
        List<AuthoringDiagnostic> diagnostics,
        boolean payloadPersisted
) {
    public static final String SCHEMA_VERSION = "bloge.visualSampleInferenceResult.v1";

    public SampleInferenceResult {
        schemaVersion = normalized(schemaVersion, SCHEMA_VERSION);
        draftId = normalized(draftId, "");
        authoringRevision = Math.max(0, authoringRevision);
        evidenceFingerprint = normalized(evidenceFingerprint, "");
        inferencerVersion = normalized(inferencerVersion, "");
        redactionProfileVersion = normalized(redactionProfileVersion, "");
        sampleCount = Math.max(0, sampleCount);
        observations = observations == null ? List.of() : List.copyOf(observations);
        confirmationRequests = confirmationRequests == null
                ? List.of() : List.copyOf(confirmationRequests);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        payloadPersisted = false;
    }

    /**
     * Explainable field statistics. No observed payload value is retained here.
     */
    public record FieldObservation(
            String factId,
            String authoringPath,
            String sourceLevel,
            String suggestedType,
            int sampleCount,
            int presenceCount,
            int nullCount,
            int distinctCount,
            boolean sensitive,
            boolean requiredCandidate,
            boolean nullableCandidate,
            String formatCandidate,
            List<String> enumCandidates,
            List<String> conflictTypes,
            List<String> widenReasons
    ) {
        public FieldObservation {
            factId = normalized(factId, "");
            authoringPath = normalized(authoringPath, "/");
            sourceLevel = normalized(sourceLevel, "OBSERVED");
            suggestedType = normalized(suggestedType, "unknown");
            sampleCount = Math.max(0, sampleCount);
            presenceCount = Math.max(0, presenceCount);
            nullCount = Math.max(0, nullCount);
            distinctCount = Math.max(0, distinctCount);
            formatCandidate = normalized(formatCandidate, "");
            enumCandidates = enumCandidates == null ? List.of() : List.copyOf(enumCandidates);
            conflictTypes = conflictTypes == null ? List.of() : List.copyOf(conflictTypes);
            widenReasons = widenReasons == null ? List.of() : List.copyOf(widenReasons);
        }
    }

    /**
     * Explicit promotion decision required before an observed fact becomes declared source.
     */
    public record InferenceConfirmation(
            String confirmationId,
            String factId,
            String code,
            String authoringPath,
            String question,
            String recommendedValue,
            List<String> allowedValues,
            boolean blocking
    ) {
        public InferenceConfirmation {
            confirmationId = normalized(confirmationId, "");
            factId = normalized(factId, "");
            code = normalized(code, "");
            authoringPath = normalized(authoringPath, "/");
            question = normalized(question, "");
            recommendedValue = normalized(recommendedValue, "");
            allowedValues = allowedValues == null ? List.of() : List.copyOf(allowedValues);
        }
    }

    private static String normalized(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
