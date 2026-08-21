package com.leanowtech.bloge.gateway.testkit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.io.IOException;
import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CapabilityStudioTypedEvidenceReplayRegistryTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void preservesStageVerifierInfrastructureUnavailability() {
        var unavailable = new CapabilityStudioStageAcceptanceResultV2Verifier.VerificationResult(
                CapabilityStudioStageAcceptanceResultV2Verifier.FailureKind.SCHEMA,
                Set.of(),
                "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_SCHEMA_UNAVAILABLE");
        var invalid = new CapabilityStudioStageAcceptanceResultV2Verifier.VerificationResult(
                CapabilityStudioStageAcceptanceResultV2Verifier.FailureKind.SCHEMA,
                Set.of(),
                "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_SCHEMA_INVALID");
        var verified = new CapabilityStudioStageAcceptanceResultV2Verifier.VerificationResult(
                CapabilityStudioStageAcceptanceResultV2Verifier.FailureKind.NONE,
                Set.of("schema"), null);

        assertThat(CapabilityStudioTypedEvidenceReplayRegistry.stageReplayStatus(unavailable))
                .isEqualTo(CapabilityStudioTypedEvidenceReplayRegistry.ReplayStatus.UNAVAILABLE);
        assertThat(CapabilityStudioTypedEvidenceReplayRegistry.stageReplayStatus(invalid))
                .isEqualTo(CapabilityStudioTypedEvidenceReplayRegistry.ReplayStatus.INVALID);
        assertThat(CapabilityStudioTypedEvidenceReplayRegistry.stageReplayStatus(verified))
                .isEqualTo(CapabilityStudioTypedEvidenceReplayRegistry.ReplayStatus.VERIFIED);
        assertThat(CapabilityStudioTypedEvidenceReplayRegistry.stageReplayStatus(null))
                .isEqualTo(CapabilityStudioTypedEvidenceReplayRegistry.ReplayStatus.UNAVAILABLE);
    }

    @Test
    void mapsMissingFormalTreeAndDurableWrapperToUnavailable() {
        var formalRequest = new CapabilityStudioTypedEvidenceReplayRegistry.ReplayRequest(
                "formal-missing",
                CapabilityStudioTypedEvidenceReplayRegistry.Slot.FORMAL_INPUT_TREE,
                "formal/missing",
                new CapabilityStudioTypedEvidenceReplayRegistry.FormalInputTreeInputs(
                        CapabilityStudioFormalInputTreeSnapshotter.TreeKind.AUTHORITY_BUNDLE,
                        fp('a'), fp('b'), fp('c'), fp('d')));
        var formalSubject = new CapabilityStudioTypedEvidenceReplayRegistry.Subject(
                temporaryDirectory.resolve("formal-missing").toAbsolutePath().normalize(),
                "formal/missing", false, 0, null);
        var durableRequest = new CapabilityStudioTypedEvidenceReplayRegistry.ReplayRequest(
                "durable-missing",
                CapabilityStudioTypedEvidenceReplayRegistry.Slot.DURABLE_EVIDENCE_CLOSURE,
                "durable/missing.json",
                new CapabilityStudioTypedEvidenceReplayRegistry.DurableWrapperInputs(
                        fp('e'), fp('f'), fp('1')));
        var durableSubject = new CapabilityStudioTypedEvidenceReplayRegistry.Subject(
                temporaryDirectory.resolve("durable-missing.json").toAbsolutePath().normalize(),
                "durable/missing.json", true, 1, fp('2'));

        assertThat(CapabilityStudioTypedEvidenceReplayRegistry
                .replay(formalRequest, formalSubject).status())
                .isEqualTo(CapabilityStudioTypedEvidenceReplayRegistry.ReplayStatus.UNAVAILABLE);
        assertThat(CapabilityStudioTypedEvidenceReplayRegistry
                .replay(durableRequest, durableSubject).status())
                .isEqualTo(CapabilityStudioTypedEvidenceReplayRegistry.ReplayStatus.UNAVAILABLE);
    }

    @Test
    void eachRealAdapterReportsUnavailableWhenAdmittedSubjectDisappears() throws Exception {
        Path formalPath = Files.createDirectory(temporaryDirectory.resolve("formal-race"));
        byte[] fileBytes = new byte[]{'x'};
        Path durablePath = Files.write(temporaryDirectory.resolve("durable-race.json"), fileBytes);
        Path stagePath = Files.write(temporaryDirectory.resolve("stage-race.json"), fileBytes);

        var formal = new CapabilityStudioTypedEvidenceReplayRegistry.ReplayRequest(
                "formal-race", CapabilityStudioTypedEvidenceReplayRegistry.Slot.FORMAL_INPUT_TREE,
                "formal/race", new CapabilityStudioTypedEvidenceReplayRegistry.FormalInputTreeInputs(
                CapabilityStudioFormalInputTreeSnapshotter.TreeKind.AUTHORITY_BUNDLE,
                fp('a'), fp('b'), fp('c'), fp('d')));
        var durable = new CapabilityStudioTypedEvidenceReplayRegistry.ReplayRequest(
                "durable-race",
                CapabilityStudioTypedEvidenceReplayRegistry.Slot.DURABLE_EVIDENCE_CLOSURE,
                "durable/race.json",
                new CapabilityStudioTypedEvidenceReplayRegistry.DurableWrapperInputs(
                        fp('e'), fp('f'), fp('1')));
        var stage = new CapabilityStudioTypedEvidenceReplayRegistry.ReplayRequest(
                "stage-race",
                CapabilityStudioTypedEvidenceReplayRegistry.Slot.STAGE_ACCEPTANCE_RESULT,
                "stage/race.json",
                new CapabilityStudioTypedEvidenceReplayRegistry.StageResultInputs(
                        Instant.parse("2026-01-01T00:00:00Z")));

        assertUnavailableAfterAdmission(formal,
                new CapabilityStudioTypedEvidenceReplayRegistry.Subject(
                        formalPath, "formal/race", false, 0, null));
        assertUnavailableAfterAdmission(durable,
                new CapabilityStudioTypedEvidenceReplayRegistry.Subject(
                        durablePath, "durable/race.json", true, fileBytes.length,
                        CapabilityStudioFormalEvidenceRunManifest.sha256(fileBytes)));
        assertUnavailableAfterAdmission(stage,
                new CapabilityStudioTypedEvidenceReplayRegistry.Subject(
                        stagePath, "stage/race.json", true, fileBytes.length,
                        CapabilityStudioFormalEvidenceRunManifest.sha256(fileBytes)));
    }

    private static void assertUnavailableAfterAdmission(
            CapabilityStudioTypedEvidenceReplayRegistry.ReplayRequest request,
            CapabilityStudioTypedEvidenceReplayRegistry.Subject subject) {
        var observation = CapabilityStudioTypedEvidenceReplayRegistry.replay(
                request, subject, (slot, path) -> {
                    try {
                        Files.delete(path);
                    } catch (IOException failure) {
                        throw new IllegalStateException(failure);
                    }
                });
        assertThat(observation.status())
                .isEqualTo(CapabilityStudioTypedEvidenceReplayRegistry.ReplayStatus.UNAVAILABLE);
        assertThat(Files.notExists(subject.path())).isTrue();
    }

    private static String fp(char seed) {
        return "sha256:" + String.valueOf(seed).repeat(64);
    }
}
