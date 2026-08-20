package com.leanowtech.bloge.gateway.testkit.mounted;

import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.AdmissionLifecycleRequest;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.DeploymentDecisionStatus;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.DeploymentUnavailableException;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.ExecutionLeaseCommitStatus;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.ExecutionLeaseRequest;
import com.leanowtech.bloge.gateway.testkit.mounted.MountedProviderTestFixtures.Fixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MountedCapabilityStudioStageAcceptanceAuthorityProviderTest {
    private static final String PROVIDER_CLASS =
            "com.leanowtech.bloge.gateway.testkit.mounted."
                    + "MountedCapabilityStudioStageAcceptanceAuthorityProvider";

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
    void missingAndBlankLegacyAuthorityPropertyKeepStablePhaseTwoCode() {
        assertThatThrownBy(MountedCapabilityStudioStageAcceptanceAuthorityProvider::new)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(MountedCapabilityStudioStageAcceptanceAuthorityProvider
                        .AUTHORITY_BUNDLE_ROOT_REQUIRED_CODE)
                .hasMessageNotContaining("/")
                .hasMessageNotContaining("authorityBundleRoot");

        System.setProperty(MountedCapabilityStudioStageAcceptanceAuthorityProvider
                .AUTHORITY_BUNDLE_ROOT_PROPERTY, " \t\n");
        assertThatThrownBy(MountedCapabilityStudioStageAcceptanceAuthorityProvider::new)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(MountedCapabilityStudioStageAcceptanceAuthorityProvider
                        .AUTHORITY_BUNDLE_ROOT_REQUIRED_CODE);
    }

    @Test
    void formalConfigurationIsLazyAndPartialOrMalformedConfigurationIsInvalid()
            throws Exception {
        Fixture fixture = MountedProviderTestFixtures.write(temporaryDirectory, "properties");
        System.setProperty(MountedCapabilityStudioStageAcceptanceAuthorityProvider
                .AUTHORITY_BUNDLE_ROOT_PROPERTY, fixture.authorityRoot().toString());

        var phaseTwoOnly = new MountedCapabilityStudioStageAcceptanceAuthorityProvider();
        assertThat(phaseTwoOnly.authorityBinding()).isNotNull();
        assertThat(phaseTwoOnly.formalTargetBoundAuthorityBinding()).isNull();

        System.setProperty(MountedCapabilityStudioStageAcceptanceAuthorityProvider
                .TARGET_ADMISSION_BUNDLE_ROOT_PROPERTY, fixture.targetRoot().toString());
        var partial = new MountedCapabilityStudioStageAcceptanceAuthorityProvider();
        assertThat(partial.authorityBinding()).isNotNull();
        assertThatThrownBy(partial::formalTargetBoundAuthorityBinding)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(MountedCapabilityStudioStageAcceptanceAuthorityProvider
                        .FORMAL_CONFIGURATION_INCOMPLETE_CODE);

        System.setProperty(MountedCapabilityStudioStageAcceptanceAuthorityProvider
                .EXECUTION_LEASE_STATE_ROOT_PROPERTY, "relative/state");
        var malformedState = new MountedCapabilityStudioStageAcceptanceAuthorityProvider();
        assertThatThrownBy(malformedState::formalTargetBoundAuthorityBinding)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(MountedCapabilityStudioStageAcceptanceAuthorityProvider
                        .EXECUTION_LEASE_STATE_ROOT_INVALID_CODE)
                .hasMessageNotContaining("relative/state");

        System.setProperty(MountedCapabilityStudioStageAcceptanceAuthorityProvider
                .TARGET_ADMISSION_BUNDLE_ROOT_PROPERTY, "relative/target");
        System.setProperty(MountedCapabilityStudioStageAcceptanceAuthorityProvider
                .EXECUTION_LEASE_STATE_ROOT_PROPERTY, fixture.stateRoot().toString());
        var malformedTarget = new MountedCapabilityStudioStageAcceptanceAuthorityProvider();
        assertThatThrownBy(malformedTarget::formalTargetBoundAuthorityBinding)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(MountedCapabilityStudioStageAcceptanceAuthorityProvider
                        .TARGET_ADMISSION_BUNDLE_ROOT_INVALID_CODE);
    }

    @Test
    void malformedBundlesAreInvalidButMissingTargetMountIsDeploymentUnavailable()
            throws Exception {
        Fixture fixture = MountedProviderTestFixtures.write(temporaryDirectory, "classification");
        configure(fixture);
        System.setProperty(MountedCapabilityStudioStageAcceptanceAuthorityProvider
                .AUTHORITY_BUNDLE_ROOT_PROPERTY, "\u0000/authority-bundle");
        assertThatThrownBy(MountedCapabilityStudioStageAcceptanceAuthorityProvider::new)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(MountedCapabilityStudioStageAcceptanceAuthorityProvider
                        .AUTHORITY_BUNDLE_LOAD_FAILED_CODE)
                .hasMessageNotContaining("authority-bundle");

        configure(fixture);
        Files.writeString(fixture.targetRoot().resolve("target.json"), "\n",
                StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
        var malformedTarget = new MountedCapabilityStudioStageAcceptanceAuthorityProvider();
        assertThat(malformedTarget.authorityBinding()).isNotNull();
        assertThatThrownBy(malformedTarget::formalTargetBoundAuthorityBinding)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(MountedCapabilityStudioStageAcceptanceAuthorityProvider
                        .TARGET_ADMISSION_BUNDLE_LOAD_FAILED_CODE)
                .hasMessageNotContaining(fixture.targetRoot().toString());

        Fixture unavailable = MountedProviderTestFixtures.write(
                temporaryDirectory, "unavailable");
        configure(unavailable);
        Path missing = temporaryDirectory.resolve("missing-target").toAbsolutePath();
        System.setProperty(MountedCapabilityStudioStageAcceptanceAuthorityProvider
                .TARGET_ADMISSION_BUNDLE_ROOT_PROPERTY, missing.toString());
        var unavailableTarget = new MountedCapabilityStudioStageAcceptanceAuthorityProvider();
        assertThat(unavailableTarget.authorityBinding()).isNotNull();
        assertThatThrownBy(unavailableTarget::formalTargetBoundAuthorityBinding)
                .isExactlyInstanceOf(DeploymentUnavailableException.class)
                .hasMessage("RG.CAPABILITY_STUDIO.DEPLOYMENT_UNAVAILABLE")
                .hasMessageNotContaining(missing.toString());

        Fixture linked = MountedProviderTestFixtures.write(
                temporaryDirectory, "linked-target");
        configure(linked);
        Path targetLink = temporaryDirectory.resolve("target-root-link").toAbsolutePath();
        Files.createSymbolicLink(targetLink, linked.targetRoot());
        System.setProperty(MountedCapabilityStudioStageAcceptanceAuthorityProvider
                .TARGET_ADMISSION_BUNDLE_ROOT_PROPERTY, targetLink.toString());
        var unsafeTarget = new MountedCapabilityStudioStageAcceptanceAuthorityProvider();
        assertThatThrownBy(unsafeTarget::formalTargetBoundAuthorityBinding)
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage(MountedCapabilityStudioStageAcceptanceAuthorityProvider
                        .TARGET_ADMISSION_BUNDLE_LOAD_FAILED_CODE)
                .hasMessageNotContaining(targetLink.toString());
    }

    @Test
    void exposesLegacyAuthorityBindingAndOnePrecomputedFormalSnapshot() throws Exception {
        Fixture fixture = MountedProviderTestFixtures.write(temporaryDirectory, "formal");
        configure(fixture);

        var provider = new MountedCapabilityStudioStageAcceptanceAuthorityProvider();
        var authority = provider.authorityBinding();
        var formal = provider.formalTargetBoundAuthorityBinding();

        assertThat(authority).isSameAs(provider.authorityBinding());
        assertThat(authority.resolver()).isSameAs(provider.evidenceResolver());
        assertThat(authority.issuerPolicy()).isSameAs(provider.evidenceIssuerPolicy());
        assertThat(authority.ownerAuthority()).isSameAs(provider.ownerAuthority());
        assertThat(authority.fingerprint()).isEqualTo(provider.authorityBindingFingerprint());
        assertThat(formal).isSameAs(provider.formalTargetBoundAuthorityBinding());
        assertThat(formal.authorityBinding()).isSameAs(authority);
        assertThat(formal.targetAdmissionBinding().deploymentAuthorityBinding().fingerprint())
                .matches("sha256:[0-9a-f]{64}");
        var deployment = formal.targetAdmissionBinding().deploymentAuthorityBinding();
        assertThat(deployment.fingerprint()).isEqualTo(
                CapabilityStudioStageAcceptanceAuthorityProvider
                        .DeploymentAdmissionAuthorityBinding.aggregateFingerprint(
                                deployment.trustedClockBinding().fingerprint(),
                                deployment.lifecycleAuthorityBinding().fingerprint(),
                                deployment.executionLeaseAuthorityBinding().fingerprint()));
        assertThat(formal.fingerprint()).matches("sha256:[0-9a-f]{64}");
        assertThat(formal.fingerprint()).isEqualTo(
                CapabilityStudioStageAcceptanceAuthorityProvider.formalAggregateFingerprint(
                        CapabilityStudioStageAcceptanceAuthorityProvider
                                .FormalTargetBoundAuthorityBinding.MESSAGE_VERSION,
                        authority.fingerprint(), deployment.fingerprint(),
                        formal.targetAdmissionBinding()
                                .targetAdmissionMaterialFingerprint(),
                        formal.targetAdmissionBinding().targetRawFingerprint(),
                        formal.targetAdmissionBinding().targetCanonicalFingerprint()));
        assertThat(provider.toString()).isEqualTo(
                "MountedCapabilityStudioStageAcceptanceAuthorityProvider"
                        + "[mounts=REDACTED, material=REDACTED]");

        deleteDirectFiles(fixture.authorityRoot());
        deleteDirectFiles(fixture.targetRoot());
        assertThat(provider.formalTargetBoundAuthorityBinding()).isSameAs(formal);
        assertThat(formal.targetAdmissionBinding().targetBindingBytes()).isNotEmpty();
    }

    @Test
    void legacyAuthorityRootStillUsesAbsoluteNormalizedResolution() throws Exception {
        Fixture fixture = MountedProviderTestFixtures.write(temporaryDirectory, "legacy-relative");
        configure(fixture);
        Path workingDirectory = Path.of("").toAbsolutePath().normalize();
        String relativeAuthorityRoot = workingDirectory
                .relativize(fixture.authorityRoot()).toString();
        System.setProperty(MountedCapabilityStudioStageAcceptanceAuthorityProvider
                .AUTHORITY_BUNDLE_ROOT_PROPERTY, relativeAuthorityRoot);

        var provider = new MountedCapabilityStudioStageAcceptanceAuthorityProvider();

        assertThat(provider.authorityBinding()).isNotNull();
        assertThat(provider.formalTargetBoundAuthorityBinding()).isNotNull();
    }

    @Test
    void formalCallbacksCommitAndRecoverAcrossFreshProviderInstances() throws Exception {
        Fixture fixture = MountedProviderTestFixtures.write(temporaryDirectory, "provider-retry");
        configure(fixture);
        var firstProvider = new MountedCapabilityStudioStageAcceptanceAuthorityProvider();
        var firstFormal = firstProvider.formalTargetBoundAuthorityBinding();
        var admission = firstFormal.targetAdmissionBinding();
        var deployment = admission.deploymentAuthorityBinding();
        var firstTime = deployment.trustedClock().verificationTime();

        var preflight = deployment.lifecycleAuthority().verify(
                new AdmissionLifecycleRequest(admission.lifecycleMaterial(),
                        firstFormal.fingerprint(), admission.targetRawFingerprint(),
                        admission.targetCanonicalFingerprint(), deployment.fingerprint(),
                        firstTime));
        assertThat(preflight.status()).isEqualTo(DeploymentDecisionStatus.VERIFIED);

        ExecutionLeaseRequest firstRequest = request(firstFormal, firstTime, "1");
        var committed = deployment.executionLeaseAuthority().commit(firstRequest);
        assertThat(committed.status()).isEqualTo(ExecutionLeaseCommitStatus.COMMITTED);

        var restartedProvider = new MountedCapabilityStudioStageAcceptanceAuthorityProvider();
        var restartedFormal = restartedProvider.formalTargetBoundAuthorityBinding();
        var restartedDeployment = restartedFormal.targetAdmissionBinding()
                .deploymentAuthorityBinding();
        var recovered = restartedDeployment.executionLeaseAuthority().commit(
                request(restartedFormal, firstTime.plusSeconds(30), "1"));
        var mismatch = restartedDeployment.executionLeaseAuthority().commit(
                request(restartedFormal, firstTime.plusSeconds(31), "2"));

        assertThat(restartedFormal.fingerprint()).isEqualTo(firstFormal.fingerprint());
        assertThat(recovered.status()).isEqualTo(ExecutionLeaseCommitStatus.RECOVERED);
        assertThat(recovered.receipt()).isEqualTo(committed.receipt());
        assertThat(mismatch.status()).isEqualTo(ExecutionLeaseCommitStatus.REJECTED);
        assertThat(mismatch.receipt()).isNull();
    }

    @Test
    void formalCallbacksRejectWrongProviderOuterAndMountedLeaseBeforeCommit()
            throws Exception {
        Fixture fixture = MountedProviderTestFixtures.write(
                temporaryDirectory, "callback-identity");
        configure(fixture);
        var formal = new MountedCapabilityStudioStageAcceptanceAuthorityProvider()
                .formalTargetBoundAuthorityBinding();
        var admission = formal.targetAdmissionBinding();
        var deployment = admission.deploymentAuthorityBinding();
        var time = deployment.trustedClock().verificationTime();

        var wrongLifecycle = deployment.lifecycleAuthority().verify(
                new AdmissionLifecycleRequest(admission.lifecycleMaterial(),
                        fingerprint('9'), admission.targetRawFingerprint(),
                        admission.targetCanonicalFingerprint(), deployment.fingerprint(), time));
        var wrongOuter = deployment.executionLeaseAuthority().commit(request(
                formal, time, "1", fingerprint('9'), MountedProviderTestFixtures.LEASE));
        var wrongLease = deployment.executionLeaseAuthority().commit(request(
                formal, time, "1", formal.fingerprint(), "lease:wrong"));
        var valid = deployment.executionLeaseAuthority().commit(
                request(formal, time, "1"));

        assertThat(wrongLifecycle.status()).isEqualTo(DeploymentDecisionStatus.REJECTED);
        assertThat(wrongOuter.status()).isEqualTo(ExecutionLeaseCommitStatus.REJECTED);
        assertThat(wrongLease.status()).isEqualTo(ExecutionLeaseCommitStatus.REJECTED);
        assertThat(valid.status()).isEqualTo(ExecutionLeaseCommitStatus.COMMITTED);
    }

    @Test
    void formalOuterChangesForEveryMountedRootAndMaterialCoordinate() throws Exception {
        Fixture base = MountedProviderTestFixtures.write(temporaryDirectory, "base");
        configure(base);
        String initial = new MountedCapabilityStudioStageAcceptanceAuthorityProvider()
                .formalTargetBoundAuthorityBinding().fingerprint();
        List<String> fingerprints = new ArrayList<>();
        fingerprints.add(initial);

        Path authorityCopy = temporaryDirectory.resolve("authority-copy").toAbsolutePath();
        MountedProviderTestFixtures.copyDirectory(base.authorityRoot(), authorityCopy);
        configure(new Fixture(authorityCopy, base.targetRoot(), base.stateRoot()));
        fingerprints.add(new MountedCapabilityStudioStageAcceptanceAuthorityProvider()
                .formalTargetBoundAuthorityBinding().fingerprint());

        Path targetCopy = temporaryDirectory.resolve("target-copy").toAbsolutePath();
        MountedProviderTestFixtures.copyDirectory(base.targetRoot(), targetCopy);
        configure(new Fixture(base.authorityRoot(), targetCopy, base.stateRoot()));
        fingerprints.add(new MountedCapabilityStudioStageAcceptanceAuthorityProvider()
                .formalTargetBoundAuthorityBinding().fingerprint());

        Path stateCopy = MountedProviderTestFixtures.privateDirectory(
                temporaryDirectory.resolve("state-copy"));
        configure(new Fixture(base.authorityRoot(), base.targetRoot(), stateCopy));
        fingerprints.add(new MountedCapabilityStudioStageAcceptanceAuthorityProvider()
                .formalTargetBoundAuthorityBinding().fingerprint());

        Fixture replacement = MountedProviderTestFixtures.write(
                temporaryDirectory, "replacement");
        MountedProviderTestFixtures.replaceDirectoryContents(
                base.authorityRoot(), replacement.authorityRoot());
        configure(base);
        fingerprints.add(new MountedCapabilityStudioStageAcceptanceAuthorityProvider()
                .formalTargetBoundAuthorityBinding().fingerprint());

        MountedProviderTestFixtures.replaceDirectoryContents(
                base.targetRoot(), replacement.targetRoot());
        configure(base);
        fingerprints.add(new MountedCapabilityStudioStageAcceptanceAuthorityProvider()
                .formalTargetBoundAuthorityBinding().fingerprint());

        assertThat(Set.copyOf(fingerprints)).hasSize(fingerprints.size());
        assertThat(MountedCapabilityStudioStageAcceptanceAuthorityProvider
                .componentFingerprint("domain:v1", "artifact", "root", "material-a"))
                .isNotEqualTo(MountedCapabilityStudioStageAcceptanceAuthorityProvider
                        .componentFingerprint(
                                "domain:v1", "artifact", "root", "material-b"));
    }

    @Test
    void unsafeStateDescriptorSymlinkIsInvalidWhenFormalSnapshotIsRequested()
            throws Exception {
        Fixture fixture = MountedProviderTestFixtures.write(temporaryDirectory, "lock-link");
        Path outside = temporaryDirectory.resolve("outside-lock");
        Files.writeString(outside, "");
        Files.createSymbolicLink(fixture.stateRoot().resolve(
                FilesystemDeploymentAdmissionAuthority.LOCK_FILE), outside);
        configure(fixture);

        var provider = new MountedCapabilityStudioStageAcceptanceAuthorityProvider();
        assertThat(provider.authorityBinding()).isNotNull();
        assertThatThrownBy(provider::formalTargetBoundAuthorityBinding)
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage(MountedCapabilityStudioStageAcceptanceAuthorityProvider
                        .EXECUTION_LEASE_STATE_ROOT_INVALID_CODE)
                .hasMessageNotContaining(outside.toString());
    }

    @Test
    void causeChainedDeploymentUnavailableMarkerIsPreservedWithoutLeakingWrapperText() {
        DeploymentUnavailableException marker = new DeploymentUnavailableException();
        IllegalStateException wrapped = new IllegalStateException(
                "UPPERCASE_CREDENTIAL_PAYLOAD", marker);

        assertThat(MountedCapabilityStudioStageAcceptanceAuthorityProvider
                .deploymentUnavailableCause(wrapped)).isSameAs(marker);
        assertThat(MountedCapabilityStudioStageAcceptanceAuthorityProvider
                .deploymentUnavailableCause(new IllegalStateException("MALFORMED"))).isNull();
        assertThat(marker).hasMessage("RG.CAPABILITY_STUDIO.DEPLOYMENT_UNAVAILABLE")
                .hasMessageNotContaining("CREDENTIAL")
                .hasMessageNotContaining("PAYLOAD");
    }

    @Test
    void serviceDescriptorContainsExactlyOneProviderWithoutInstantiatingIt() throws IOException {
        String resourceName = "META-INF/services/"
                + CapabilityStudioStageAcceptanceAuthorityProvider.class.getName();
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(resourceName)) {
            assertThat(stream).as("service descriptor").isNotNull();
            assertThat(new String(stream.readAllBytes(), StandardCharsets.UTF_8))
                    .isEqualTo(PROVIDER_CLASS + "\n");
        }

        List<String> implementations;
        try (Stream<ServiceLoader.Provider<CapabilityStudioStageAcceptanceAuthorityProvider>>
                     providers = ServiceLoader.load(
                     CapabilityStudioStageAcceptanceAuthorityProvider.class).stream()) {
            implementations = providers.map(ServiceLoader.Provider::type)
                    .map(Class::getName)
                    .toList();
        }
        assertThat(implementations).containsExactly(PROVIDER_CLASS);
    }

    @Test
    void productionSourcesContainNoSigningFallbackOrSensitiveToString() throws IOException {
        Path sourceRoot = Path.of("src/main/java/com/leanowtech/bloge/gateway/testkit/mounted");
        StringBuilder sources = new StringBuilder();
        try (var files = Files.list(sourceRoot)) {
            for (Path source : files.sorted().toList()) {
                sources.append(Files.readString(source));
            }
        }
        assertThat(sources.toString())
                .doesNotContain("PrivateKey")
                .doesNotContain("KeyPair")
                .doesNotContain("Signature.getInstance")
                .doesNotContain("defaultTrust")
                .doesNotContain("formalTargetBoundAuthorityBinding() {\n        return null")
                .contains("material=REDACTED")
                .contains("mounts=REDACTED")
                .contains("state=REDACTED");
    }

    private static ExecutionLeaseRequest request(
            CapabilityStudioStageAcceptanceAuthorityProvider.FormalTargetBoundAuthorityBinding
                    formal,
            java.time.Instant time,
            String contractRevision) {
        return request(formal, time, contractRevision, formal.fingerprint(),
                MountedProviderTestFixtures.LEASE);
    }

    private static ExecutionLeaseRequest request(
            CapabilityStudioStageAcceptanceAuthorityProvider.FormalTargetBoundAuthorityBinding
                    formal,
            java.time.Instant time,
            String contractRevision,
            String providerOuterFingerprint,
            String executionLeaseId) {
        var admission = formal.targetAdmissionBinding();
        return new ExecutionLeaseRequest("SAR-mounted-provider", 1,
                fingerprint('1'), fingerprint('2'), "contract:mounted-provider",
                contractRevision, executionLeaseId, providerOuterFingerprint,
                admission.targetRawFingerprint(), admission.targetCanonicalFingerprint(),
                admission.lifecycleMaterial(),
                admission.deploymentAuthorityBinding().fingerprint(), time);
    }

    private static void configure(Fixture fixture) {
        System.setProperty(MountedCapabilityStudioStageAcceptanceAuthorityProvider
                .AUTHORITY_BUNDLE_ROOT_PROPERTY, fixture.authorityRoot().toString());
        System.setProperty(MountedCapabilityStudioStageAcceptanceAuthorityProvider
                .TARGET_ADMISSION_BUNDLE_ROOT_PROPERTY, fixture.targetRoot().toString());
        System.setProperty(MountedCapabilityStudioStageAcceptanceAuthorityProvider
                .EXECUTION_LEASE_STATE_ROOT_PROPERTY, fixture.stateRoot().toString());
    }

    private static void deleteDirectFiles(Path root) throws IOException {
        try (var files = Files.list(root)) {
            for (Path file : files.toList()) {
                Files.delete(file);
            }
        }
    }

    private static String fingerprint(char seed) {
        return "sha256:" + String.valueOf(seed).repeat(64);
    }
}
