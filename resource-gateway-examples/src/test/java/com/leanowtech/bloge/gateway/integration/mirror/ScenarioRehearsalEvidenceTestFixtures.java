package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;

final class ScenarioRehearsalEvidenceTestFixtures {
    static final Instant STARTED =
            Instant.parse("2026-07-24T08:00:00Z");
    static final Instant COMPLETED = STARTED.plusSeconds(1);
    static final String REQUEST_ID = "rehearsal-request-1";

    private ScenarioRehearsalEvidenceTestFixtures() {
    }

    static ScenarioRehearsalResult result(
            ObjectMapper mapper,
            CapabilitySnapshot.Scope scope,
            char planFingerprint) {
        ScenarioCaseRehearsalResult oneCase =
                ScenarioRehearsalResultIntegrity.sealCase(
                        mapper,
                        new ScenarioCaseRehearsalResult(
                                "", "", 0,
                                ref(
                                        "SCENARIO_CASE",
                                        "support-case",
                                        fingerprint('1')),
                                ScenarioCase.CaseType.NEGATIVE,
                                ref(
                                        "TEST_SUITE",
                                        "support-suite",
                                        fingerprint('2')),
                                "negative-case",
                                ref(
                                        "MIRROR_PLAN",
                                        "support-plan",
                                        fingerprint('3')),
                                ref(
                                        "FIXTURE_BUNDLE",
                                        "support-fixture",
                                        fingerprint('4')),
                                null,
                                REQUEST_ID + ":case:000",
                                ScenarioCaseRehearsalResult.Outcome.FAIL,
                                "",
                                "",
                                null,
                                null,
                                List.of(),
                                "RG.MIRROR.REHEARSAL.CASE_REJECTED",
                                STARTED,
                                COMPLETED));
        List<ScenarioCaseRehearsalResult> cases =
                List.of(oneCase);
        return ScenarioRehearsalResultIntegrity.seal(
                mapper,
                new ScenarioRehearsalResult(
                        "", "", REQUEST_ID,
                        ref(
                                "COMPILED_REHEARSAL_PLAN",
                                "support-compiled",
                                fingerprint(planFingerprint)),
                        scope,
                        ref(
                                "CAPABILITY",
                                "support-capability",
                                fingerprint('6')),
                        ScenarioRehearsalResult.deriveOutcome(cases),
                        cases,
                        ScenarioRehearsalResult.Summary.from(cases),
                        STARTED,
                        COMPLETED));
    }

    static MirrorArtifactRef ref(
            String kind, String id, String fingerprint) {
        return new MirrorArtifactRef(kind, id, 1, fingerprint);
    }

    static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
