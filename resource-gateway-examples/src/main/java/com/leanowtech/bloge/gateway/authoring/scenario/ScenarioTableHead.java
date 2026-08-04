package com.leanowtech.bloge.gateway.authoring.scenario;

/** Payload-free repository head used to authorize and fence bounded Matrix reads. */
public record ScenarioTableHead(
        String scenarioDraftSetId,
        long revision,
        String draftFingerprint,
        String classification,
        int caseCount
) {
    /** Rejects an incomplete or impossible table-index head. */
    public ScenarioTableHead {
        scenarioDraftSetId = normalized(scenarioDraftSetId);
        draftFingerprint = normalized(draftFingerprint);
        classification = normalized(classification);
        if (scenarioDraftSetId.isBlank() || revision < 1 || draftFingerprint.isBlank()
                || classification.isBlank() || caseCount < 0) {
            throw new IllegalArgumentException("Scenario Matrix head requires exact payload-free coordinates");
        }
    }

    /** Builds a head from a verified canonical stored asset. */
    public static ScenarioTableHead from(StoredScenarioDraftSet stored) {
        return new ScenarioTableHead(
                stored.scenarioDraftSetId(), stored.revision(), stored.fingerprint(),
                stored.draftSet().metadata().classification(),
                stored.draftSet().scenarios().size());
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
