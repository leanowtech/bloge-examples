package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

final class ScenarioRehearsalBatchEvidenceTestFixtures {
    static final Instant CREATED =
            Instant.parse("2026-07-24T08:00:00Z");
    static final Instant COMPLETED = CREATED.plusSeconds(5);
    static final CapabilitySnapshot.Scope SCOPE =
            new CapabilitySnapshot.Scope(
                    "tenant-a",
                    "org-a",
                    "support",
                    "test",
                    "sg");

    private ScenarioRehearsalBatchEvidenceTestFixtures() {
    }

    static Material material(ObjectMapper mapper) {
        MirrorArtifactRef plan = new MirrorArtifactRef(
                "COMPILED_REHEARSAL_PLAN",
                "refund-plan",
                3,
                fingerprint('a'));
        ScenarioRehearsalBatchRequest request =
                new ScenarioRehearsalBatchRequest(
                        "",
                        "refund-regression",
                        List.of(
                                new ScenarioRehearsalBatchRequest.Entry(
                                        "refund-happy-path",
                                        plan)));
        String childRequestId =
                request.requestId() + ":plan:000";
        String childRunId =
                ScenarioRehearsalRunIdentity.derive(
                        mapper, SCOPE, childRequestId);
        String jobId =
                ScenarioRehearsalBatchIdentity.derive(
                        mapper, SCOPE, request.requestId());
        ScenarioRehearsalBatchManifest manifest =
                ScenarioRehearsalBatchManifestIntegrity.seal(
                        mapper,
                        new ScenarioRehearsalBatchManifest(
                                "",
                                jobId,
                                "",
                                SCOPE,
                                request.requestId(),
                                List.of(
                                        new ScenarioRehearsalBatchManifest
                                                .Entry(
                                                0,
                                                "refund-happy-path",
                                                plan,
                                                childRequestId,
                                                childRunId,
                                                2,
                                                Duration.ofSeconds(20))),
                                2));
        ScenarioRehearsalBatchItemPage.Item item =
                new ScenarioRehearsalBatchItemPage.Item(
                        0,
                        plan,
                        childRequestId,
                        ScenarioRehearsalBatchItemPage.Status.PASSED,
                        1,
                        childRunId,
                        fingerprint('e'),
                        fingerprint('d'),
                        "",
                        CREATED.plusSeconds(1),
                        COMPLETED);
        ScenarioRehearsalBatchJob job =
                ScenarioRehearsalBatchIntegrity.seal(
                        mapper,
                        new ScenarioRehearsalBatchJob(
                                "",
                                jobId,
                                request.requestId(),
                                ProtocolFingerprint.ofBounded(
                                        mapper,
                                        request,
                                        2 * 1024 * 1024),
                                manifest.manifestFingerprint(),
                                SCOPE,
                                ScenarioRehearsalBatchJob.Status.SUCCEEDED,
                                ScenarioRehearsalBatchPolicy.FailureMode
                                        .COLLECT_ALL,
                                ScenarioRehearsalBatchPolicy.Priority.NORMAL,
                                3,
                                new ScenarioRehearsalBatchJob.Summary(
                                        1, 1, 1, 0, 0, 0),
                                CREATED.plusSeconds(30),
                                "",
                                "",
                                "",
                                CREATED,
                                COMPLETED,
                                COMPLETED,
                                ""));
        return new Material(
                request,
                manifest,
                job,
                List.of(item));
    }

    static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }

    record Material(
            ScenarioRehearsalBatchRequest request,
            ScenarioRehearsalBatchManifest manifest,
            ScenarioRehearsalBatchJob job,
            List<ScenarioRehearsalBatchItemPage.Item> items) {
    }
}
