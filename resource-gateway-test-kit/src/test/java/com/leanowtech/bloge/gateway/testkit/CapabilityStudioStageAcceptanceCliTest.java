package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceKind;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceResolution;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceResolver;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityVerifier.ReferenceKind;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityVerifier.ResolutionRequest;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityVerifier.ResolvedEvidence;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.AuthorityBinding;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.EvidenceExecutionLeaseCommitResult;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.ExecutionLeaseCommitStatus;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.ExecutionLeaseTransitionWitness;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.FormalEvidenceAuthorityBinding;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CapabilityStudioStageAcceptanceCliTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final CapabilityStudioStageAcceptanceTargetBindingVerifier TARGET_VERIFIER =
            new CapabilityStudioStageAcceptanceTargetBindingVerifier();
    private static final String TARGET_LEASE = "lease:stage-acceptance:1";
    private static final String TARGET_IDENTITY = "runtime:capability-studio";
    private static final String TARGET_SCOPE = "tenant:demo/environment:acceptance";
    private static final Instant NOW = Instant.parse("2026-01-01T00:12:00Z");
    private static final String CLOCK_FINGERPRINT = fingerprint('7');
    private static final String LIFECYCLE_AUTHORITY_FINGERPRINT = fingerprint('8');
    private static final String LEASE_AUTHORITY_FINGERPRINT = fingerprint('9');

    @TempDir
    Path temporaryDirectory;

    @AfterEach
    void clearEvidencePublicationPin() {
        System.clearProperty(CapabilityStudioExecutionLeaseEvidencePublication
                .EXPECTED_PUBLICATION_FINGERPRINT_ENV);
    }

    @Test
    void acceptsOnlyAfterTheDeploymentProviderVerifiesEveryAuthority() throws Exception {
        ObjectNode result = passResult();
        Path artifact = write("pass.json", result.toString());
        Output output = new Output();
        Provider provider = acceptingProvider(result);

        int exit = run(artifact, List.of(provider), output);

        var binding = provider.formalTargetBoundAuthorityBinding();
        var admission = binding.targetAdmissionBinding();
        var deployment = admission.deploymentAuthorityBinding();
        var lifecycle = admission.lifecycleMaterial();
        var receipt = committedLease(leaseRequest(result, binding, NOW)).receipt();
        assertThat(exit).isEqualTo(CapabilityStudioStageAcceptanceCli.EXIT_ACCEPTED);
        assertThat(output.text()).isEqualTo("ACCEPTED outcome=ACCEPTED "
                + "authorityBindingFingerprint=" + binding.fingerprint()
                + " authorityMaterialFingerprint="
                + binding.authorityBinding().fingerprint()
                + " leaseCommitStatus=COMMITTED leaseReceiptFingerprint="
                + receipt.fingerprint()
                + " targetAdmissionMaterialFingerprint="
                + admission.targetAdmissionMaterialFingerprint()
                + " deploymentAdmissionAuthorityMaterialFingerprint="
                + deployment.fingerprint()
                + " targetRawFingerprint=" + admission.targetRawFingerprint()
                + " targetCanonicalFingerprint=" + admission.targetCanonicalFingerprint()
                + " trustedClockMaterialFingerprint="
                + deployment.trustedClockBinding().fingerprint()
                + " admissionLifecycleAuthorityMaterialFingerprint="
                + deployment.lifecycleAuthorityBinding().fingerprint()
                + " executionLeaseAuthorityMaterialFingerprint="
                + deployment.executionLeaseAuthorityBinding().fingerprint()
                + " lifecycleMaterialFingerprint=" + lifecycle.fingerprint()
                + " revocationSnapshotFingerprint="
                + lifecycle.revocationAuthority().snapshotFingerprint()
                + " reasonCode="
                + "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_CLI.ACCEPTED"
                + System.lineSeparator());
        Map<String, String> transcript = transcript(output.text());
        assertThat(CapabilityStudioStageAcceptanceAuthorityProvider
                .DeploymentAdmissionAuthorityBinding.aggregateFingerprint(
                        transcript.get("trustedClockMaterialFingerprint"),
                        transcript.get("admissionLifecycleAuthorityMaterialFingerprint"),
                        transcript.get("executionLeaseAuthorityMaterialFingerprint")))
                .isEqualTo(transcript.get(
                        "deploymentAdmissionAuthorityMaterialFingerprint"));
        assertThat(CapabilityStudioStageAcceptanceAuthorityProvider
                .FormalTargetBoundAuthorityBinding.aggregateFingerprint(
                        CapabilityStudioStageAcceptanceAuthorityProvider
                                .FormalTargetBoundAuthorityBinding.MESSAGE_VERSION,
                        transcript.get("authorityMaterialFingerprint"),
                        transcript.get("deploymentAdmissionAuthorityMaterialFingerprint"),
                        transcript.get("targetAdmissionMaterialFingerprint"),
                        transcript.get("targetRawFingerprint"),
                        transcript.get("targetCanonicalFingerprint")))
                .isEqualTo(transcript.get("authorityBindingFingerprint"));
        assertThat(output.text()).doesNotContain(
                "attestation:environment:1", "actor:correctness", "signature:correctness",
                TARGET_LEASE);
    }

    @Test
    void recoveredLeaseReceiptIsAnAcceptedClosedOutput() throws Exception {
        ObjectNode result = passResult();
        String providerReason = "LEASE_RECOVERY_CREDENTIAL_TOKEN_ABC123";
        var admission = withDeploymentAuthorities(targetAdmission(result), () -> NOW,
                request -> CapabilityStudioStageAcceptanceAuthorityProvider
                        .DeploymentAuthorityDecision.verified(
                                "LIFECYCLE_CREDENTIAL_TOKEN_ABC123"),
                request -> CapabilityStudioStageAcceptanceAuthorityProvider
                        .ExecutionLeaseCommitResult.recovered(
                        committedLease(request).receipt(), providerReason));
        Provider delegate = acceptingProvider(result);
        Provider provider = new Provider(delegate.resolver(), delegate.issuer(), delegate.owner(),
                admission);
        Output output = new Output();

        int exit = run(write("recovered.json", result.toString()), List.of(provider), output);

        var binding = provider.formalTargetBoundAuthorityBinding();
        var targetAdmission = binding.targetAdmissionBinding();
        var deployment = targetAdmission.deploymentAuthorityBinding();
        var lifecycle = targetAdmission.lifecycleMaterial();
        var receipt = committedLease(leaseRequest(result, binding, NOW)).receipt();
        assertThat(exit).isEqualTo(CapabilityStudioStageAcceptanceCli.EXIT_ACCEPTED);
        assertThat(output.text()).isEqualTo("ACCEPTED outcome=ACCEPTED "
                + "authorityBindingFingerprint=" + binding.fingerprint()
                + " authorityMaterialFingerprint="
                + binding.authorityBinding().fingerprint()
                + " leaseCommitStatus=RECOVERED leaseReceiptFingerprint="
                + receipt.fingerprint()
                + " targetAdmissionMaterialFingerprint="
                + targetAdmission.targetAdmissionMaterialFingerprint()
                + " deploymentAdmissionAuthorityMaterialFingerprint="
                + deployment.fingerprint()
                + " targetRawFingerprint=" + targetAdmission.targetRawFingerprint()
                + " targetCanonicalFingerprint="
                + targetAdmission.targetCanonicalFingerprint()
                + " trustedClockMaterialFingerprint="
                + deployment.trustedClockBinding().fingerprint()
                + " admissionLifecycleAuthorityMaterialFingerprint="
                + deployment.lifecycleAuthorityBinding().fingerprint()
                + " executionLeaseAuthorityMaterialFingerprint="
                + deployment.executionLeaseAuthorityBinding().fingerprint()
                + " lifecycleMaterialFingerprint=" + lifecycle.fingerprint()
                + " revocationSnapshotFingerprint="
                + lifecycle.revocationAuthority().snapshotFingerprint()
                + " reasonCode="
                + "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_CLI.ACCEPTED"
                + System.lineSeparator()).doesNotContain(providerReason, "CREDENTIAL_TOKEN");
    }

    @Test
    void committedAndRecoveredTranscriptsDifferOnlyByInvocationStatus() throws Exception {
        ObjectNode result = passResult();
        AtomicReference<CapabilityStudioStageAcceptanceAuthorityProvider.ExecutionLeaseReceipt>
                durableReceipt = new AtomicReference<>();
        AtomicInteger commits = new AtomicInteger();
        var admission = withDeploymentAuthorities(targetAdmission(result), () -> NOW,
                request -> CapabilityStudioStageAcceptanceAuthorityProvider
                        .DeploymentAuthorityDecision.verified("LIFECYCLE_VERIFIED"),
                request -> {
                    if (commits.getAndIncrement() == 0) {
                        var committed = committedLease(request);
                        durableReceipt.set(committed.receipt());
                        return committed;
                    }
                    return CapabilityStudioStageAcceptanceAuthorityProvider
                            .ExecutionLeaseCommitResult.recovered(
                                    durableReceipt.get(), "RECOVERY_PAYLOAD_SUPPRESSED");
                });
        Provider delegate = acceptingProvider(result);
        Provider provider = new Provider(delegate.resolver(), delegate.issuer(), delegate.owner(),
                admission);
        Path artifact = write("commit-recover.json", result.toString());
        Output committed = new Output();
        Output recovered = new Output();

        int committedExit = run(artifact, List.of(provider), committed);
        int recoveredExit = run(artifact, List.of(provider), recovered);

        assertThat(committedExit).isEqualTo(CapabilityStudioStageAcceptanceCli.EXIT_ACCEPTED);
        assertThat(recoveredExit).isEqualTo(CapabilityStudioStageAcceptanceCli.EXIT_ACCEPTED);
        assertThat(recovered.text().replace("leaseCommitStatus=RECOVERED",
                "leaseCommitStatus=COMMITTED")).isEqualTo(committed.text());
        assertThat(recovered.text()).doesNotContain("RECOVERY_PAYLOAD_SUPPRESSED");
    }

    @Test
    void acceptedOutputFailureReturnsInvalidWithoutWritingToStderr() throws Exception {
        ObjectNode result = passResult();
        Path artifact = write("broken-output.json", result.toString());
        Provider provider = acceptingProvider(result);
        PrintStream broken = new PrintStream(new OutputStream() {
            @Override
            public void write(int value) throws IOException {
                throw new IOException("OUTPUT_CREDENTIAL_TOKEN_ABC123");
            }
        }, true, StandardCharsets.UTF_8);
        ByteArrayOutputStream errors = new ByteArrayOutputStream();

        int exit = CapabilityStudioStageAcceptanceCli.run(
                new String[]{artifact.toString()}, broken,
                new PrintStream(errors, true, StandardCharsets.UTF_8), NOW,
                () -> List.of(provider),
                provider.formalTargetBoundAuthorityBinding().fingerprint());

        assertThat(exit).isEqualTo(CapabilityStudioStageAcceptanceCli.EXIT_INVALID);
        assertThat(broken.checkError()).isTrue();
        assertThat(errors.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    void leaseRequestBindsExactOriginalStageResultBytesAndVerifiedClosure() throws Exception {
        ObjectNode result = passResult();
        String exactWire = result.toPrettyString() + System.lineSeparator();
        byte[] exactBytes = exactWire.getBytes(StandardCharsets.UTF_8);
        AtomicReference<CapabilityStudioStageAcceptanceAuthorityProvider.ExecutionLeaseRequest>
                observed = new AtomicReference<>();
        var admission = withDeploymentAuthorities(targetAdmission(result), () -> NOW,
                request -> CapabilityStudioStageAcceptanceAuthorityProvider
                        .DeploymentAuthorityDecision.verified("LIFECYCLE_VERIFIED"),
                request -> {
                    observed.set(request);
                    return committedLease(request);
                });
        Provider delegate = acceptingProvider(result);
        Provider provider = new Provider(delegate.resolver(), delegate.issuer(), delegate.owner(),
                admission);
        Output output = new Output();

        int exit = run(write("exact-stage-result.json", exactWire), List.of(provider), output);

        assertThat(exit).isEqualTo(CapabilityStudioStageAcceptanceCli.EXIT_ACCEPTED);
        assertThat(observed.get().stageResultRawFingerprint())
                .isEqualTo(rawFingerprint(exactBytes))
                .isNotEqualTo(rawFingerprint(result.toString()
                        .getBytes(StandardCharsets.UTF_8)));
        assertThat(observed.get().evidenceClosureFingerprint())
                .isEqualTo(result.path("evidenceClosureFingerprint").textValue());
        assertThat(output.text()).contains("leaseCommitStatus=COMMITTED");
    }

    @Test
    void invalidProtocolAndHonestNonPassNeverLoadExternalProvider() throws Exception {
        AtomicInteger loads = new AtomicInteger();
        Output invalidOutput = new Output();
        int invalid = run(write("invalid.json", "{}"), () -> {
            loads.incrementAndGet();
            return List.of();
        }, invalidOutput);
        Output blockedOutput = new Output();
        int blocked = run(write("blocked.json", blockedResult().toString()), () -> {
            loads.incrementAndGet();
            return List.of();
        }, blockedOutput);

        assertThat(invalid).isEqualTo(CapabilityStudioStageAcceptanceCli.EXIT_INVALID);
        assertThat(invalidOutput.text()).contains("outcome=PROTOCOL_INVALID");
        assertThat(blocked).isEqualTo(CapabilityStudioStageAcceptanceCli.EXIT_NOT_ACCEPTED);
        assertThat(blockedOutput.text()).contains("outcome=NOT_ACCEPTED", "STATUS_NOT_PASS");
        assertThat(loads).hasValue(0);
    }

    @Test
    void rejectsMissingOrAmbiguousProviderConfiguration() throws Exception {
        ObjectNode result = passResult();
        Path artifact = write("pass.json", result.toString());
        Output missingOutput = new Output();
        Output ambiguousOutput = new Output();

        int missing = run(artifact, List.of(), missingOutput);
        int ambiguous = run(artifact,
                List.of(acceptingProvider(result), acceptingProvider(result)), ambiguousOutput);

        assertThat(missing).isEqualTo(CapabilityStudioStageAcceptanceCli.EXIT_INVALID);
        assertThat(ambiguous).isEqualTo(CapabilityStudioStageAcceptanceCli.EXIT_INVALID);
        assertThat(missingOutput.text()).contains("PROVIDER_CONFIGURATION");
        assertThat(ambiguousOutput.text()).contains("PROVIDER_CONFIGURATION");
    }

    @Test
    void expectedBindingPinIsRequiredAndValidatedBeforeProviderAuthorityBinding() throws Exception {
        Path artifact = write("pin.json", passResult().toString());
        AtomicInteger loads = new AtomicInteger();
        Output missing = new Output();
        Output malformed = new Output();
        int missingExit = CapabilityStudioStageAcceptanceCli.run(
                new String[]{artifact.toString()}, missing.out(), missing.err(), NOW,
                () -> {
                    loads.incrementAndGet();
                    return List.of(acceptingProvider(passResult()));
                }, null);
        int malformedExit = CapabilityStudioStageAcceptanceCli.run(
                new String[]{artifact.toString()}, malformed.out(), malformed.err(), NOW,
                () -> {
                    loads.incrementAndGet();
                    return List.of(acceptingProvider(passResult()));
                }, "SHA256:" + "a".repeat(64));

        assertThat(missingExit).isEqualTo(CapabilityStudioStageAcceptanceCli.EXIT_INVALID);
        assertThat(malformedExit).isEqualTo(CapabilityStudioStageAcceptanceCli.EXIT_INVALID);
        assertThat(missing.text()).contains("EXPECTED_AUTHORITY_BINDING_INVALID");
        assertThat(malformed.text()).contains("EXPECTED_AUTHORITY_BINDING_INVALID");
        assertThat(loads).hasValue(0);
    }

    @Test
    void mismatchingPinFailsBeforeResolverIssuerOrOwnerCallbacks() throws Exception {
        ObjectNode result = passResult();
        Path artifact = write("pin-mismatch.json", result.toString());
        AtomicInteger callbacks = new AtomicInteger();
        Provider provider = new Provider(
                request -> {
                    callbacks.incrementAndGet();
                    return EvidenceResolution.available(facts(request,
                            result.path("evidenceClosureFingerprint").asText()));
                },
                (reference, evidence, context) -> {
                    callbacks.incrementAndGet();
                    return verified();
                },
                (signoff, signature, context) -> {
                    callbacks.incrementAndGet();
                    return verified();
                }, targetAdmission(result));
        Output output = new Output();

        int exit = CapabilityStudioStageAcceptanceCli.run(
                new String[]{artifact.toString()}, output.out(), output.err(), NOW,
                () -> List.of(provider), fingerprint('b'));

        assertThat(exit).isEqualTo(CapabilityStudioStageAcceptanceCli.EXIT_INVALID);
        assertThat(output.text()).contains("PROVIDER_CONFIGURATION");
        assertThat(callbacks).hasValue(0);
    }

    @Test
    void mismatchingOuterAggregateFailsBeforeTargetOrPostRunCallbacks() throws Exception {
        ObjectNode result = passResult();
        Path artifact = write("aggregate-mismatch.json", result.toString());
        AtomicInteger callbacks = new AtomicInteger();
        Provider valid = acceptingProvider(result);
        CapabilityStudioStageAcceptanceAuthorityProvider.FormalTargetAdmissionBinding admission =
                targetAdmission(result, facts -> {
                    callbacks.incrementAndGet();
                    return verifiedTarget();
                }, facts -> {
                    callbacks.incrementAndGet();
                    return verifiedTarget();
                });
        CapabilityStudioStageAcceptanceAuthorityProvider malformed =
                new CapabilityStudioStageAcceptanceAuthorityProvider() {
                    @Override
                    public CapabilityStudioStageAcceptanceAuthorityProvider
                            .FormalTargetBoundAuthorityBinding formalTargetBoundAuthorityBinding() {
                        var authority =
                                new CapabilityStudioStageAcceptanceAuthorityProvider.AuthorityBinding(
                                        fingerprint('a'), valid.resolver(), valid.issuer(),
                                        valid.owner());
                        return new CapabilityStudioStageAcceptanceAuthorityProvider
                                .FormalTargetBoundAuthorityBinding(
                                fingerprint('b'), authority, admission);
                    }

                    @Override
                    public CapabilityStudioStageAcceptanceAuthorityProvider.AuthorityBinding
                            authorityBinding() {
                        callbacks.incrementAndGet();
                        return valid.authorityBinding();
                    }

                    @Override
                    public CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceResolver
                            evidenceResolver() {
                        callbacks.incrementAndGet();
                        return valid.resolver();
                    }

                    @Override
                    public CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceIssuerPolicy
                            evidenceIssuerPolicy() {
                        callbacks.incrementAndGet();
                        return valid.issuer();
                    }

                    @Override
                    public CapabilityStudioStageAcceptanceAuthorityVerifier.OwnerAuthority
                            ownerAuthority() {
                        callbacks.incrementAndGet();
                        return valid.owner();
                    }
                };
        Output output = new Output();

        int exit = CapabilityStudioStageAcceptanceCli.run(
                new String[]{artifact.toString()}, output.out(), output.err(), NOW,
                () -> List.of(malformed), bindingFingerprint(result));

        assertThat(exit).isEqualTo(CapabilityStudioStageAcceptanceCli.EXIT_INVALID);
        assertThat(output.text()).contains("PROVIDER_CONFIGURATION");
        assertThat(callbacks).hasValue(0);
    }

    @Test
    void legacyProviderIsFailClosedEvenWhenItsOldAccessorsAreComplete() throws Exception {
        ObjectNode result = passResult();
        Path artifact = write("legacy-provider.json", result.toString());
        Provider delegate = acceptingProvider(result);
        CapabilityStudioStageAcceptanceAuthorityProvider legacy =
                new CapabilityStudioStageAcceptanceAuthorityProvider() {
                    @Override
                    public EvidenceResolver evidenceResolver() {
                        return delegate.resolver();
                    }

                    @Override
                    public CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceIssuerPolicy
                            evidenceIssuerPolicy() {
                        return delegate.issuer();
                    }

                    @Override
                    public CapabilityStudioStageAcceptanceAuthorityVerifier.OwnerAuthority
                            ownerAuthority() {
                        return delegate.owner();
                    }
                };
        Output output = new Output();

        int exit = CapabilityStudioStageAcceptanceCli.run(
                new String[]{artifact.toString()}, output.out(), output.err(), NOW,
                () -> List.of(legacy), fingerprint('a'));

        assertThat(exit).isEqualTo(CapabilityStudioStageAcceptanceCli.EXIT_NOT_ACCEPTED);
        assertThat(output.text()).contains(
                "outcome=BLOCKED", "FORMAL_TARGET_BINDING_UNAVAILABLE");
    }

    @Test
    void legacyAtomicBindingBlocksBeforePostRunAuthorityCallbacks() throws Exception {
        ObjectNode result = passResult();
        Path artifact = write("legacy-binding.json", result.toString());
        AtomicInteger postRunCallbacks = new AtomicInteger();
        Provider delegate = acceptingProvider(result);
        CapabilityStudioStageAcceptanceAuthorityProvider legacyBinding =
                new CapabilityStudioStageAcceptanceAuthorityProvider() {
                    @Override
                    public CapabilityStudioStageAcceptanceAuthorityProvider
                            .TargetBoundAuthorityBinding targetBoundAuthorityBinding() {
                        var formal = delegate.targetAdmission();
                        var legacy = new CapabilityStudioStageAcceptanceAuthorityProvider
                                .TargetAdmissionBinding(formal.targetBindingBytes(),
                                formal.candidateAttestationBytes(),
                                formal.environmentAttestationBytes(),
                                formal.verificationContext(), formal.candidateAuthority(),
                                formal.environmentAuthority());
                        return new CapabilityStudioStageAcceptanceAuthorityProvider
                                .TargetBoundAuthorityBinding(delegate.authorityBinding(), legacy);
                    }

                    @Override
                    public EvidenceResolver evidenceResolver() {
                        postRunCallbacks.incrementAndGet();
                        return delegate.resolver();
                    }

                    @Override
                    public CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceIssuerPolicy
                            evidenceIssuerPolicy() {
                        postRunCallbacks.incrementAndGet();
                        return delegate.issuer();
                    }

                    @Override
                    public CapabilityStudioStageAcceptanceAuthorityVerifier.OwnerAuthority
                            ownerAuthority() {
                        postRunCallbacks.incrementAndGet();
                        return delegate.owner();
                    }
                };
        Output output = new Output();

        int exit = run(artifact, List.of(legacyBinding), output);

        assertThat(exit).isEqualTo(CapabilityStudioStageAcceptanceCli.EXIT_NOT_ACCEPTED);
        assertThat(output.text()).contains(
                "outcome=BLOCKED", "FORMAL_TARGET_BINDING_UNAVAILABLE");
        assertThat(postRunCallbacks).hasValue(0);
    }

    @Test
    void invokesCandidateEnvironmentAndPostRunCallbacksInStrictOrder() throws Exception {
        ObjectNode result = passResult();
        Path artifact = write("callback-order.json", result.toString());
        List<String> calls = new ArrayList<>();
        String closure = result.path("evidenceClosureFingerprint").textValue();
        var targetAdmission = targetAdmission(result, facts -> {
            calls.add("candidate");
            return verifiedTarget();
        }, facts -> {
            calls.add("environment");
            return verifiedTarget();
        });
        targetAdmission = withDeploymentAuthorities(targetAdmission, () -> {
            calls.add("clock");
            return NOW;
        }, request -> {
            calls.add("lifecycle");
            return CapabilityStudioStageAcceptanceAuthorityProvider
                    .DeploymentAuthorityDecision.verified("LIFECYCLE_VERIFIED");
        }, request -> {
            calls.add("lease");
            return committedLease(request);
        });
        Provider provider = new Provider(
                request -> {
                    calls.add("resolver");
                    return EvidenceResolution.available(facts(request, closure));
                },
                (reference, evidence, context) -> {
                    calls.add("issuer");
                    return verified();
                },
                (signoff, signature, context) -> {
                    calls.add("owner");
                    return verified();
                }, targetAdmission);

        int exit = run(artifact, List.of(provider), new Output());

        assertThat(exit).isEqualTo(CapabilityStudioStageAcceptanceCli.EXIT_ACCEPTED);
        assertThat(calls).containsSubsequence("clock", "lifecycle", "candidate",
                "environment", "resolver", "issuer", "owner", "lease");
        assertThat(calls.indexOf("issuer")).isGreaterThan(calls.indexOf("resolver"));
        assertThat(calls.indexOf("owner")).isGreaterThan(calls.indexOf("issuer"));
        assertThat(calls.getLast()).isEqualTo("lease");
        assertThat(calls.stream().filter("lease"::equals)).hasSize(1);
    }

    @Test
    void lifecycleRejectionAndUnavailabilityStopEveryLaterCallback() throws Exception {
        ObjectNode result = passResult();
        Path artifact = write("lifecycle-decisions.json", result.toString());
        AtomicInteger laterCallbacks = new AtomicInteger();
        Provider delegate = acceptingProvider(result);
        var baseAdmission = targetAdmission(result, facts -> {
            laterCallbacks.incrementAndGet();
            return verifiedTarget();
        }, facts -> {
            laterCallbacks.incrementAndGet();
            return verifiedTarget();
        });
        var rejectedAdmission = withDeploymentAuthorities(baseAdmission, () -> NOW,
                request -> CapabilityStudioStageAcceptanceAuthorityProvider
                        .DeploymentAuthorityDecision.rejected(
                                "LIFECYCLE_CREDENTIAL_ROLLBACK_ABC123"),
                CapabilityStudioStageAcceptanceCliTest::committedLease);
        Provider rejected = new Provider(request -> {
            laterCallbacks.incrementAndGet();
            return delegate.resolver().resolve(request);
        }, delegate.issuer(), delegate.owner(), rejectedAdmission);
        Output rejectedOutput = new Output();

        int rejectedExit = run(artifact, List.of(rejected), rejectedOutput);

        assertThat(rejectedExit).isEqualTo(CapabilityStudioStageAcceptanceCli.EXIT_NOT_ACCEPTED);
        assertThat(rejectedOutput.text()).isEqualTo(
                "NOT_ACCEPTED outcome=REJECTED reasonCode="
                        + "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_CLI."
                        + "ADMISSION_LIFECYCLE_REJECTED" + System.lineSeparator())
                .doesNotContain("LIFECYCLE_CREDENTIAL_ROLLBACK_ABC123");
        assertThat(laterCallbacks).hasValue(0);

        var unavailableAdmission = withDeploymentAuthorities(baseAdmission, () -> NOW,
                request -> CapabilityStudioStageAcceptanceAuthorityProvider
                        .DeploymentAuthorityDecision.unavailable(
                                "REVOCATION_CREDENTIAL_TOKEN_ABC123"),
                CapabilityStudioStageAcceptanceCliTest::committedLease);
        Output unavailableOutput = new Output();

        int unavailableExit = run(artifact,
                List.of(new Provider(delegate.resolver(), delegate.issuer(), delegate.owner(),
                        unavailableAdmission)), unavailableOutput);

        assertThat(unavailableExit)
                .isEqualTo(CapabilityStudioStageAcceptanceCli.EXIT_NOT_ACCEPTED);
        assertThat(unavailableOutput.text()).isEqualTo(
                "NOT_ACCEPTED outcome=BLOCKED reasonCode="
                        + "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_CLI."
                        + "ADMISSION_LIFECYCLE_UNAVAILABLE" + System.lineSeparator())
                .doesNotContain("REVOCATION_CREDENTIAL_TOKEN_ABC123");
        assertThat(laterCallbacks).hasValue(0);
    }

    @Test
    void trustedClockAndMountedDependencyOutagesBlockButMalformedProvidersAreInvalid()
            throws Exception {
        ObjectNode result = passResult();
        Path artifact = write("dependency-outages.json", result.toString());
        AtomicInteger laterCallbacks = new AtomicInteger();
        Provider delegate = acceptingProvider(result);
        var clockAdmission = withDeploymentAuthorities(targetAdmission(result, facts -> {
            laterCallbacks.incrementAndGet();
            return verifiedTarget();
        }, facts -> {
            laterCallbacks.incrementAndGet();
            return verifiedTarget();
        }), () -> {
            throw new CapabilityStudioStageAcceptanceAuthorityProvider
                    .DeploymentUnavailableException();
        }, request -> {
            laterCallbacks.incrementAndGet();
            return CapabilityStudioStageAcceptanceAuthorityProvider
                    .DeploymentAuthorityDecision.verified("LIFECYCLE_VERIFIED");
        }, CapabilityStudioStageAcceptanceCliTest::committedLease);
        Output clockOutput = new Output();

        int clockExit = run(artifact,
                List.of(new Provider(delegate.resolver(), delegate.issuer(), delegate.owner(),
                        clockAdmission)), clockOutput);

        assertThat(clockExit).isEqualTo(CapabilityStudioStageAcceptanceCli.EXIT_NOT_ACCEPTED);
        assertThat(clockOutput.text()).contains("outcome=BLOCKED", "TRUSTED_CLOCK_UNAVAILABLE");
        assertThat(laterCallbacks).hasValue(0);

        CapabilityStudioStageAcceptanceAuthorityProvider unavailable = providerThrowingSnapshot(
                new CapabilityStudioStageAcceptanceAuthorityProvider
                        .DeploymentUnavailableException());
        CapabilityStudioStageAcceptanceAuthorityProvider malformed = providerThrowingSnapshot(
                new IllegalStateException("provider-mount-secret"));
        Output unavailableOutput = new Output();
        Output malformedOutput = new Output();

        int unavailableExit = run(artifact, List.of(unavailable), unavailableOutput);
        int malformedExit = run(artifact, List.of(malformed), malformedOutput);

        assertThat(unavailableExit)
                .isEqualTo(CapabilityStudioStageAcceptanceCli.EXIT_NOT_ACCEPTED);
        assertThat(unavailableOutput.text()).contains(
                "outcome=BLOCKED", "FORMAL_TARGET_BINDING_UNAVAILABLE");
        assertThat(malformedExit).isEqualTo(CapabilityStudioStageAcceptanceCli.EXIT_INVALID);
        assertThat(malformedOutput.text()).contains("PROVIDER_CONFIGURATION")
                .doesNotContain("provider-mount-secret");
    }

    @Test
    void leaseReplayAndStoreOutageOccurOnlyAfterPostRunAcceptance() throws Exception {
        ObjectNode result = passResult();
        Path artifact = write("lease-decisions.json", result.toString());
        Provider delegate = acceptingProvider(result);
        AtomicInteger leaseCalls = new AtomicInteger();
        AtomicInteger postRunCalls = new AtomicInteger();
        var rejectedAdmission = withDeploymentAuthorities(delegate.targetAdmission(), () -> NOW,
                request -> CapabilityStudioStageAcceptanceAuthorityProvider
                        .DeploymentAuthorityDecision.verified("LIFECYCLE_VERIFIED"),
                request -> {
                    leaseCalls.incrementAndGet();
                    return CapabilityStudioStageAcceptanceAuthorityProvider
                            .ExecutionLeaseCommitResult.rejected(
                                    "LEASE_CREDENTIAL_REPLAY_ABC123");
                });
        Provider rejected = providerWithCountingPostRunCallbacks(
                result, postRunCalls, rejectedAdmission);
        Output rejectedOutput = new Output();

        int rejectedExit = run(artifact, List.of(rejected), rejectedOutput);

        assertThat(rejectedExit).isEqualTo(CapabilityStudioStageAcceptanceCli.EXIT_NOT_ACCEPTED);
        assertThat(rejectedOutput.text()).isEqualTo(
                "NOT_ACCEPTED outcome=REJECTED reasonCode="
                        + "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_CLI."
                        + "EXECUTION_LEASE_REJECTED" + System.lineSeparator())
                .doesNotContain("LEASE_CREDENTIAL_REPLAY_ABC123");
        assertThat(postRunCalls).hasPositiveValue();
        assertThat(leaseCalls).hasValue(1);

        var unavailableAdmission = withDeploymentAuthorities(delegate.targetAdmission(), () -> NOW,
                request -> CapabilityStudioStageAcceptanceAuthorityProvider
                        .DeploymentAuthorityDecision.verified("LIFECYCLE_VERIFIED"),
                request -> CapabilityStudioStageAcceptanceAuthorityProvider
                        .ExecutionLeaseCommitResult.unavailable(
                                "LEASE_STORE_CREDENTIAL_TOKEN_ABC123"));
        Output unavailableOutput = new Output();

        int unavailableExit = run(artifact,
                List.of(new Provider(delegate.resolver(), delegate.issuer(), delegate.owner(),
                        unavailableAdmission)), unavailableOutput);

        assertThat(unavailableExit)
                .isEqualTo(CapabilityStudioStageAcceptanceCli.EXIT_NOT_ACCEPTED);
        assertThat(unavailableOutput.text()).isEqualTo(
                "NOT_ACCEPTED outcome=BLOCKED reasonCode="
                        + "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_CLI."
                        + "EXECUTION_LEASE_UNAVAILABLE" + System.lineSeparator())
                .doesNotContain("LEASE_STORE_CREDENTIAL_TOKEN_ABC123");
    }

    @Test
    void lifecycleCommitReceiptFromAnotherDeploymentAuthorityIsInvalid() throws Exception {
        ObjectNode result = passResult();
        Provider delegate = acceptingProvider(result);
        var admission = withDeploymentAuthorities(delegate.targetAdmission(), () -> NOW,
                request -> CapabilityStudioStageAcceptanceAuthorityProvider
                        .DeploymentAuthorityDecision.verified("LIFECYCLE_VERIFIED"),
                request -> {
                    var revocation = request.lifecycleMaterial().revocationAuthority();
                    var wrongAuthorityReceipt = new CapabilityStudioStageAcceptanceAuthorityProvider
                            .AtomicAdmissionLifecycleCommitReceipt(
                            fingerprint('f'), request.lifecycleMaterial().fingerprint(),
                            revocation.registryRef(), revocation.revision(),
                            revocation.snapshotFingerprint(), 1, NOW,
                            request.commitIdentityFingerprint());
                    var receipt = new CapabilityStudioStageAcceptanceAuthorityProvider
                            .ExecutionLeaseReceipt(request.commitIdentityFingerprint(),
                            request.lifecycleMaterial(), wrongAuthorityReceipt);
                    return CapabilityStudioStageAcceptanceAuthorityProvider
                            .ExecutionLeaseCommitResult.committed(
                            receipt, "LEASE_CREDENTIAL_TOKEN_ABC123");
                });
        Output output = new Output();

        int exit = run(write("wrong-lifecycle-receipt.json", result.toString()),
                List.of(new Provider(delegate.resolver(), delegate.issuer(), delegate.owner(),
                        admission)), output);

        assertThat(exit).isEqualTo(CapabilityStudioStageAcceptanceCli.EXIT_INVALID);
        assertThat(output.text()).contains("PROVIDER_CONFIGURATION")
                .doesNotContain("LEASE_CREDENTIAL_TOKEN_ABC123");
    }

    @Test
    void missingFormalDependenciesBlockAtTheirCapabilityBoundaries() throws Exception {
        ObjectNode result = passResult();
        Path artifact = write("missing-formal-dependencies.json", result.toString());
        Provider delegate = acceptingProvider(result);

        var noClock = withDeploymentAuthorities(
                delegate.targetAdmission(), null,
                request -> CapabilityStudioStageAcceptanceAuthorityProvider
                        .DeploymentAuthorityDecision.verified("LIFECYCLE_VERIFIED"),
                CapabilityStudioStageAcceptanceCliTest::committedLease);
        Output clockOutput = new Output();
        int clockExit = run(artifact,
                List.of(new Provider(delegate.resolver(), delegate.issuer(), delegate.owner(),
                        noClock)), clockOutput);
        assertThat(clockExit).isEqualTo(CapabilityStudioStageAcceptanceCli.EXIT_NOT_ACCEPTED);
        assertThat(clockOutput.text()).contains("TRUSTED_CLOCK_UNAVAILABLE");

        var noLifecycle = withDeploymentAuthorities(
                delegate.targetAdmission(), () -> NOW, null,
                CapabilityStudioStageAcceptanceCliTest::committedLease);
        Output lifecycleOutput = new Output();
        int lifecycleExit = run(artifact,
                List.of(new Provider(delegate.resolver(), delegate.issuer(), delegate.owner(),
                        noLifecycle)), lifecycleOutput);
        assertThat(lifecycleExit).isEqualTo(CapabilityStudioStageAcceptanceCli.EXIT_NOT_ACCEPTED);
        assertThat(lifecycleOutput.text()).contains("ADMISSION_LIFECYCLE_UNAVAILABLE");

        var noLease = withDeploymentAuthorities(
                delegate.targetAdmission(), () -> NOW,
                request -> CapabilityStudioStageAcceptanceAuthorityProvider
                        .DeploymentAuthorityDecision.verified("LIFECYCLE_VERIFIED"), null);
        Output leaseOutput = new Output();
        int leaseExit = run(artifact,
                List.of(new Provider(delegate.resolver(), delegate.issuer(), delegate.owner(),
                        noLease)), leaseOutput);
        assertThat(leaseExit).isEqualTo(CapabilityStudioStageAcceptanceCli.EXIT_NOT_ACCEPTED);
        assertThat(leaseOutput.text()).contains("EXECUTION_LEASE_UNAVAILABLE");
    }

    @Test
    void environmentAuthorityThrowBlocksBeforePostRunCallbacks() throws Exception {
        ObjectNode result = passResult();
        Path artifact = write("environment-throw.json", result.toString());
        AtomicInteger postRunCallbacks = new AtomicInteger();
        String secret = "ENVIRONMENT_CREDENTIAL_TOKEN_ABC123";
        Provider provider = providerWithCountingPostRunCallbacks(result, postRunCallbacks,
                targetAdmission(result, facts -> verifiedTarget(), facts -> {
                    throw new IllegalStateException(secret);
                }));
        Output output = new Output();

        int exit = run(artifact, List.of(provider), output);

        assertThat(exit).isEqualTo(CapabilityStudioStageAcceptanceCli.EXIT_NOT_ACCEPTED);
        assertThat(output.text()).contains("outcome=BLOCKED",
                "TARGET_BINDING_UNAVAILABLE").doesNotContain(secret);
        assertThat(postRunCallbacks).hasValue(0);
    }

    @Test
    void nullEnvironmentAuthorityOutcomeBlocksBeforePostRunCallbacks() throws Exception {
        ObjectNode result = passResult();
        Path artifact = write("environment-null.json", result.toString());
        AtomicInteger postRunCallbacks = new AtomicInteger();
        Provider provider = providerWithCountingPostRunCallbacks(result, postRunCallbacks,
                targetAdmission(result, facts -> verifiedTarget(), facts -> null));
        Output output = new Output();

        int exit = run(artifact, List.of(provider), output);

        assertThat(exit).isEqualTo(CapabilityStudioStageAcceptanceCli.EXIT_NOT_ACCEPTED);
        assertThat(output.text()).contains("outcome=BLOCKED",
                "TARGET_BINDING_UNAVAILABLE");
        assertThat(postRunCallbacks).hasValue(0);
    }

    @Test
    void consumesExactlyOneTargetBoundSnapshotWithoutLegacyAccessorFallback() throws Exception {
        ObjectNode result = passResult();
        Path artifact = write("single-snapshot.json", result.toString());
        Provider delegate = acceptingProvider(result);
        AtomicInteger snapshots = new AtomicInteger();
        AtomicInteger legacyAccessors = new AtomicInteger();
        CapabilityStudioStageAcceptanceAuthorityProvider stateful =
                new CapabilityStudioStageAcceptanceAuthorityProvider() {
                    @Override
                    public CapabilityStudioStageAcceptanceAuthorityProvider
                            .FormalTargetBoundAuthorityBinding formalTargetBoundAuthorityBinding() {
                        if (snapshots.incrementAndGet() != 1) {
                            return null;
                        }
                        return new CapabilityStudioStageAcceptanceAuthorityProvider
                                .FormalTargetBoundAuthorityBinding(
                                delegate.authorityBinding(), delegate.targetAdmission());
                    }

                    @Override
                    public CapabilityStudioStageAcceptanceAuthorityProvider.AuthorityBinding
                            authorityBinding() {
                        legacyAccessors.incrementAndGet();
                        return delegate.authorityBinding();
                    }

                    @Override
                    public EvidenceResolver evidenceResolver() {
                        legacyAccessors.incrementAndGet();
                        return delegate.resolver();
                    }

                    @Override
                    public CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceIssuerPolicy
                            evidenceIssuerPolicy() {
                        legacyAccessors.incrementAndGet();
                        return delegate.issuer();
                    }

                    @Override
                    public CapabilityStudioStageAcceptanceAuthorityVerifier.OwnerAuthority
                            ownerAuthority() {
                        legacyAccessors.incrementAndGet();
                        return delegate.owner();
                    }
                };

        int exit = run(artifact, List.of(stateful), new Output());

        assertThat(exit).isEqualTo(CapabilityStudioStageAcceptanceCli.EXIT_ACCEPTED);
        assertThat(snapshots).hasValue(1);
        assertThat(legacyAccessors).hasValue(0);
    }

    @Test
    void targetRejectionAndUnavailableCallbackStopBeforePostRunAuthority() throws Exception {
        ObjectNode result = passResult();
        Path artifact = write("target-rejected.json", result.toString());
        AtomicInteger postRunCallbacks = new AtomicInteger();
        Provider base = new Provider(
                request -> {
                    postRunCallbacks.incrementAndGet();
                    return EvidenceResolution.available(facts(request,
                            result.path("evidenceClosureFingerprint").asText()));
                },
                (reference, evidence, context) -> {
                    postRunCallbacks.incrementAndGet();
                    return verified();
                },
                (signoff, signature, context) -> {
                    postRunCallbacks.incrementAndGet();
                    return verified();
                }, targetAdmission(result));
        Provider rejected = new Provider(base.resolver(), base.issuer(), base.owner(),
                targetAdmission(result, facts ->
                        CapabilityStudioStageAcceptanceTargetBindingVerifier.AuthorityDecision
                                .rejected(), facts ->
                        CapabilityStudioStageAcceptanceTargetBindingVerifier.AuthorityDecision
                                .verified()));
        Output rejectedOutput = new Output();

        int rejectedExit = run(artifact, () -> List.of(rejected), rejectedOutput);

        assertThat(rejectedExit).isEqualTo(CapabilityStudioStageAcceptanceCli.EXIT_NOT_ACCEPTED);
        assertThat(rejectedOutput.text()).contains("outcome=REJECTED",
                "TARGET_BINDING_REJECTED");
        assertThat(postRunCallbacks).hasValue(0);

        String secret = "TARGET_AUTHORITY_CREDENTIAL_TOKEN_ABC123";
        Provider blocked = new Provider(base.resolver(), base.issuer(), base.owner(),
                targetAdmission(result, facts -> {
                    throw new IllegalStateException(secret);
                }, facts ->
                        CapabilityStudioStageAcceptanceTargetBindingVerifier.AuthorityDecision
                                .verified()));
        Output blockedOutput = new Output();

        int blockedExit = run(artifact, () -> List.of(blocked), blockedOutput);

        assertThat(blockedExit).isEqualTo(CapabilityStudioStageAcceptanceCli.EXIT_NOT_ACCEPTED);
        assertThat(blockedOutput.text()).contains("outcome=BLOCKED",
                "TARGET_BINDING_UNAVAILABLE").doesNotContain(secret);
        assertThat(postRunCallbacks).hasValue(0);
    }

    @Test
    void stageAndTargetCoordinateDriftAreRejectedBeforePostRunAuthority() throws Exception {
        ObjectNode result = passResult();
        Provider provider = acceptingProvider(result);
        result.put("resultId", "SAR-stage-drift");
        refreshClosure(result);
        Path artifact = write("stage-drift.json", result.toString());
        Output output = new Output();

        int exit = CapabilityStudioStageAcceptanceCli.run(
                new String[]{artifact.toString()}, output.out(), output.err(), NOW,
                () -> List.of(provider), bindingFingerprint(passResult()));

        assertThat(exit).isEqualTo(CapabilityStudioStageAcceptanceCli.EXIT_NOT_ACCEPTED);
        assertThat(output.text()).contains("outcome=REJECTED",
                "TARGET_BINDING_REJECTED");

        ObjectNode targetResult = passResult();
        CapabilityStudioStageAcceptanceAuthorityProvider.FormalTargetAdmissionBinding original =
                targetAdmission(targetResult);
        ObjectNode driftedTarget;
        try {
            driftedTarget = (ObjectNode) JSON.readTree(original.targetBindingBytes());
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
        driftedTarget.put("fingerprint", fingerprint('0'));
        CapabilityStudioStageAcceptanceAuthorityProvider.FormalTargetAdmissionBinding driftedAdmission =
                new CapabilityStudioStageAcceptanceAuthorityProvider.FormalTargetAdmissionBinding(
                        original.targetAdmissionMaterialFingerprint(),
                        TARGET_VERIFIER.rawAttestationFingerprint(
                                JSON.writeValueAsBytes(driftedTarget)),
                        original.targetCanonicalFingerprint(),
                        JSON.writeValueAsBytes(driftedTarget), original.candidateAttestationBytes(),
                        original.environmentAttestationBytes(), original.verificationContext(),
                        original.candidateAuthority(), original.environmentAuthority(),
                        original.lifecycleMaterial(), original.deploymentAuthorityBinding());
        Provider targetDelegate = acceptingProvider(targetResult);
        Provider targetDriftProvider = new Provider(
                targetDelegate.resolver(), targetDelegate.issuer(), targetDelegate.owner(),
                driftedAdmission);
        Path targetArtifact = write("target-drift.json", targetResult.toString());
        Output targetOutput = new Output();

        String driftedOuter = new CapabilityStudioStageAcceptanceAuthorityProvider
                .FormalTargetBoundAuthorityBinding(
                targetDriftProvider.authorityBinding(), driftedAdmission).fingerprint();
        int targetExit = CapabilityStudioStageAcceptanceCli.run(
                new String[]{targetArtifact.toString()}, targetOutput.out(), targetOutput.err(),
                NOW, () -> List.of(targetDriftProvider), driftedOuter);

        assertThat(targetExit).isEqualTo(CapabilityStudioStageAcceptanceCli.EXIT_NOT_ACCEPTED);
        assertThat(targetOutput.text()).contains("outcome=REJECTED",
                "TARGET_BINDING_REJECTED");
    }

    @Test
    void rejectsIncompleteOrFailingProviderWithoutLeakingException() throws Exception {
        String secret = "provider-secret-must-not-escape";
        ObjectNode result = passResult();
        Path artifact = write("pass.json", result.toString());
        Output incompleteOutput = new Output();
        Output failingOutput = new Output();
        CapabilityStudioStageAcceptanceAuthorityProvider incomplete = new Provider(
                null, (reference, evidence, context) -> verified(),
                (signoff, signature, context) -> verified(), targetAdmission(result));
        CapabilityStudioStageAcceptanceAuthorityProvider failing = new Provider(
                request -> {
                    throw new IllegalStateException(secret);
                }, (reference, evidence, context) -> verified(),
                (signoff, signature, context) -> verified(), targetAdmission(result));

        int incompleteExit = run(artifact, List.of(incomplete), incompleteOutput);
        int failingExit = run(artifact, List.of(failing), failingOutput);

        assertThat(incompleteExit).isEqualTo(CapabilityStudioStageAcceptanceCli.EXIT_INVALID);
        assertThat(incompleteOutput.text()).contains("PROVIDER_CONFIGURATION");
        assertThat(failingExit).isEqualTo(CapabilityStudioStageAcceptanceCli.EXIT_NOT_ACCEPTED);
        assertThat(failingOutput.text()).contains("outcome=BLOCKED").doesNotContain(secret);
    }

    @Test
    void mapsDeterministicAuthorityRejectionToValidNotAccepted() throws Exception {
        ObjectNode result = passResult();
        Path artifact = write("pass.json", result.toString());
        Output output = new Output();
        Provider accepting = acceptingProvider(result);
        Provider rejecting = new Provider(
                accepting.resolver(),
                (reference, evidence, context) ->
                        CapabilityStudioStageAcceptanceAuthorityVerifier.AuthorityDecision
                                .rejected("ISSUER_CREDENTIAL_TOKEN_ABC123"),
                accepting.owner(), accepting.targetAdmission());

        int exit = run(artifact, List.of(rejecting), output);

        assertThat(exit).isEqualTo(CapabilityStudioStageAcceptanceCli.EXIT_NOT_ACCEPTED);
        assertThat(output.text()).contains("outcome=REJECTED", "AUTHORITY_REJECTED")
                .doesNotContain("ISSUER_CREDENTIAL_TOKEN_ABC123");
    }

    @Test
    void usageReadAndOversizeFailuresNeverEchoPaths() throws Exception {
        Output usage = new Output();
        int usageExit = CapabilityStudioStageAcceptanceCli.run(
                new String[0], usage.out(), usage.err(), NOW, List::of);
        String missingPath = temporaryDirectory.resolve("customer-secret.json").toString();
        Output missing = new Output();
        int missingExit = CapabilityStudioStageAcceptanceCli.run(
                new String[]{missingPath}, missing.out(), missing.err(), NOW, List::of);
        Path oversized = temporaryDirectory.resolve("oversized-secret.json");
        Files.writeString(oversized, "x".repeat(
                CapabilityStudioStageAcceptanceResultV2Verifier.MAXIMUM_RESULT_BYTES + 1));
        Output tooLarge = new Output();
        int tooLargeExit = CapabilityStudioStageAcceptanceCli.run(
                new String[]{oversized.toString()}, tooLarge.out(), tooLarge.err(), NOW, List::of);

        assertThat(List.of(usageExit, missingExit, tooLargeExit))
                .containsOnly(CapabilityStudioStageAcceptanceCli.EXIT_INVALID);
        assertThat(usage.text()).contains("USAGE");
        assertThat(missing.text()).contains("READ").doesNotContain(missingPath);
        assertThat(tooLarge.text()).contains("READ").doesNotContain(oversized.toString());
    }

    @Test
    void providerLoaderFailureIsAStableConfigurationError() throws Exception {
        String secret = "loader-internal-secret";
        Output output = new Output();

        int exit = run(write("pass.json", passResult().toString()), () -> {
            throw new IllegalStateException(secret);
        }, output);

        assertThat(exit).isEqualTo(CapabilityStudioStageAcceptanceCli.EXIT_INVALID);
        assertThat(output.text()).contains("PROVIDER_CONFIGURATION").doesNotContain(secret);
    }

    @Test
    void providerDiscoveryDependencyOutagesBlockThroughDirectAndWrappedCauses()
            throws Exception {
        Path artifact = write("discovery-outage.json", passResult().toString());
        List<RuntimeException> failures = List.of(
                new CapabilityStudioStageAcceptanceAuthorityProvider
                        .DeploymentUnavailableException(),
                new IllegalStateException("wrapped-discovery-secret",
                        new CapabilityStudioStageAcceptanceAuthorityProvider
                                .DeploymentUnavailableException()));
        for (RuntimeException failure : failures) {
            Output output = new Output();
            int exit = run(artifact, () -> {
                throw failure;
            }, output);
            assertThat(exit).isEqualTo(CapabilityStudioStageAcceptanceCli.EXIT_NOT_ACCEPTED);
            assertThat(output.text()).contains(
                    "outcome=BLOCKED", "FORMAL_TARGET_BINDING_UNAVAILABLE")
                    .doesNotContain("wrapped-discovery-secret");
        }

        Output serviceLoaderOutput = new Output();
        int serviceLoaderExit = run(artifact, () -> {
            throw new ServiceConfigurationError("service-loader-secret",
                    new CapabilityStudioStageAcceptanceAuthorityProvider
                            .DeploymentUnavailableException());
        }, serviceLoaderOutput);
        assertThat(serviceLoaderExit)
                .isEqualTo(CapabilityStudioStageAcceptanceCli.EXIT_NOT_ACCEPTED);
        assertThat(serviceLoaderOutput.text()).contains(
                "outcome=BLOCKED", "FORMAL_TARGET_BINDING_UNAVAILABLE")
                .doesNotContain("service-loader-secret");

        Output malformedOutput = new Output();
        int malformedExit = run(artifact, () -> {
            throw new ServiceConfigurationError("malformed-loader-secret");
        }, malformedOutput);
        assertThat(malformedExit).isEqualTo(CapabilityStudioStageAcceptanceCli.EXIT_INVALID);
        assertThat(malformedOutput.text()).contains("PROVIDER_CONFIGURATION")
                .doesNotContain("malformed-loader-secret");
    }

    @Test
    void isolatesProviderDiscoveryAccessorsAndAuthorityCallbacks() throws Exception {
        String secret = "capability-studio-provider-secret";
        ObjectNode result = passResult();
        Path artifact = write("noisy-provider.json", result.toString());
        Output output = new Output();
        ByteArrayOutputStream capturedOutBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream capturedErrBytes = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        PrintStream capturedOut = new PrintStream(capturedOutBytes, true, StandardCharsets.UTF_8);
        PrintStream capturedErr = new PrintStream(capturedErrBytes, true, StandardCharsets.UTF_8);

        try {
            System.setOut(capturedOut);
            System.setErr(capturedErr);
            int exit = run(artifact, () -> {
                System.out.println(secret + " discovery-out");
                System.err.println(secret + " discovery-err");
                return List.of(new NoisyProvider(result, secret));
            }, output);

            assertThat(exit).isEqualTo(CapabilityStudioStageAcceptanceCli.EXIT_ACCEPTED);
            assertThat(output.text()).doesNotContain(secret);
            assertThat(capturedOutBytes.toString(StandardCharsets.UTF_8)).doesNotContain(secret);
            assertThat(capturedErrBytes.toString(StandardCharsets.UTF_8)).doesNotContain(secret);
            assertThat(System.out).isSameAs(capturedOut);
            assertThat(System.err).isSameAs(capturedErr);
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
        assertThat(System.out).isSameAs(originalOut);
        assertThat(System.err).isSameAs(originalErr);
    }

    @Test
    void restoresGlobalStreamsWithoutConvertingProviderErrors() throws Exception {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        AssertionError providerError = new AssertionError("provider-fatal-error");

        assertThatThrownBy(() -> run(write("provider-error.json", passResult().toString()), () -> {
            System.out.println("must-be-discarded");
            System.err.println("must-also-be-discarded");
            throw providerError;
        }, new Output())).isSameAs(providerError);

        assertThat(System.out).isSameAs(originalOut);
        assertThat(System.err).isSameAs(originalErr);
    }

    @Test
    void evidencePublicationProvisioningCliCreatesFixedDeclarationAndLock() throws Exception {
        Path publicationParent = Files.createDirectory(
                temporaryDirectory.toRealPath().resolve("provisioned-publication"));
        Files.setPosixFilePermissions(publicationParent,
                PosixFilePermissions.fromString("rwx------"));
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = CapabilityStudioExecutionLeaseEvidencePublicationProvisioningCli.run(
                new String[]{"--publication-parent", publicationParent.toString(),
                        "--publication-nonce", fingerprint('e')},
                new PrintStream(output, true, StandardCharsets.UTF_8));

        assertThat(exit).isZero();
        var declaration = CapabilityStudioExecutionLeaseEvidencePublication.readExisting(
                publicationParent);
        assertThat(declaration.transcriptRelativePath()).isEqualTo(
                CapabilityStudioExecutionLeaseEvidencePublication.TRANSCRIPT_FILE);
        assertThat(declaration.evidenceTransactionId())
                .matches("sha256:[0-9a-f]{64}");
        assertThat(output.toString(StandardCharsets.UTF_8)).isEqualTo(
                "PROVISIONED status=PROVISIONED publicationFingerprint="
                        + declaration.publicationFingerprint()
                        + " ownerBootstrapFingerprint="
                        + declaration.ownerBootstrapFingerprint()
                        + " lockRawFingerprint=" + declaration.lockRawFingerprint()
                        + " reasonCode=RG.CAPABILITY_STUDIO."
                        + "EXECUTION_LEASE_EVIDENCE_PUBLICATION_CLI.PROVISIONED\n");
        assertThat(publicationParent.resolve(
                CapabilityStudioExecutionLeaseEvidencePublication
                        .PUBLICATION_DECLARATION_FILE)).isRegularFile();
        assertThat(publicationParent.resolve(
                CapabilityStudioExecutionLeaseEvidencePublication.PUBLICATION_LOCK_FILE))
                .isRegularFile();
    }

    @Test
    void publicationDeclarationAdmitsOneFixedTransactionPerPrivateParent()
            throws Exception {
        ObjectNode result = passResult();
        Path stageA = write("single-parent-stage-a.json", result.toString());
        Path parentA = Files.createDirectory(
                temporaryDirectory.toRealPath().resolve("single-parent-a"));
        Files.setPosixFilePermissions(parentA,
                PosixFilePermissions.fromString("rwx------"));
        var declarationA = CapabilityStudioExecutionLeaseEvidencePublication.provision(
                parentA, fingerprint('a'));
        Path outputA = parentA.resolve(declarationA.transcriptRelativePath());
        Provider ordinaryA = acceptingProvider(result);
        var formalA = ordinaryA.formalTargetBoundAuthorityBinding();
        var evidenceA = positiveEvidenceProvider(ordinaryA, formalA);
        AtomicInteger providerLoads = new AtomicInteger();
        CapabilityStudioStageAcceptanceCli.ProviderSource sourceA = () -> {
            providerLoads.incrementAndGet();
            return List.of(evidenceA);
        };

        ByteArrayOutputStream committedOut = new ByteArrayOutputStream();
        assertThat(CapabilityStudioExecutionLeaseEvidenceCli.run(
                new String[]{stageA.toString(), outputA.toString()},
                new PrintStream(committedOut, true, StandardCharsets.UTF_8), System.err,
                NOW, sourceA, formalA.fingerprint(), declarationA.publicationFingerprint()))
                .as(committedOut.toString(StandardCharsets.UTF_8)).isZero();
        assertThat(committedOut.toString(StandardCharsets.UTF_8))
                .contains("evidencePublicationStatus=COMMITTED");
        int loadsAfterCommit = providerLoads.get();
        var verifiedA = CapabilityStudioExecutionLeaseEvidenceBundleVerifier.verify(
                outputA, rawFingerprint(Files.readAllBytes(stageA)), formalA.fingerprint(),
                declarationA.publicationFingerprint());
        assertThat(verifiedA.evidenceTransactionId())
                .isEqualTo(declarationA.evidenceTransactionId());

        Map<String, UnknownTreeEntry> beforeWrongOutput = observeUnknownTree(parentA);
        ByteArrayOutputStream wrongOutput = new ByteArrayOutputStream();
        assertThat(CapabilityStudioExecutionLeaseEvidenceCli.run(
                new String[]{stageA.toString(), parentA.resolve("second.json").toString()},
                new PrintStream(wrongOutput, true, StandardCharsets.UTF_8), System.err,
                NOW, sourceA, formalA.fingerprint(), declarationA.publicationFingerprint()))
                .isEqualTo(2);
        assertThat(wrongOutput.toString(StandardCharsets.UTF_8))
                .contains("PUBLICATION_INVALID");
        assertThat(providerLoads).hasValue(loadsAfterCommit);
        assertThat(observeUnknownTree(parentA)).isEqualTo(beforeWrongOutput);

        Path stageB = write("single-parent-stage-b.json",
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(result));
        Map<String, UnknownTreeEntry> beforeWrongRequest = observeUnknownTree(parentA);
        ByteArrayOutputStream wrongRequest = new ByteArrayOutputStream();
        assertThat(CapabilityStudioExecutionLeaseEvidenceCli.run(
                new String[]{stageB.toString(), outputA.toString()},
                new PrintStream(wrongRequest, true, StandardCharsets.UTF_8), System.err,
                NOW, sourceA, formalA.fingerprint(), declarationA.publicationFingerprint()))
                .isEqualTo(2);
        assertThat(wrongRequest.toString(StandardCharsets.UTF_8))
                .contains("RECOVERY_INVALID");
        assertThat(providerLoads).hasValue(loadsAfterCommit);
        assertThat(observeUnknownTree(parentA)).isEqualTo(beforeWrongRequest);

        ByteArrayOutputStream recoveredOut = new ByteArrayOutputStream();
        assertThat(CapabilityStudioExecutionLeaseEvidenceCli.run(
                new String[]{stageA.toString(), outputA.toString()},
                new PrintStream(recoveredOut, true, StandardCharsets.UTF_8), System.err,
                NOW.plusSeconds(1), sourceA, formalA.fingerprint(),
                declarationA.publicationFingerprint())).isZero();
        assertThat(recoveredOut.toString(StandardCharsets.UTF_8))
                .contains("evidencePublicationStatus=RECOVERED");
        assertThat(providerLoads).hasValue(loadsAfterCommit);

        Path parentB = Files.createDirectory(
                temporaryDirectory.toRealPath().resolve("single-parent-b"));
        Files.setPosixFilePermissions(parentB,
                PosixFilePermissions.fromString("rwx------"));
        var declarationB = CapabilityStudioExecutionLeaseEvidencePublication.provision(
                parentB, fingerprint('b'));
        Path outputB = parentB.resolve(declarationB.transcriptRelativePath());
        Provider ordinaryB = acceptingProvider(result);
        var formalB = ordinaryB.formalTargetBoundAuthorityBinding();
        ByteArrayOutputStream independentOut = new ByteArrayOutputStream();
        assertThat(CapabilityStudioExecutionLeaseEvidenceCli.run(
                new String[]{stageB.toString(), outputB.toString()},
                new PrintStream(independentOut, true, StandardCharsets.UTF_8), System.err,
                NOW, () -> List.of(positiveEvidenceProvider(ordinaryB, formalB)),
                formalB.fingerprint(), declarationB.publicationFingerprint()))
                .as(independentOut.toString(StandardCharsets.UTF_8)).isZero();
        CapabilityStudioExecutionLeaseEvidenceBundleVerifier.verify(
                outputB, rawFingerprint(Files.readAllBytes(stageB)), formalB.fingerprint(),
                declarationB.publicationFingerprint());

        Path copyParent = Files.createDirectory(
                temporaryDirectory.toRealPath().resolve("single-parent-copy"));
        Files.setPosixFilePermissions(copyParent,
                PosixFilePermissions.fromString("rwx------"));
        var copyDeclaration = CapabilityStudioExecutionLeaseEvidencePublication.provision(
                copyParent, fingerprint('c'));
        Path copied = copyParent.resolve(copyDeclaration.transcriptRelativePath());
        Files.copy(outputA, copied);
        Files.setPosixFilePermissions(copied,
                PosixFilePermissions.fromString("r--------"));
        Map<String, UnknownTreeEntry> beforeCopyVerify = observeUnknownTree(copyParent);
        assertThatThrownBy(() -> CapabilityStudioExecutionLeaseEvidenceBundleVerifier.verify(
                copied, rawFingerprint(Files.readAllBytes(stageA)), formalA.fingerprint(),
                copyDeclaration.publicationFingerprint()))
                .isInstanceOf(CapabilityStudioExecutionLeaseEvidenceBundleVerifier
                        .VerificationException.class);
        assertThat(observeUnknownTree(copyParent)).isEqualTo(beforeCopyVerify);
    }

    @Test
    void evidenceCliWithoutProvisioningIsUnavailableAndDoesNotWrite() throws Exception {
        ObjectNode result = passResult();
        Path artifact = write("unprovisioned-evidence.json", result.toString());
        Path publicationParent = Files.createDirectory(
                temporaryDirectory.toRealPath().resolve("unprovisioned-publication"));
        Files.setPosixFilePermissions(publicationParent,
                PosixFilePermissions.fromString("rwx------"));
        Path output = publicationParent.resolve(
                CapabilityStudioExecutionLeaseEvidencePublication.TRANSCRIPT_FILE);
        AtomicInteger providerLoads = new AtomicInteger();
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();

        int exit = CapabilityStudioExecutionLeaseEvidenceCli.run(
                new String[]{artifact.toString(), output.toString()},
                new PrintStream(stdout, true, StandardCharsets.UTF_8), System.err, NOW,
                () -> {
                    providerLoads.incrementAndGet();
                    return List.of();
                }, fingerprint('a'), fingerprint('e'));

        assertThat(exit).isEqualTo(CapabilityStudioStageAcceptanceCli.EXIT_NOT_ACCEPTED);
        assertThat(stdout.toString(StandardCharsets.UTF_8)).isEqualTo(
                "NOT_ACCEPTED outcome=BLOCKED reasonCode=RG.CAPABILITY_STUDIO."
                        + "EXECUTION_LEASE_EVIDENCE_CLI.PUBLICATION_UNAVAILABLE\n");
        assertThat(providerLoads).hasValue(0);
        try (var children = Files.list(publicationParent)) {
            assertThat(children.toList()).isEmpty();
        }
    }

    @Test
    void provisionedEvidenceCliAndOfflineBundleVerifierSucceed() throws Exception {
        ObjectNode result = passResult();
        Path artifact = write("provisioned-evidence.json", result.toString());
        Path publicationParent = Files.createDirectory(
                temporaryDirectory.toRealPath().resolve("evidence-publication"));
        Files.setPosixFilePermissions(publicationParent,
                PosixFilePermissions.fromString("rwx------"));
        var declaration = CapabilityStudioExecutionLeaseEvidencePublication.provision(
                publicationParent, fingerprint('e'));
        Path output = publicationParent.resolve(
                CapabilityStudioExecutionLeaseEvidencePublication.TRANSCRIPT_FILE);
        Provider ordinary = acceptingProvider(result);
        var formal = ordinary.formalTargetBoundAuthorityBinding();
        var evidenceProvider = positiveEvidenceProvider(ordinary, formal);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();

        int exit = CapabilityStudioExecutionLeaseEvidenceCli.run(
                new String[]{artifact.toString(), output.toString()},
                new PrintStream(stdout, true, StandardCharsets.UTF_8), System.err, NOW,
                () -> List.of(evidenceProvider), formal.fingerprint(),
                declaration.publicationFingerprint());

        assertThat(exit).as(stdout.toString(StandardCharsets.UTF_8)).isZero();
        assertThat(stdout.toString(StandardCharsets.UTF_8))
                .contains("evidencePublicationStatus=COMMITTED");
        var transcript = CapabilityStudioExecutionLeaseTranscript.verify(
                Files.readAllBytes(output));
        var verified = CapabilityStudioExecutionLeaseEvidenceBundleVerifier.verify(
                output, rawFingerprint(Files.readAllBytes(artifact)), formal.fingerprint(),
                declaration.publicationFingerprint());
        assertThat(verified.transcriptFingerprint())
                .isEqualTo(transcript.transcriptFingerprint());
        assertThat(verified.leaseReceiptFingerprint())
                .isEqualTo(transcript.executionLeaseReceipt().fingerprint());
    }

    @TestFactory
    Stream<DynamicTest> evidenceExecutePreservesEveryUnknownDeterministicObject() {
        return Stream.of(UnknownEvidenceObject.values()).flatMap(object ->
                Stream.of(UnknownVariant.values()).map(variant -> DynamicTest.dynamicTest(
                        object + "_" + variant,
                        () -> assertUnknownExecutePreserved(object, variant))));
    }

    @TestFactory
    Stream<DynamicTest> evidenceProvisioningPreservesUnknownFixedObjects() {
        return Stream.of(UnknownEvidenceObject.OWNER_BOOTSTRAP,
                        UnknownEvidenceObject.PROVISION_DECLARATION,
                        UnknownEvidenceObject.PUBLICATION_LOCK)
                .flatMap(object -> Stream.of(UnknownVariant.values()).map(variant ->
                        DynamicTest.dynamicTest(object + "_" + variant,
                                () -> assertUnknownProvisioningPreserved(object, variant))));
    }

    @TestFactory
    Stream<DynamicTest> evidenceWrongUidMetadataIsInvalidAndSideEffectFree() {
        return Stream.of(UnknownEvidenceObject.values()).map(object -> DynamicTest.dynamicTest(
                object.toString(), () -> assertWrongUidMetadataPreserved(object)));
    }

    @Test
    void evidenceCliCommitsThenRecoversOneWitnessAndImmutableTranscript() throws Exception {
        ObjectNode result = passResult();
        Path artifact = write("evidence-pass.json", result.toString());
        Path evidenceDirectory = Files.createDirectory(
                temporaryDirectory.toRealPath().resolve("evidence-output"));
        Files.setPosixFilePermissions(evidenceDirectory,
                PosixFilePermissions.fromString("rwx------"));
        Path transcriptOutput = evidenceDirectory.resolve(
                CapabilityStudioExecutionLeaseEvidencePublication.TRANSCRIPT_FILE);
        var publication = CapabilityStudioExecutionLeaseEvidencePublication.provision(
                evidenceDirectory, fingerprint('e'));
        System.setProperty(CapabilityStudioExecutionLeaseEvidencePublication
                .EXPECTED_PUBLICATION_FINGERPRINT_ENV,
                publication.publicationFingerprint());
        Provider ordinary = acceptingProvider(result);
        var formal = ordinary.formalTargetBoundAuthorityBinding();
        AtomicReference<CapabilityStudioStageAcceptanceAuthorityProvider
                .EvidenceExecutionLeaseCommitResult> durable = new AtomicReference<>();
        AtomicReference<Boolean> committed = new AtomicReference<>(false);
        AtomicReference<Boolean> failAfterOnce = new AtomicReference<>(false);
        AtomicReference<Boolean> failBeforeLeaseOnce = new AtomicReference<>(false);
        java.util.List<Long> attemptGenerations = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.List<Instant> attemptTimes = new java.util.concurrent.CopyOnWriteArrayList<>();
        CapabilityStudioStageAcceptanceAuthorityProvider evidenceProvider =
                new CapabilityStudioStageAcceptanceAuthorityProvider() {
                    @Override
                    public FormalEvidenceAuthorityBinding formalEvidenceAuthorityBinding() {
                        return new FormalEvidenceAuthorityBinding(formal, fingerprint('6'),
                                (phase, transactionId) -> {
                                    return evidenceObservation(
                                            phase, transactionId, committed.get());
                                },
                                new CapabilityStudioStageAcceptanceAuthorityProvider
                                        .EvidenceExecutionLeaseTransactionAuthority() {
                            @Override
                            public CapabilityStudioStageAcceptanceAuthorityProvider
                                    .EvidenceExecutionLeaseTransactionResult commit(
                                    CapabilityStudioStageAcceptanceAuthorityProvider
                                            .EvidenceExecutionLeaseAttempt attempt,
                                    CapabilityStudioStageAcceptanceAuthorityProvider
                                            .EvidenceTransactionJournal journal) {
                                var request = attempt.request();
                                var current = evidenceObservation(
                                        CapabilityStudioDeploymentStateObservation.Phase.BEFORE,
                                        attempt.evidenceTransactionId(), committed.get());
                                var before = journal.prepareBefore(attempt, current);
                                attemptGenerations.add(attempt.attemptGeneration());
                                attemptTimes.add(attempt.semanticVerificationTime());
                                if (failBeforeLeaseOnce.compareAndSet(true, false)) {
                                    throw new CapabilityStudioStageAcceptanceAuthorityProvider
                                            .DeploymentUnavailableException();
                                }
                                EvidenceExecutionLeaseCommitResult result;
                                    var existing = durable.get();
                                    if (existing != null) {
                                        result = new EvidenceExecutionLeaseCommitResult(
                                                ExecutionLeaseCommitStatus.RECOVERED,
                                                existing.receipt(), existing.transitionWitness(),
                                                "LEASE_RECOVERED");
                                    } else {
                                        var receipt = committedLease(request).receipt();
                                        var witness = new ExecutionLeaseTransitionWitness(
                                                fingerprint('6'),
                                                request.commitIdentityFingerprint(),
                                                receipt.fingerprint(), fingerprint('1'), 0, 0,
                                                fingerprint('2'), 0, fingerprint('3'),
                                                fingerprint('7'), fingerprint('4'), 1, 1,
                                                fingerprint('5'), 0, fingerprint('3'));
                                        result = new EvidenceExecutionLeaseCommitResult(
                                                ExecutionLeaseCommitStatus.COMMITTED, receipt,
                                                witness, "LEASE_COMMITTED");
                                        durable.set(result);
                                        committed.set(true);
                                    }
                                var after = evidenceObservation(
                                        CapabilityStudioDeploymentStateObservation.Phase.AFTER,
                                        attempt.evidenceTransactionId(), true);
                                if (failAfterOnce.compareAndSet(true, false)) {
                                    throw new CapabilityStudioStageAcceptanceAuthorityProvider
                                            .DeploymentUnavailableException();
                                }
                                journal.persistCommitted(attempt, before, after, result);
                                return new CapabilityStudioStageAcceptanceAuthorityProvider
                                        .EvidenceExecutionLeaseTransactionResult(
                                        before, after, result);
                            }

                            @Override
                            public EvidenceExecutionLeaseCommitResult recoverExisting(
                                    CapabilityStudioStageAcceptanceAuthorityProvider
                                            .ExecutionLeaseRequest request) {
                                var existing = durable.get();
                                return existing == null
                                        ? new EvidenceExecutionLeaseCommitResult(
                                        ExecutionLeaseCommitStatus.UNAVAILABLE,
                                        null, null, "LEASE_UNAVAILABLE")
                                        : new EvidenceExecutionLeaseCommitResult(
                                        ExecutionLeaseCommitStatus.RECOVERED,
                                        existing.receipt(), existing.transitionWitness(),
                                        "LEASE_RECOVERED");
                            }
                        });
                    }

                    @Override
                    public CapabilityStudioStageAcceptanceAuthorityProvider
                            .FormalEvidenceRecoveryBinding formalEvidenceRecoveryBinding() {
                        return new CapabilityStudioStageAcceptanceAuthorityProvider
                                .FormalEvidenceRecoveryBinding(fingerprint('6'),
                                (phase, transactionId) -> evidenceObservation(
                                        phase, transactionId, committed.get()),
                                (attempt, journal) -> {
                                    var existing = durable.get();
                                    if (existing == null) {
                                        journal.closeAbsent(attempt);
                                        return new CapabilityStudioStageAcceptanceAuthorityProvider
                                                .ExistingEvidenceRecoveryResult(
                                                CapabilityStudioStageAcceptanceAuthorityProvider
                                                        .ExistingEvidenceRecoveryStatus.ABSENT,
                                                null, null, "LEASE_ABSENT");
                                    }
                                    boolean exact = existing.receipt().requestFingerprint()
                                            .equals(attempt.request()
                                                    .commitIdentityFingerprint());
                                    var historicalBefore = exact ? evidenceObservation(
                                            CapabilityStudioDeploymentStateObservation.Phase.BEFORE,
                                            attempt.evidenceTransactionId(), false) : null;
                                    var historicalAfter = exact ? evidenceObservation(
                                            CapabilityStudioDeploymentStateObservation.Phase.AFTER,
                                            attempt.evidenceTransactionId(), true) : null;
                                    return new CapabilityStudioStageAcceptanceAuthorityProvider
                                            .ExistingEvidenceRecoveryResult(
                                            exact
                                                    ? CapabilityStudioStageAcceptanceAuthorityProvider
                                                    .ExistingEvidenceRecoveryStatus.FOUND
                                                    : CapabilityStudioStageAcceptanceAuthorityProvider
                                                    .ExistingEvidenceRecoveryStatus.CONFLICT,
                                            exact ? existing.receipt() : null,
                                            exact ? existing.transitionWitness() : null,
                                            historicalBefore, historicalAfter,
                                            exact ? "LEASE_RECOVERED" : "LEASE_CONFLICT");
                                });
                    }

                    @Override
                    public AuthorityBinding authorityBinding() {
                        return ordinary.authorityBinding();
                    }

                    @Override
                    public EvidenceResolver evidenceResolver() {
                        return ordinary.resolver();
                    }

                    @Override
                    public CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceIssuerPolicy
                            evidenceIssuerPolicy() {
                        return ordinary.issuer();
                    }

                    @Override
                    public CapabilityStudioStageAcceptanceAuthorityVerifier.OwnerAuthority
                            ownerAuthority() {
                        return ordinary.owner();
                    }
                };
        AtomicInteger providerLoads = new AtomicInteger();
        CapabilityStudioStageAcceptanceCli.ProviderSource evidenceSource = () -> {
            providerLoads.incrementAndGet();
            return List.of(evidenceProvider);
        };
        ByteArrayOutputStream firstOut = new ByteArrayOutputStream();
        int first = CapabilityStudioExecutionLeaseEvidenceCli.run(
                new String[]{artifact.toString(), transcriptOutput.toString()},
                new PrintStream(firstOut, true, StandardCharsets.UTF_8), System.err, NOW,
                evidenceSource, formal.fingerprint());
        assertThat(first).as(firstOut.toString(StandardCharsets.UTF_8)).isZero();
        int loadsAfterCommit = providerLoads.get();
        byte[] persisted = Files.readAllBytes(transcriptOutput);
        var transcript = CapabilityStudioExecutionLeaseTranscript.verify(persisted);

        ByteArrayOutputStream retryOut = new ByteArrayOutputStream();
        int retry = CapabilityStudioExecutionLeaseEvidenceCli.run(
                new String[]{artifact.toString(), transcriptOutput.toString()},
                new PrintStream(retryOut, true, StandardCharsets.UTF_8), System.err, NOW,
                evidenceSource, formal.fingerprint());

        assertThat(first).isZero();
        assertThat(retry).isZero();
        assertThat(providerLoads).hasValue(loadsAfterCommit);
        assertThat(firstOut.toString(StandardCharsets.UTF_8))
                .contains("evidencePublicationStatus=COMMITTED", "commitStatus=COMMITTED");
        assertThat(retryOut.toString(StandardCharsets.UTF_8))
                .contains("evidencePublicationStatus=RECOVERED", "commitStatus=RECOVERED");
        assertThat(Files.readAllBytes(transcriptOutput)).isEqualTo(persisted);
        assertThat(transcript.executionLeaseTransitionWitness())
                .isEqualTo(durable.get().transitionWitness());
        assertThat(transcript.beforeStateObservation().generation()).isZero();
        assertThat(transcript.afterStateObservation().generation()).isEqualTo(1);
        String firstTransaction = publication.evidenceTransactionId();
        Path firstWrapper = evidenceDirectory.resolve("." + transcriptOutput.getFileName()
                + "." + firstTransaction.substring("sha256:".length()) + ".evidence-v3");
        Path retainedTranscript = firstWrapper.resolve("committed-transcript-v1.json");
        Path commitManifest = firstWrapper.resolve("commit-manifest-v1.json");
        assertThat(retainedTranscript).isRegularFile();
        assertThat(commitManifest).isRegularFile();
        assertThat(Files.readAttributes(retainedTranscript,
                java.nio.file.attribute.BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS).fileKey()).isEqualTo(
                Files.readAttributes(transcriptOutput,
                        java.nio.file.attribute.BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS).fileKey());
        assertThat(((Number) Files.getAttribute(retainedTranscript, "unix:nlink",
                LinkOption.NOFOLLOW_LINKS)).longValue()).isEqualTo(2);
        var verifiedBundle = CapabilityStudioExecutionLeaseEvidenceBundleVerifier.verify(
                transcriptOutput, rawFingerprint(Files.readAllBytes(artifact)),
                formal.fingerprint(), publication.publicationFingerprint());
        assertThat(verifiedBundle.transcriptFingerprint())
                .isEqualTo(transcript.transcriptFingerprint());
        assertThat(verifiedBundle.leaseReceiptFingerprint())
                .isEqualTo(transcript.executionLeaseReceipt().fingerprint());
        ByteArrayOutputStream bundleVerifyOut = new ByteArrayOutputStream();
        assertThat(CapabilityStudioExecutionLeaseEvidenceBundleVerifyCli.run(new String[]{
                "--transcript", transcriptOutput.toString(),
                "--expected-stage-result-raw-fingerprint",
                rawFingerprint(Files.readAllBytes(artifact)),
                "--expected-formal-outer-fingerprint", formal.fingerprint(),
                "--expected-publication-fingerprint", publication.publicationFingerprint()
        }, new PrintStream(bundleVerifyOut, true, StandardCharsets.UTF_8))).isZero();
        assertThat(bundleVerifyOut.toString(StandardCharsets.UTF_8))
                .contains("verificationScope=DURABLE_WRAPPER")
                .endsWith("reasonCode="
                        + CapabilityStudioExecutionLeaseEvidenceBundleVerifyCli.VERIFIED_REASON
                        + "\n");
        ByteArrayOutputStream semanticOnlyOut = new ByteArrayOutputStream();
        assertThat(CapabilityStudioExecutionLeaseTranscriptVerifyCli.run(
                new String[]{transcriptOutput.toString()},
                new PrintStream(semanticOnlyOut, true, StandardCharsets.UTF_8))).isZero();
        assertThat(semanticOnlyOut.toString(StandardCharsets.UTF_8))
                .contains("verificationScope=SEMANTIC_ONLY");

        Path copiedFinal = evidenceDirectory.resolve("copied-final-only.json");
        Files.copy(transcriptOutput, copiedFinal);
        Files.setPosixFilePermissions(copiedFinal,
                PosixFilePermissions.fromString("r--------"));
        assertThatThrownBy(() -> CapabilityStudioExecutionLeaseEvidenceBundleVerifier.verify(
                copiedFinal, rawFingerprint(Files.readAllBytes(artifact)),
                formal.fingerprint()))
                .isInstanceOf(CapabilityStudioExecutionLeaseEvidenceBundleVerifier
                        .VerificationException.class);

        byte[] exactManifest = Files.readAllBytes(commitManifest);
        ObjectNode malformedManifest = (ObjectNode) JSON.readTree(exactManifest);
        malformedManifest.put("unknownCredential", "UPPERCASE_SECRET_PAYLOAD");
        Files.setPosixFilePermissions(commitManifest,
                PosixFilePermissions.fromString("rw-------"));
        Files.write(commitManifest, JSON.writeValueAsBytes(malformedManifest));
        Files.setPosixFilePermissions(commitManifest,
                PosixFilePermissions.fromString("r--------"));
        assertThatThrownBy(() -> CapabilityStudioExecutionLeaseEvidenceBundleVerifier.verify(
                transcriptOutput, rawFingerprint(Files.readAllBytes(artifact)),
                formal.fingerprint()))
                .isInstanceOfSatisfying(
                        CapabilityStudioExecutionLeaseEvidenceBundleVerifier
                                .VerificationException.class,
                        failure -> assertThat(failure.failureKind()).isEqualTo(
                                CapabilityStudioStageAcceptanceAuthorityProvider
                                        .EvidenceFailureKind.INVALID));
        ByteArrayOutputStream malformedBundleOut = new ByteArrayOutputStream();
        assertThat(CapabilityStudioExecutionLeaseEvidenceBundleVerifyCli.run(new String[]{
                "--transcript", transcriptOutput.toString(),
                "--expected-stage-result-raw-fingerprint",
                rawFingerprint(Files.readAllBytes(artifact)),
                "--expected-formal-outer-fingerprint", formal.fingerprint()
        }, new PrintStream(malformedBundleOut, true, StandardCharsets.UTF_8)))
                .isEqualTo(2);
        assertThat(malformedBundleOut.toString(StandardCharsets.UTF_8)).isEqualTo(
                "INVALID errorCode=RG.CAPABILITY_STUDIO."
                        + "EXECUTION_LEASE_EVIDENCE_BUNDLE_VERIFY_CLI.INVALID\n")
                .doesNotContain("UPPERCASE_SECRET_PAYLOAD", commitManifest.toString());
        Files.setPosixFilePermissions(commitManifest,
                PosixFilePermissions.fromString("rw-------"));
        Files.write(commitManifest, exactManifest);
        Files.setPosixFilePermissions(commitManifest,
                PosixFilePermissions.fromString("r--------"));
        ByteArrayOutputStream expiredRetryOut = new ByteArrayOutputStream();
        int expiredRetry = CapabilityStudioExecutionLeaseEvidenceCli.run(
                new String[]{artifact.toString(), transcriptOutput.toString()},
                new PrintStream(expiredRetryOut, true, StandardCharsets.UTF_8), System.err,
                NOW.plusSeconds(86_400), () -> {
                    throw new AssertionError("recovery-first loaded Provider");
                }, formal.fingerprint());
        assertThat(expiredRetry).isZero();
        assertThat(expiredRetryOut.toString(StandardCharsets.UTF_8))
                .contains("evidencePublicationStatus=RECOVERED",
                        "commitStatus=RECOVERED");

        Path driftedStage = write("evidence-pass-reformatted.json",
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(result));
        ByteArrayOutputStream driftedOut = new ByteArrayOutputStream();
        int drifted = CapabilityStudioExecutionLeaseEvidenceCli.run(
                new String[]{driftedStage.toString(), transcriptOutput.toString()},
                new PrintStream(driftedOut, true, StandardCharsets.UTF_8), System.err,
                NOW, () -> {
                    throw new AssertionError("raw-drift recovery loaded Provider");
                }, formal.fingerprint());
        assertThat(drifted).isEqualTo(2);
        assertThat(driftedOut.toString(StandardCharsets.UTF_8))
                .startsWith("INVALID errorCode=");

        Path unknownOutput = evidenceDirectory.resolve("unknown-final.json");
        Files.writeString(unknownOutput, "UNKNOWN-CREDENTIAL-MATERIAL");
        Files.setPosixFilePermissions(unknownOutput,
                PosixFilePermissions.fromString("r--------"));
        byte[] unknownBytes = Files.readAllBytes(unknownOutput);
        ByteArrayOutputStream unknownOut = new ByteArrayOutputStream();
        int unknown = CapabilityStudioExecutionLeaseEvidenceCli.run(
                new String[]{artifact.toString(), unknownOutput.toString()},
                new PrintStream(unknownOut, true, StandardCharsets.UTF_8), System.err,
                NOW, evidenceSource, formal.fingerprint());
        assertThat(unknown).isEqualTo(2);
        assertThat(Files.readAllBytes(unknownOutput)).isEqualTo(unknownBytes);
        assertThat(unknownOut.toString(StandardCharsets.UTF_8))
                .doesNotContain("UNKNOWN-CREDENTIAL-MATERIAL");

        Path unknownStagingOutput = evidenceDirectory.resolve("unknown-staging.json");
        String unknownTransaction = publication.evidenceTransactionId();
        Path unknownStaging = evidenceDirectory.resolve("."
                + unknownStagingOutput.getFileName() + "."
                + unknownTransaction.substring("sha256:".length()) + ".evidence-v3");
        Files.createDirectory(unknownStaging);
        Files.setPosixFilePermissions(unknownStaging,
                PosixFilePermissions.fromString("rwx------"));
        Files.writeString(unknownStaging.resolve("unknown.part"), "DO-NOT-DELETE");
        Map<String, byte[]> unknownClosure;
        try (var children = Files.list(unknownStaging)) {
            unknownClosure = children.collect(java.util.stream.Collectors.toMap(
                        path -> path.getFileName().toString(), path -> {
                            try {
                                return Files.readAllBytes(path);
                            } catch (IOException failure) {
                                throw new java.io.UncheckedIOException(failure);
                            }
                        }));
        }
        ByteArrayOutputStream unknownStagingOut = new ByteArrayOutputStream();
        int unknownStagingExit = CapabilityStudioExecutionLeaseEvidenceCli.run(
                new String[]{artifact.toString(), unknownStagingOutput.toString()},
                new PrintStream(unknownStagingOut, true, StandardCharsets.UTF_8), System.err,
                NOW, evidenceSource, formal.fingerprint());
        assertThat(unknownStagingExit).isEqualTo(2);
        for (Map.Entry<String, byte[]> entry : unknownClosure.entrySet()) {
            assertThat(Files.readAllBytes(unknownStaging.resolve(entry.getKey())))
                    .isEqualTo(entry.getValue());
        }

        durable.set(null);
        committed.set(false);
        Path crashParent = Files.createDirectory(
                temporaryDirectory.toRealPath().resolve("stdout-failure-publication"));
        Files.setPosixFilePermissions(crashParent,
                PosixFilePermissions.fromString("rwx------"));
        var crashPublication = CapabilityStudioExecutionLeaseEvidencePublication.provision(
                crashParent, fingerprint('b'));
        Path crashOutput = crashParent.resolve(crashPublication.transcriptRelativePath());
        PrintStream broken = new PrintStream(new OutputStream() {
            private int remaining = 17;

            @Override
            public void write(int value) throws IOException {
                if (remaining-- <= 0) {
                    throw new IOException("broken stdout");
                }
            }
        });
        int outputFailure = CapabilityStudioExecutionLeaseEvidenceCli.run(
                new String[]{artifact.toString(), crashOutput.toString()}, broken, System.err,
                NOW, evidenceSource, formal.fingerprint(),
                crashPublication.publicationFingerprint());
        ByteArrayOutputStream recoveredOut = new ByteArrayOutputStream();
        int recoveredAfterOutputFailure = CapabilityStudioExecutionLeaseEvidenceCli.run(
                new String[]{artifact.toString(), crashOutput.toString()},
                new PrintStream(recoveredOut, true, StandardCharsets.UTF_8), System.err,
                NOW, evidenceSource, formal.fingerprint(),
                crashPublication.publicationFingerprint());
        assertThat(outputFailure).isEqualTo(2);
        assertThat(crashOutput).exists();
        assertThat(recoveredAfterOutputFailure).isZero();
        assertThat(recoveredOut.toString(StandardCharsets.UTF_8))
                .contains("evidencePublicationStatus=RECOVERED", "commitStatus=RECOVERED");

        durable.set(null);
        committed.set(false);
        failAfterOnce.set(true);
        Path afterFailureParent = Files.createDirectory(
                temporaryDirectory.toRealPath().resolve("after-failure-publication"));
        Files.setPosixFilePermissions(afterFailureParent,
                PosixFilePermissions.fromString("rwx------"));
        var afterFailurePublication =
                CapabilityStudioExecutionLeaseEvidencePublication.provision(
                        afterFailureParent, fingerprint('c'));
        Path afterFailureOutput = afterFailureParent.resolve(
                afterFailurePublication.transcriptRelativePath());
        ByteArrayOutputStream blockedOut = new ByteArrayOutputStream();
        int afterFailure = CapabilityStudioExecutionLeaseEvidenceCli.run(
                new String[]{artifact.toString(), afterFailureOutput.toString()},
                new PrintStream(blockedOut, true, StandardCharsets.UTF_8), System.err,
                NOW, evidenceSource, formal.fingerprint(),
                afterFailurePublication.publicationFingerprint());
        assertThat(afterFailure).isEqualTo(
                CapabilityStudioStageAcceptanceCli.EXIT_NOT_ACCEPTED);
        assertThat(afterFailureOutput).doesNotExist();

        ByteArrayOutputStream afterRetryOut = new ByteArrayOutputStream();
        int afterRetry = CapabilityStudioExecutionLeaseEvidenceCli.run(
                new String[]{artifact.toString(), afterFailureOutput.toString()},
                new PrintStream(afterRetryOut, true, StandardCharsets.UTF_8), System.err,
                NOW.plusSeconds(86_400), evidenceSource, formal.fingerprint(),
                afterFailurePublication.publicationFingerprint());
        assertThat(afterRetry).isZero();
        assertThat(afterRetryOut.toString(StandardCharsets.UTF_8))
                .contains("commitStatus=RECOVERED");

        durable.set(null);
        committed.set(false);
        failBeforeLeaseOnce.set(true);
        attemptGenerations.clear();
        attemptTimes.clear();
        Path absentParent = Files.createDirectory(
                temporaryDirectory.toRealPath().resolve("absent-retry-publication"));
        Files.setPosixFilePermissions(absentParent,
                PosixFilePermissions.fromString("rwx------"));
        var absentPublication = CapabilityStudioExecutionLeaseEvidencePublication.provision(
                absentParent, fingerprint('d'));
        Path absentOutput = absentParent.resolve(absentPublication.transcriptRelativePath());
        ByteArrayOutputStream absentFirstOut = new ByteArrayOutputStream();
        int absentFirst = CapabilityStudioExecutionLeaseEvidenceCli.run(
                new String[]{artifact.toString(), absentOutput.toString()},
                new PrintStream(absentFirstOut, true, StandardCharsets.UTF_8), System.err,
                NOW, evidenceSource, formal.fingerprint(),
                absentPublication.publicationFingerprint());
        assertThat(absentFirst).isEqualTo(
                CapabilityStudioStageAcceptanceCli.EXIT_NOT_ACCEPTED);
        ByteArrayOutputStream absentRetryOut = new ByteArrayOutputStream();
        int absentRetry = CapabilityStudioExecutionLeaseEvidenceCli.run(
                new String[]{artifact.toString(), absentOutput.toString()},
                new PrintStream(absentRetryOut, true, StandardCharsets.UTF_8), System.err,
                NOW.plusSeconds(60), evidenceSource, formal.fingerprint(),
                absentPublication.publicationFingerprint());
        assertThat(absentRetry).isZero();
        assertThat(attemptGenerations).containsExactly(1L, 2L);
        assertThat(attemptTimes).containsExactly(NOW, NOW.plusSeconds(60));
        Path absentWrapper = absentParent.resolve("." + absentOutput.getFileName()
                + "." + absentPublication.evidenceTransactionId()
                .substring("sha256:".length()) + ".evidence-v3");
        assertThat(absentWrapper.resolve(
                "attempt-closure-v1-g00000000000000000001.json")).isRegularFile();
        assertThat(absentWrapper.resolve(
                "before-v2-g00000000000000000002.json")).isRegularFile();
        String absentManifest = Files.readString(
                absentWrapper.resolve("commit-manifest-v1.json"));
        assertThat(absentManifest).contains("\"attemptGeneration\":2",
                "\"previousAttemptClosureFingerprint\":\"sha256:");

        Path pseudoFinal = evidenceDirectory.resolve("self-consistent-unowned.json");
        Files.copy(absentOutput, pseudoFinal);
        Files.setPosixFilePermissions(pseudoFinal,
                PosixFilePermissions.fromString("r--------"));
        byte[] pseudoBytes = Files.readAllBytes(pseudoFinal);
        ByteArrayOutputStream pseudoOut = new ByteArrayOutputStream();
        assertThat(CapabilityStudioExecutionLeaseEvidenceCli.run(
                new String[]{artifact.toString(), pseudoFinal.toString()},
                new PrintStream(pseudoOut, true, StandardCharsets.UTF_8), System.err,
                NOW, evidenceSource, formal.fingerprint())).isEqualTo(2);
        assertThat(Files.readAllBytes(pseudoFinal)).isEqualTo(pseudoBytes);
        assertThat(pseudoOut.toString(StandardCharsets.UTF_8))
                .contains("PUBLICATION_INVALID").doesNotContain("result:test");

        Path emptyOutput = evidenceDirectory.resolve("preexisting-empty-wrapper.json");
        Path emptyWrapper = evidenceDirectory.resolve("." + emptyOutput.getFileName()
                + "." + firstTransaction.substring("sha256:".length()) + ".evidence-v3");
        Files.createDirectory(emptyWrapper);
        Files.setPosixFilePermissions(emptyWrapper,
                PosixFilePermissions.fromString("rwx------"));
        ByteArrayOutputStream emptyOut = new ByteArrayOutputStream();
        assertThat(CapabilityStudioExecutionLeaseEvidenceCli.run(
                new String[]{artifact.toString(), emptyOutput.toString()},
                new PrintStream(emptyOut, true, StandardCharsets.UTF_8), System.err,
                NOW, evidenceSource, formal.fingerprint())).isEqualTo(2);
        try (var children = Files.list(emptyWrapper)) {
            assertThat(children.toList()).isEmpty();
        }

        Path claimOutput = evidenceDirectory.resolve("wrong-owner-claim.json");
        Path wrongClaim = evidenceDirectory.resolve("." + claimOutput.getFileName()
                + "." + firstTransaction.substring("sha256:".length())
                + ".owner-claim-v3.json");
        Files.writeString(wrongClaim, "WRONG_OWNER_CREDENTIAL");
        Files.setPosixFilePermissions(wrongClaim,
                PosixFilePermissions.fromString("r--------"));
        byte[] wrongClaimBytes = Files.readAllBytes(wrongClaim);
        ByteArrayOutputStream claimOut = new ByteArrayOutputStream();
        assertThat(CapabilityStudioExecutionLeaseEvidenceCli.run(
                new String[]{artifact.toString(), claimOutput.toString()},
                new PrintStream(claimOut, true, StandardCharsets.UTF_8), System.err,
                NOW, evidenceSource, formal.fingerprint())).isEqualTo(2);
        assertThat(Files.readAllBytes(wrongClaim)).isEqualTo(wrongClaimBytes);
        assertThat(claimOut.toString(StandardCharsets.UTF_8))
                .doesNotContain("WRONG_OWNER_CREDENTIAL");

        for (String conflictingPart : java.util.List.of(
                ".owner-v3.json.part",
                ".before-v2-g00000000000000000001.json.part",
                ".committed-transcript-v1.json.part",
                ".commit-manifest-v1.json.part")) {
            durable.set(null);
            committed.set(false);
            String safe = conflictingPart.replace('.', '-');
            Path conflictParent = Files.createDirectory(temporaryDirectory.toRealPath()
                    .resolve("conflict" + safe + "-publication"));
            Files.setPosixFilePermissions(conflictParent,
                    PosixFilePermissions.fromString("rwx------"));
            var conflictPublication =
                    CapabilityStudioExecutionLeaseEvidencePublication.provision(
                            conflictParent, rawFingerprint(safe.getBytes(StandardCharsets.UTF_8)));
            Path conflictOutput = conflictParent.resolve(
                    conflictPublication.transcriptRelativePath());
            ByteArrayOutputStream seedOut = new ByteArrayOutputStream();
            assertThat(CapabilityStudioExecutionLeaseEvidenceCli.run(
                    new String[]{artifact.toString(), conflictOutput.toString()},
                    new PrintStream(seedOut, true, StandardCharsets.UTF_8), System.err,
                    NOW, evidenceSource, formal.fingerprint(),
                    conflictPublication.publicationFingerprint()))
                    .as(seedOut.toString(StandardCharsets.UTF_8)).isZero();
            Path wrapper = conflictParent.resolve("." + conflictOutput.getFileName()
                    + "." + conflictPublication.evidenceTransactionId()
                    .substring("sha256:".length())
                    + ".evidence-v3");
            Path conflict = wrapper.resolve(conflictingPart);
            Files.writeString(conflict, "UNKNOWN_PART_CREDENTIAL");
            Files.setPosixFilePermissions(conflict,
                    PosixFilePermissions.fromString("r--------"));
            Map<String, UnknownTreeEntry> beforeConflict = observeUnknownTree(conflictParent);
            ByteArrayOutputStream conflictOut = new ByteArrayOutputStream();
            assertThat(CapabilityStudioExecutionLeaseEvidenceCli.run(
                    new String[]{artifact.toString(), conflictOutput.toString()},
                    new PrintStream(conflictOut, true, StandardCharsets.UTF_8), System.err,
                    NOW, evidenceSource, formal.fingerprint(),
                    conflictPublication.publicationFingerprint()))
                    .as(conflictingPart + ": "
                            + conflictOut.toString(StandardCharsets.UTF_8)).isEqualTo(2);
            assertThat(observeUnknownTree(conflictParent)).isEqualTo(beforeConflict);
            assertThat(conflictOut.toString(StandardCharsets.UTF_8))
                    .doesNotContain("UNKNOWN_PART_CREDENTIAL");
        }
    }

    @Test
    void evidencePublicationInstallsOwnedFileAndPreservesConflicts()
            throws Exception {
        byte[] bytes = "payload-free-transcript".getBytes(StandardCharsets.UTF_8);
        Path sourceParent = Files.createDirectory(temporaryDirectory.resolve("source"));
        Path targetParent = Files.createDirectory(temporaryDirectory.resolve("target"));
        Path source = sourceParent.resolve("source.part");
        Path target = targetParent.resolve("target.json");
        CapabilityStudioExecutionLeaseEvidenceCli.installOwnedFile(source, target, bytes);
        assertThat(source).doesNotExist();
        assertThat(Files.readAllBytes(target)).isEqualTo(bytes);
        assertThat(((Number) Files.getAttribute(target, "unix:nlink",
                LinkOption.NOFOLLOW_LINKS)).longValue()).isEqualTo(1);

        Path bothParent = Files.createDirectory(temporaryDirectory.resolve("both"));
        Path sameSource = bothParent.resolve("same.part");
        Path sameTarget = bothParent.resolve("same.json");
        CapabilityStudioExecutionLeaseEvidenceCli.prepareOwnedSource(sameSource, bytes);
        Files.createLink(sameTarget, sameSource);
        CapabilityStudioExecutionLeaseEvidenceCli.installOwnedFile(
                sameSource, sameTarget, bytes);
        assertThat(sameSource).doesNotExist();
        assertThat(Files.readAllBytes(sameTarget)).isEqualTo(bytes);

        Path distinctSource = bothParent.resolve("distinct.part");
        Path distinctTarget = bothParent.resolve("distinct.json");
        CapabilityStudioExecutionLeaseEvidenceCli.prepareOwnedSource(distinctSource, bytes);
        CapabilityStudioExecutionLeaseEvidenceCli.prepareOwnedSource(distinctTarget, bytes);
        Object sourceKey = Files.readAttributes(distinctSource,
                java.nio.file.attribute.BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS).fileKey();
        Object targetKey = Files.readAttributes(distinctTarget,
                java.nio.file.attribute.BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS).fileKey();
        assertThatThrownBy(() -> CapabilityStudioExecutionLeaseEvidenceCli
                .installOwnedFile(distinctSource, distinctTarget, bytes))
                .isInstanceOfSatisfying(
                        CapabilityStudioExecutionLeaseEvidenceCli
                                .EvidenceInvalidException.class,
                        failure -> assertThat(failure.failureKind()).isEqualTo(
                                CapabilityStudioStageAcceptanceAuthorityProvider
                                        .EvidenceFailureKind.INVALID));
        assertThat(Files.readAttributes(distinctSource,
                java.nio.file.attribute.BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS).fileKey()).isEqualTo(sourceKey);
        assertThat(Files.readAttributes(distinctTarget,
                java.nio.file.attribute.BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS).fileKey()).isEqualTo(targetKey);
        assertThat(Files.readAllBytes(distinctSource)).isEqualTo(bytes);
        assertThat(Files.readAllBytes(distinctTarget)).isEqualTo(bytes);
    }

    @Test
    void evidencePublicationLockInterruptIsBoundedPreservedAndReentrant()
            throws Exception {
        Path parent = Files.createDirectory(
                temporaryDirectory.toRealPath().resolve("evidence-interrupt"));
        Files.setPosixFilePermissions(parent,
                PosixFilePermissions.fromString("rwx------"));
        Path missingStage = parent.resolve("missing-stage.json");
        Path output = parent.resolve(
                CapabilityStudioExecutionLeaseEvidencePublication.TRANSCRIPT_FILE);
        var publication = CapabilityStudioExecutionLeaseEvidencePublication.provision(
                parent, fingerprint('e'));
        ByteArrayOutputStream interruptedOut = new ByteArrayOutputStream();
        Thread.currentThread().interrupt();
        try {
            long started = System.nanoTime();
            int interrupted = CapabilityStudioExecutionLeaseEvidenceCli.run(
                    new String[]{missingStage.toString(), output.toString()},
                    new PrintStream(interruptedOut, true, StandardCharsets.UTF_8),
                    System.err, NOW, java.util.List::of, fingerprint('1'),
                    publication.publicationFingerprint());
            assertThat(interrupted).isEqualTo(
                    CapabilityStudioStageAcceptanceCli.EXIT_NOT_ACCEPTED);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(java.time.Duration.ofNanos(System.nanoTime() - started))
                    .isLessThan(java.time.Duration.ofSeconds(1));
            assertThat(interruptedOut.toString(StandardCharsets.UTF_8))
                    .contains("PUBLICATION_UNAVAILABLE");
        } finally {
            Thread.interrupted();
        }

        ByteArrayOutputStream retryOut = new ByteArrayOutputStream();
        int retry = CapabilityStudioExecutionLeaseEvidenceCli.run(
                new String[]{missingStage.toString(), output.toString()},
                new PrintStream(retryOut, true, StandardCharsets.UTF_8),
                System.err, NOW, java.util.List::of, fingerprint('1'),
                publication.publicationFingerprint());
        assertThat(retry).as(retryOut.toString(StandardCharsets.UTF_8))
                .isEqualTo(CapabilityStudioStageAcceptanceCli.EXIT_INVALID);
        assertThat(retryOut.toString(StandardCharsets.UTF_8))
                .contains("RECOVERY_INVALID")
                .doesNotContain("PUBLICATION_UNAVAILABLE");
    }

    @Test
    void evidenceTransactionInventoryStopsAtItsFixedBoundWithoutMutation()
            throws Exception {
        Path inventory = temporaryDirectory.resolve("bounded-inventory");
        Files.createDirectory(inventory);
        for (int index = 0; index < 2 * 1_024 + 8; index++) {
            Files.write(inventory.resolve(String.format("entry-%04d.json", index)),
                    new byte[]{(byte) index});
        }
        Set<String> before;
        try (var children = Files.list(inventory)) {
            before = children.map(path -> path.getFileName().toString())
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }

        assertThatThrownBy(() -> CapabilityStudioExecutionLeaseEvidenceCli
                .requireBoundedTransactionInventoryForTesting(inventory))
                .isInstanceOf(IOException.class);

        Set<String> after;
        try (var children = Files.list(inventory)) {
            after = children.map(path -> path.getFileName().toString())
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
        assertThat(after).isEqualTo(before).hasSize(2 * 1_024 + 8);
        assertThat(Files.readAllBytes(inventory.resolve("entry-0000.json")))
                .containsExactly((byte) 0);
        assertThat(Files.readAllBytes(inventory.resolve("entry-2055.json")))
                .containsExactly((byte) 7);
    }

    @Test
    void evidenceCliClassifiesMissingDependencyUnavailableAndSymlinkConflictInvalid()
            throws Exception {
        Path artifact = write("evidence-classification.json", passResult().toString());
        Path realTemporaryDirectory = temporaryDirectory.toRealPath();
        Path missingOutput = realTemporaryDirectory
                .resolve("missing-parent/transcript.json")
                .toAbsolutePath();
        ByteArrayOutputStream missingOut = new ByteArrayOutputStream();
        ByteArrayOutputStream missingErr = new ByteArrayOutputStream();
        int missing = CapabilityStudioExecutionLeaseEvidenceCli.run(
                new String[]{artifact.toString(), missingOutput.toString()},
                new PrintStream(missingOut, true, StandardCharsets.UTF_8),
                new PrintStream(missingErr, true, StandardCharsets.UTF_8), NOW,
                () -> {
                    throw new AssertionError("unavailable recovery loaded Provider");
                }, fingerprint('d'));
        assertThat(missing).isEqualTo(CapabilityStudioStageAcceptanceCli.EXIT_NOT_ACCEPTED);
        assertThat(missingOut.toString(StandardCharsets.UTF_8)).isEqualTo(
                "NOT_ACCEPTED outcome=BLOCKED reasonCode="
                        + "RG.CAPABILITY_STUDIO.EXECUTION_LEASE_EVIDENCE_CLI."
                        + "PUBLICATION_UNAVAILABLE\n")
                .doesNotContain(temporaryDirectory.toString());
        assertThat(missingErr).hasToString("");

        Path realParent = Files.createDirectory(realTemporaryDirectory.resolve("real-parent"));
        Files.setPosixFilePermissions(realParent,
                PosixFilePermissions.fromString("rwx------"));
        Path alias = realTemporaryDirectory.resolve("parent-alias");
        Files.createSymbolicLink(alias, realParent);
        Path invalidOutput = alias.resolve("transcript.json").toAbsolutePath().normalize();
        ByteArrayOutputStream invalidOut = new ByteArrayOutputStream();
        ByteArrayOutputStream invalidErr = new ByteArrayOutputStream();
        int invalid = CapabilityStudioExecutionLeaseEvidenceCli.run(
                new String[]{artifact.toString(), invalidOutput.toString()},
                new PrintStream(invalidOut, true, StandardCharsets.UTF_8),
                new PrintStream(invalidErr, true, StandardCharsets.UTF_8), NOW,
                () -> {
                    throw new AssertionError("invalid recovery loaded Provider");
                }, fingerprint('d'));
        assertThat(invalid).isEqualTo(CapabilityStudioStageAcceptanceCli.EXIT_INVALID);
        assertThat(invalidOut.toString(StandardCharsets.UTF_8)).isEqualTo(
                "INVALID errorCode="
                        + "RG.CAPABILITY_STUDIO.EXECUTION_LEASE_EVIDENCE_CLI."
                        + "PUBLICATION_INVALID\n")
                .doesNotContain(temporaryDirectory.toString());
        assertThat(invalidErr).hasToString("");
    }

    private int run(Path artifact,
                    List<CapabilityStudioStageAcceptanceAuthorityProvider> providers,
                    Output output) {
        return run(artifact, () -> providers, output);
    }

    private int run(Path artifact,
                    CapabilityStudioStageAcceptanceCli.ProviderSource providers,
                    Output output) {
        return CapabilityStudioStageAcceptanceCli.run(
                new String[]{artifact.toString()}, output.out(), output.err(), NOW, providers,
                bindingFingerprint(passResult()));
    }

    private Path write(String name, String value) throws Exception {
        Path path = temporaryDirectory.resolve(name);
        Files.writeString(path, value);
        return path;
    }

    static ObjectNode passResult() {
        ObjectNode result = CapabilityStudioStageAcceptanceAuthorityVerifierTest.validStagePass();
        ObjectNode candidate = candidateAttestation();
        ObjectNode environment = environmentAttestation(result, candidate);
        try {
            byte[] candidateBytes = JSON.writeValueAsBytes(candidate);
            environment.with("candidateAttestation").put("fingerprint",
                    TARGET_VERIFIER.rawAttestationFingerprint(candidateBytes));
            byte[] environmentBytes = JSON.writeValueAsBytes(environment);
            String environmentFingerprint = TARGET_VERIFIER.rawAttestationFingerprint(
                    environmentBytes);
            result.with("environmentAttestation").put("fingerprint", environmentFingerprint);
            ((ObjectNode) result.path("evidenceRefs").path(0))
                    .put("fingerprint", environmentFingerprint);
            refreshClosure(result);
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
        return result;
    }

    private static ObjectNode blockedResult() {
        return new CapabilityStudioStageAcceptanceResultV2Builder(
                "SAR-cli-blocked", 1, "contract:cli", "1",
                new CapabilityStudioStageAcceptanceResultV2Builder.CandidateBuild(
                        "build:cli", "1", "abcdef1",
                        CapabilityStudioStageAcceptanceResultV2Builder.SourceTreeStatus.CLEAN,
                        fingerprint('5')),
                new CapabilityStudioStageAcceptanceResultV2Builder.ExactRef(
                        "baseline:cli", fingerprint('1')),
                new CapabilityStudioStageAcceptanceResultV2Builder.ExactRef(
                        "demo:cli", fingerprint('2')),
                fingerprint('4'), fingerprint('3'),
                CapabilityStudioStageAcceptanceResultV2Builder.ExecutionWindow.notStarted(
                        "2026-01-01T00:11:00Z"))
                .build();
    }

    private static Provider acceptingProvider(ObjectNode result) {
        String closure = result.path("evidenceClosureFingerprint").textValue();
        return new Provider(request -> EvidenceResolution.available(facts(request, closure)),
                (reference, evidence, context) -> verified(),
                (signoff, signature, context) -> verified(), targetAdmission(result));
    }

    private void assertUnknownExecutePreserved(
            UnknownEvidenceObject object, UnknownVariant variant) throws Exception {
        EvidenceBaseline baseline = committedEvidenceBaseline(object + "-" + variant);
        Path target = unknownTarget(baseline, object);
        seedUnknown(target, exactUnknownBytes(baseline, object), object, variant);
        Map<String, UnknownTreeEntry> before = observeUnknownTree(baseline.parent());
        AtomicInteger providerLoads = new AtomicInteger();
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = CapabilityStudioExecutionLeaseEvidenceCli.run(
                new String[]{baseline.artifact().toString(),
                        baseline.output().toString()},
                new PrintStream(output, true, StandardCharsets.UTF_8), System.err, NOW,
                () -> {
                    providerLoads.incrementAndGet();
                    return List.of(baseline.provider());
                }, baseline.formalFingerprint(), baseline.publicationFingerprint());

        boolean publicationPreflight = object == UnknownEvidenceObject.OWNER_BOOTSTRAP
                || object == UnknownEvidenceObject.PROVISION_DECLARATION
                || object == UnknownEvidenceObject.PUBLICATION_LOCK;
        assertThat(exit).as(object + " " + variant).isEqualTo(2);
        assertThat(output.toString(StandardCharsets.UTF_8)).isEqualTo(
                "INVALID errorCode=RG.CAPABILITY_STUDIO."
                        + "EXECUTION_LEASE_EVIDENCE_CLI."
                        + (publicationPreflight ? "PUBLICATION" : "RECOVERY")
                        + "_INVALID\n");
        assertThat(providerLoads).hasValue(0);
        assertThat(observeUnknownTree(baseline.parent()))
                .as(object + " " + variant).isEqualTo(before);
    }

    private void assertUnknownProvisioningPreserved(
            UnknownEvidenceObject object, UnknownVariant variant) throws Exception {
        String name = "provision-unknown-" + object + "-" + variant;
        Path parent = Files.createDirectory(temporaryDirectory.toRealPath().resolve(name));
        Files.setPosixFilePermissions(parent,
                PosixFilePermissions.fromString("rwx------"));
        CapabilityStudioExecutionLeaseEvidencePublication.provision(parent, fingerprint('e'));
        Path target = switch (object) {
            case OWNER_BOOTSTRAP -> parent.resolve(
                    CapabilityStudioExecutionLeaseEvidencePublication.OWNER_BOOTSTRAP_FILE);
            case PROVISION_DECLARATION -> parent.resolve(
                    CapabilityStudioExecutionLeaseEvidencePublication
                            .PUBLICATION_DECLARATION_FILE);
            case PUBLICATION_LOCK -> parent.resolve(
                    CapabilityStudioExecutionLeaseEvidencePublication.PUBLICATION_LOCK_FILE);
            default -> throw new AssertionError("unsupported provisioning object");
        };
        byte[] exact = Files.readAllBytes(target);
        seedUnknown(target, exact, object, variant);
        Map<String, UnknownTreeEntry> before = observeUnknownTree(parent);

        var failure = org.junit.jupiter.api.Assertions.assertThrows(
                CapabilityStudioExecutionLeaseEvidencePublication.PublicationException.class,
                () -> CapabilityStudioExecutionLeaseEvidencePublication.provision(
                        parent, fingerprint('e')));

        assertThat(failure.failureKind()).isEqualTo(
                CapabilityStudioStageAcceptanceAuthorityProvider
                        .EvidenceFailureKind.INVALID);
        assertThat(observeUnknownTree(parent)).as(object + " " + variant).isEqualTo(before);
    }

    private void assertWrongUidMetadataPreserved(UnknownEvidenceObject object) throws Exception {
        Path parent = Files.createDirectory(temporaryDirectory.toRealPath().resolve(
                "wrong-uid-" + object.toString().toLowerCase()));
        Files.setPosixFilePermissions(parent,
                PosixFilePermissions.fromString("rwx------"));
        Files.write(parent.resolve("sentinel"), new byte[]{1});
        Map<String, UnknownTreeEntry> before = observeUnknownTree(parent);

        if (object == UnknownEvidenceObject.OWNER_BOOTSTRAP
                || object == UnknownEvidenceObject.PROVISION_DECLARATION
                || object == UnknownEvidenceObject.PUBLICATION_LOCK) {
            var failure = org.junit.jupiter.api.Assertions.assertThrows(
                    CapabilityStudioExecutionLeaseEvidencePublication
                            .PublicationException.class,
                    () -> CapabilityStudioExecutionLeaseEvidencePublication
                            .requireMatchingUidForTesting(1000, 1001));
            assertThat(failure.failureKind()).isEqualTo(
                    CapabilityStudioStageAcceptanceAuthorityProvider
                            .EvidenceFailureKind.INVALID);
        } else {
            var failure = org.junit.jupiter.api.Assertions.assertThrows(
                    CapabilityStudioExecutionLeaseEvidenceCli.EvidenceInvalidException.class,
                    () -> CapabilityStudioExecutionLeaseEvidenceCli
                            .requireOwnedUidForTesting(1000, 1001));
            assertThat(failure.failureKind()).isEqualTo(
                    CapabilityStudioStageAcceptanceAuthorityProvider
                            .EvidenceFailureKind.INVALID);
        }
        assertThat(observeUnknownTree(parent)).isEqualTo(before);
    }

    private EvidenceBaseline committedEvidenceBaseline(String caseName) throws Exception {
        String safe = caseName.toLowerCase().replace('_', '-');
        ObjectNode result = passResult();
        Path artifact = write(safe + "-stage.json", result.toString());
        Path parent = Files.createDirectory(
                temporaryDirectory.toRealPath().resolve(safe + "-publication"));
        Files.setPosixFilePermissions(parent,
                PosixFilePermissions.fromString("rwx------"));
        var declaration = CapabilityStudioExecutionLeaseEvidencePublication.provision(
                parent, fingerprint('e'));
        Path output = parent.resolve(
                CapabilityStudioExecutionLeaseEvidencePublication.TRANSCRIPT_FILE);
        Provider ordinary = acceptingProvider(result);
        var formal = ordinary.formalTargetBoundAuthorityBinding();
        CapabilityStudioStageAcceptanceAuthorityProvider provider =
                positiveEvidenceProvider(ordinary, formal);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        int exit = CapabilityStudioExecutionLeaseEvidenceCli.run(
                new String[]{artifact.toString(), output.toString()},
                new PrintStream(stdout, true, StandardCharsets.UTF_8), System.err, NOW,
                () -> List.of(provider), formal.fingerprint(),
                declaration.publicationFingerprint());
        assertThat(exit).as(stdout.toString(StandardCharsets.UTF_8)).isZero();
        String transactionId = declaration.evidenceTransactionId();
        Path wrapper = parent.resolve("." + output.getFileName() + "."
                + transactionId.substring("sha256:".length()) + ".evidence-v3");
        return new EvidenceBaseline(artifact, parent, output, wrapper, provider,
                formal.fingerprint(), declaration.publicationFingerprint());
    }

    private static Path unknownTarget(
            EvidenceBaseline baseline, UnknownEvidenceObject object) {
        return switch (object) {
            case FINAL -> baseline.output();
            case WRAPPER -> baseline.wrapper();
            case OWNER_BOOTSTRAP -> baseline.parent().resolve(
                    CapabilityStudioExecutionLeaseEvidencePublication.OWNER_BOOTSTRAP_FILE);
            case BEFORE_PART -> baseline.wrapper().resolve(
                    ".before-v2-g00000000000000000001.json.part");
            case COMMITTED_PART -> baseline.wrapper().resolve(
                    ".committed-transcript-v1.json.part");
            case COMMIT_MANIFEST_PART -> baseline.wrapper().resolve(
                    ".commit-manifest-v1.json.part");
            case PROVISION_DECLARATION -> baseline.parent().resolve(
                    CapabilityStudioExecutionLeaseEvidencePublication
                            .PUBLICATION_DECLARATION_FILE);
            case PUBLICATION_LOCK -> baseline.parent().resolve(
                    CapabilityStudioExecutionLeaseEvidencePublication.PUBLICATION_LOCK_FILE);
        };
    }

    private static byte[] exactUnknownBytes(
            EvidenceBaseline baseline, UnknownEvidenceObject object) throws IOException {
        return switch (object) {
            case FINAL -> Files.readAllBytes(baseline.output());
            case WRAPPER -> null;
            case OWNER_BOOTSTRAP -> Files.readAllBytes(unknownTarget(baseline, object));
            case BEFORE_PART -> Files.readAllBytes(baseline.wrapper().resolve(
                    "before-v2-g00000000000000000001.json"));
            case COMMITTED_PART -> Files.readAllBytes(baseline.wrapper().resolve(
                    "committed-transcript-v1.json"));
            case COMMIT_MANIFEST_PART -> Files.readAllBytes(baseline.wrapper().resolve(
                    "commit-manifest-v1.json"));
            case PROVISION_DECLARATION, PUBLICATION_LOCK ->
                    Files.readAllBytes(unknownTarget(baseline, object));
        };
    }

    private static void seedUnknown(
            Path target,
            byte[] exactBytes,
            UnknownEvidenceObject object,
            UnknownVariant variant) throws IOException {
        Path replacement = target.resolveSibling("." + target.getFileName()
                + ".unknown-replacement");
        deleteUnknownTree(replacement);
        if (variant == UnknownVariant.DISTINCT_INODE
                && object == UnknownEvidenceObject.WRAPPER) {
            copyUnknownTree(target, replacement);
        }
        deleteUnknownTree(target);
        int expectedMode = object == UnknownEvidenceObject.PUBLICATION_LOCK ? 0600
                : object == UnknownEvidenceObject.WRAPPER ? 0700 : 0400;
        switch (variant) {
            case EMPTY_DIRECTORY -> {
                Files.createDirectory(target);
                Files.setPosixFilePermissions(target,
                        PosixFilePermissions.fromString("rwx------"));
            }
            case WRONG_BYTES -> writeUnknownFile(target,
                    "UNKNOWN_EVIDENCE_BYTES".getBytes(StandardCharsets.UTF_8), expectedMode);
            case WRONG_MODE -> {
                if (object == UnknownEvidenceObject.WRAPPER) {
                    Files.createDirectory(target);
                    Files.setPosixFilePermissions(target,
                            PosixFilePermissions.fromString("rwxr-x---"));
                } else {
                    writeUnknownFile(target, exactBytes, expectedMode == 0600 ? 0400 : 0600);
                }
            }
            case SYMLINK -> {
                Path referenced = replacement.resolveSibling(
                        replacement.getFileName() + ".target");
                deleteUnknownTree(referenced);
                if (object == UnknownEvidenceObject.WRAPPER) {
                    Files.createDirectory(referenced);
                    Files.setPosixFilePermissions(referenced,
                            PosixFilePermissions.fromString("rwx------"));
                } else {
                    writeUnknownFile(referenced, exactBytes, expectedMode);
                }
                Files.createSymbolicLink(target, referenced);
            }
            case HARDLINK -> {
                Path referenced = replacement.resolveSibling(
                        replacement.getFileName() + ".target");
                deleteUnknownTree(referenced);
                writeUnknownFile(referenced,
                        exactBytes == null ? new byte[]{1} : exactBytes,
                        object == UnknownEvidenceObject.WRAPPER ? 0400 : expectedMode);
                Files.createLink(target, referenced);
            }
            case DISTINCT_INODE -> {
                if (object == UnknownEvidenceObject.WRAPPER) {
                    Files.move(replacement, target);
                } else {
                    writeUnknownFile(target, exactBytes, expectedMode);
                }
            }
        }
    }

    private static void writeUnknownFile(Path path, byte[] bytes, int mode) throws IOException {
        Files.write(path, bytes);
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(
                mode == 0600 ? "rw-------" : "r--------"));
    }

    private static void copyUnknownTree(Path source, Path target) throws IOException {
        try (var paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path destination = target.resolve(source.relativize(path));
                BasicFileAttributes attributes = Files.readAttributes(path,
                        BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                int mode = ((Number) Files.getAttribute(path, "unix:mode",
                        LinkOption.NOFOLLOW_LINKS)).intValue() & 0777;
                if (attributes.isDirectory()) {
                    Files.createDirectory(destination);
                } else {
                    Files.write(destination, Files.readAllBytes(path));
                }
                Files.setPosixFilePermissions(destination,
                        PosixFilePermissions.fromString(mode == 0700
                                ? "rwx------" : mode == 0600 ? "rw-------" : "r--------"));
            }
        }
    }

    private static void deleteUnknownTree(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var paths = Files.walk(path)) {
            for (Path child : paths.sorted((left, right) -> Integer.compare(
                    right.getNameCount(), left.getNameCount())).toList()) {
                Files.delete(child);
            }
        }
    }

    private static Map<String, UnknownTreeEntry> observeUnknownTree(Path root)
            throws IOException {
        Map<String, UnknownTreeEntry> entries = new LinkedHashMap<>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted().toList()) {
                BasicFileAttributes attributes = Files.readAttributes(path,
                        BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                long device = ((Number) Files.getAttribute(path, "unix:dev",
                        LinkOption.NOFOLLOW_LINKS)).longValue();
                long inode = ((Number) Files.getAttribute(path, "unix:ino",
                        LinkOption.NOFOLLOW_LINKS)).longValue();
                long links = ((Number) Files.getAttribute(path, "unix:nlink",
                        LinkOption.NOFOLLOW_LINKS)).longValue();
                long uid = ((Number) Files.getAttribute(path, "unix:uid",
                        LinkOption.NOFOLLOW_LINKS)).longValue();
                int mode = ((Number) Files.getAttribute(path, "unix:mode",
                        LinkOption.NOFOLLOW_LINKS)).intValue() & 07777;
                String kind = attributes.isRegularFile() ? "FILE"
                        : attributes.isDirectory() ? "DIRECTORY"
                        : attributes.isSymbolicLink() ? "SYMLINK" : "OTHER";
                String raw = attributes.isRegularFile()
                        ? rawFingerprint(Files.readAllBytes(path))
                        : attributes.isSymbolicLink()
                        ? rawFingerprint(Files.readSymbolicLink(path).toString()
                        .getBytes(StandardCharsets.UTF_8)) : null;
                String relative = path.equals(root) ? "."
                        : root.relativize(path).toString();
                entries.put(relative, new UnknownTreeEntry(kind,
                        String.valueOf(attributes.fileKey()), device, inode, links, uid,
                        mode, attributes.size(), attributes.lastModifiedTime(), raw));
            }
        }
        return Map.copyOf(entries);
    }

    private static CapabilityStudioStageAcceptanceAuthorityProvider positiveEvidenceProvider(
            Provider ordinary,
            CapabilityStudioStageAcceptanceAuthorityProvider.FormalTargetBoundAuthorityBinding
                    formal) {
        return new CapabilityStudioStageAcceptanceAuthorityProvider() {
            @Override
            public FormalEvidenceAuthorityBinding formalEvidenceAuthorityBinding() {
                return new FormalEvidenceAuthorityBinding(formal, fingerprint('6'),
                        (phase, transactionId) -> evidenceObservation(
                                phase, transactionId,
                                phase == CapabilityStudioDeploymentStateObservation.Phase.AFTER),
                        new CapabilityStudioStageAcceptanceAuthorityProvider
                                .EvidenceExecutionLeaseTransactionAuthority() {
                            @Override
                            public CapabilityStudioStageAcceptanceAuthorityProvider
                                    .EvidenceExecutionLeaseTransactionResult commit(
                                    CapabilityStudioStageAcceptanceAuthorityProvider
                                            .EvidenceExecutionLeaseAttempt attempt,
                                    CapabilityStudioStageAcceptanceAuthorityProvider
                                            .EvidenceTransactionJournal journal) {
                                var before = journal.prepareBefore(attempt,
                                        evidenceObservation(
                                                CapabilityStudioDeploymentStateObservation
                                                        .Phase.BEFORE,
                                                attempt.evidenceTransactionId(), false));
                                var receipt = committedLease(attempt.request()).receipt();
                                var witness = new ExecutionLeaseTransitionWitness(
                                        fingerprint('6'),
                                        attempt.request().commitIdentityFingerprint(),
                                        receipt.fingerprint(), fingerprint('1'), 0, 0,
                                        fingerprint('2'), 0, fingerprint('3'),
                                        fingerprint('7'), fingerprint('4'), 1, 1,
                                        fingerprint('5'), 0, fingerprint('3'));
                                var lease = new EvidenceExecutionLeaseCommitResult(
                                        ExecutionLeaseCommitStatus.COMMITTED, receipt, witness,
                                        "LEASE_COMMITTED");
                                var after = evidenceObservation(
                                        CapabilityStudioDeploymentStateObservation.Phase.AFTER,
                                        attempt.evidenceTransactionId(), true);
                                journal.persistCommitted(attempt, before, after, lease);
                                return new CapabilityStudioStageAcceptanceAuthorityProvider
                                        .EvidenceExecutionLeaseTransactionResult(
                                        before, after, lease);
                            }

                            @Override
                            public EvidenceExecutionLeaseCommitResult recoverExisting(
                                    CapabilityStudioStageAcceptanceAuthorityProvider
                                            .ExecutionLeaseRequest request) {
                                return new EvidenceExecutionLeaseCommitResult(
                                        ExecutionLeaseCommitStatus.UNAVAILABLE,
                                        null, null, "LEASE_UNAVAILABLE");
                            }
                        });
            }

            @Override
            public AuthorityBinding authorityBinding() {
                return ordinary.authorityBinding();
            }

            @Override
            public EvidenceResolver evidenceResolver() {
                return ordinary.resolver();
            }

            @Override
            public CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceIssuerPolicy
                    evidenceIssuerPolicy() {
                return ordinary.issuer();
            }

            @Override
            public CapabilityStudioStageAcceptanceAuthorityVerifier.OwnerAuthority
                    ownerAuthority() {
                return ordinary.owner();
            }
        };
    }

    private static Provider providerWithCountingPostRunCallbacks(
            ObjectNode result,
            AtomicInteger callbacks,
            CapabilityStudioStageAcceptanceAuthorityProvider.FormalTargetAdmissionBinding admission) {
        String closure = result.path("evidenceClosureFingerprint").textValue();
        return new Provider(request -> {
            callbacks.incrementAndGet();
            return EvidenceResolution.available(facts(request, closure));
        }, (reference, evidence, context) -> {
            callbacks.incrementAndGet();
            return verified();
        }, (signoff, signature, context) -> {
            callbacks.incrementAndGet();
            return verified();
        }, admission);
    }

    private static CapabilityStudioStageAcceptanceAuthorityProvider.FormalTargetAdmissionBinding
    targetAdmission(ObjectNode result) {
        return targetAdmission(result, facts ->
                        CapabilityStudioStageAcceptanceTargetBindingVerifier.AuthorityDecision
                                .verified(), facts ->
                        CapabilityStudioStageAcceptanceTargetBindingVerifier.AuthorityDecision
                                .verified());
    }

    private static CapabilityStudioStageAcceptanceAuthorityProvider.FormalTargetAdmissionBinding
            withDeploymentAuthorities(
            CapabilityStudioStageAcceptanceAuthorityProvider.FormalTargetAdmissionBinding source,
            CapabilityStudioStageAcceptanceAuthorityProvider.TrustedVerificationClock clock,
            CapabilityStudioStageAcceptanceAuthorityProvider.AdmissionLifecycleAuthority
                    lifecycle,
            CapabilityStudioStageAcceptanceAuthorityProvider.ExecutionLeaseAuthority lease) {
        return new CapabilityStudioStageAcceptanceAuthorityProvider.FormalTargetAdmissionBinding(
                source.targetAdmissionMaterialFingerprint(), source.targetRawFingerprint(),
                source.targetCanonicalFingerprint(), source.targetBindingBytes(),
                source.candidateAttestationBytes(), source.environmentAttestationBytes(),
                source.verificationContext(), source.candidateAuthority(),
                source.environmentAuthority(), source.lifecycleMaterial(),
                new CapabilityStudioStageAcceptanceAuthorityProvider
                        .DeploymentAdmissionAuthorityBinding(
                        new CapabilityStudioStageAcceptanceAuthorityProvider
                                .TrustedVerificationClockBinding(
                                CLOCK_FINGERPRINT, clock),
                        new CapabilityStudioStageAcceptanceAuthorityProvider
                                .AdmissionLifecycleAuthorityBinding(
                                LIFECYCLE_AUTHORITY_FINGERPRINT, lifecycle),
                        new CapabilityStudioStageAcceptanceAuthorityProvider
                                .ExecutionLeaseAuthorityBinding(
                                LEASE_AUTHORITY_FINGERPRINT, lease)));
    }

    private static CapabilityStudioStageAcceptanceAuthorityProvider providerThrowingSnapshot(
            RuntimeException failure) {
        return new CapabilityStudioStageAcceptanceAuthorityProvider() {
            @Override
            public CapabilityStudioStageAcceptanceAuthorityProvider
                    .FormalTargetBoundAuthorityBinding formalTargetBoundAuthorityBinding() {
                throw failure;
            }

            @Override
            public EvidenceResolver evidenceResolver() {
                return request -> EvidenceResolution.unavailable();
            }

            @Override
            public CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceIssuerPolicy
                    evidenceIssuerPolicy() {
                return (reference, evidence, context) ->
                        CapabilityStudioStageAcceptanceAuthorityVerifier.AuthorityDecision
                                .unavailable();
            }

            @Override
            public CapabilityStudioStageAcceptanceAuthorityVerifier.OwnerAuthority
                    ownerAuthority() {
                return (signoff, signature, context) ->
                        CapabilityStudioStageAcceptanceAuthorityVerifier.AuthorityDecision
                                .unavailable();
            }
        };
    }

    private static CapabilityStudioStageAcceptanceAuthorityProvider.FormalTargetAdmissionBinding
    targetAdmission(
            ObjectNode result,
            CapabilityStudioStageAcceptanceTargetBindingVerifier.CandidateAuthority candidateAuthority,
            CapabilityStudioStageAcceptanceTargetBindingVerifier.EnvironmentAuthority
                    environmentAuthority) {
        try {
            ObjectNode candidate = candidateAttestation();
            ObjectNode environment = environmentAttestation(result, candidate);
            byte[] candidateBytes = JSON.writeValueAsBytes(candidate);
            environment.with("candidateAttestation").put("fingerprint",
                    TARGET_VERIFIER.rawAttestationFingerprint(candidateBytes));
            byte[] environmentBytes = JSON.writeValueAsBytes(environment);
            String candidateFingerprint = TARGET_VERIFIER.rawAttestationFingerprint(candidateBytes);
            String environmentFingerprint = TARGET_VERIFIER.rawAttestationFingerprint(environmentBytes);
            ObjectNode target = JSON.createObjectNode()
                    .put("schemaVersion", CapabilityStudioStageAcceptanceTargetBindingVerifier
                            .TARGET_BINDING_SCHEMA_VERSION)
                    .put("resultId", result.path("resultId").textValue())
                    .put("resultRevision", result.path("revision").intValue())
                    .put("contractId", result.path("contractId").textValue())
                    .put("contractRevision", result.path("contractRevision").textValue())
                    .put("executionLeaseId", TARGET_LEASE);
            target.putObject("candidateAttestation")
                    .put("candidateRef", candidate.path("candidateRef").textValue())
                    .put("attestationRevision", candidate.path("attestationRevision").intValue())
                    .put("fingerprint", candidateFingerprint);
            target.putObject("environmentAttestation")
                    .put("environmentRef", environment.path("environmentRef").textValue())
                    .put("attestationRevision", environment.path("attestationRevision").intValue())
                    .put("fingerprint", environmentFingerprint);
            target.putArray("trustedTargetIdentities").add(TARGET_IDENTITY);
            target.put("fingerprint", fingerprint('0'));
            target.put("fingerprint", TARGET_VERIFIER.targetBindingFingerprint(target));
            byte[] targetBytes = JSON.writeValueAsBytes(target);
            CapabilityStudioStageAcceptanceTargetBindingVerifier.VerificationContext context =
                    new CapabilityStudioStageAcceptanceTargetBindingVerifier.VerificationContext(
                            TARGET_LEASE, Set.of(TARGET_IDENTITY),
                            target.path("fingerprint").textValue());
            return new CapabilityStudioStageAcceptanceAuthorityProvider.FormalTargetAdmissionBinding(
                    fingerprint('9'), TARGET_VERIFIER.rawAttestationFingerprint(targetBytes),
                    target.path("fingerprint").textValue(), targetBytes, candidateBytes,
                    environmentBytes, context,
                    candidateAuthority, environmentAuthority, lifecycleMaterial(),
                    new CapabilityStudioStageAcceptanceAuthorityProvider
                            .DeploymentAdmissionAuthorityBinding(
                            new CapabilityStudioStageAcceptanceAuthorityProvider
                                    .TrustedVerificationClockBinding(
                                    CLOCK_FINGERPRINT, () -> NOW),
                            new CapabilityStudioStageAcceptanceAuthorityProvider
                                    .AdmissionLifecycleAuthorityBinding(
                                    LIFECYCLE_AUTHORITY_FINGERPRINT,
                                    request -> CapabilityStudioStageAcceptanceAuthorityProvider
                                            .DeploymentAuthorityDecision.verified(
                                                    "LIFECYCLE_VERIFIED")),
                            new CapabilityStudioStageAcceptanceAuthorityProvider
                                    .ExecutionLeaseAuthorityBinding(
                                    LEASE_AUTHORITY_FINGERPRINT,
                                    CapabilityStudioStageAcceptanceCliTest::committedLease)));
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static ObjectNode candidateAttestation() {
        ObjectNode candidate = JSON.createObjectNode()
                .put("schemaVersion", CapabilityStudioStageAcceptanceTargetBindingVerifier
                        .CANDIDATE_ATTESTATION_SCHEMA_VERSION)
                .put("candidateRef", "candidate:capability-studio:2026-01")
                .put("attestationRevision", 1)
                .put("role", "CANDIDATE_AUTHORITY")
                .put("buildRef", "build:capability-studio")
                .put("revision", "rev-2")
                .put("sourceCommit", "abcdef1234567")
                .put("sourceTreeStatus", "CLEAN")
                .put("artifactDigest", fingerprint('5'))
                .put("executionIntentFingerprint", fingerprint('4'))
                .put("scope", TARGET_SCOPE)
                .put("issuer", "issuer:candidate-authority")
                .put("issuedAt", "2026-01-01T00:00:00Z")
                .put("expiresAt", "2026-01-01T00:30:00Z");
        reference(candidate, "baselineRef", "baseline:capability-studio:v2", '1');
        reference(candidate, "demoPackRef", "demo-pack:capability-studio:v2", '2');
        return candidate;
    }

    private static ObjectNode environmentAttestation(ObjectNode result, ObjectNode candidate) {
        ObjectNode environment = JSON.createObjectNode()
                .put("schemaVersion", CapabilityStudioStageAcceptanceTargetBindingVerifier
                        .ENVIRONMENT_ATTESTATION_SCHEMA_VERSION)
                .put("environmentRef", result.path("environmentAttestation")
                        .path("exactRef").textValue())
                .put("attestationRevision", 1)
                .put("role", "ENVIRONMENT_AUTHORITY")
                .put("executionLeaseId", TARGET_LEASE)
                .put("environmentFingerprint", result.path("environmentAttestation")
                        .path("environmentFingerprint").textValue())
                .put("targetProfile", result.path("environmentAttestation")
                        .path("profile").textValue())
                .put("scope", TARGET_SCOPE)
                .put("region", "region:sg1")
                .put("runtimeIdentity", TARGET_IDENTITY)
                .put("networkPolicy", result.path("deploymentEgressObservation")
                        .path("networkPolicyRef").textValue())
                .put("logicalClock", "2026-01-01T00:00:00Z")
                .put("issuer", result.path("environmentAttestation").path("issuer").textValue())
                .put("issuedAt", "2026-01-01T00:00:00Z")
                .put("expiresAt", "2026-01-01T00:30:00Z");
        environment.putObject("candidateAttestation")
                .put("candidateRef", candidate.path("candidateRef").textValue())
                .put("attestationRevision", candidate.path("attestationRevision").intValue())
                .put("fingerprint", fingerprint('0'));
        reference(environment, "featureFlagsRef", "feature-flags:capability-studio:v1", '6');
        environment.putObject("admissionWindow")
                .put("from", "2026-01-01T00:00:00Z")
                .put("through", "2026-01-01T00:30:00Z");
        environment.putArray("trustedTargetIdentities").add(TARGET_IDENTITY);
        return environment;
    }

    private static String bindingFingerprint(ObjectNode result) {
        return CapabilityStudioStageAcceptanceAuthorityProvider.FormalTargetBoundAuthorityBinding
                .aggregateFingerprint(
                        CapabilityStudioStageAcceptanceAuthorityProvider.FormalTargetBoundAuthorityBinding
                                .MESSAGE_VERSION,
                        fingerprint('a'),
                        targetAdmission(result).deploymentAuthorityBinding().fingerprint(),
                        targetAdmission(result).targetAdmissionMaterialFingerprint(),
                        targetAdmission(result).targetRawFingerprint(),
                        targetAdmission(result).targetCanonicalFingerprint());
    }

    private static CapabilityStudioStageAcceptanceAuthorityProvider.ExecutionLeaseCommitResult
            committedLease(
            CapabilityStudioStageAcceptanceAuthorityProvider.ExecutionLeaseRequest request) {
        var revocation = request.lifecycleMaterial().revocationAuthority();
        var lifecycleReceipt = new CapabilityStudioStageAcceptanceAuthorityProvider
                .AtomicAdmissionLifecycleCommitReceipt(
                request.deploymentAdmissionAuthorityMaterialFingerprint(),
                request.lifecycleMaterial().fingerprint(), revocation.registryRef(),
                revocation.revision(), revocation.snapshotFingerprint(), 1, NOW,
                request.commitIdentityFingerprint());
        var receipt = new CapabilityStudioStageAcceptanceAuthorityProvider.ExecutionLeaseReceipt(
                request.commitIdentityFingerprint(), request.lifecycleMaterial(), lifecycleReceipt);
        return CapabilityStudioStageAcceptanceAuthorityProvider.ExecutionLeaseCommitResult
                .committed(receipt, "LEASE_COMMITTED");
    }

    private static CapabilityStudioStageAcceptanceAuthorityProvider.ExecutionLeaseRequest
            leaseRequest(
            ObjectNode result,
            CapabilityStudioStageAcceptanceAuthorityProvider.FormalTargetBoundAuthorityBinding
                    binding,
            Instant trustedVerificationTime) {
        return leaseRequest(result, binding, trustedVerificationTime,
                result.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static CapabilityStudioStageAcceptanceAuthorityProvider.ExecutionLeaseRequest
            leaseRequest(
            ObjectNode result,
            CapabilityStudioStageAcceptanceAuthorityProvider.FormalTargetBoundAuthorityBinding
                    binding,
            Instant trustedVerificationTime,
            byte[] exactStageResultBytes) {
        var admission = binding.targetAdmissionBinding();
        return new CapabilityStudioStageAcceptanceAuthorityProvider.ExecutionLeaseRequest(
                result.path("resultId").textValue(), result.path("revision").longValue(),
                rawFingerprint(exactStageResultBytes),
                result.path("evidenceClosureFingerprint").textValue(),
                result.path("contractId").textValue(),
                result.path("contractRevision").textValue(),
                admission.verificationContext().executionLeaseId(), binding.fingerprint(),
                admission.targetRawFingerprint(), admission.targetCanonicalFingerprint(),
                admission.lifecycleMaterial(), admission.deploymentAuthorityBinding().fingerprint(),
                trustedVerificationTime);
    }

    private static String rawFingerprint(byte[] value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static CapabilityStudioDeploymentStateObservation.Observation evidenceObservation(
            CapabilityStudioDeploymentStateObservation.Phase phase,
            String transactionId,
            boolean committed) {
        boolean post = committed || phase == CapabilityStudioDeploymentStateObservation.Phase.AFTER;
        return CapabilityStudioDeploymentStateObservation.create(phase, transactionId,
                fingerprint('6'), fingerprint('e'), post ? 1 : 0,
                post ? fingerprint('1') : null,
                post ? fingerprint('4') : fingerprint('1'), fingerprint('a'),
                post ? fingerprint('5') : fingerprint('2'), fingerprint('b'),
                0, fingerprint('3'), fingerprint('c'),
                post ? lifecycleMaterial().fingerprint() : null,
                post ? 1 : 0, post ? 1 : 0, fingerprint('d'));
    }

    private static CapabilityStudioStageAcceptanceAuthorityProvider.AdmissionLifecycleMaterial
            lifecycleMaterial() {
        var revocation = new CapabilityStudioStageAcceptanceAuthorityProvider
                .RevocationAuthoritySnapshot("registry:stage-acceptance", 1,
                fingerprint('8'), NOW.minusSeconds(60), NOW.plusSeconds(600));
        return new CapabilityStudioStageAcceptanceAuthorityProvider.AdmissionLifecycleMaterial(
                fingerprint('9'), "bundle:stage-acceptance", 1, "ACTIVE", null, revocation);
    }

    private static void refreshClosure(ObjectNode result) {
        String closure = CapabilityStudioStageAcceptanceResultV2Verifier.closureFingerprint(result);
        result.put("evidenceClosureFingerprint", closure);
        for (var signoff : result.path("signoffs")) {
            ((ObjectNode) signoff).put("evidenceClosureFingerprint", closure);
        }
    }

    private static void reference(ObjectNode parent, String field, String exactRef, char seed) {
        parent.set(field, JSON.createObjectNode().put("exactRef", exactRef)
                .put("fingerprint", fingerprint(seed)));
    }

    private static void printSecret(String secret, String phase) {
        System.out.println(secret + " " + phase + " out");
        System.err.println(secret + " " + phase + " err");
    }

    private static ResolvedEvidence facts(ResolutionRequest request, String closure) {
        if (request.kind() == ReferenceKind.SIGNATURE) {
            return new ResolvedEvidence(request.coordinate(), EvidenceKind.OWNER_SIGNATURE,
                    "issuer:owner-authority", "tenant:demo/environment:acceptance",
                    fingerprint('5'), fingerprint('4'), fingerprint('3'), null, null, closure,
                    "key:owner:1", "Ed25519", fingerprint('6'),
                    instant("00:07:00"), instant("00:30:00"), "c2lnbmF0dXJl");
        }
        if (request.key().equals("environment")) {
            return new ResolvedEvidence(request.coordinate(), EvidenceKind.ENVIRONMENT_ATTESTATION,
                    "issuer:deployment-control-plane", "tenant:demo/environment:acceptance",
                    fingerprint('5'), null, fingerprint('3'),
                    instant("00:00:00"), instant("00:30:00"), null,
                    "key:environment:1", "Ed25519", fingerprint('6'),
                    instant("00:00:00"), instant("00:30:00"), "c2lnbmF0dXJl");
        }
        if (request.key().equals("egress")) {
            return new ResolvedEvidence(request.coordinate(),
                    EvidenceKind.DEPLOYMENT_EGRESS_OBSERVATION,
                    "issuer:network-observer", "tenant:demo/environment:acceptance",
                    null, fingerprint('4'), null,
                    instant("00:00:00"), instant("00:05:00"), null,
                    "key:egress:1", "Ed25519", fingerprint('6'),
                    instant("00:05:00"), instant("00:30:00"), "c2lnbmF0dXJl");
        }
        return new ResolvedEvidence(request.coordinate(), EvidenceKind.ACCEPTANCE_EVIDENCE,
                "issuer:acceptance-evidence", "tenant:demo/environment:acceptance",
                fingerprint('5'), fingerprint('4'), fingerprint('3'),
                instant("00:00:00"), instant("00:05:00"), closure,
                "key:acceptance:1", "Ed25519", fingerprint('6'),
                instant("00:05:00"), instant("00:30:00"), "c2lnbmF0dXJl");
    }

    private static CapabilityStudioStageAcceptanceAuthorityVerifier.AuthorityDecision verified() {
        return CapabilityStudioStageAcceptanceAuthorityVerifier.AuthorityDecision.verified();
    }

    private static Instant instant(String time) {
        return Instant.parse("2026-01-01T" + time + "Z");
    }

    private static Map<String, String> transcript(String output) {
        String line = output.stripTrailing();
        if (line.contains("\n") || line.contains("\r")) {
            throw new IllegalArgumentException("transcript must contain one line");
        }
        String[] tokens = line.split(" ");
        if (tokens.length < 2 || !"ACCEPTED".equals(tokens[0])) {
            throw new IllegalArgumentException("transcript status is invalid");
        }
        Map<String, String> fields = new LinkedHashMap<>();
        for (int index = 1; index < tokens.length; index++) {
            int separator = tokens[index].indexOf('=');
            if (separator < 1 || separator == tokens[index].length() - 1
                    || fields.put(tokens[index].substring(0, separator),
                    tokens[index].substring(separator + 1)) != null) {
                throw new IllegalArgumentException("transcript field is invalid");
            }
        }
        return Map.copyOf(fields);
    }

    private static String fingerprint(char seed) {
        return "sha256:" + String.valueOf(seed).repeat(64);
    }

    private enum UnknownEvidenceObject {
        FINAL,
        WRAPPER,
        OWNER_BOOTSTRAP,
        BEFORE_PART,
        COMMITTED_PART,
        COMMIT_MANIFEST_PART,
        PROVISION_DECLARATION,
        PUBLICATION_LOCK
    }

    private enum UnknownVariant {
        EMPTY_DIRECTORY,
        WRONG_BYTES,
        WRONG_MODE,
        SYMLINK,
        HARDLINK,
        DISTINCT_INODE
    }

    private record EvidenceBaseline(
            Path artifact,
            Path parent,
            Path output,
            Path wrapper,
            CapabilityStudioStageAcceptanceAuthorityProvider provider,
            String formalFingerprint,
            String publicationFingerprint) {
    }

    private record UnknownTreeEntry(
            String kind,
            String fileKey,
            long device,
            long inode,
            long links,
            long uid,
            int mode,
            long size,
            FileTime modifiedTime,
            String rawFingerprint) {
    }

    private record Provider(
            CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceResolver resolver,
            CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceIssuerPolicy issuer,
            CapabilityStudioStageAcceptanceAuthorityVerifier.OwnerAuthority owner,
            CapabilityStudioStageAcceptanceAuthorityProvider.FormalTargetAdmissionBinding targetAdmission)
            implements CapabilityStudioStageAcceptanceAuthorityProvider {
        private Provider(
                CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceResolver resolver,
                CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceIssuerPolicy issuer,
                CapabilityStudioStageAcceptanceAuthorityVerifier.OwnerAuthority owner) {
            this(resolver, issuer, owner, null);
        }

        @Override
        public CapabilityStudioStageAcceptanceAuthorityProvider.AuthorityBinding authorityBinding() {
            return new CapabilityStudioStageAcceptanceAuthorityProvider.AuthorityBinding(
                    fingerprint('a'), resolver, issuer, owner);
        }

        @Override
        public CapabilityStudioStageAcceptanceAuthorityProvider.FormalTargetBoundAuthorityBinding
                formalTargetBoundAuthorityBinding() {
            return targetAdmission == null ? null
                    : new CapabilityStudioStageAcceptanceAuthorityProvider
                    .FormalTargetBoundAuthorityBinding(authorityBinding(), targetAdmission);
        }

        @Override
        public String authorityBindingFingerprint() {
            return fingerprint('a');
        }

        @Override
        public CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceResolver evidenceResolver() {
            return resolver;
        }

        @Override
        public CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceIssuerPolicy
                evidenceIssuerPolicy() {
            return issuer;
        }

        @Override
        public CapabilityStudioStageAcceptanceAuthorityVerifier.OwnerAuthority ownerAuthority() {
            return owner;
        }
    }

    private static final class NoisyProvider
            implements CapabilityStudioStageAcceptanceAuthorityProvider {
        private final ObjectNode result;
        private final String secret;
        private final CapabilityStudioStageAcceptanceAuthorityProvider.FormalTargetAdmissionBinding
                targetAdmission;

        private NoisyProvider(ObjectNode result, String secret) {
            this.result = result;
            this.secret = secret;
            var localAdmission = targetAdmission(result, facts -> {
                printSecret(secret, "candidate-authority");
                return verifiedTarget();
            }, facts -> {
                printSecret(secret, "environment-authority");
                return verifiedTarget();
            });
            this.targetAdmission = withDeploymentAuthorities(localAdmission, () -> {
                printSecret(secret, "trusted-clock");
                return NOW;
            }, request -> {
                printSecret(secret, "lifecycle-authority");
                return CapabilityStudioStageAcceptanceAuthorityProvider
                        .DeploymentAuthorityDecision.verified("LIFECYCLE_VERIFIED");
            }, request -> {
                printSecret(secret, "lease-authority");
                return committedLease(request);
            });
        }

        @Override
        public CapabilityStudioStageAcceptanceAuthorityProvider.AuthorityBinding authorityBinding() {
            printSecret(secret, "binding-accessor");
            return new CapabilityStudioStageAcceptanceAuthorityProvider.AuthorityBinding(
                    fingerprint('a'), evidenceResolver(), evidenceIssuerPolicy(), ownerAuthority());
        }

        @Override
        public CapabilityStudioStageAcceptanceAuthorityProvider.FormalTargetBoundAuthorityBinding
                formalTargetBoundAuthorityBinding() {
            printSecret(secret, "target-bound-binding-accessor");
            return new CapabilityStudioStageAcceptanceAuthorityProvider
                    .FormalTargetBoundAuthorityBinding(authorityBinding(), targetAdmission);
        }

        @Override
        public String authorityBindingFingerprint() {
            printSecret(secret, "binding-accessor");
            return fingerprint('a');
        }

        @Override
        public CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceResolver evidenceResolver() {
            printSecret(secret, "resolver-accessor");
            String closure = result.path("evidenceClosureFingerprint").textValue();
            return request -> {
                printSecret(secret, "resolver-callback");
                return EvidenceResolution.available(facts(request, closure));
            };
        }

        @Override
        public CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceIssuerPolicy
                evidenceIssuerPolicy() {
            printSecret(secret, "issuer-accessor");
            return (reference, evidence, context) -> {
                printSecret(secret, "issuer-callback");
                return verified();
            };
        }

        @Override
        public CapabilityStudioStageAcceptanceAuthorityVerifier.OwnerAuthority ownerAuthority() {
            printSecret(secret, "owner-accessor");
            return (signoff, signature, context) -> {
                printSecret(secret, "owner-callback");
                return verified();
            };
        }
    }

    private static CapabilityStudioStageAcceptanceTargetBindingVerifier.AuthorityDecision
    verifiedTarget() {
        return CapabilityStudioStageAcceptanceTargetBindingVerifier.AuthorityDecision.verified();
    }

    private static final class Output {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final PrintStream stream = new PrintStream(bytes, true, StandardCharsets.UTF_8);

        PrintStream out() {
            return stream;
        }

        PrintStream err() {
            return stream;
        }

        String text() {
            return bytes.toString(StandardCharsets.UTF_8);
        }
    }
}
