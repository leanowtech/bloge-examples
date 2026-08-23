package com.leanowtech.bloge.gateway.testkit;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Independent Java re-execution of the frozen Gate A real-material attack pack. */
class CapabilityStudioGateAMaterialAttackReferenceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void rejectsDuplicateMembersInCallerPinnedAdmissionTrustPin() throws IOException {
        Path pin = temporaryDirectory.resolve("duplicate-admission-trust-pin.json");
        Files.writeString(pin, """
                {"admissionContext":{"admissionVerificationTime":"2026-08-21T09:32:00Z",
                "admissionVerificationTime":"2026-08-21T09:32:01Z"}}
                """);

        assertThatThrownBy(() ->
                CapabilityStudioGateAMaterialAttackReference.readAdmissionVerificationTimeForTesting(pin))
                .isInstanceOf(IOException.class);
    }

    @Test
    void rejectsSymlinkForCallerPinnedAdmissionTrustPin() throws IOException {
        Path target = temporaryDirectory.resolve("admission-trust-pin.json");
        Files.writeString(target, "{\"admissionContext\":{\"admissionVerificationTime\":\"2026-08-21T09:32:00Z\"}}");
        Path link = temporaryDirectory.resolve("caller-pinned-admission-trust-pin.json");
        Files.createSymbolicLink(link, target.getFileName());

        assertThatThrownBy(() ->
                CapabilityStudioGateAMaterialAttackReference.readAdmissionVerificationTimeForTesting(link))
                .isInstanceOf(IOException.class);
    }

    @Test
    void replaysAllFrozenGuardsAndReviewerSupplementalAttacksFromMaterial() throws Exception {
        var results = CapabilityStudioGateAMaterialAttackReference.verifyFrozenMaterialAttacks();
        var primary = CapabilityStudioGateAMaterialAttackReference.guardCatalogOrder();

        assertThat(results).hasSize(43);
        assertThat(results.subList(0, primary.size()))
                .extracting(CapabilityStudioGateAMaterialAttackReference.Observed::guardId)
                .containsExactlyElementsOf(primary);
        assertThat(results).allMatch(result -> !result.status().equals("PASS"));
    }
}
