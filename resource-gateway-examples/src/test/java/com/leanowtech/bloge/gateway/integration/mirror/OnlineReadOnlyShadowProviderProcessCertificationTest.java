package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.ControlPlaneCertificateIdentityPolicy;
import com.leanowtech.bloge.gateway.testing.api.OnlineReadOnlyShadowProviderProcess;
import com.leanowtech.bloge.gateway.testing.api.OnlineReadOnlyShadowProviderProcess.CandidateResponseFault;
import com.leanowtech.bloge.gateway.testing.api.PinnedMutualTlsRecoveryFleetPublicationTransport;
import com.leanowtech.bloge.gateway.testing.api.RecoveryFleetPublicationTlsFixture;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Certifies the online read-only Shadow protocol across isolated provider JVM and trust boundaries.
 */
@Timeout(90)
class OnlineReadOnlyShadowProviderProcessCertificationTest {
    private static final Instant NOW =
            OnlineReadOnlyShadowBaselineTestFixtures.NOW;
    private static final Clock BASELINE_CLOCK =
            Clock.fixed(
                    NOW.plusSeconds(1),
                    ZoneOffset.UTC);
    private static final Clock CANDIDATE_CLOCK =
            Clock.fixed(
                    NOW.plusSeconds(3),
                    ZoneOffset.UTC);
    private static final Clock RESOLUTION_CLOCK =
            Clock.fixed(
                    NOW.plusSeconds(4),
                    ZoneOffset.UTC);

    @TempDir
    private Path directory;

    private final ObjectMapper mapper =
            OnlineReadOnlyShadowBaselineTestFixtures.mapper();
    private final PayloadFreeEqualityReadOnlyShadowPolicy policy =
            new PayloadFreeEqualityReadOnlyShadowPolicy(mapper);
    private final VisualEvidenceSigner resolutionSigner =
            InMemoryVisualEvidenceSigner.usingClock(
                    RESOLUTION_CLOCK);
    private final ReadOnlyShadowSourceResolutionAttestationIntegrity
            resolutionIntegrity =
            new ReadOnlyShadowSourceResolutionAttestationIntegrity(
                    mapper,
                    resolutionSigner,
                    RESOLUTION_CLOCK);

    private MirrorPlan plan;
    private ReadOnlyShadowJobRequest request;
    private ReadOnlyShadowAccessAuthority.Admission admission;
    private OnlineReadOnlyShadowBaselineCommand baselineCommand;
    private SyntheticRegionalReadOnlyShadowProvider.BaselineFixture
            baselineFixture;
    private OnlineReadOnlyShadowProviderProcess.EvidenceKeyMaterial
            baselineKey;
    private OnlineReadOnlyShadowProviderProcess.EvidenceKeyMaterial
            candidateKey;
    private OnlineReadOnlyShadowBaselineObservationIntegrity
            baselineIntegrity;
    private MirrorEvidenceIntegrityService candidateIntegrity;
    private RecoveryFleetPublicationTlsFixture.Material
            baselineTls;
    private RecoveryFleetPublicationTlsFixture.Material
            candidateTls;

    @BeforeEach
    void setUp() throws Exception {
        plan = MirrorPersistenceTestFixtures.plan(
                mapper,
                MirrorPersistenceTestFixtures
                        .scope("support"),
                "refund-shadow-plan",
                '4');
        request = request(plan);
        admission =
                OnlineReadOnlyShadowBaselineTestFixtures
                        .admission(request);
        baselineCommand = baselineCommand();
        baselineFixture = baselineFixture();
        baselineKey = evidenceKey(
                "online-baseline-process-key",
                NOW.minusSeconds(60));
        candidateKey = evidenceKey(
                "online-candidate-process-key",
                NOW.minusSeconds(60));
        baselineIntegrity =
                new OnlineReadOnlyShadowBaselineObservationIntegrity(
                        mapper,
                        OnlineReadOnlyShadowBaselineEvidenceAuthority
                                .from(
                                        new PublicEvidenceSigner(
                                                baselineKey)),
                        RESOLUTION_CLOCK);
        candidateIntegrity =
                new MirrorEvidenceIntegrityService(
                        mapper,
                        new PublicEvidenceSigner(
                                candidateKey),
                        RESOLUTION_CLOCK);
        baselineTls =
                RecoveryFleetPublicationTlsFixture.Material
                        .create(
                                directory.resolve("baseline-tls"),
                                "online-baseline");
        candidateTls =
                RecoveryFleetPublicationTlsFixture.Material
                        .create(
                                directory.resolve("candidate-tls"),
                                "online-candidate");
    }

    @Test
    void certifiesIndependentProcessesPrivateTrustDomainsAndCompleteDataPlane()
            throws Exception {
        OnlineReadOnlyShadowProviderProcess.Configuration
                baselineConfiguration =
                baselineConfiguration();
        try (ChildProvider baseline =
                     ChildProvider.start(
                             mapper,
                             baselineConfiguration,
                             directory.resolve(
                                     "baseline-process.log"))) {
            OnlineReadOnlyShadowBaselineAuthority
                    baselineAuthority =
                    baselineAuthority(
                            baseline.uri(),
                            baselineTls);
            assertThat(baselineAuthority.ready())
                    .isTrue();
            OnlineReadOnlyShadowBaselineObservation
                    seed =
                    baselineIntegrity.requireVerified(
                            baselineAuthority.observe(
                                    baselineCommand));
            OnlineReadOnlyShadowCandidateCommand
                    candidateCommand =
                    candidateCommand(seed);
            OnlineReadOnlyShadowProviderProcess.Configuration
                    candidateConfiguration =
                    candidateConfiguration(
                            candidateCommand,
                            CandidateResponseFault.NONE,
                            0,
                            0);

            try (ChildProvider candidate =
                         ChildProvider.start(
                                 mapper,
                                 candidateConfiguration,
                                 directory.resolve(
                                         "candidate-process.log"))) {
                OnlineReadOnlyShadowCandidateAuthority
                        candidateAuthority =
                        candidateAuthority(
                                candidate.uri(),
                                candidateTls);
                ReadOnlyShadowDataPlane.ExecutionResult
                        result =
                        dataPlane(
                                baselineAuthority,
                                candidateAuthority)
                                .execute(permit(1));

                assertCompleteResult(result);
                assertThat(baseline.pid())
                        .isNotEqualTo(candidate.pid())
                        .isNotEqualTo(
                                ProcessHandle.current()
                                        .pid());
                assertThat(candidate.pid())
                        .isNotEqualTo(
                                ProcessHandle.current()
                                        .pid());
                assertThat(spkiPin(
                        baselineTls
                                .certificateAuthority()))
                        .isNotEqualTo(spkiPin(
                                candidateTls
                                        .certificateAuthority()));
                assertThat(baselineTls.clientUriSan())
                        .isNotEqualTo(
                                candidateTls.clientUriSan());
                assertThat(baselineTls.serverUriSan())
                        .isNotEqualTo(
                                candidateTls.serverUriSan());

                OnlineReadOnlyShadowProviderProcess.Audit
                        baselineAudit =
                        baseline.audit(mapper);
                OnlineReadOnlyShadowProviderProcess.Audit
                        candidateAudit =
                        candidate.audit(mapper);
                assertThat(baselineAudit.executions())
                        .isEqualTo(2);
                assertThat(baselineAudit.exactReads())
                        .isEqualTo(2);
                assertPeer(
                        baselineAudit,
                        baselineTls);
                assertThat(candidateAudit.executions())
                        .isEqualTo(1);
                assertThat(candidateAudit.exactReads())
                        .isEqualTo(1);
                assertThat(candidateAudit
                        .candidateGenerations())
                        .isEqualTo(1);
                assertPeer(
                        candidateAudit,
                        candidateTls);

                int requestsBeforeCrossRole =
                        baselineAudit.requests();
                assertThat(candidateAuthority(
                        baseline.uri(),
                        candidateTls).ready())
                        .isFalse();
                assertThat(baseline.audit(mapper)
                        .requests())
                        .isEqualTo(
                                requestsBeforeCrossRole);

                RecoveryFleetPublicationTlsFixture.Material
                        rogueClient =
                        baselineTls.rotateClient(
                                directory.resolve(
                                        "baseline-tls"),
                                "online-baseline-rogue");
                assertThat(baselineAuthority(
                        baseline.uri(),
                        rogueClient).ready())
                        .isFalse();
                OnlineReadOnlyShadowProviderProcess.Audit
                        rejectedAudit =
                        baseline.audit(mapper);
                assertThat(rejectedAudit.failureCode())
                        .isEqualTo(
                                "CLIENT_IDENTITY_REJECTED");
                assertThat(rejectedAudit.peerSubject())
                        .isEqualTo(
                                rogueClient
                                        .clientCertificate()
                                        .getSubjectX500Principal()
                                        .getName());
                assertThat(rejectedAudit.peerUriSan())
                        .isEqualTo(
                                rogueClient.clientUriSan());
            }
        }
    }

    @Test
    void preservesTheDataPlaneAcrossDualPinnedServerLeafRotation()
            throws Exception {
        RecoveryFleetPublicationTlsFixture.Material
                nextBaselineTls =
                baselineTls.rotateServer(
                        directory.resolve(
                                "baseline-tls"),
                        "online-baseline-next");
        RecoveryFleetPublicationTlsFixture.Material
                nextCandidateTls =
                candidateTls.rotateServer(
                        directory.resolve(
                                "candidate-tls"),
                        "online-candidate-next");
        Set<String> baselineOldPins =
                Set.of(spkiPin(
                        baselineTls
                                .serverCertificate()));
        Set<String> baselineNewPins =
                Set.of(spkiPin(
                        nextBaselineTls
                                .serverCertificate()));
        Set<String> baselineRollingPins =
                Set.of(
                        baselineOldPins.iterator()
                                .next(),
                        baselineNewPins.iterator()
                                .next());
        Set<String> candidateOldPins =
                Set.of(spkiPin(
                        candidateTls
                                .serverCertificate()));
        Set<String> candidateNewPins =
                Set.of(spkiPin(
                        nextCandidateTls
                                .serverCertificate()));
        Set<String> candidateRollingPins =
                Set.of(
                        candidateOldPins.iterator()
                                .next(),
                        candidateNewPins.iterator()
                                .next());

        assertThat(baselineOldPins)
                .doesNotContainAnyElementsOf(
                        baselineNewPins);
        assertThat(candidateOldPins)
                .doesNotContainAnyElementsOf(
                        candidateNewPins);
        assertThat(nextBaselineTls
                .certificateAuthority())
                .isEqualTo(
                        baselineTls
                                .certificateAuthority());
        assertThat(nextCandidateTls
                .certificateAuthority())
                .isEqualTo(
                        candidateTls
                                .certificateAuthority());
        assertThat(nextBaselineTls.serverUriSan())
                .isEqualTo(
                        baselineTls.serverUriSan());
        assertThat(nextCandidateTls.serverUriSan())
                .isEqualTo(
                        candidateTls.serverUriSan());

        ChildProvider baseline =
                ChildProvider.start(
                        mapper,
                        baselineConfiguration(
                                baselineTls,
                                0),
                        directory.resolve(
                                "rotation-baseline-old.log"));
        ChildProvider candidate = null;
        try {
            int baselinePort =
                    baseline.ready().port();
            long baselineOldPid =
                    baseline.pid();
            OnlineReadOnlyShadowBaselineAuthority
                    baselineOldOnly =
                    baselineAuthority(
                            baseline.uri(),
                            baselineTls,
                            baselineOldPins);
            OnlineReadOnlyShadowBaselineAuthority
                    baselineNewOnly =
                    baselineAuthority(
                            baseline.uri(),
                            baselineTls,
                            baselineNewPins);
            OnlineReadOnlyShadowBaselineAuthority
                    baselineRolling =
                    baselineAuthority(
                            baseline.uri(),
                            baselineTls,
                            baselineRollingPins);
            assertThat(baselineOldOnly.ready())
                    .isTrue();
            assertThat(baselineNewOnly.ready())
                    .isFalse();
            assertThat(baselineRolling.ready())
                    .isTrue();

            OnlineReadOnlyShadowBaselineObservation
                    seed =
                    baselineIntegrity.requireVerified(
                            baselineRolling.observe(
                                    baselineCommand));
            OnlineReadOnlyShadowCandidateCommand
                    command =
                    candidateCommand(seed);
            candidate =
                    ChildProvider.start(
                            mapper,
                            candidateConfiguration(
                                    command,
                                    CandidateResponseFault.NONE,
                                    0,
                                    0,
                                    candidateTls),
                            directory.resolve(
                                    "rotation-candidate-old.log"));
            int candidatePort =
                    candidate.ready().port();
            long candidateOldPid =
                    candidate.pid();
            OnlineReadOnlyShadowCandidateAuthority
                    candidateOldOnly =
                    candidateAuthority(
                            candidate.uri(),
                            candidateTls,
                            candidateOldPins);
            OnlineReadOnlyShadowCandidateAuthority
                    candidateNewOnly =
                    candidateAuthority(
                            candidate.uri(),
                            candidateTls,
                            candidateNewPins);
            OnlineReadOnlyShadowCandidateAuthority
                    candidateRolling =
                    candidateAuthority(
                            candidate.uri(),
                            candidateTls,
                            candidateRollingPins);
            assertThat(candidateOldOnly.ready())
                    .isTrue();
            assertThat(candidateNewOnly.ready())
                    .isFalse();
            assertThat(candidateRolling.ready())
                    .isTrue();
            assertCompleteResult(
                    dataPlane(
                            baselineRolling,
                            candidateRolling)
                            .execute(permit(1)));
            assertThat(baseline.audit(mapper)
                    .executions())
                    .isEqualTo(2);
            assertThat(candidate.audit(mapper)
                    .candidateGenerations())
                    .isEqualTo(1);

            candidate.close();
            candidate = null;
            baseline.close();

            baseline =
                    ChildProvider.start(
                            mapper,
                            baselineConfiguration(
                                    nextBaselineTls,
                                    baselinePort),
                            directory.resolve(
                                    "rotation-baseline-next.log"));
            candidate =
                    ChildProvider.start(
                            mapper,
                            candidateConfiguration(
                                    command,
                                    CandidateResponseFault.NONE,
                                    0,
                                    candidatePort,
                                    nextCandidateTls),
                            directory.resolve(
                                    "rotation-candidate-next.log"));

            assertThat(baseline.pid())
                    .isNotEqualTo(
                            baselineOldPid);
            assertThat(candidate.pid())
                    .isNotEqualTo(
                            candidateOldPid);
            assertThat(baselineOldOnly.ready())
                    .isFalse();
            assertThat(baselineNewOnly.ready())
                    .isTrue();
            assertThat(baselineRolling.ready())
                    .isTrue();
            assertThat(candidateOldOnly.ready())
                    .isFalse();
            assertThat(candidateNewOnly.ready())
                    .isTrue();
            assertThat(candidateRolling.ready())
                    .isTrue();

            ReadOnlyShadowDataPlane.ExecutionResult
                    afterRotation =
                    dataPlane(
                            baselineRolling,
                            candidateRolling)
                            .execute(permit(2));
            assertCompleteResult(afterRotation);

            OnlineReadOnlyShadowProviderProcess.Audit
                    baselineAudit =
                    baseline.audit(mapper);
            OnlineReadOnlyShadowProviderProcess.Audit
                    candidateAudit =
                    candidate.audit(mapper);
            assertThat(baselineAudit.executions())
                    .isEqualTo(1);
            assertThat(baselineAudit.exactReads())
                    .isEqualTo(2);
            assertPeer(
                    baselineAudit,
                    nextBaselineTls);
            assertThat(candidateAudit.executions())
                    .isEqualTo(1);
            assertThat(candidateAudit.exactReads())
                    .isEqualTo(1);
            assertThat(candidateAudit
                    .candidateGenerations())
                    .isEqualTo(1);
            assertPeer(
                    candidateAudit,
                    nextCandidateTls);
        } finally {
            if (candidate != null) {
                candidate.close();
            }
            baseline.close();
        }
    }

    @Test
    void recoversCommittedCandidateAfterResponseLossWithoutRegeneration()
            throws Exception {
        try (ChildProvider baseline =
                     ChildProvider.start(
                             mapper,
                             baselineConfiguration(),
                             directory.resolve(
                                     "crash-baseline-process.log"))) {
            OnlineReadOnlyShadowBaselineAuthority
                    baselineAuthority =
                    baselineAuthority(
                            baseline.uri(),
                            baselineTls);
            OnlineReadOnlyShadowBaselineObservation
                    seed =
                    baselineIntegrity.requireVerified(
                            baselineAuthority.observe(
                                    baselineCommand));
            OnlineReadOnlyShadowCandidateCommand
                    candidateCommand =
                    candidateCommand(seed);
            Path candidateLog =
                    directory.resolve(
                            "crash-candidate-process.log");
            OnlineReadOnlyShadowProviderProcess.Configuration
                    initialConfiguration =
                    candidateConfiguration(
                            candidateCommand,
                            CandidateResponseFault
                                    .PROCESS_HALT,
                            0,
                            0);
            ChildProvider candidate =
                    ChildProvider.start(
                            mapper,
                            initialConfiguration,
                            candidateLog);
            try {
                OnlineReadOnlyShadowProviderProcess.Configuration
                        restartConfiguration =
                        candidateConfiguration(
                                candidateCommand,
                                CandidateResponseFault
                                        .PROCESS_HALT,
                                0,
                                candidate.ready().port());
                candidate.writeConfiguration(
                        mapper,
                        restartConfiguration);
                OnlineReadOnlyShadowCandidateAuthority
                        candidateAuthority =
                        candidateAuthority(
                                candidate.uri(),
                                candidateTls);
                GovernedReadOnlyShadowDataPlane dataPlane =
                        dataPlane(
                                baselineAuthority,
                                candidateAuthority);

                assertThatThrownBy(() ->
                        dataPlane.execute(permit(1)))
                        .isInstanceOf(
                                ReadOnlyShadowDataPlane
                                        .Failure.class)
                        .extracting(failure ->
                                ((ReadOnlyShadowDataPlane
                                        .Failure) failure)
                                        .reason())
                        .isEqualTo(
                                ReadOnlyShadowDataPlane
                                        .FailureReason
                                        .CANDIDATE_RUNTIME_UNAVAILABLE);
                assertThat(candidate.process()
                        .waitFor(
                                10,
                                TimeUnit.SECONDS))
                        .isTrue();
                assertThat(candidate.process()
                        .exitValue())
                        .isEqualTo(
                                OnlineReadOnlyShadowProviderProcess
                                        .COMMITTED_RESPONSE_LOSS_EXIT);
                OnlineReadOnlyShadowProviderProcess.Audit
                        crashed =
                        candidate.audit(mapper);
                assertThat(crashed
                        .responseFaultInjected())
                        .isTrue();
                assertThat(crashed
                        .injectedResponseFault())
                        .isEqualTo(
                                CandidateResponseFault
                                        .PROCESS_HALT);
                assertThat(crashed
                        .candidateGenerations())
                        .isEqualTo(1);
                assertThat(Files.isRegularFile(
                        Path.of(
                                restartConfiguration
                                        .candidateStateFile())))
                        .isTrue();
                assertThat(Files.isRegularFile(
                        Path.of(
                                restartConfiguration
                                        .responseFaultMarkerFile())))
                        .isTrue();

                try (ChildProvider restarted =
                             ChildProvider.start(
                                     mapper,
                                     restartConfiguration,
                                     directory.resolve(
                                             "restarted-candidate-process.log"))) {
                    assertThat(restarted.pid())
                            .isNotEqualTo(
                                    candidate.pid());
                    ReadOnlyShadowDataPlane.ExecutionResult
                            recovered =
                            dataPlane.execute(
                                    permit(2));

                    assertCompleteResult(recovered);
                    OnlineReadOnlyShadowProviderProcess.Audit
                            recoveredAudit =
                            restarted.audit(mapper);
                    assertThat(recoveredAudit
                            .candidateGenerations())
                            .isEqualTo(1);
                    assertThat(recoveredAudit.executions())
                            .isEqualTo(1);
                    assertThat(recoveredAudit.exactReads())
                            .isEqualTo(1);
                    assertThat(recoveredAudit
                            .responseFaultInjected())
                            .isFalse();
                    assertThat(recoveredAudit
                            .injectedResponseFault())
                            .isEqualTo(
                                    CandidateResponseFault.NONE);
                    assertPeer(
                            recoveredAudit,
                            candidateTls);
                }
            } finally {
                candidate.close();
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(
            value = CandidateResponseFault.class,
            names = {
                    "TRUNCATED_BODY",
                    "DELAYED_HEADERS",
                    "STALLED_BODY"})
    void retriesCommittedCandidateAfterRetryableNetworkResponseFault(
            CandidateResponseFault fault) throws Exception {
        try (ChildProvider baseline =
                     ChildProvider.start(
                             mapper,
                             baselineConfiguration(),
                             directory.resolve(
                                     "fault-baseline-process.log"))) {
            OnlineReadOnlyShadowBaselineAuthority
                    baselineAuthority =
                    baselineAuthority(
                            baseline.uri(),
                            baselineTls);
            OnlineReadOnlyShadowBaselineObservation
                    seed =
                    baselineIntegrity.requireVerified(
                            baselineAuthority.observe(
                                    baselineCommand));
            OnlineReadOnlyShadowCandidateCommand
                    command =
                    candidateCommand(seed);
            long delayMillis =
                    fault == CandidateResponseFault
                            .TRUNCATED_BODY
                            ? 0 : 750;
            OnlineReadOnlyShadowProviderProcess.Configuration
                    configuration =
                    candidateConfiguration(
                            command,
                            fault,
                            delayMillis,
                            0);
            try (ChildProvider candidate =
                         ChildProvider.start(
                                 mapper,
                                 configuration,
                                 directory.resolve(
                                         "fault-candidate-"
                                                 + fault.name()
                                                 .toLowerCase(
                                                         Locale.ROOT)
                                                 + ".log"))) {
                OnlineReadOnlyShadowCandidateAuthority
                        candidateAuthority =
                        candidateAuthority(
                                candidate.uri(),
                                candidateTls,
                                Duration.ofMillis(250));
                GovernedReadOnlyShadowDataPlane dataPlane =
                        dataPlane(
                                baselineAuthority,
                                candidateAuthority);

                assertThatThrownBy(() ->
                        dataPlane.execute(permit(1)))
                        .isInstanceOf(
                                ReadOnlyShadowDataPlane
                                        .Failure.class)
                        .extracting(failure ->
                                ((ReadOnlyShadowDataPlane
                                        .Failure) failure)
                                        .reason())
                        .isEqualTo(
                                ReadOnlyShadowDataPlane
                                        .FailureReason
                                        .CANDIDATE_RUNTIME_UNAVAILABLE);
                assertThat(candidate.process()
                        .isAlive())
                        .isTrue();
                OnlineReadOnlyShadowProviderProcess.Audit
                        faultAudit =
                        candidate.audit(mapper);
                assertThat(faultAudit
                        .injectedResponseFault())
                        .isEqualTo(fault);
                assertThat(faultAudit
                        .responseFaultInjected())
                        .isTrue();
                assertThat(faultAudit
                        .candidateGenerations())
                        .isEqualTo(1);
                assertThat(faultAudit.executions())
                        .isEqualTo(1);
                assertThat(faultAudit.exactReads())
                        .isZero();
                assertThat(Files.readString(
                        Path.of(configuration
                                .responseFaultMarkerFile())))
                        .isEqualTo(fault.name());

                ReadOnlyShadowDataPlane.ExecutionResult
                        recovered =
                        dataPlane.execute(permit(2));

                assertCompleteResult(recovered);
                OnlineReadOnlyShadowProviderProcess.Audit
                        recoveredAudit =
                        candidate.audit(mapper);
                assertThat(recoveredAudit.executions())
                        .isEqualTo(2);
                assertThat(recoveredAudit.exactReads())
                        .isEqualTo(1);
                assertThat(recoveredAudit
                        .candidateGenerations())
                        .isEqualTo(1);
                assertThat(recoveredAudit
                        .injectedResponseFault())
                        .isEqualTo(fault);
                assertThat(recoveredAudit
                        .responseFaultInjected())
                        .isTrue();
                assertPeer(
                        recoveredAudit,
                        candidateTls);
            }
        }
    }

    private GovernedReadOnlyShadowDataPlane dataPlane(
            OnlineReadOnlyShadowBaselineAuthority
                    baselineAuthority,
            OnlineReadOnlyShadowCandidateAuthority
                    candidateAuthority) {
        ReadOnlyShadowSourceResolutionAttestationRepository
                attestations =
                mock(
                        ReadOnlyShadowSourceResolutionAttestationRepository
                                .class);
        when(attestations.create(any()))
                .thenAnswer(answer ->
                        answer.getArgument(0));
        OnlineReadOnlyShadowBaselineConnector baseline =
                new OnlineReadOnlyShadowBaselineConnector(
                        baselineAuthority,
                        baselineIntegrity,
                        mapper,
                        RESOLUTION_CLOCK);
        OnlineReadOnlyShadowCandidateConnector candidate =
                new OnlineReadOnlyShadowCandidateConnector(
                        baselineAuthority,
                        baselineIntegrity,
                        candidateAuthority,
                        candidateIntegrity,
                        policy,
                        mapper,
                        RESOLUTION_CLOCK);
        OnlineReadOnlyShadowSourceResolutionVerifier resolver =
                new OnlineReadOnlyShadowSourceResolutionVerifier(
                        baselineAuthority,
                        baselineIntegrity,
                        candidateAuthority,
                        candidateIntegrity,
                        policy,
                        attestations,
                        resolutionIntegrity,
                        mapper,
                        RESOLUTION_CLOCK);
        return new GovernedReadOnlyShadowDataPlane(
                authority(),
                guard(),
                baseline,
                candidate,
                resolver,
                policy,
                RESOLUTION_CLOCK);
    }

    private ReadOnlyShadowAccessAuthority authority() {
        return new ReadOnlyShadowAccessAuthority() {
            @Override
            public boolean ready() {
                return true;
            }

            @Override
            public Admission admit(
                    ReadOnlyShadowDataPlane.Permit permit) {
                return admission;
            }

            @Override
            public Confirmation confirm(
                    Admission admitted,
                    Instant startedAt,
                    Instant completedAt) {
                return ReadOnlyShadowSourceResolutionTestFixtures
                        .confirmation(admission);
            }
        };
    }

    private static ReadOnlyShadowExecutionGuard guard() {
        ReadOnlyShadowExecutionGuard guard =
                mock(ReadOnlyShadowExecutionGuard.class);
        ReadOnlyShadowExecutionGuard.Lease lease =
                mock(ReadOnlyShadowExecutionGuard.Lease.class);
        when(guard.ready()).thenReturn(true);
        when(guard.acquire(any(), any()))
                .thenReturn(lease);
        return guard;
    }

    private ReadOnlyShadowDataPlane.Permit permit(
            int attempt) {
        return new ReadOnlyShadowDataPlane.Permit(
                baselineCommand.executionId(),
                request,
                attempt,
                request.deadlineAt(),
                new ReadOnlyShadowDataPlane
                        .ExecutionControl() {
                    @Override
                    public Instant leaseExpiresAt() {
                        return request.deadlineAt();
                    }

                    @Override
                    public Instant heartbeat() {
                        return request.deadlineAt();
                    }
                });
    }

    private OnlineReadOnlyShadowBaselineAuthority
    baselineAuthority(
            URI uri,
            RecoveryFleetPublicationTlsFixture.Material
                    material) {
        return baselineAuthority(
                uri,
                material,
                Set.of(spkiPin(
                        material
                                .serverCertificate())));
    }

    private OnlineReadOnlyShadowBaselineAuthority
    baselineAuthority(
            URI uri,
            RecoveryFleetPublicationTlsFixture.Material
                    material,
            Set<String> serverSpkiPins) {
        return new HttpOnlineReadOnlyShadowBaselineAuthority(
                mapper,
                RESOLUTION_CLOCK,
                OnlineReadOnlyShadowBaselineTransport.from(
                        transport(
                                material,
                                serverSpkiPins)),
                new HttpOnlineReadOnlyShadowBaselineAuthority
                        .Settings(
                        uri,
                        Duration.ofSeconds(3),
                        512 * 1024,
                        false),
                (operation, target) -> Map.of(
                        "Authorization",
                        "BLOGE baseline-process-certification"));
    }

    private OnlineReadOnlyShadowCandidateAuthority
    candidateAuthority(
            URI uri,
            RecoveryFleetPublicationTlsFixture.Material
                    material) {
        return candidateAuthority(
                uri,
                material,
                Set.of(spkiPin(
                        material
                                .serverCertificate())),
                Duration.ofSeconds(3));
    }

    private OnlineReadOnlyShadowCandidateAuthority
    candidateAuthority(
            URI uri,
            RecoveryFleetPublicationTlsFixture.Material
                    material,
            Duration requestTimeout) {
        return candidateAuthority(
                uri,
                material,
                Set.of(spkiPin(
                        material
                                .serverCertificate())),
                requestTimeout);
    }

    private OnlineReadOnlyShadowCandidateAuthority
    candidateAuthority(
            URI uri,
            RecoveryFleetPublicationTlsFixture.Material
                    material,
            Set<String> serverSpkiPins) {
        return candidateAuthority(
                uri,
                material,
                serverSpkiPins,
                Duration.ofSeconds(3));
    }

    private OnlineReadOnlyShadowCandidateAuthority
    candidateAuthority(
            URI uri,
            RecoveryFleetPublicationTlsFixture.Material
                    material,
            Set<String> serverSpkiPins,
            Duration requestTimeout) {
        return new HttpOnlineReadOnlyShadowCandidateAuthority(
                mapper,
                RESOLUTION_CLOCK,
                OnlineReadOnlyShadowCandidateTransport.from(
                        transport(
                                material,
                                serverSpkiPins)),
                new HttpOnlineReadOnlyShadowCandidateAuthority
                        .Settings(
                        uri,
                        requestTimeout,
                        2 * 1024 * 1024,
                        false),
                (operation, target) -> Map.of(
                        "Authorization",
                        "BLOGE candidate-process-certification"));
    }

    private static PinnedMutualTlsRecoveryFleetPublicationTransport
    transport(
            RecoveryFleetPublicationTlsFixture.Material
                    material,
            Set<String> serverSpkiPins) {
        String issuerPin =
                spkiPin(
                        material.certificateAuthority());
        ControlPlaneCertificateIdentityPolicy policy =
                new ControlPlaneCertificateIdentityPolicy(
                        material.clientCertificate()
                                .getSubjectX500Principal()
                                .getName(),
                        material.clientUriSan(),
                        Set.of(issuerPin),
                        material.serverUriSan(),
                        Set.of(issuerPin));
        var settings =
                new PinnedMutualTlsRecoveryFleetPublicationTransport
                        .Settings(
                        material.trustStore(),
                        "test:trust",
                        material.clientKeyStore(),
                        "test:client",
                        serverSpkiPins,
                        policy);
        return new PinnedMutualTlsRecoveryFleetPublicationTransport(
                settings,
                reference ->
                        RecoveryFleetPublicationTlsFixture
                                .password());
    }

    private OnlineReadOnlyShadowProviderProcess.Configuration
    baselineConfiguration() {
        return baselineConfiguration(
                baselineTls,
                0);
    }

    private OnlineReadOnlyShadowProviderProcess.Configuration
    baselineConfiguration(
            RecoveryFleetPublicationTlsFixture.Material
                    serverMaterial,
            int port) {
        Path process =
                directory.resolve("baseline-process");
        return new OnlineReadOnlyShadowProviderProcess
                .Configuration(
                OnlineReadOnlyShadowProviderProcess
                        .Configuration.SCHEMA_VERSION,
                OnlineReadOnlyShadowProviderProcess.Role
                        .BASELINE,
                port,
                serverMaterial.serverKeyStore()
                        .toAbsolutePath().toString(),
                serverMaterial.trustStore()
                        .toAbsolutePath().toString(),
                new String(
                        RecoveryFleetPublicationTlsFixture
                                .password()),
                baselineTls.clientCertificate()
                        .getSubjectX500Principal()
                        .getName(),
                baselineTls.clientUriSan(),
                BASELINE_CLOCK.instant(),
                process.resolve("ready.json")
                        .toAbsolutePath().toString(),
                process.resolve("audit.json")
                        .toAbsolutePath().toString(),
                process.resolve("unused-state.json")
                        .toAbsolutePath().toString(),
                process.resolve("unused-crash")
                        .toAbsolutePath().toString(),
                CandidateResponseFault.NONE,
                0,
                baselineKey,
                baselineCommand,
                baselineFixture,
                null,
                null,
                "");
    }

    private OnlineReadOnlyShadowProviderProcess.Configuration
    candidateConfiguration(
            OnlineReadOnlyShadowCandidateCommand command,
            CandidateResponseFault responseFault,
            long responseFaultDelayMillis,
            int port) {
        return candidateConfiguration(
                command,
                responseFault,
                responseFaultDelayMillis,
                port,
                candidateTls);
    }

    private OnlineReadOnlyShadowProviderProcess.Configuration
    candidateConfiguration(
            OnlineReadOnlyShadowCandidateCommand command,
            CandidateResponseFault responseFault,
            long responseFaultDelayMillis,
            int port,
            RecoveryFleetPublicationTlsFixture.Material
                    serverMaterial) {
        Path process =
                directory.resolve("candidate-process");
        return new OnlineReadOnlyShadowProviderProcess
                .Configuration(
                OnlineReadOnlyShadowProviderProcess
                        .Configuration.SCHEMA_VERSION,
                OnlineReadOnlyShadowProviderProcess.Role
                        .CANDIDATE,
                port,
                serverMaterial.serverKeyStore()
                        .toAbsolutePath().toString(),
                serverMaterial.trustStore()
                        .toAbsolutePath().toString(),
                new String(
                        RecoveryFleetPublicationTlsFixture
                                .password()),
                candidateTls.clientCertificate()
                        .getSubjectX500Principal()
                        .getName(),
                candidateTls.clientUriSan(),
                CANDIDATE_CLOCK.instant(),
                process.resolve("ready.json")
                        .toAbsolutePath().toString(),
                process.resolve("audit.json")
                        .toAbsolutePath().toString(),
                process.resolve("candidate-state.json")
                        .toAbsolutePath().toString(),
                process.resolve("committed-response-fault")
                        .toAbsolutePath().toString(),
                responseFault,
                responseFaultDelayMillis,
                candidateKey,
                null,
                null,
                command,
                plan,
                fingerprint('f'));
    }

    private ReadOnlyShadowJobRequest request(
            MirrorPlan exactPlan) {
        ReadOnlyShadowJobRequest source =
                OnlineReadOnlyShadowBaselineTestFixtures
                        .request("synthetic-pair");
        return new ReadOnlyShadowJobRequest(
                source.schemaVersion(),
                source.requestId(),
                exactPlan.scope(),
                source.inventoryRef(),
                source.unitId(),
                source.scenarioCaseRef(),
                exactPlan.rootCapability(),
                new MirrorArtifactRef(
                        "MIRROR_PLAN",
                        exactPlan.planId(),
                        1,
                        exactPlan.planFingerprint()),
                source.baselineBindingRef(),
                policy.reference(),
                source.accessGrant(),
                NOW.plus(Duration.ofMinutes(5)));
    }

    private SyntheticRegionalReadOnlyShadowProvider
            .BaselineFixture baselineFixture() {
        return new SyntheticRegionalReadOnlyShadowProvider
                .BaselineFixture(
                request.baselineBindingRef(),
                ref(
                        "WORKLOAD_IDENTITY",
                        "process-read-identity",
                        'a'),
                ref(
                        "WORKLOAD_IDENTITY_ATTESTATION",
                        "process-read-identity",
                        'b'),
                ref(
                        "PAYLOAD_VAULT_RECEIPT",
                        "process-vault-receipt",
                        'c'),
                ref(
                        "READ_ONLY_TRANSPORT_ATTESTATION",
                        "process-read-transport",
                        'd'),
                fingerprint('e'),
                fingerprint('f'),
                fingerprint('1'),
                fingerprint('2'),
                ref(
                        "JSON_SCHEMA",
                        "process-response",
                        '3'),
                Map.of(
                        DomainFidelityProfile.Dimension
                                .BEHAVIOR,
                        fingerprint('f'),
                        DomainFidelityProfile.Dimension
                                .CONTRACT,
                        plan.capabilityClosureFingerprint()),
                MirrorRunEvidence.EvidenceClass
                        .CERTIFIABLE,
                true);
    }

    private OnlineReadOnlyShadowBaselineCommand
    baselineCommand() {
        return new OnlineReadOnlyShadowBaselineCommand(
                OnlineReadOnlyShadowBaselineCommand
                        .SCHEMA_VERSION,
                "execution-process-pair",
                request.requestId(),
                request.scope(),
                request.inventoryRef(),
                request.unitId(),
                request.scenarioCaseRef(),
                request.targetCapabilityRef(),
                request.baselineBindingRef(),
                request.comparisonPolicyRef(),
                request.accessGrant(),
                admission.admissionFingerprint(),
                admission.admittedAt(),
                admission.validUntil());
    }

    private OnlineReadOnlyShadowCandidateCommand
    candidateCommand(
            OnlineReadOnlyShadowBaselineObservation baseline) {
        return new OnlineReadOnlyShadowCandidateCommand(
                OnlineReadOnlyShadowCandidateCommand
                        .SCHEMA_VERSION,
                baselineCommand.executionId(),
                request.requestId(),
                request.scope(),
                request.inventoryRef(),
                request.unitId(),
                request.scenarioCaseRef(),
                request.targetCapabilityRef(),
                request.candidatePlanRef(),
                request.comparisonPolicyRef(),
                baseline.artifactRef(),
                baseline.payloadVaultReceiptRef(),
                baseline.requestContextFingerprint(),
                request.accessGrant(),
                admission.admissionFingerprint(),
                admission.admittedAt(),
                admission.validUntil());
    }

    private static void assertCompleteResult(
            ReadOnlyShadowDataPlane.ExecutionResult result) {
        assertThat(result.baseline().role())
                .isEqualTo(
                        ReadOnlyShadowComparison.SourceRole
                                .BASELINE);
        assertThat(result.candidate().role())
                .isEqualTo(
                        ReadOnlyShadowComparison.SourceRole
                                .CANDIDATE);
        assertThat(result.baseline()
                .requestContextFingerprint())
                .isEqualTo(
                        result.candidate()
                                .requestContextFingerprint());
        assertThat(result.sourceResolutionAttestationRef()
                .kind())
                .isEqualTo(
                        ReadOnlyShadowSourceResolutionAttestation
                                .ARTIFACT_KIND);
        assertThat(result.results())
                .isNotEmpty();
        assertThat(result.accessProof()
                .writeCredentialExposed())
                .isFalse();
        assertThat(result.accessProof()
                .writeAttemptCount())
                .isZero();
    }

    private static void assertPeer(
            OnlineReadOnlyShadowProviderProcess.Audit audit,
            RecoveryFleetPublicationTlsFixture.Material
                    material) {
        assertThat(audit.failureCode())
                .isEmpty();
        assertThat(audit.peerSubject())
                .isEqualTo(
                        material.clientCertificate()
                                .getSubjectX500Principal()
                                .getName());
        assertThat(audit.peerUriSan())
                .isEqualTo(
                        material.clientUriSan());
    }

    private static OnlineReadOnlyShadowProviderProcess
            .EvidenceKeyMaterial evidenceKey(
            String keyId,
            Instant createdAt) throws Exception {
        KeyPairGenerator generator =
                KeyPairGenerator.getInstance(
                        "Ed25519");
        KeyPair keyPair =
                generator.generateKeyPair();
        return new OnlineReadOnlyShadowProviderProcess
                .EvidenceKeyMaterial(
                keyId,
                Base64.getEncoder()
                        .encodeToString(
                                keyPair.getPublic()
                                        .getEncoded()),
                Base64.getEncoder()
                        .encodeToString(
                                keyPair.getPrivate()
                                        .getEncoded()),
                createdAt);
    }

    private static String spkiPin(
            X509Certificate certificate) {
        return PinnedMutualTlsRecoveryFleetPublicationTransport
                .spkiPin(certificate);
    }

    private static MirrorArtifactRef ref(
            String kind,
            String id,
            char material) {
        return new MirrorArtifactRef(
                kind,
                id,
                1,
                fingerprint(material));
    }

    private static String fingerprint(
            char material) {
        return "sha256:"
                + String.valueOf(material)
                .repeat(64);
    }

    private static final class PublicEvidenceSigner
            implements VisualEvidenceSigner {
        private final OnlineReadOnlyShadowProviderProcess
                .EvidenceKeyMaterial material;
        private final PublicKey publicKey;

        private PublicEvidenceSigner(
                OnlineReadOnlyShadowProviderProcess
                        .EvidenceKeyMaterial material)
                throws Exception {
            this.material = material;
            this.publicKey =
                    KeyFactory.getInstance(
                            "Ed25519")
                            .generatePublic(
                                    new X509EncodedKeySpec(
                                            Base64.getDecoder()
                                                    .decode(
                                                            material
                                                                    .encodedPublicKey())));
        }

        @Override
        public VisualRunEvidenceSeal seal(
                String materialFingerprint) {
            throw new IllegalStateException(
                    "public verifier cannot sign evidence");
        }

        @Override
        public Verification verify(
                VisualRunEvidenceSeal seal,
                String actualMaterialFingerprint) {
            try {
                if (seal == null
                        || !material.keyId()
                        .equals(seal.keyId())
                        || !"Ed25519".equals(
                        seal.algorithm())
                        || !actualMaterialFingerprint
                        .equals(
                                seal.materialFingerprint())) {
                    return new Verification(
                            false,
                            "INVALID",
                            "Evidence coordinates are invalid.");
                }
                Signature verifier =
                        Signature.getInstance(
                                "Ed25519");
                verifier.initVerify(publicKey);
                verifier.update(
                        actualMaterialFingerprint
                                .getBytes(
                                        StandardCharsets.UTF_8));
                boolean verified =
                        verifier.verify(
                                Base64.getDecoder()
                                        .decode(
                                                seal.signature()));
                return verified
                        ? new Verification(
                        true, "VERIFIED", "")
                        : new Verification(
                        false,
                        "INVALID",
                        "Evidence signature is invalid.");
            } catch (Exception invalid) {
                return new Verification(
                        false,
                        "INVALID",
                        "Evidence signature cannot be verified.");
            }
        }

        @Override
        public Optional<VerificationKey> key(
                String keyId) {
            if (!material.keyId()
                    .equals(keyId)) {
                return Optional.empty();
            }
            return Optional.of(
                    new VerificationKey(
                            "",
                            material.keyId(),
                            "Ed25519",
                            material.encodedPublicKey(),
                            material.createdAt(),
                            "ACTIVE",
                            "PROCESS_CERTIFICATION"));
        }

        @Override
        public boolean available() {
            return true;
        }
    }

    private record ChildProvider(
            Process process,
            OnlineReadOnlyShadowProviderProcess.Ready ready,
            Path configurationFile,
            Path auditFile,
            Path logFile
    ) implements AutoCloseable {

        private static ChildProvider start(
                ObjectMapper mapper,
                OnlineReadOnlyShadowProviderProcess
                        .Configuration configuration,
                Path logFile) throws Exception {
            Path readyFile =
                    Path.of(configuration.readyFile());
            Path auditFile =
                    Path.of(configuration.auditFile());
            Files.createDirectories(
                    readyFile.getParent());
            Files.deleteIfExists(readyFile);
            Files.deleteIfExists(auditFile);
            Files.createDirectories(
                    logFile.getParent());
            Path configurationFile =
                    readyFile.getParent()
                            .resolve("configuration.json");
            mapper.writeValue(
                    configurationFile.toFile(),
                    configuration);
            String classPath =
                    System.getProperty(
                            "surefire.test.class.path",
                            System.getProperty(
                                    "java.class.path"));
            Process process =
                    new ProcessBuilder(
                            Path.of(
                                    System.getProperty(
                                            "java.home"),
                                    "bin",
                                    "java")
                                    .toString(),
                            "--enable-preview",
                            "-cp",
                            classPath,
                            OnlineReadOnlyShadowProviderProcess
                                    .class.getName(),
                            configurationFile
                                    .toAbsolutePath()
                                    .toString())
                            .redirectErrorStream(true)
                            .redirectOutput(logFile.toFile())
                            .start();
            Instant deadline =
                    Instant.now()
                            .plusSeconds(15);
            while (Instant.now()
                    .isBefore(deadline)
                    && process.isAlive()
                    && !Files.isRegularFile(
                    readyFile)) {
                Thread.sleep(25);
            }
            if (!Files.isRegularFile(readyFile)) {
                String output =
                        Files.exists(logFile)
                                ? Files.readString(
                                logFile)
                                : "";
                process.destroyForcibly();
                throw new IllegalStateException(
                        "provider process did not become ready: "
                                + output);
            }
            OnlineReadOnlyShadowProviderProcess.Ready ready =
                    mapper.readValue(
                            readyFile.toFile(),
                            OnlineReadOnlyShadowProviderProcess
                                    .Ready.class);
            if (!OnlineReadOnlyShadowProviderProcess
                    .Ready.SCHEMA_VERSION.equals(
                            ready.schemaVersion())
                    || ready.role()
                    != configuration.role()
                    || ready.pid() != process.pid()
                    || ready.port() < 1) {
                process.destroyForcibly();
                throw new IllegalStateException(
                        "provider readiness coordinates are invalid");
            }
            return new ChildProvider(
                    process,
                    ready,
                    configurationFile,
                    auditFile,
                    logFile);
        }

        private URI uri() {
            return URI.create(
                    "https://localhost:"
                            + ready.port());
        }

        private long pid() {
            return ready.pid();
        }

        private OnlineReadOnlyShadowProviderProcess.Audit
        audit(
                ObjectMapper mapper) throws Exception {
            Instant deadline =
                    Instant.now()
                            .plusSeconds(5);
            while (Instant.now()
                    .isBefore(deadline)
                    && !Files.isRegularFile(
                    auditFile)) {
                Thread.sleep(10);
            }
            return mapper.readValue(
                    auditFile.toFile(),
                    OnlineReadOnlyShadowProviderProcess
                            .Audit.class);
        }

        private void writeConfiguration(
                ObjectMapper mapper,
                OnlineReadOnlyShadowProviderProcess
                        .Configuration configuration)
                throws IOException {
            mapper.writeValue(
                    configurationFile.toFile(),
                    configuration);
        }

        @Override
        public void close() throws Exception {
            if (!process.isAlive()) {
                return;
            }
            process.destroy();
            if (!process.waitFor(
                    5,
                    TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(
                        5,
                        TimeUnit.SECONDS);
            }
        }
    }
}
