package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.AdmissionLifecycleMaterial;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.AdmissionLifecycleAuthorityBinding;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.AtomicAdmissionLifecycleCommitReceipt;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.AuthorityBinding;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.DeploymentAdmissionAuthorityBinding;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.DeploymentAuthorityDecision;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.ExecutionLeaseAuthorityBinding;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.ExecutionLeaseCommitResult;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.ExecutionLeaseCommitStatus;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.ExecutionLeaseReceipt;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.ExecutionLeaseRequest;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.FormalTargetAdmissionBinding;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.FormalTargetBoundAuthorityBinding;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.RevocationAuthoritySnapshot;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.TargetAdmissionBinding;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.TargetBoundAuthorityBinding;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.TrustedVerificationClockBinding;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceResolution;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CapabilityStudioStageAcceptanceAuthorityProviderTest {
    private static final ObjectMapper STRICT_JSON = new ObjectMapper(
            new JsonFactory().rebuild()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .build());
    private static final Instant NOW = Instant.parse("2026-01-01T00:12:00Z");
    private static final String CLOCK_FINGERPRINT = fingerprint('1');
    private static final String LIFECYCLE_AUTHORITY_FINGERPRINT = fingerprint('2');
    private static final String LEASE_AUTHORITY_FINGERPRINT = fingerprint('3');
    private static final String DEPLOYMENT_AUTHORITY_FINGERPRINT =
            DeploymentAdmissionAuthorityBinding.aggregateFingerprint(
                    CLOCK_FINGERPRINT, LIFECYCLE_AUTHORITY_FINGERPRINT,
                    LEASE_AUTHORITY_FINGERPRINT);
    private static final String OLD_MESSAGE =
            "{\"messageVersion\":\"resource-gateway.capability-studio."
                    + "stage-acceptance-provider-binding.v1\","
                    + "\"authorityMaterialFingerprint\":\"" + fingerprint('a')
                    + "\",\"targetBindingFingerprint\":\"" + fingerprint('b') + "\"}";

    @Test
    void preservesExactPhaseTwoRecordShapesAndConstructors() {
        AuthorityBinding authority = authorityBinding(fingerprint('a'));
        TargetAdmissionBinding admission = legacyAdmission(new byte[]{1});
        TargetBoundAuthorityBinding outer = new TargetBoundAuthorityBinding(authority, admission);

        assertThat(componentNames(AuthorityBinding.class)).containsExactly(
                "fingerprint", "resolver", "issuerPolicy", "ownerAuthority");
        assertThat(componentNames(TargetAdmissionBinding.class)).containsExactly(
                "targetBindingBytes", "candidateAttestationBytes",
                "environmentAttestationBytes", "verificationContext",
                "candidateAuthority", "environmentAuthority");
        assertThat(componentNames(TargetBoundAuthorityBinding.class)).containsExactly(
                "fingerprint", "authorityBinding", "targetAdmissionBinding");
        assertThat(new AuthorityBinding(fingerprint('a'), authority.resolver(),
                authority.issuerPolicy(), authority.ownerAuthority())).isEqualTo(authority);
        TargetAdmissionBinding reconstructed = new TargetAdmissionBinding(
                admission.targetBindingBytes(),
                admission.candidateAttestationBytes(), admission.environmentAttestationBytes(),
                admission.verificationContext(), admission.candidateAuthority(),
                admission.environmentAuthority());
        assertThat(reconstructed.targetBindingFingerprint())
                .isEqualTo(admission.targetBindingFingerprint());
        assertThat(new TargetBoundAuthorityBinding(outer.fingerprint(), authority, admission))
                .isEqualTo(outer);
    }

    @Test
    void preservesLiteralPhaseTwoMessageVersionAndHelperOutputs() {
        assertThat(TargetBoundAuthorityBinding.MESSAGE_VERSION)
                .isEqualTo("resource-gateway.capability-studio."
                        + "stage-acceptance-provider-binding.v1");
        assertThat(TargetBoundAuthorityBinding.aggregateCanonicalMessage(
                TargetBoundAuthorityBinding.MESSAGE_VERSION,
                fingerprint('a'), fingerprint('b'))).isEqualTo(OLD_MESSAGE);
        assertThat(TargetBoundAuthorityBinding.aggregateFingerprint(
                TargetBoundAuthorityBinding.MESSAGE_VERSION,
                fingerprint('a'), fingerprint('b')))
                .isEqualTo("sha256:4b7f068491a5829e2cdbe0c3bea5f5c5"
                        + "da97f37fe33c7a21d483e23c916b8ba9");
        assertThat(CapabilityStudioStageAcceptanceAuthorityProvider.aggregateFingerprint(
                TargetBoundAuthorityBinding.MESSAGE_VERSION,
                fingerprint('a'), fingerprint('b')))
                .isEqualTo("sha256:4b7f068491a5829e2cdbe0c3bea5f5c5"
                        + "da97f37fe33c7a21d483e23c916b8ba9");
    }

    @Test
    void phaseTwoAdmissionBytesRemainDefensiveAndBounded() {
        byte[] target = "target-binding-secret".getBytes(StandardCharsets.UTF_8);
        byte[] candidate = new byte[]{2, 3};
        byte[] environment = new byte[]{4, 5};
        TargetAdmissionBinding admission = legacyAdmission(target, candidate, environment);

        target[0] = 'X';
        candidate[0] = 9;
        environment[0] = 8;
        byte[] copy = admission.targetBindingBytes();
        copy[0] = 'Y';

        assertThat(admission.targetBindingBytes()).containsExactly(
                "target-binding-secret".getBytes(StandardCharsets.UTF_8));
        assertThat(admission.candidateAttestationBytes()).containsExactly(2, 3);
        assertThat(admission.environmentAttestationBytes()).containsExactly(4, 5);
        assertThat(admission.toString()).doesNotContain("target-binding-secret");
        assertThatThrownBy(() -> legacyAdmission(
                new byte[CapabilityStudioStageAcceptanceTargetBindingVerifier
                        .MAXIMUM_TARGET_BINDING_BYTES + 1]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("targetBindingBytes");
    }

    @Test
    void formalV2AggregateIsDeterministicAndDriftsForEveryMaterialCoordinate() {
        String version = FormalTargetBoundAuthorityBinding.MESSAGE_VERSION;
        String authority = fingerprint('a');
        String deploymentAuthority = fingerprint('f');
        String admission = fingerprint('b');
        String raw = fingerprint('c');
        String canonical = fingerprint('d');

        assertThat(Set.of(
                CapabilityStudioStageAcceptanceAuthorityProvider.formalAggregateFingerprint(
                        version, authority, deploymentAuthority, admission, raw, canonical),
                CapabilityStudioStageAcceptanceAuthorityProvider.formalAggregateFingerprint(
                        version, fingerprint('e'), deploymentAuthority,
                        admission, raw, canonical),
                CapabilityStudioStageAcceptanceAuthorityProvider.formalAggregateFingerprint(
                        version, authority, fingerprint('e'), admission, raw, canonical),
                CapabilityStudioStageAcceptanceAuthorityProvider.formalAggregateFingerprint(
                        version, authority, deploymentAuthority,
                        fingerprint('e'), raw, canonical),
                CapabilityStudioStageAcceptanceAuthorityProvider.formalAggregateFingerprint(
                        version, authority, deploymentAuthority,
                        admission, fingerprint('e'), canonical),
                CapabilityStudioStageAcceptanceAuthorityProvider.formalAggregateFingerprint(
                        version, authority, deploymentAuthority,
                        admission, raw, fingerprint('e'))))
                .hasSize(6);

        FormalTargetBoundAuthorityBinding first = new FormalTargetBoundAuthorityBinding(
                authorityBinding(authority), formalAdmission(new byte[]{1}));
        FormalTargetBoundAuthorityBinding second = new FormalTargetBoundAuthorityBinding(
                authorityBinding(authority), formalAdmission(new byte[]{1}));
        assertThat(first.fingerprint()).isEqualTo(second.fingerprint()).isNotEqualTo(authority);
        assertThat(first.toString()).doesNotContain(authority, fingerprint('b'));
        assertThat(first.targetAdmissionBinding().deploymentAuthorityBinding().toString())
                .doesNotContain(DEPLOYMENT_AUTHORITY_FINGERPRINT);
    }

    @Test
    void deploymentAuthorityAggregateIsDerivedAndDriftsForEveryComponentCoordinate() {
        DeploymentAdmissionAuthorityBinding base = deploymentBinding(
                CLOCK_FINGERPRINT, LIFECYCLE_AUTHORITY_FINGERPRINT,
                LEASE_AUTHORITY_FINGERPRINT);
        DeploymentAdmissionAuthorityBinding clockDrift = deploymentBinding(
                fingerprint('4'), LIFECYCLE_AUTHORITY_FINGERPRINT,
                LEASE_AUTHORITY_FINGERPRINT);
        DeploymentAdmissionAuthorityBinding lifecycleDrift = deploymentBinding(
                CLOCK_FINGERPRINT, fingerprint('4'), LEASE_AUTHORITY_FINGERPRINT);
        DeploymentAdmissionAuthorityBinding leaseDrift = deploymentBinding(
                CLOCK_FINGERPRINT, LIFECYCLE_AUTHORITY_FINGERPRINT, fingerprint('4'));

        assertThat(Set.of(base.fingerprint(), clockDrift.fingerprint(),
                lifecycleDrift.fingerprint(), leaseDrift.fingerprint())).hasSize(4);
        assertThat(base.fingerprint()).isEqualTo(DEPLOYMENT_AUTHORITY_FINGERPRINT);
        assertThat(Set.of(
                new FormalTargetBoundAuthorityBinding(authorityBinding(fingerprint('a')),
                        formalAdmission(new byte[]{1}, base)).fingerprint(),
                new FormalTargetBoundAuthorityBinding(authorityBinding(fingerprint('a')),
                        formalAdmission(new byte[]{1}, clockDrift)).fingerprint(),
                new FormalTargetBoundAuthorityBinding(authorityBinding(fingerprint('a')),
                        formalAdmission(new byte[]{1}, lifecycleDrift)).fingerprint(),
                new FormalTargetBoundAuthorityBinding(authorityBinding(fingerprint('a')),
                        formalAdmission(new byte[]{1}, leaseDrift)).fingerprint()))
                .hasSize(4);
        assertThatThrownBy(() -> new DeploymentAdmissionAuthorityBinding(
                fingerprint('f'), base.trustedClockBinding(),
                base.lifecycleAuthorityBinding(), base.executionLeaseAuthorityBinding()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("deployment admission authority binding fingerprint is invalid");
        assertThatThrownBy(() -> new TrustedVerificationClockBinding("SHA256:bad", () -> NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AdmissionLifecycleAuthorityBinding(
                "sha256:bad", request -> DeploymentAuthorityDecision.verified("VERIFIED")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExecutionLeaseAuthorityBinding(
                "", request -> ExecutionLeaseCommitResult.unavailable("UNAVAILABLE")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DeploymentAdmissionAuthorityBinding(
                null, base.lifecycleAuthorityBinding(), base.executionLeaseAuthorityBinding()))
                .isInstanceOf(NullPointerException.class);
        assertThat(base.toString()).doesNotContain(
                CLOCK_FINGERPRINT, LIFECYCLE_AUTHORITY_FINGERPRINT,
                LEASE_AUTHORITY_FINGERPRINT, base.fingerprint());
        assertThat(base.trustedClockBinding().toString()).doesNotContain(CLOCK_FINGERPRINT);
        assertThat(base.lifecycleAuthorityBinding().toString())
                .doesNotContain(LIFECYCLE_AUTHORITY_FINGERPRINT);
        assertThat(base.executionLeaseAuthorityBinding().toString())
                .doesNotContain(LEASE_AUTHORITY_FINGERPRINT);
    }

    @Test
    void formalV2ConstructorRejectsAnExplicitWrongAggregate() {
        AuthorityBinding authority = authorityBinding(fingerprint('a'));
        FormalTargetAdmissionBinding admission = formalAdmission(new byte[]{1});

        assertThatThrownBy(() -> new FormalTargetBoundAuthorityBinding(
                fingerprint('f'), authority, admission))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("formal target-bound authority binding fingerprint is invalid");
    }

    @Test
    void leaseCommitIsDurablyIdempotentAndRejectsMismatchedRetry() {
        InMemoryLeaseAuthority authority = new InMemoryLeaseAuthority(
                DEPLOYMENT_AUTHORITY_FINGERPRINT);
        ExecutionLeaseRequest request = leaseRequest("1", NOW);
        ExecutionLeaseRequest crashRetry = leaseRequest("1", NOW.plusSeconds(30));
        ExecutionLeaseRequest rawDrift = leaseRequest("1", NOW,
                fingerprint('6'), request.evidenceClosureFingerprint());
        ExecutionLeaseRequest closureDrift = leaseRequest("1", NOW,
                request.stageResultRawFingerprint(), fingerprint('7'));

        ExecutionLeaseCommitResult committed = authority.commit(request);
        ExecutionLeaseCommitResult recovered = authority.commit(crashRetry);
        ExecutionLeaseCommitResult mismatched = authority.commit(leaseRequest("2", NOW));
        ExecutionLeaseCommitResult rawMismatch = authority.commit(rawDrift);
        ExecutionLeaseCommitResult closureMismatch = authority.commit(closureDrift);

        assertThat(crashRetry.commitIdentityFingerprint())
                .isEqualTo(request.commitIdentityFingerprint());
        assertThat(leaseRequest("2", NOW).commitIdentityFingerprint())
                .isNotEqualTo(request.commitIdentityFingerprint());
        assertThat(rawDrift.commitIdentityFingerprint())
                .isNotEqualTo(request.commitIdentityFingerprint());
        assertThat(closureDrift.commitIdentityFingerprint())
                .isNotEqualTo(request.commitIdentityFingerprint());
        assertThat(crashRetry.commitIdentityCanonicalMessage())
                .isEqualTo(request.commitIdentityCanonicalMessage())
                .doesNotContain(NOW.toString(), NOW.plusSeconds(30).toString());
        assertThat(request.commitIdentityCanonicalMessage()).contains(
                "\"stageResultRawFingerprint\":\"" + request.stageResultRawFingerprint(),
                "\"evidenceClosureFingerprint\":\""
                        + request.evidenceClosureFingerprint());
        assertThat(committed.status()).isEqualTo(ExecutionLeaseCommitStatus.COMMITTED);
        assertThat(recovered.status()).isEqualTo(ExecutionLeaseCommitStatus.RECOVERED);
        assertThat(recovered.receipt()).isEqualTo(committed.receipt());
        assertThat(recovered.receipt().fingerprint())
                .isEqualTo(committed.receipt().fingerprint());
        assertThat(mismatched.status()).isEqualTo(ExecutionLeaseCommitStatus.REJECTED);
        assertThat(rawMismatch.status()).isEqualTo(ExecutionLeaseCommitStatus.REJECTED);
        assertThat(closureMismatch.status()).isEqualTo(ExecutionLeaseCommitStatus.REJECTED);
        assertThat(mismatched.receipt()).isNull();
        assertThat(rawMismatch.receipt()).isNull();
        assertThat(closureMismatch.receipt()).isNull();
        assertThat(authority.commits).hasValue(1);
        assertThat(committed.receipt().requestFingerprint())
                .isEqualTo(request.commitIdentityFingerprint());
        assertThat(committed.receipt().lifecycleMaterial()).isEqualTo(request.lifecycleMaterial());
        assertThat(committed.receipt().lifecycleCommitReceipt().fencingSequence()).isEqualTo(1);
        assertThat(committed.receipt().lifecycleCommitReceipt()
                .deploymentAdmissionAuthorityMaterialFingerprint())
                .isEqualTo(DEPLOYMENT_AUTHORITY_FINGERPRINT);
        assertThat(committed.receipt().toString()).doesNotContain(
                request.executionLeaseId(), request.deploymentAdmissionAuthorityMaterialFingerprint());
        assertThatThrownBy(() -> leaseRequest(
                "1", NOW, "sha256:bad", request.evidenceClosureFingerprint()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> leaseRequest(
                "1", NOW, request.stageResultRawFingerprint(), "SHA256:" + "a".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void executionLeaseCommitIdentityIsExactStrictCanonicalJson() throws Exception {
        ExecutionLeaseRequest request = leaseRequest("1", NOW);
        String expected = "{\"messageVersion\":\""
                + CapabilityStudioStageAcceptanceAuthorityProvider
                        .EXECUTION_LEASE_COMMIT_IDENTITY_MESSAGE_VERSION
                + "\",\"resultId\":\"SAR-test\""
                + ",\"resultRevision\":1"
                + ",\"stageResultRawFingerprint\":\"" + fingerprint('4') + "\""
                + ",\"evidenceClosureFingerprint\":\"" + fingerprint('5') + "\""
                + ",\"contractId\":\"contract:test\""
                + ",\"contractRevision\":\"1\""
                + ",\"executionLeaseId\":\"lease:test\""
                + ",\"providerOuterFingerprint\":\"" + fingerprint('a') + "\""
                + ",\"targetRawFingerprint\":\"" + fingerprint('b') + "\""
                + ",\"targetCanonicalFingerprint\":\"" + fingerprint('c') + "\""
                + ",\"lifecycleMaterialFingerprint\":\""
                + lifecycleMaterial().fingerprint() + "\""
                + ",\"deploymentAdmissionAuthorityMaterialFingerprint\":\""
                + DEPLOYMENT_AUTHORITY_FINGERPRINT + "\"}";

        String canonicalMessage = request.commitIdentityCanonicalMessage();
        assertThat(canonicalMessage).isEqualTo(expected);
        var parsed = STRICT_JSON.readTree(canonicalMessage);
        assertThat(parsed.size()).isEqualTo(13);
        assertThat(parsed.path("stageResultRawFingerprint").textValue())
                .isEqualTo(request.stageResultRawFingerprint());
        assertThat(parsed.path("evidenceClosureFingerprint").textValue())
                .isEqualTo(request.evidenceClosureFingerprint());
        assertThat(STRICT_JSON.writeValueAsString(parsed)).isEqualTo(expected);
    }

    @Test
    void lifecycleCommitReceiptRejectsFingerprintAndRevocationHeadTamper() {
        ExecutionLeaseRequest request = leaseRequest("1", NOW);
        AtomicAdmissionLifecycleCommitReceipt valid = lifecycleReceipt(request, 1);
        var revocation = request.lifecycleMaterial().revocationAuthority();

        assertThat(valid.toString()).doesNotContain(
                valid.fingerprint(), valid.requestFingerprint(), revocation.registryRef());
        assertThat(lifecycleReceipt(request, 2).fingerprint())
                .isNotEqualTo(valid.fingerprint());
        assertThatThrownBy(() -> new AtomicAdmissionLifecycleCommitReceipt(
                fingerprint('f'), valid.deploymentAdmissionAuthorityMaterialFingerprint(),
                valid.lifecycleMaterialFingerprint(), valid.revocationRegistryRef(),
                valid.revocationRegistryRevision(), valid.revocationSnapshotFingerprint(),
                valid.fencingSequence(), valid.committedAt(), valid.requestFingerprint()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("atomic lifecycle commit receipt fingerprint is invalid");

        AtomicAdmissionLifecycleCommitReceipt wrongHead =
                new AtomicAdmissionLifecycleCommitReceipt(
                        request.deploymentAdmissionAuthorityMaterialFingerprint(),
                        request.lifecycleMaterial().fingerprint(), revocation.registryRef(),
                        revocation.revision(), fingerprint('d'), 1, NOW,
                        request.commitIdentityFingerprint());
        assertThatThrownBy(() -> new ExecutionLeaseReceipt(
                request.commitIdentityFingerprint(), request.lifecycleMaterial(), wrongHead))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("execution lease lifecycle commit receipt is invalid");
    }

    @Test
    void concurrentExactLeaseRequestsCreateOneCommitAndRecoverOneReceipt() throws Exception {
        InMemoryLeaseAuthority authority = new InMemoryLeaseAuthority(
                DEPLOYMENT_AUTHORITY_FINGERPRINT);
        ExecutionLeaseRequest request = leaseRequest("1", NOW);
        int consumers = 16;
        CountDownLatch ready = new CountDownLatch(consumers);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(consumers)) {
            var futures = java.util.stream.IntStream.range(0, consumers)
                    .mapToObj(index -> executor.submit(() -> {
                        ready.countDown();
                        start.await();
                        return authority.commit(request);
                    })).toList();
            ready.await();
            start.countDown();
            var results = futures.stream().map(future -> {
                try {
                    return future.get();
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
            }).toList();
            assertThat(results.stream().filter(result ->
                    result.status() == ExecutionLeaseCommitStatus.COMMITTED)).hasSize(1);
            assertThat(results.stream().filter(result ->
                    result.status() == ExecutionLeaseCommitStatus.RECOVERED))
                    .hasSize(consumers - 1);
            assertThat(results.stream().map(result -> result.receipt().fingerprint()).distinct())
                    .hasSize(1);
            assertThat(authority.commits).hasValue(1);
        }
    }

    @Test
    void failedEvidenceTransactionAndExistingRecoveryResultsAreClosedWithoutMaterial() {
        for (ExecutionLeaseCommitStatus status : Set.of(
                ExecutionLeaseCommitStatus.INVALID,
                ExecutionLeaseCommitStatus.REJECTED,
                ExecutionLeaseCommitStatus.UNAVAILABLE)) {
            var lease = new CapabilityStudioStageAcceptanceAuthorityProvider
                    .EvidenceExecutionLeaseCommitResult(status, null, null, "CLOSED_RESULT");
            var transaction = new CapabilityStudioStageAcceptanceAuthorityProvider
                    .EvidenceExecutionLeaseTransactionResult(null, null, lease);
            assertThat(transaction.beforeObservation()).isNull();
            assertThat(transaction.afterObservation()).isNull();
            assertThat(transaction.leaseResult().status()).isEqualTo(status);
            assertThat(transaction.failureKind()).contains(switch (status) {
                case INVALID -> CapabilityStudioStageAcceptanceAuthorityProvider
                        .EvidenceFailureKind.INVALID;
                case REJECTED -> CapabilityStudioStageAcceptanceAuthorityProvider
                        .EvidenceFailureKind.REJECTED;
                case UNAVAILABLE -> CapabilityStudioStageAcceptanceAuthorityProvider
                        .EvidenceFailureKind.UNAVAILABLE;
                case COMMITTED, RECOVERED -> throw new AssertionError();
            });
            assertThat(transaction.toString()).doesNotContain("CLOSED_RESULT");
        }
        for (var status : CapabilityStudioStageAcceptanceAuthorityProvider
                .ExistingEvidenceRecoveryStatus.values()) {
            if (status == CapabilityStudioStageAcceptanceAuthorityProvider
                    .ExistingEvidenceRecoveryStatus.FOUND) {
                continue;
            }
            var result = new CapabilityStudioStageAcceptanceAuthorityProvider
                    .ExistingEvidenceRecoveryResult(status, null, null, "CLOSED_RECOVERY");
            assertThat(result.receipt()).isNull();
            assertThat(result.transitionWitness()).isNull();
            assertThat(result.toString()).doesNotContain("CLOSED_RECOVERY");
            if (status == CapabilityStudioStageAcceptanceAuthorityProvider
                    .ExistingEvidenceRecoveryStatus.CONFLICT) {
                assertThat(result.failureKind()).contains(
                        CapabilityStudioStageAcceptanceAuthorityProvider
                                .EvidenceFailureKind.INVALID);
            } else if (status == CapabilityStudioStageAcceptanceAuthorityProvider
                    .ExistingEvidenceRecoveryStatus.UNAVAILABLE) {
                assertThat(result.failureKind()).contains(
                        CapabilityStudioStageAcceptanceAuthorityProvider
                                .EvidenceFailureKind.UNAVAILABLE);
            } else {
                assertThat(result.failureKind()).isEmpty();
            }
        }

        var rejected = DeploymentAuthorityDecision.rejected("UPPERCASE_CREDENTIAL_PAYLOAD");
        var unavailable = DeploymentAuthorityDecision.unavailable("PROVIDER_PATH_SECRET");
        assertThat(rejected.failureKind()).contains(
                CapabilityStudioStageAcceptanceAuthorityProvider
                        .EvidenceFailureKind.REJECTED);
        assertThat(unavailable.failureKind()).contains(
                CapabilityStudioStageAcceptanceAuthorityProvider
                        .EvidenceFailureKind.UNAVAILABLE);
        assertThat(rejected.toString()).doesNotContain("UPPERCASE_CREDENTIAL_PAYLOAD");
        assertThat(unavailable.toString()).doesNotContain("PROVIDER_PATH_SECRET");
    }

    @Test
    void evidenceJournalTypedDefaultsPreserveLegacyImplementationsAndRedactFailures() {
        var legacy = new CapabilityStudioStageAcceptanceAuthorityProvider
                .EvidenceTransactionJournal() {
            @Override
            public CapabilityStudioDeploymentStateObservation.Observation prepareBefore(
                    CapabilityStudioStageAcceptanceAuthorityProvider
                            .EvidenceExecutionLeaseAttempt attempt,
                    CapabilityStudioDeploymentStateObservation.Observation current) {
                return current;
            }

            @Override
            public void persistCommitted(
                    CapabilityStudioStageAcceptanceAuthorityProvider
                            .EvidenceExecutionLeaseAttempt attempt,
                    CapabilityStudioDeploymentStateObservation.Observation before,
                    CapabilityStudioDeploymentStateObservation.Observation after,
                    CapabilityStudioStageAcceptanceAuthorityProvider
                            .EvidenceExecutionLeaseCommitResult result) {
            }
        };
        assertThat(legacy.prepareBeforeResult(null, null).status()).isEqualTo(
                CapabilityStudioStageAcceptanceAuthorityProvider
                        .EvidenceJournalStatus.COMPLETED);
        assertThat(legacy.persistCommittedResult(null, null, null, null).status())
                .isEqualTo(CapabilityStudioStageAcceptanceAuthorityProvider
                        .EvidenceJournalStatus.COMPLETED);

        var runtimeOutage = new CapabilityStudioStageAcceptanceAuthorityProvider
                .EvidenceTransactionJournal() {
            @Override
            public CapabilityStudioDeploymentStateObservation.Observation prepareBefore(
                    CapabilityStudioStageAcceptanceAuthorityProvider
                            .EvidenceExecutionLeaseAttempt attempt,
                    CapabilityStudioDeploymentStateObservation.Observation current) {
                throw new IllegalStateException("UPPERCASE_CREDENTIAL_PAYLOAD");
            }

            @Override
            public void persistCommitted(
                    CapabilityStudioStageAcceptanceAuthorityProvider
                            .EvidenceExecutionLeaseAttempt attempt,
                    CapabilityStudioDeploymentStateObservation.Observation before,
                    CapabilityStudioDeploymentStateObservation.Observation after,
                    CapabilityStudioStageAcceptanceAuthorityProvider
                            .EvidenceExecutionLeaseCommitResult result) {
                throw new IllegalStateException("PROVIDER_PATH_SECRET");
            }
        };
        var unavailableBefore = runtimeOutage.prepareBeforeResult(null, null);
        var unavailableAfter = runtimeOutage.persistCommittedResult(
                null, null, null, null);
        assertThat(unavailableBefore.failureKind()).contains(
                CapabilityStudioStageAcceptanceAuthorityProvider
                        .EvidenceFailureKind.UNAVAILABLE);
        assertThat(unavailableAfter.failureKind()).contains(
                CapabilityStudioStageAcceptanceAuthorityProvider
                        .EvidenceFailureKind.UNAVAILABLE);
        assertThat(unavailableBefore.toString())
                .doesNotContain("UPPERCASE_CREDENTIAL_PAYLOAD");
        assertThat(unavailableAfter.toString()).doesNotContain("PROVIDER_PATH_SECRET");

        CapabilityStudioStageAcceptanceAuthorityProvider.ExistingEvidenceRecoveryJournal
                recoveryOutage = attempt -> {
                    throw new IllegalStateException("RECOVERY_CREDENTIAL_PAYLOAD");
                };
        assertThat(recoveryOutage.closeAbsentResult(null).failureKind()).contains(
                CapabilityStudioStageAcceptanceAuthorityProvider
                        .EvidenceFailureKind.UNAVAILABLE);
        assertThat(CapabilityStudioStageAcceptanceAuthorityProvider
                .EvidenceJournalResult.invalid().toString())
                .doesNotContain("CREDENTIAL", "PAYLOAD", "PATH");
    }

    private static String[] componentNames(Class<?> recordType) {
        return Arrays.stream(recordType.getRecordComponents())
                .map(RecordComponent::getName).toArray(String[]::new);
    }

    private static AuthorityBinding authorityBinding(String fingerprint) {
        return new AuthorityBinding(fingerprint,
                request -> EvidenceResolution.unavailable(),
                (reference, evidence, context) ->
                        CapabilityStudioStageAcceptanceAuthorityVerifier.AuthorityDecision
                                .unavailable(),
                (signoff, signature, context) ->
                        CapabilityStudioStageAcceptanceAuthorityVerifier.AuthorityDecision
                                .unavailable());
    }

    private static TargetAdmissionBinding legacyAdmission(byte[] target) {
        return legacyAdmission(target, new byte[]{2}, new byte[]{3});
    }

    private static TargetAdmissionBinding legacyAdmission(
            byte[] target, byte[] candidate, byte[] environment) {
        var context = new CapabilityStudioStageAcceptanceTargetBindingVerifier.VerificationContext(
                "lease:test", Set.of("runtime:test"), fingerprint('b'));
        return new TargetAdmissionBinding(target, candidate, environment, context,
                facts -> CapabilityStudioStageAcceptanceTargetBindingVerifier.AuthorityDecision
                        .verified(),
                facts -> CapabilityStudioStageAcceptanceTargetBindingVerifier.AuthorityDecision
                        .verified());
    }

    private static FormalTargetAdmissionBinding formalAdmission(byte[] target) {
        return formalAdmission(target, deploymentBinding(
                CLOCK_FINGERPRINT, LIFECYCLE_AUTHORITY_FINGERPRINT,
                LEASE_AUTHORITY_FINGERPRINT));
    }

    private static FormalTargetAdmissionBinding formalAdmission(
            byte[] target, DeploymentAdmissionAuthorityBinding deployment) {
        String raw = rawFingerprint(target);
        var context = new CapabilityStudioStageAcceptanceTargetBindingVerifier.VerificationContext(
                "lease:test", Set.of("runtime:test"), fingerprint('d'));
        var lifecycle = lifecycleMaterial();
        return new FormalTargetAdmissionBinding(fingerprint('b'), raw, fingerprint('d'),
                target, new byte[]{2}, new byte[]{3}, context,
                facts -> CapabilityStudioStageAcceptanceTargetBindingVerifier.AuthorityDecision
                        .verified(),
                facts -> CapabilityStudioStageAcceptanceTargetBindingVerifier.AuthorityDecision
                        .verified(),
                lifecycle, deployment);
    }

    private static DeploymentAdmissionAuthorityBinding deploymentBinding(
            String clockFingerprint,
            String lifecycleAuthorityFingerprint,
            String leaseAuthorityFingerprint) {
        String deploymentFingerprint = DeploymentAdmissionAuthorityBinding.aggregateFingerprint(
                clockFingerprint, lifecycleAuthorityFingerprint, leaseAuthorityFingerprint);
        return new DeploymentAdmissionAuthorityBinding(
                new TrustedVerificationClockBinding(clockFingerprint, () -> NOW),
                new AdmissionLifecycleAuthorityBinding(lifecycleAuthorityFingerprint,
                        request -> DeploymentAuthorityDecision.verified("LIFECYCLE_VERIFIED")),
                new ExecutionLeaseAuthorityBinding(leaseAuthorityFingerprint,
                        new InMemoryLeaseAuthority(deploymentFingerprint)));
    }

    private static ExecutionLeaseRequest leaseRequest(
            String contractRevision, Instant trustedVerificationTime) {
        return leaseRequest(contractRevision, trustedVerificationTime,
                fingerprint('4'), fingerprint('5'));
    }

    private static ExecutionLeaseRequest leaseRequest(
            String contractRevision,
            Instant trustedVerificationTime,
            String stageResultRawFingerprint,
            String evidenceClosureFingerprint) {
        return new ExecutionLeaseRequest(
                "SAR-test", 1, stageResultRawFingerprint, evidenceClosureFingerprint,
                "contract:test", contractRevision, "lease:test",
                fingerprint('a'), fingerprint('b'), fingerprint('c'), lifecycleMaterial(),
                DEPLOYMENT_AUTHORITY_FINGERPRINT, trustedVerificationTime);
    }

    private static AdmissionLifecycleMaterial lifecycleMaterial() {
        var revocation = new RevocationAuthoritySnapshot(
                "registry:test", 1, fingerprint('e'), NOW.minusSeconds(60), NOW.plusSeconds(600));
        return new AdmissionLifecycleMaterial(
                fingerprint('b'), "bundle:test", 1, "ACTIVE", null, revocation);
    }

    private static String fingerprint(char seed) {
        return "sha256:" + String.valueOf(seed).repeat(64);
    }

    private static String rawFingerprint(byte[] value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static final class InMemoryLeaseAuthority
            implements CapabilityStudioStageAcceptanceAuthorityProvider.ExecutionLeaseAuthority {
        private final String authorityFingerprint;
        private final ConcurrentHashMap<String, StoredCommit> store = new ConcurrentHashMap<>();
        private final AtomicInteger commits = new AtomicInteger();

        private InMemoryLeaseAuthority(String authorityFingerprint) {
            this.authorityFingerprint = authorityFingerprint;
        }

        @Override
        public ExecutionLeaseCommitResult commit(ExecutionLeaseRequest request) {
            if (!authorityFingerprint.equals(
                    request.deploymentAdmissionAuthorityMaterialFingerprint())
                    || !request.lifecycleMaterial().revocationAuthority().expiresAt()
                    .isAfter(request.trustedVerificationTime())) {
                return ExecutionLeaseCommitResult.rejected("LEASE_POLICY_REJECTED");
            }
            AtomicReference<ExecutionLeaseCommitResult> result = new AtomicReference<>();
            store.compute(request.executionLeaseId(), (lease, existing) -> {
                if (existing == null) {
                    long fencingSequence = commits.incrementAndGet();
                    ExecutionLeaseReceipt receipt = new ExecutionLeaseReceipt(
                            request.commitIdentityFingerprint(), request.lifecycleMaterial(),
                            lifecycleReceipt(request, fencingSequence));
                    result.set(ExecutionLeaseCommitResult.committed(
                            receipt, "LEASE_COMMITTED"));
                    return new StoredCommit(request.commitIdentityFingerprint(), receipt);
                }
                if (existing.requestFingerprint().equals(request.commitIdentityFingerprint())) {
                    result.set(ExecutionLeaseCommitResult.recovered(
                            existing.receipt(), "LEASE_RECEIPT_RECOVERED"));
                } else {
                    result.set(ExecutionLeaseCommitResult.rejected("LEASE_REQUEST_MISMATCH"));
                }
                return existing;
            });
            return result.get();
        }
    }

    private static AtomicAdmissionLifecycleCommitReceipt lifecycleReceipt(
            ExecutionLeaseRequest request, long fencingSequence) {
        RevocationAuthoritySnapshot revocation =
                request.lifecycleMaterial().revocationAuthority();
        return new AtomicAdmissionLifecycleCommitReceipt(
                request.deploymentAdmissionAuthorityMaterialFingerprint(),
                request.lifecycleMaterial().fingerprint(), revocation.registryRef(),
                revocation.revision(), revocation.snapshotFingerprint(), fencingSequence,
                NOW, request.commitIdentityFingerprint());
    }

    private record StoredCommit(String requestFingerprint, ExecutionLeaseReceipt receipt) { }
}
