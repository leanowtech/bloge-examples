package com.leanowtech.bloge.gateway.testkit.mounted;

import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.ExecutionLeaseCommitStatus;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.ExecutionLeaseRequest;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.FormalTargetBoundAuthorityBinding;
import com.leanowtech.bloge.gateway.testkit.mounted.MountedCapabilityStudioStageAcceptanceAuthorityProvider.FormalMaterialDeclaration;
import com.leanowtech.bloge.gateway.testkit.mounted.MountedProviderTestFixtures.Fixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class MountedCapabilityStudioFormalMaterialCliTest {
    @TempDir
    Path temporaryDirectory;

    @AfterEach
    void clearProperties() {
        System.clearProperty(MountedCapabilityStudioStageAcceptanceAuthorityProvider
                .AUTHORITY_BUNDLE_ROOT_PROPERTY);
        System.clearProperty(MountedCapabilityStudioStageAcceptanceAuthorityProvider
                .TARGET_ADMISSION_BUNDLE_ROOT_PROPERTY);
        System.clearProperty(MountedCapabilityStudioStageAcceptanceAuthorityProvider
                .EXECUTION_LEASE_STATE_ROOT_PROPERTY);
    }

    @Test
    void declaresFixedOrderMaterialWithoutCommittingOrAdvancingTheStore() throws Exception {
        Fixture fixture = MountedProviderTestFixtures.write(temporaryDirectory, "declare");
        configure(fixture);

        RunResult first = run(new String[0], Clock.systemUTC());
        FormalMaterialDeclaration declaration =
                new MountedCapabilityStudioStageAcceptanceAuthorityProvider()
                        .formalMaterialDeclaration();
        byte[] state = Files.readAllBytes(fixture.stateRoot().resolve(
                FilesystemDeploymentAdmissionAuthority.STATE_FILE));
        var stateJson = MountedProviderTestFixtures.JSON.readTree(state);
        RunResult second = run(new String[0], Clock.systemUTC());

        assertThat(first.exitCode()).isZero();
        assertThat(first.output()).isEqualTo(declaredLine(declaration));
        assertThat(first.output().lines()).hasSize(1);
        assertThat(first.output()).doesNotContain("ACCEPTED", "leaseReceiptFingerprint");
        assertThat(stateJson.path("generation").longValue()).isZero();
        assertThat(stateJson.path("leases").size()).isZero();
        assertThat(second).isEqualTo(first);
        assertThat(Files.readAllBytes(fixture.stateRoot().resolve(
                FilesystemDeploymentAdmissionAuthority.STATE_FILE))).containsExactly(state);
    }

    @Test
    void declarationIsStableWhenBothReadOnlyBundlesMove() throws Exception {
        Fixture fixture = MountedProviderTestFixtures.write(temporaryDirectory, "relocation");
        configure(fixture);
        RunResult original = run(new String[0], Clock.systemUTC());

        Path authorityCopy = temporaryDirectory.resolve("relocated-authority").toAbsolutePath();
        Path targetCopy = temporaryDirectory.resolve("relocated-target").toAbsolutePath();
        MountedProviderTestFixtures.copyDirectory(fixture.authorityRoot(), authorityCopy);
        MountedProviderTestFixtures.copyDirectory(fixture.targetRoot(), targetCopy);
        configure(new Fixture(authorityCopy, targetCopy, fixture.stateRoot()));
        RunResult relocated = run(new String[0], Clock.systemUTC());

        assertThat(original.exitCode()).isZero();
        assertThat(relocated).isEqualTo(original);
        assertThat(relocated.output()).doesNotContain(
                fixture.authorityRoot().toString(), fixture.targetRoot().toString(),
                authorityCopy.toString(), targetCopy.toString());
    }

    @Test
    void declarationLeavesACommittedStoreByteForByteUnchanged() throws Exception {
        Fixture fixture = MountedProviderTestFixtures.write(temporaryDirectory, "committed");
        configure(fixture);
        var provider = new MountedCapabilityStudioStageAcceptanceAuthorityProvider();
        var formal = provider.formalTargetBoundAuthorityBinding();
        FormalMaterialDeclaration declaration = provider.formalMaterialDeclaration();
        var deployment = formal.targetAdmissionBinding().deploymentAuthorityBinding();
        var committed = deployment.executionLeaseAuthority().commit(request(
                formal, deployment.trustedClock().verificationTime()));
        assertThat(committed.status()).isEqualTo(ExecutionLeaseCommitStatus.COMMITTED);

        byte[] descriptor = durableBytes(fixture, FilesystemDeploymentAdmissionAuthority.LOCK_FILE);
        byte[] state = durableBytes(fixture, FilesystemDeploymentAdmissionAuthority.STATE_FILE);
        byte[] checkpoint = durableBytes(
                fixture, FilesystemDeploymentAdmissionAuthority.CHECKPOINT_FILE);
        byte[] revocation = durableBytes(
                fixture, FilesystemDeploymentAdmissionAuthority.REVOCATION_HEAD_FILE);
        var stateJson = MountedProviderTestFixtures.JSON.readTree(state);

        RunResult first = run(new String[0], Clock.systemUTC());
        RunResult second = run(new String[0], Clock.systemUTC());

        assertThat(first.output()).isEqualTo(declaredLine(declaration));
        assertThat(second).isEqualTo(first);
        assertThat(stateJson.path("generation").longValue()).isEqualTo(1);
        assertThat(stateJson.path("fencingSequence").longValue()).isEqualTo(1);
        assertThat(stateJson.path("leases").size()).isEqualTo(1);
        assertThat(durableBytes(fixture, FilesystemDeploymentAdmissionAuthority.LOCK_FILE))
                .containsExactly(descriptor);
        assertThat(durableBytes(fixture, FilesystemDeploymentAdmissionAuthority.STATE_FILE))
                .containsExactly(state);
        assertThat(durableBytes(fixture, FilesystemDeploymentAdmissionAuthority.CHECKPOINT_FILE))
                .containsExactly(checkpoint);
        assertThat(durableBytes(
                fixture, FilesystemDeploymentAdmissionAuthority.REVOCATION_HEAD_FILE))
                .containsExactly(revocation);
    }

    @Test
    void declarationRepairsOnlyTheExactOneGenerationCheckpointIntermediate()
            throws Exception {
        Fixture fixture = MountedProviderTestFixtures.write(temporaryDirectory, "repair");
        configure(fixture);
        var provider = new MountedCapabilityStudioStageAcceptanceAuthorityProvider();
        var formal = provider.formalTargetBoundAuthorityBinding();
        FormalMaterialDeclaration declaration = provider.formalMaterialDeclaration();
        byte[] genesisState = durableBytes(
                fixture, FilesystemDeploymentAdmissionAuthority.STATE_FILE);
        byte[] genesisCheckpoint = durableBytes(
                fixture, FilesystemDeploymentAdmissionAuthority.CHECKPOINT_FILE);
        var deployment = formal.targetAdmissionBinding().deploymentAuthorityBinding();
        var committed = deployment.executionLeaseAuthority().commit(request(
                formal, deployment.trustedClock().verificationTime()));
        assertThat(committed.status()).isEqualTo(ExecutionLeaseCommitStatus.COMMITTED);

        byte[] descriptor = durableBytes(fixture, FilesystemDeploymentAdmissionAuthority.LOCK_FILE);
        byte[] committedState = durableBytes(
                fixture, FilesystemDeploymentAdmissionAuthority.STATE_FILE);
        byte[] committedCheckpoint = durableBytes(
                fixture, FilesystemDeploymentAdmissionAuthority.CHECKPOINT_FILE);
        byte[] revocation = durableBytes(
                fixture, FilesystemDeploymentAdmissionAuthority.REVOCATION_HEAD_FILE);
        var genesisStateJson = MountedProviderTestFixtures.JSON.readTree(genesisState);
        var committedStateJson = MountedProviderTestFixtures.JSON.readTree(committedState);
        assertThat(committedStateJson.path("generation").longValue()).isEqualTo(1);
        assertThat(committedStateJson.path("previousStateFingerprint").textValue())
                .isEqualTo(genesisStateJson.path("stateFingerprint").textValue());

        Files.write(fixture.stateRoot().resolve(
                FilesystemDeploymentAdmissionAuthority.CHECKPOINT_FILE), genesisCheckpoint);
        RunResult repaired = run(new String[0], Clock.systemUTC());

        assertThat(repaired.exitCode()).isZero();
        assertThat(repaired.output()).isEqualTo(declaredLine(declaration));
        assertThat(durableBytes(fixture, FilesystemDeploymentAdmissionAuthority.LOCK_FILE))
                .containsExactly(descriptor);
        assertThat(durableBytes(fixture, FilesystemDeploymentAdmissionAuthority.STATE_FILE))
                .containsExactly(committedState);
        assertThat(durableBytes(fixture, FilesystemDeploymentAdmissionAuthority.CHECKPOINT_FILE))
                .containsExactly(committedCheckpoint);
        assertThat(durableBytes(
                fixture, FilesystemDeploymentAdmissionAuthority.REVOCATION_HEAD_FILE))
                .containsExactly(revocation);
    }

    @Test
    void missingPartialInvalidAndUnavailableConfigurationUseClosedRedactedOutput()
            throws Exception {
        RunResult missing = run(new String[0], Clock.systemUTC());

        Fixture fixture = MountedProviderTestFixtures.write(temporaryDirectory, "failures");
        System.setProperty(MountedCapabilityStudioStageAcceptanceAuthorityProvider
                .AUTHORITY_BUNDLE_ROOT_PROPERTY, fixture.authorityRoot().toString());
        RunResult phaseTwoOnly = run(new String[0], Clock.systemUTC());
        System.setProperty(MountedCapabilityStudioStageAcceptanceAuthorityProvider
                .TARGET_ADMISSION_BUNDLE_ROOT_PROPERTY, fixture.targetRoot().toString());
        RunResult partial = run(new String[0], Clock.systemUTC());

        System.setProperty(MountedCapabilityStudioStageAcceptanceAuthorityProvider
                .EXECUTION_LEASE_STATE_ROOT_PROPERTY, fixture.stateRoot().toString());
        Path missingTarget = temporaryDirectory.resolve("MISSING_TARGET_PAYLOAD").toAbsolutePath();
        System.setProperty(MountedCapabilityStudioStageAcceptanceAuthorityProvider
                .TARGET_ADMISSION_BUNDLE_ROOT_PROPERTY, missingTarget.toString());
        RunResult unavailable = run(new String[0], Clock.systemUTC());

        configure(fixture);
        RunResult clockUnavailable = run(new String[0], new FailingClock());
        RunResult extraArgument = run(new String[]{"UPPERCASE_CREDENTIAL_PAYLOAD"},
                Clock.systemUTC());

        configure(fixture);
        Path configuredAuthority = fixture.authorityRoot();
        Files.move(configuredAuthority,
                configuredAuthority.resolveSibling("AUTHORITY_MOUNT_DISAPPEARED"));
        RunResult authorityUnavailable = run(new String[0], Clock.systemUTC());

        assertInvalid(missing);
        assertInvalid(phaseTwoOnly);
        assertInvalid(partial);
        assertBlocked(unavailable);
        assertBlocked(clockUnavailable);
        assertInvalid(extraArgument);
        assertBlocked(authorityUnavailable);
        for (RunResult result : new RunResult[]{missing, phaseTwoOnly, partial, unavailable,
                clockUnavailable, extraArgument, authorityUnavailable}) {
            assertThat(result.output()).doesNotContain(temporaryDirectory.toString(),
                    "MISSING_TARGET_PAYLOAD", "UPPERCASE_CREDENTIAL_PAYLOAD",
                    "AUTHORITY_MOUNT_DISAPPEARED", "ACCEPTED");
            assertThat(result.output().lines()).hasSize(1);
        }
    }

    @Test
    void outputFailureCannotClaimADeclaration() throws Exception {
        configure(MountedProviderTestFixtures.write(temporaryDirectory, "broken-output"));
        PrintStream broken = new PrintStream(new OutputStream() {
            @Override
            public void write(int value) throws IOException {
                throw new IOException("OUTPUT_PAYLOAD");
            }
        }, true, StandardCharsets.UTF_8);

        int exit = MountedCapabilityStudioFormalMaterialCli.run(
                new String[0], broken, Clock.systemUTC());

        assertThat(exit).isEqualTo(2);
        assertThat(broken.checkError()).isTrue();
    }

    private RunResult run(String[] arguments, Clock clock) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int exit = MountedCapabilityStudioFormalMaterialCli.run(arguments,
                new PrintStream(bytes, true, StandardCharsets.UTF_8), clock);
        return new RunResult(exit, bytes.toString(StandardCharsets.UTF_8));
    }

    private void assertInvalid(RunResult result) {
        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.output()).isEqualTo("NOT_DECLARED status=INVALID reasonCode="
                + "RG.CAPABILITY_STUDIO.MOUNTED_FORMAL_MATERIAL_CLI.INVALID\n");
    }

    private void assertBlocked(RunResult result) {
        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.output()).isEqualTo("NOT_DECLARED status=BLOCKED reasonCode="
                + "RG.CAPABILITY_STUDIO.MOUNTED_FORMAL_MATERIAL_CLI.UNAVAILABLE\n");
    }

    private static String declaredLine(FormalMaterialDeclaration declaration) {
        return "DECLARED status=DECLARED authorityMaterialFingerprint="
                + declaration.authorityMaterialFingerprint()
                + " formalOuterFingerprint=" + declaration.formalOuterFingerprint()
                + " targetAdmissionMaterialFingerprint="
                + declaration.targetAdmissionMaterialFingerprint()
                + " deploymentAdmissionAuthorityMaterialFingerprint="
                + declaration.deploymentAdmissionAuthorityMaterialFingerprint()
                + " trustedClockMaterialFingerprint="
                + declaration.trustedClockMaterialFingerprint()
                + " admissionLifecycleAuthorityMaterialFingerprint="
                + declaration.admissionLifecycleAuthorityMaterialFingerprint()
                + " executionLeaseAuthorityMaterialFingerprint="
                + declaration.executionLeaseAuthorityMaterialFingerprint()
                + " storeDescriptorFingerprint=" + declaration.storeDescriptorFingerprint()
                + " reasonCode=RG.CAPABILITY_STUDIO.MOUNTED_FORMAL_MATERIAL_CLI.DECLARED\n";
    }

    private static ExecutionLeaseRequest request(
            FormalTargetBoundAuthorityBinding formal, Instant trustedTime) {
        var admission = formal.targetAdmissionBinding();
        return new ExecutionLeaseRequest("result:mounted-material-cli", 1,
                fingerprint('1'), fingerprint('2'), "contract:mounted-material-cli", "1",
                MountedProviderTestFixtures.LEASE, formal.fingerprint(),
                admission.targetRawFingerprint(), admission.targetCanonicalFingerprint(),
                admission.lifecycleMaterial(),
                admission.deploymentAuthorityBinding().fingerprint(), trustedTime);
    }

    private static byte[] durableBytes(Fixture fixture, String name) throws IOException {
        return Files.readAllBytes(fixture.stateRoot().resolve(name));
    }

    private static String fingerprint(char seed) {
        return "sha256:" + String.valueOf(seed).repeat(64);
    }

    private static void configure(Fixture fixture) {
        System.setProperty(MountedCapabilityStudioStageAcceptanceAuthorityProvider
                .AUTHORITY_BUNDLE_ROOT_PROPERTY, fixture.authorityRoot().toString());
        System.setProperty(MountedCapabilityStudioStageAcceptanceAuthorityProvider
                .TARGET_ADMISSION_BUNDLE_ROOT_PROPERTY, fixture.targetRoot().toString());
        System.setProperty(MountedCapabilityStudioStageAcceptanceAuthorityProvider
                .EXECUTION_LEASE_STATE_ROOT_PROPERTY, fixture.stateRoot().toString());
    }

    private record RunResult(int exitCode, String output) {
    }

    private static final class FailingClock extends Clock {
        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            throw new IllegalStateException("UPPERCASE_CLOCK_PAYLOAD");
        }
    }
}
