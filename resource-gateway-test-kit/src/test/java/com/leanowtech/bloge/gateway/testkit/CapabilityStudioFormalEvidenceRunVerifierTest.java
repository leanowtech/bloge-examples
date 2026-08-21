package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CapabilityStudioFormalEvidenceRunVerifierTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void honestNotRunAndBlockedNeedNoEvidenceAndRemainIncomplete() throws Exception {
        var fixture = CapabilityStudioFormalEvidenceRunTestFixtures.empty(temporaryDirectory);
        ((ObjectNode) fixture.manifestNode().withArray("obligations").get(0))
                .put("status", "BLOCKED");
        fixture.manifestNode().put("blocked", 1).put("notRun", 13);
        fixture.write();

        var result = CapabilityStudioFormalEvidenceRunVerifier.verify(
                fixture.manifest(), fixture.root());
        assertThat(result.verificationLevel()).isEqualTo("INCOMPLETE");
        assertThat(result.passed()).isZero();
        assertThat(result.blocked()).isOne();
        assertThat(result.evidenceCount()).isZero();
    }

    @Test
    void publicProjectionRejectsReplayCountLevelContradictions() {
        assertThatThrownBy(() -> new CapabilityStudioFormalEvidenceRunVerifier.Verification(
                "INCOMPLETE", 1, 0, 0, 0, 14, 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CapabilityStudioFormalEvidenceRunVerifier.Verification(
                "STRUCTURE_VERIFIED", 0, 0, 0, 0, 14, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void replaysARealStageAcceptanceResultVerifier() throws Exception {
        var fixture = CapabilityStudioFormalEvidenceRunTestFixtures.stageResult(temporaryDirectory);

        var result = CapabilityStudioFormalEvidenceRunVerifier.verify(
                fixture.manifest(), fixture.root());
        assertThat(result.verificationLevel()).isEqualTo("STRUCTURE_VERIFIED");
        assertThat(result.typedReplayCount()).isOne();
        assertThat(result.passed()).isZero();
    }

    @Test
    void replaysARealFormalInputTreeSnapshotVerifier() throws Exception {
        var fixture = CapabilityStudioFormalEvidenceRunTestFixtures.formalInputTree(
                temporaryDirectory);
        var result = CapabilityStudioFormalEvidenceRunVerifier.verify(
                fixture.manifest(), fixture.root());
        assertThat(result.verificationLevel()).isEqualTo("STRUCTURE_VERIFIED");
        assertThat(result.typedReplayCount()).isOne();
    }

    @Test
    void replaysARealDurableEvidenceWrapperVerifier() throws Exception {
        var fixture = CapabilityStudioFormalEvidenceRunTestFixtures.durableWrapper(
                temporaryDirectory);

        var result = CapabilityStudioFormalEvidenceRunVerifier.verify(
                fixture.manifest(), fixture.root());
        assertThat(result.verificationLevel()).isEqualTo("STRUCTURE_VERIFIED");
        assertThat(result.typedReplayCount()).isOne();
    }

    @Test
    void replaysAllThreeTypedAdaptersFromOneExactEvidenceRoot() throws Exception {
        var fixture = CapabilityStudioFormalEvidenceRunTestFixtures.allAdapters(
                temporaryDirectory);

        assertThat(fixture.manifestNode().withArray("typedEvidenceReplays")
                .findValuesAsText("id"))
                .containsExactly("durable-replay", "stage-replay", "tree-replay");
        assertThat(fixture.manifestNode().withArray("typedEvidenceReplays")
                .findValuesAsText("kind"))
                .containsExactly("EXECUTION_LEASE_DURABLE_WRAPPER_V1",
                        "STAGE_ACCEPTANCE_RESULT_V2", "FORMAL_INPUT_TREE_V1");

        var result = CapabilityStudioFormalEvidenceRunVerifier.verify(
                fixture.manifest(), fixture.root());
        assertThat(result.verificationLevel()).isEqualTo("STRUCTURE_VERIFIED");
        assertThat(result.typedReplayCount()).isEqualTo(3);
        assertThat(result.passed()).isZero();
        assertThat(result.failed()).isZero();
        assertThat(result.blocked()).isZero();
        assertThat(result.notRun()).isEqualTo(14);
    }

    @Test
    void rejectsPassPlaceholderWrongTupleRevisionAndReplayTamper() throws Exception {
        var pass = CapabilityStudioFormalEvidenceRunTestFixtures.empty(temporaryDirectory);
        ((ObjectNode) pass.manifestNode().withArray("obligations").get(0)).put("status", "PASS");
        pass.write();
        assertInvalid(pass);

        var wrongKind = CapabilityStudioFormalEvidenceRunTestFixtures.stageResult(temporaryDirectory);
        ObjectNode replay = (ObjectNode) wrongKind.manifestNode().withArray(
                "typedEvidenceReplays").get(0);
        replay.put("kind", "FORMAL_INPUT_TREE_V1");
        wrongKind.write();
        assertInvalid(wrongKind);

        var wrongRevision = CapabilityStudioFormalEvidenceRunTestFixtures.stageResult(temporaryDirectory);
        ((ObjectNode) wrongRevision.manifestNode().withArray("typedEvidenceReplays").get(0))
                .put("verifierRevision", 1);
        wrongRevision.write();
        assertInvalid(wrongRevision);

        var tampered = CapabilityStudioFormalEvidenceRunTestFixtures.stageResult(temporaryDirectory);
        ((ObjectNode) tampered.manifestNode().withArray("typedEvidenceReplays").get(0))
                .with("inputs").put("verificationInstant", "2025-12-31T23:59:59Z");
        tampered.write();
        assertInvalid(tampered);
    }

    @Test
    void finalClosureUnavailabilityCannotEraseEarlierAdapterFacts() throws Exception {
        var fixture = CapabilityStudioFormalEvidenceRunTestFixtures.stageResult(temporaryDirectory);
        ((ObjectNode) fixture.manifestNode().withArray("typedEvidenceReplays").get(0))
                .with("inputs").put("verificationInstant", "2025-12-31T23:59:59Z");
        fixture.write();

        var run = CapabilityStudioFormalEvidenceRunVerifier.verifyDetailed(
                fixture.manifest(), fixture.root(),
                new CapabilityStudioFormalEvidenceRunVerifier.VerificationObserver() {
                    @Override
                    public void beforeFinalManifestRead(Path manifest) {
                        throw new IllegalStateException("simulated filesystem unavailability");
                    }
                });

        assertThat(run.decision().terminal())
                .isEqualTo(CapabilityStudioCandidateReplayDeriver.Terminal.UNAVAILABLE);
        assertThat(run.decision().invalidReplayCount()).isOne();
        assertThat(run.decision().unavailableReplayCount()).isZero();
        assertThat(run.decision().closureOutcome())
                .isEqualTo(CapabilityStudioCandidateReplayDeriver.ClosureOutcome.UNAVAILABLE);
    }

    @Test
    void rejectsManifestReplacementDeletionAndIdentityDrift() throws Exception {
        var replaced = CapabilityStudioFormalEvidenceRunTestFixtures.stageResult(temporaryDirectory);
        assertThatThrownBy(() -> CapabilityStudioFormalEvidenceRunVerifier.verify(
                replaced.manifest(), replaced.root(), new CapabilityStudioFormalEvidenceRunVerifier.VerificationObserver() {
                    @Override
                    public void afterInitialManifestRead(Path manifest) {
                        try {
                            Files.move(manifest, manifest.resolveSibling("replacement.json"));
                            Files.copy(manifest.resolveSibling("replacement.json"), manifest);
                        } catch (IOException failure) {
                            throw new IllegalStateException(failure);
                        }
                    }
                })).isInstanceOf(CapabilityStudioFormalEvidenceRunVerifier.VerificationException.class);

        var deleted = CapabilityStudioFormalEvidenceRunTestFixtures.stageResult(temporaryDirectory);
        assertThatThrownBy(() -> CapabilityStudioFormalEvidenceRunVerifier.verify(
                deleted.manifest(), deleted.root(), new CapabilityStudioFormalEvidenceRunVerifier.VerificationObserver() {
                    @Override
                    public void afterInitialManifestRead(Path manifest) {
                        try {
                            Files.delete(manifest);
                        } catch (IOException failure) {
                            throw new IllegalStateException(failure);
                        }
                    }
                })).isInstanceOf(CapabilityStudioFormalEvidenceRunVerifier.VerificationException.class);

        var drifted = CapabilityStudioFormalEvidenceRunTestFixtures.stageResult(temporaryDirectory);
        assertThatThrownBy(() -> CapabilityStudioFormalEvidenceRunVerifier.verify(
                drifted.manifest(), drifted.root(), new CapabilityStudioFormalEvidenceRunVerifier.VerificationObserver() {
                    @Override
                    public void afterInitialManifestRead(Path manifest) {
                        try {
                            Files.setPosixFilePermissions(manifest,
                                    PosixFilePermissions.fromString("r--------"));
                        } catch (IOException failure) {
                            throw new IllegalStateException(failure);
                        }
                    }
                })).isInstanceOf(CapabilityStudioFormalEvidenceRunVerifier.VerificationException.class);

        var finalReplacement = CapabilityStudioFormalEvidenceRunTestFixtures.stageResult(
                temporaryDirectory);
        assertThatThrownBy(() -> CapabilityStudioFormalEvidenceRunVerifier.verify(
                finalReplacement.manifest(), finalReplacement.root(),
                new CapabilityStudioFormalEvidenceRunVerifier.VerificationObserver() {
                    @Override
                    public void beforeFinalManifestRead(Path manifest) {
                        try {
                            Path replacement = manifest.resolveSibling("final-replacement.json");
                            Files.move(manifest, replacement);
                            Files.copy(replacement, manifest);
                        } catch (IOException failure) {
                            throw new IllegalStateException(failure);
                        }
                    }
                })).isInstanceOf(CapabilityStudioFormalEvidenceRunVerifier.VerificationException.class);
    }

    @Test
    void rejectsSymlinkAncestorForManifestAndBundleRoot() throws Exception {
        var fixture = CapabilityStudioFormalEvidenceRunTestFixtures.stageResult(temporaryDirectory);
        Path alias = temporaryDirectory.toRealPath().resolve("ancestor-alias");
        Files.createSymbolicLink(alias, fixture.manifest().getParent());

        assertInvalid(alias.resolve("manifest.json"), fixture.root());
        assertInvalid(fixture.manifest(), alias.resolve("bundle"));
    }

    @Test
    void rejectsSubjectDirectorySymlinkSwapBeforeTypedReplay() throws Exception {
        var fixture = CapabilityStudioFormalEvidenceRunTestFixtures.formalInputTree(
                temporaryDirectory);
        AtomicBoolean swapped = new AtomicBoolean();

        assertUnavailable(() -> CapabilityStudioFormalEvidenceRunVerifier.verify(
                fixture.manifest(), fixture.root(),
                new CapabilityStudioFormalEvidenceRunVerifier.VerificationObserver() {
                    @Override
                    public void beforeTypedReplay(String replayId) {
                        Path subject = fixture.root().resolve("tree-subject");
                        Path moved = fixture.root().resolve("tree-subject-moved");
                        try {
                            Files.move(subject, moved);
                            Files.createSymbolicLink(subject, moved);
                            swapped.set(true);
                        } catch (IOException failure) {
                            throw new IllegalStateException(failure);
                        }
                    }
                }));
        assertThat(swapped).isTrue();
    }

    @Test
    void distinguishesInventoryIdentityDriftFromDigestTamper() throws Exception {
        var unreadable = CapabilityStudioFormalEvidenceRunTestFixtures.stageResult(
                temporaryDirectory);
        assertUnavailable(() -> CapabilityStudioFormalEvidenceRunVerifier.verify(
                unreadable.manifest(), unreadable.root(),
                new CapabilityStudioFormalEvidenceRunVerifier.VerificationObserver() {
                    @Override
                    public void beforeInventoryRead(String relativePath, Path file) {
                        try {
                            Files.setPosixFilePermissions(file,
                                    PosixFilePermissions.fromString("---------"));
                        } catch (IOException failure) {
                            throw new IllegalStateException(failure);
                        }
                    }
                }));

        var digestTamper = CapabilityStudioFormalEvidenceRunTestFixtures.stageResult(
                temporaryDirectory);
        ((ObjectNode) digestTamper.manifestNode().withArray("evidenceInventory").get(0))
                .put("rawFingerprint", CapabilityStudioFormalEvidenceRunTestFixtures.fp('9'));
        digestTamper.write();
        assertInvalid(digestTamper);
    }

    @Test
    void rejectsInsecurePermissionsAndLinkedManifest() throws Exception {
        var worldReadable = CapabilityStudioFormalEvidenceRunTestFixtures.stageResult(
                temporaryDirectory);
        Files.setPosixFilePermissions(worldReadable.root().resolve("stage-result.json"),
                PosixFilePermissions.fromString("rw-r--r--"));
        assertInvalid(worldReadable);

        var worldAccessibleDirectory = CapabilityStudioFormalEvidenceRunTestFixtures.stageResult(
                temporaryDirectory);
        Files.setPosixFilePermissions(worldAccessibleDirectory.root(),
                PosixFilePermissions.fromString("rwx---r-x"));
        assertInvalid(worldAccessibleDirectory);

        var worldReadableManifest = CapabilityStudioFormalEvidenceRunTestFixtures.stageResult(
                temporaryDirectory);
        Files.setPosixFilePermissions(worldReadableManifest.manifest(),
                PosixFilePermissions.fromString("rw-r--r--"));
        assertInvalid(worldReadableManifest);

        var linkedManifest = CapabilityStudioFormalEvidenceRunTestFixtures.stageResult(
                temporaryDirectory);
        Files.createLink(linkedManifest.manifest().resolveSibling("manifest-link.json"),
                linkedManifest.manifest());
        assertInvalid(linkedManifest);
    }

    @Test
    void rejectsUnknownSymlinkHardlinkAndInventoryOrderDrift() throws Exception {
        var unknown = CapabilityStudioFormalEvidenceRunTestFixtures.stageResult(temporaryDirectory);
        Files.writeString(unknown.root().resolve("unknown.txt"), "unknown");
        assertInvalid(unknown);

        var symlink = CapabilityStudioFormalEvidenceRunTestFixtures.stageResult(temporaryDirectory);
        Files.createSymbolicLink(symlink.root().resolve("unknown-link"),
                symlink.root().resolve("stage-result.json"));
        assertInvalid(symlink);

        var hardlink = CapabilityStudioFormalEvidenceRunTestFixtures.stageResult(temporaryDirectory);
        Files.createLink(hardlink.root().resolve("hardlink.json"),
                hardlink.root().resolve("stage-result.json"));
        hardlink.addAllInventory();
        hardlink.write();
        assertInvalid(hardlink);

        var externalHardlink = CapabilityStudioFormalEvidenceRunTestFixtures.stageResult(
                temporaryDirectory);
        Files.createLink(externalHardlink.manifest().resolveSibling("external-hardlink.json"),
                externalHardlink.root().resolve("stage-result.json"));
        assertInvalid(externalHardlink);

        var durableScope = CapabilityStudioFormalEvidenceRunTestFixtures.durableWrapper(
                temporaryDirectory);
        Files.createLink(durableScope.root().resolve("outside-hardlink.json"),
                durableScope.root().resolve("durable/execution-lease-transcript-v1.json"));
        durableScope.addAllInventory();
        durableScope.write();
        assertInvalid(durableScope);

        var arbitraryDurableLink = CapabilityStudioFormalEvidenceRunTestFixtures.durableWrapper(
                temporaryDirectory);
        Files.createLink(arbitraryDurableLink.root().resolve("durable/arbitrary-hardlink.json"),
                arbitraryDurableLink.root().resolve(
                        "durable/execution-lease-transcript-v1.json"));
        arbitraryDurableLink.addAllInventory();
        arbitraryDurableLink.write();
        assertInvalid(arbitraryDurableLink);

        var order = CapabilityStudioFormalEvidenceRunTestFixtures.stageResult(temporaryDirectory);
        Files.writeString(order.root().resolve("extra.json"), "{}");
        order.addAllInventory();
        order.write();
        var inventory = order.manifestNode().withArray("evidenceInventory");
        var first = inventory.remove(0);
        inventory.add(first);
        order.write();
        assertInvalid(order);

        var duplicateInventory = CapabilityStudioFormalEvidenceRunTestFixtures.stageResult(
                temporaryDirectory);
        duplicateInventory.manifestNode().withArray("evidenceInventory")
                .add(duplicateInventory.manifestNode().withArray("evidenceInventory").get(0).deepCopy());
        duplicateInventory.write();
        assertInvalid(duplicateInventory);

        var duplicateReplay = CapabilityStudioFormalEvidenceRunTestFixtures.stageResult(
                temporaryDirectory);
        duplicateReplay.manifestNode().withArray("typedEvidenceReplays")
                .add(duplicateReplay.manifestNode().withArray("typedEvidenceReplays").get(0).deepCopy());
        duplicateReplay.write();
        assertInvalid(duplicateReplay);
    }

    @Test
    void rejectsDuplicateAndTrailingWireAndAcceptsPackagedSchema() throws Exception {
        var fixture = CapabilityStudioFormalEvidenceRunTestFixtures.empty(temporaryDirectory);
        fixture.write();
        byte[] wire = Files.readAllBytes(fixture.manifest());
        Files.write(fixture.manifest(), Arrays.copyOf(wire, wire.length + 1));
        assertInvalid(fixture);

        var duplicate = CapabilityStudioFormalEvidenceRunTestFixtures.empty(temporaryDirectory);
        duplicate.write();
        String duplicateWire = Files.readString(duplicate.manifest()).replaceFirst(
                "\\\"contractId\\\":", "\\\"contractId\\\":\\\"RG-CS-FELT-v1\\\",\\\"contractId\\\":");
        Files.writeString(duplicate.manifest(), duplicateWire);
        assertInvalid(duplicate);

        assertThat(CapabilityStudioSchemaSupport.validate(
                fixture.manifestNode(), CapabilityStudioSchemaSupport.FORMAL_EVIDENCE_RUN_MANIFEST_RESOURCE))
                .isEmpty();

        var compileFirst = CapabilityStudioFormalEvidenceRunTestFixtures.empty(
                temporaryDirectory);
        compileFirst.write();
        Files.delete(compileFirst.root());
        Files.writeString(compileFirst.manifest(), "{}");
        assertInvalid(compileFirst.manifest(), compileFirst.root());
    }

    private static void assertInvalid(
            CapabilityStudioFormalEvidenceRunTestFixtures.Fixture fixture) {
        assertInvalid(fixture.manifest(), fixture.root());
    }

    private static void assertInvalid(Path manifest, Path bundleRoot) {
        assertInvalid(() -> CapabilityStudioFormalEvidenceRunVerifier.verify(
                manifest, bundleRoot));
    }

    private static void assertInvalid(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable operation) {
        assertThatThrownBy(operation)
                .isInstanceOf(CapabilityStudioFormalEvidenceRunVerifier.VerificationException.class)
                .hasMessage("formal evidence run verification failed")
                .satisfies(error -> assertThat(((CapabilityStudioFormalEvidenceRunVerifier.VerificationException)
                        error).failureKind()).isEqualTo(
                        CapabilityStudioFormalEvidenceRunVerifier.FailureKind.INVALID));
    }

    private static void assertUnavailable(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable operation) {
        assertThatThrownBy(operation)
                .isInstanceOf(CapabilityStudioFormalEvidenceRunVerifier.VerificationException.class)
                .hasMessage("formal evidence run verification failed")
                .satisfies(error -> assertThat(((CapabilityStudioFormalEvidenceRunVerifier
                        .VerificationException) error).failureKind()).isEqualTo(
                        CapabilityStudioFormalEvidenceRunVerifier.FailureKind.UNAVAILABLE));
    }
}
