package com.leanowtech.bloge.gateway.testkit.mounted;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.AdmissionLifecycleMaterial;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.ExecutionLeaseCommitStatus;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.ExecutionLeaseRequest;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.RevocationAuthoritySnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MountedCapabilityStudioRevocationHeadCliTest {
    private static final Instant NOW = Instant.parse("2026-08-20T00:00:00Z");

    @TempDir
    Path temporaryDirectory;

    @Test
    void updatesOnceAndReturnsDeterministicAlreadyCurrentReceipt() throws Exception {
        Fixture fixture = fixture("cli-success");
        RevocationAuthoritySnapshot secondRevision = revision(2);
        Path input = writeInput(fixture, secondRevision, fixture.predecessor, "head.json");
        Map<String, String> environment = pins(fixture.descriptor, input);

        RunResult first = run(fixture.root, input, environment);
        AdmissionLifecycleMaterial lifecycle = new AdmissionLifecycleMaterial(
                fingerprint('1'), "bundle:cli", 1, "ACTIVE", null, secondRevision);
        var store = FilesystemDeploymentAdmissionAuthority.prepareStore(
                fixture.root, fingerprint('e'), secondRevision);
        var authority = new FilesystemDeploymentAdmissionAuthority(
                store, lifecycle, fingerprint('2'), fingerprint('3'), fingerprint('4'),
                fingerprint('5'), "lease:cli", Clock.fixed(NOW, ZoneOffset.UTC),
                FilesystemDeploymentAdmissionAuthority.MAX_LEASES);
        var committed = authority.commit(new ExecutionLeaseRequest(
                "result:cli", 1, fingerprint('6'), fingerprint('7'),
                "contract:cli", "1", "lease:cli", fingerprint('5'),
                fingerprint('2'), fingerprint('3'), lifecycle, fingerprint('4'), NOW));
        RunResult retry = run(fixture.root, input, environment);
        String newHead = MountedProviderTestFixtures.JSON.readTree(input.toFile())
                .path("headFingerprint").textValue();

        assertThat(first.exit).isZero();
        assertThat(first.output.lines()).hasSize(1);
        assertThat(first.output).startsWith("status=UPDATED ")
                .contains("storeDescriptorFingerprint=" + fixture.descriptor)
                .contains("previousHeadFingerprint=" + fixture.predecessor)
                .contains(" revision=2 ")
                .doesNotContain(fixture.root.toString(), input.toString(), "registry:test");
        assertThat(receipt(first.output)).isEqualTo(sha256((
                "resource-gateway.capability-studio.revocation-head-update-receipt.v1\n"
                + fixture.descriptor + "\n" + fixture.predecessor + "\n"
                + newHead + "\n2").getBytes(StandardCharsets.UTF_8)));
        assertThat(committed.status()).isEqualTo(ExecutionLeaseCommitStatus.COMMITTED);
        assertThat(retry.exit).isZero();
        assertThat(retry.output.lines()).hasSize(1);
        assertThat(retry.output).startsWith("status=ALREADY_CURRENT ");
        assertThat(receipt(retry.output)).isEqualTo(receipt(first.output));
    }

    @Test
    void classifiesDurableStoreAndClockOutagesAsBlocked() throws Exception {
        Fixture missingRoot = fixture("cli-missing-root");
        Path missingRootInput = writeInput(missingRoot, revision(2),
                missingRoot.predecessor, "missing-root.json");
        Files.move(missingRoot.root, missingRoot.root.resolveSibling("cli-missing-root-gone"));
        assertFailure(run(missingRoot.root, missingRootInput,
                pins(missingRoot.descriptor, missingRootInput)), 2, "status=BLOCKED");

        Fixture missingState = fixture("cli-missing-state");
        Path missingStateInput = writeInput(missingState, revision(2),
                missingState.predecessor, "missing-state.json");
        Files.delete(missingState.root.resolve(
                FilesystemDeploymentAdmissionAuthority.STATE_FILE));
        assertFailure(run(missingState.root, missingStateInput,
                pins(missingState.descriptor, missingStateInput)), 2, "status=BLOCKED");

        Fixture corruptState = fixture("cli-corrupt-state");
        Path corruptStateInput = writeInput(corruptState, revision(2),
                corruptState.predecessor, "corrupt-state.json");
        writePrivate(corruptState.root.resolve(
                FilesystemDeploymentAdmissionAuthority.STATE_FILE), "{}".getBytes(
                StandardCharsets.UTF_8));
        assertFailure(run(corruptState.root, corruptStateInput,
                pins(corruptState.descriptor, corruptStateInput)), 2, "status=BLOCKED");

        Fixture locked = fixture("cli-locked");
        Path lockedInput = writeInput(locked, revision(2),
                locked.predecessor, "locked.json");
        try (FileChannel channel = FileChannel.open(locked.root.resolve(
                FilesystemDeploymentAdmissionAuthority.LOCK_FILE),
                StandardOpenOption.WRITE); var ignored = channel.lock()) {
            assertFailure(run(locked.root, lockedInput,
                    pins(locked.descriptor, lockedInput)), 2, "status=BLOCKED");
        }

        Fixture clock = fixture("cli-clock");
        Path clockInput = writeInput(clock, revision(2),
                clock.predecessor, "clock.json");
        assertFailure(run(clock.root, clockInput, pins(clock.descriptor, clockInput),
                new FailingClock()), 2, "status=BLOCKED");
    }

    @Test
    void outputFailureCannotClaimSuccessOrFailureTranscriptEmission() throws Exception {
        Fixture fixture = fixture("cli-output");
        Path input = writeInput(fixture, revision(2), fixture.predecessor, "output.json");
        PrintStream broken = new PrintStream(new OutputStream() {
            @Override
            public void write(int value) throws IOException {
                throw new IOException("OUTPUT_PAYLOAD");
            }
        }, true, StandardCharsets.UTF_8);

        int successExit = MountedCapabilityStudioRevocationHeadCli.run(new String[]{
                "--state-root", fixture.root.toString(),
                "--head-input", input.toString()
        }, pins(fixture.descriptor, input), broken, Clock.fixed(NOW, ZoneOffset.UTC));
        int failureExit = MountedCapabilityStudioRevocationHeadCli.run(
                new String[0], Map.of(), broken, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(successExit).isEqualTo(2);
        assertThat(failureExit).isEqualTo(2);
        assertThat(broken.checkError()).isTrue();
    }

    @Test
    void rejectsPinsMalformedDocumentsStaleUpdatesAndUnsafeInputWithoutLeakage()
            throws Exception {
        Fixture fixture = fixture("cli-rejections");
        Path valid = writeInput(fixture, revision(2), fixture.predecessor, "valid.json");

        assertFailure(run(fixture.root, valid, Map.of(
                MountedCapabilityStudioRevocationHeadCli.STORE_DESCRIPTOR_PIN_ENV,
                fingerprint('9'),
                MountedCapabilityStudioRevocationHeadCli.HEAD_INPUT_SHA256_ENV,
                sha256(Files.readAllBytes(valid)))), 2, "status=INVALID");
        assertFailure(run(fixture.root, valid, Map.of(
                MountedCapabilityStudioRevocationHeadCli.STORE_DESCRIPTOR_PIN_ENV,
                fixture.descriptor,
                MountedCapabilityStudioRevocationHeadCli.HEAD_INPUT_SHA256_ENV,
                fingerprint('9'))), 2, "status=INVALID");

        String validText = Files.readString(valid);
        Path duplicate = privateFile("duplicate.json", validText.replaceFirst(
                "\\{", "{\"revision\":2,"));
        assertFailure(run(fixture.root, duplicate, pins(fixture.descriptor, duplicate)),
                2, "status=INVALID");
        Path unknown = privateFile("unknown.json", validText.replaceFirst(
                "\\{", "{\"unexpected\":true,"));
        assertFailure(run(fixture.root, unknown, pins(fixture.descriptor, unknown)),
                2, "status=INVALID");

        Path stale = writeInput(fixture, revision(2), fingerprint('8'), "stale.json");
        assertFailure(run(fixture.root, stale, pins(fixture.descriptor, stale)),
                3, "status=REJECTED");
        Path rollback = writeInput(fixture, revision(1), fixture.predecessor,
                "rollback.json");
        assertFailure(run(fixture.root, rollback, pins(fixture.descriptor, rollback)),
                3, "status=REJECTED");
        Path expired = writeInput(fixture, new RevocationAuthoritySnapshot(
                "registry:test", 2, fingerprint('7'), NOW.minusSeconds(120),
                NOW.minusSeconds(1)), fixture.predecessor, "expired.json");
        assertFailure(run(fixture.root, expired, pins(fixture.descriptor, expired)),
                3, "status=REJECTED");

        Path link = temporaryDirectory.resolve("input-link.json").toAbsolutePath();
        Files.createSymbolicLink(link, valid);
        assertFailure(run(fixture.root, link, pins(fixture.descriptor, valid)),
                2, "status=INVALID");
        Path hardLink = temporaryDirectory.resolve("input-hard-link.json").toAbsolutePath();
        try {
            Files.createLink(hardLink, valid);
            assertFailure(run(fixture.root, hardLink, pins(fixture.descriptor, valid)),
                    2, "status=INVALID");
        } catch (UnsupportedOperationException unsupported) {
            assertThat(unsupported).isNotNull();
        }
    }

    @Test
    void canonicalMessageHasGoldenVectorAndSchemaIsPackaged() throws Exception {
        RevocationAuthoritySnapshot material = new RevocationAuthoritySnapshot(
                "registry:test", 2, fingerprint('b'), NOW, NOW.plusSeconds(3600));
        String canonical = FilesystemDeploymentAdmissionAuthority.RevocationHeadUpdate
                .canonicalMessage(fingerprint('a'), material, fingerprint('c'), null);

        assertThat(canonical).isEqualTo("{\"messageVersion\":\"resource-gateway."
                + "capability-studio.revocation-head-update.v1\","
                + "\"storeDescriptorFingerprint\":\"" + fingerprint('a') + "\","
                + "\"registryRef\":\"registry:test\",\"revision\":2,"
                + "\"snapshotFingerprint\":\"" + fingerprint('b') + "\","
                + "\"observedAt\":\"2026-08-20T00:00:00Z\","
                + "\"expiresAt\":\"2026-08-20T01:00:00Z\","
                + "\"predecessorHeadFingerprint\":\"" + fingerprint('c') + "\","
                + "\"headFingerprint\":null}");
        assertThat(FilesystemDeploymentAdmissionAuthority.RevocationHeadUpdate
                .fingerprint(fingerprint('a'), material, fingerprint('c')))
                .isEqualTo("sha256:f525b612e44c0fdbed2ccefb2719cbbd"
                        + "a6985c51d0c7d5503f948a99f49e485c");
        try (var schema = getClass().getClassLoader().getResourceAsStream(
                "schemas/capability-studio-revocation-head-update-v1.schema.json")) {
            assertThat(schema).isNotNull();
            var parsed = MountedProviderTestFixtures.JSON.readTree(schema);
            assertThat(parsed.path("additionalProperties").booleanValue()).isFalse();
            assertThat(parsed.path("required")).hasSize(9);
        }
    }

    @Test
    void exactCrashWindowsRepairOnceAndOlderHeadRollbackIsUnavailable()
            throws Exception {
        Path root = stateRoot("update-crashes");
        RevocationAuthoritySnapshot initial = revision(1);
        var durability = new UpdateFaultDurability();
        var store = FilesystemDeploymentAdmissionAuthority.prepareStore(
                root, fingerprint('e'), initial, durability);
        byte[] initialHead = Files.readAllBytes(root.resolve(
                FilesystemDeploymentAdmissionAuthority.REVOCATION_HEAD_FILE));

        var second = update(store, initialHead, revision(2));
        durability.failHeadForce = true;
        assertThat(store.advanceRevocationHead(second, NOW).status())
                .isEqualTo(FilesystemDeploymentAdmissionAuthority
                        .RevocationHeadUpdateStatus.UNAVAILABLE);
        assertThat(store.advanceRevocationHead(second, NOW).status())
                .isEqualTo(FilesystemDeploymentAdmissionAuthority
                        .RevocationHeadUpdateStatus.ALREADY_CURRENT);
        byte[] secondHead = Files.readAllBytes(root.resolve(
                FilesystemDeploymentAdmissionAuthority.REVOCATION_HEAD_FILE));

        var third = update(store, secondHead, revision(3));
        durability.failCheckpointForce = true;
        assertThat(store.advanceRevocationHead(third, NOW).status())
                .isEqualTo(FilesystemDeploymentAdmissionAuthority
                        .RevocationHeadUpdateStatus.UNAVAILABLE);
        assertThat(store.advanceRevocationHead(third, NOW).status())
                .isEqualTo(FilesystemDeploymentAdmissionAuthority
                        .RevocationHeadUpdateStatus.ALREADY_CURRENT);

        writePrivate(root.resolve(
                FilesystemDeploymentAdmissionAuthority.REVOCATION_HEAD_FILE), secondHead);
        assertThat(store.advanceRevocationHead(third, NOW).status())
                .isEqualTo(FilesystemDeploymentAdmissionAuthority
                        .RevocationHeadUpdateStatus.UNAVAILABLE);
    }

    private Fixture fixture(String name) throws Exception {
        Path root = stateRoot(name);
        var store = FilesystemDeploymentAdmissionAuthority.prepareStore(
                root, fingerprint('e'), revision(1));
        String predecessor = MountedProviderTestFixtures.JSON.readTree(root.resolve(
                FilesystemDeploymentAdmissionAuthority.REVOCATION_HEAD_FILE).toFile())
                .path("headFingerprint").textValue();
        return new Fixture(root, store.descriptorFingerprint(), predecessor);
    }

    private Path writeInput(Fixture fixture, RevocationAuthoritySnapshot material,
            String predecessor, String name) throws Exception {
        String headFingerprint = FilesystemDeploymentAdmissionAuthority.RevocationHeadUpdate
                .fingerprint(fixture.descriptor, material, predecessor);
        ObjectNode node = MountedProviderTestFixtures.JSON.createObjectNode();
        node.put("messageVersion",
                FilesystemDeploymentAdmissionAuthority.REVOCATION_HEAD_UPDATE_VERSION);
        node.put("storeDescriptorFingerprint", fixture.descriptor);
        node.put("registryRef", material.registryRef());
        node.put("revision", material.revision());
        node.put("snapshotFingerprint", material.snapshotFingerprint());
        node.put("observedAt", material.observedAt().toString());
        node.put("expiresAt", material.expiresAt().toString());
        node.put("predecessorHeadFingerprint", predecessor);
        node.put("headFingerprint", headFingerprint);
        return privateFile(name, MountedProviderTestFixtures.JSON.writeValueAsString(node));
    }

    private FilesystemDeploymentAdmissionAuthority.RevocationHeadUpdate update(
            FilesystemDeploymentAdmissionAuthority.PreparedStore store,
            byte[] currentHead,
            RevocationAuthoritySnapshot material) throws Exception {
        String predecessor = MountedProviderTestFixtures.JSON.readTree(currentHead)
                .path("headFingerprint").textValue();
        String fingerprint = FilesystemDeploymentAdmissionAuthority.RevocationHeadUpdate
                .fingerprint(store.descriptorFingerprint(), material, predecessor);
        return new FilesystemDeploymentAdmissionAuthority.RevocationHeadUpdate(
                store.descriptorFingerprint(), material, predecessor, fingerprint);
    }

    private RunResult run(Path root, Path input, Map<String, String> environment) {
        return run(root, input, environment, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private RunResult run(Path root, Path input, Map<String, String> environment,
            Clock clock) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int exit = MountedCapabilityStudioRevocationHeadCli.run(new String[]{
                "--state-root", root.toString(), "--head-input", input.toString()
        }, environment, new PrintStream(bytes, true, StandardCharsets.UTF_8),
                clock);
        return new RunResult(exit, bytes.toString(StandardCharsets.UTF_8).strip());
    }

    private void assertFailure(RunResult result, int exit, String status) {
        assertThat(result.exit).isEqualTo(exit);
        assertThat(result.output).startsWith(status).contains("reason=")
                .doesNotContain(temporaryDirectory.toString(), "registry:test",
                        "NLINK_PAYLOAD", "CHECKPOINT_FORCE_PAYLOAD");
    }

    private Map<String, String> pins(String descriptor, Path input) throws Exception {
        return Map.of(
                MountedCapabilityStudioRevocationHeadCli.STORE_DESCRIPTOR_PIN_ENV, descriptor,
                MountedCapabilityStudioRevocationHeadCli.HEAD_INPUT_SHA256_ENV,
                sha256(Files.readAllBytes(input)));
    }

    private Path privateFile(String name, String content) throws Exception {
        Path path = temporaryDirectory.resolve(name).toAbsolutePath();
        writePrivate(path, content.getBytes(StandardCharsets.UTF_8));
        return path;
    }

    private Path stateRoot(String name) throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve(name)).toAbsolutePath();
        if (Files.getFileStore(root).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(root, EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE));
        }
        return root;
    }

    private static void writePrivate(Path path, byte[] bytes) throws Exception {
        Files.write(path, bytes);
        if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(path, EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        }
    }

    private static RevocationAuthoritySnapshot revision(long revision) {
        return new RevocationAuthoritySnapshot("registry:test", revision,
                fingerprint((char) ('4' + revision)), NOW.minusSeconds(120 - revision),
                NOW.plusSeconds(600));
    }

    private static String receipt(String output) {
        return output.substring(output.indexOf("updateReceiptFingerprint=")
                + "updateReceiptFingerprint=".length());
    }

    private static String sha256(byte[] bytes) throws Exception {
        return "sha256:" + java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static String fingerprint(char seed) {
        return "sha256:" + String.valueOf(seed).repeat(64);
    }

    private record Fixture(Path root, String descriptor, String predecessor) { }

    private record RunResult(int exit, String output) { }

    private static final class FailingClock extends Clock {
        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            throw new IllegalStateException("CLOCK_PAYLOAD");
        }
    }

    private static final class UpdateFaultDurability
            implements FilesystemDeploymentAdmissionAuthority.Durability {
        private boolean failHeadForce;
        private boolean failCheckpointForce;

        @Override
        public void atomicReplace(Path source, Path target) throws java.io.IOException {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        }

        @Override
        public void forceDirectory(Path directory, Path installedEntry)
                throws java.io.IOException {
            String name = installedEntry.getFileName().toString();
            if (failHeadForce && name.equals(
                    FilesystemDeploymentAdmissionAuthority.REVOCATION_HEAD_FILE)) {
                failHeadForce = false;
                throw new java.io.IOException("HEAD_FORCE_PAYLOAD");
            }
            if (failCheckpointForce && name.equals(
                    FilesystemDeploymentAdmissionAuthority.CHECKPOINT_FILE)) {
                failCheckpointForce = false;
                throw new java.io.IOException("CHECKPOINT_FORCE_PAYLOAD");
            }
            force(directory);
        }

        @Override
        public void forceReadBarrier(Path directory, Path checkpoint)
                throws java.io.IOException {
            force(directory);
        }

        private static void force(Path directory) throws java.io.IOException {
            try (FileChannel channel = FileChannel.open(
                    directory, StandardOpenOption.READ)) {
                channel.force(true);
            }
        }
    }
}
