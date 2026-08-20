package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceKind;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceResolution;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceResolver;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityVerifier.ReferenceKind;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityVerifier.ResolutionRequest;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityVerifier.ResolvedEvidence;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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
    private static final String DEPLOYMENT_AUTHORITY_FINGERPRINT =
            CapabilityStudioStageAcceptanceAuthorityProvider
                    .DeploymentAdmissionAuthorityBinding.aggregateFingerprint(
                    CLOCK_FINGERPRINT, LIFECYCLE_AUTHORITY_FINGERPRINT,
                    LEASE_AUTHORITY_FINGERPRINT);

    @TempDir
    Path temporaryDirectory;

    @Test
    void acceptsOnlyAfterTheDeploymentProviderVerifiesEveryAuthority() throws Exception {
        ObjectNode result = passResult();
        Path artifact = write("pass.json", result.toString());
        Output output = new Output();
        Provider provider = acceptingProvider(result);

        int exit = run(artifact, List.of(provider), output);

        var binding = provider.formalTargetBoundAuthorityBinding();
        var receipt = committedLease(leaseRequest(result, binding, NOW)).receipt();
        assertThat(exit).isEqualTo(CapabilityStudioStageAcceptanceCli.EXIT_ACCEPTED);
        assertThat(output.text()).isEqualTo("ACCEPTED outcome=ACCEPTED "
                + "authorityBindingFingerprint=" + binding.fingerprint()
                + " leaseCommitStatus=COMMITTED leaseReceiptFingerprint="
                + receipt.fingerprint() + " reasonCode="
                + "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_CLI.ACCEPTED"
                + System.lineSeparator());
        assertThat(output.text()).doesNotContain(
                "attestation:environment:1", "actor:correctness", "signature:correctness",
                TARGET_LEASE, DEPLOYMENT_AUTHORITY_FINGERPRINT);
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
        var receipt = committedLease(leaseRequest(result, binding, NOW)).receipt();
        assertThat(exit).isEqualTo(CapabilityStudioStageAcceptanceCli.EXIT_ACCEPTED);
        assertThat(output.text()).isEqualTo("ACCEPTED outcome=ACCEPTED "
                + "authorityBindingFingerprint=" + binding.fingerprint()
                + " leaseCommitStatus=RECOVERED leaseReceiptFingerprint="
                + receipt.fingerprint() + " reasonCode="
                + "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_CLI.ACCEPTED"
                + System.lineSeparator()).doesNotContain(providerReason, "CREDENTIAL_TOKEN");
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

    private static ObjectNode passResult() {
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

    private static String fingerprint(char seed) {
        return "sha256:" + String.valueOf(seed).repeat(64);
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
