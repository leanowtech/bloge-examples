package com.leanowtech.bloge.gateway.visual.authoring.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * Payload-free evidence retained with an authoring draft after explicit acceptance.
 */
public record AuthoringEvidence(
        String evidenceFingerprint,
        String evidenceKind,
        SampleInferenceRequest.Target target,
        String producerVersion,
        String redactionProfileVersion,
        int sampleCount,
        JsonNode candidate,
        JsonNode declaredCandidate,
        String declaredPortName,
        boolean targetRemoved,
        List<SampleInferenceResult.FieldObservation> observations
) {
    public static final String KIND_SAMPLE_INFERENCE = "SAMPLE_INFERENCE";

    public AuthoringEvidence {
        evidenceFingerprint = normalized(evidenceFingerprint);
        evidenceKind = normalized(evidenceKind);
        producerVersion = normalized(producerVersion);
        redactionProfileVersion = normalized(redactionProfileVersion);
        sampleCount = Math.max(0, sampleCount);
        candidate = candidate == null ? null : candidate.deepCopy();
        declaredCandidate = declaredCandidate == null ? null : declaredCandidate.deepCopy();
        declaredPortName = normalized(declaredPortName);
        observations = observations == null ? List.of() : List.copyOf(observations);
    }

    public static AuthoringEvidence fromInference(
            SampleInferenceResult result,
            JsonNode declaredCandidate,
            String declaredPortName,
            boolean targetRemoved) {
        return new AuthoringEvidence(
                result.evidenceFingerprint(),
                KIND_SAMPLE_INFERENCE,
                result.target(),
                result.inferencerVersion(),
                result.redactionProfileVersion(),
                result.sampleCount(),
                result.candidate(),
                declaredCandidate,
                declaredPortName,
                targetRemoved,
                result.observations().stream()
                        .map(AuthoringEvidence::withoutObservedValues)
                        .toList()
        );
    }

    private static SampleInferenceResult.FieldObservation withoutObservedValues(
            SampleInferenceResult.FieldObservation observation) {
        return new SampleInferenceResult.FieldObservation(
                observation.factId(),
                observation.authoringPath(),
                observation.sourceLevel(),
                observation.suggestedType(),
                observation.sampleCount(),
                observation.presenceCount(),
                observation.nullCount(),
                observation.distinctCount(),
                observation.sensitive(),
                observation.requiredCandidate(),
                observation.nullableCandidate(),
                observation.formatCandidate(),
                List.of(),
                observation.conflictTypes(),
                observation.widenReasons()
        );
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
