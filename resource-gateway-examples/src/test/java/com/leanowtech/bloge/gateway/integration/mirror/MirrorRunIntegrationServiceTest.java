package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.model.ReservedKeys;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.planning.CompiledMirrorPlan;
import com.leanowtech.bloge.gateway.testing.runtime.MirrorRunRejectedException;
import com.leanowtech.bloge.gateway.testing.runtime.MirrorRunRequest;
import com.leanowtech.bloge.gateway.testing.runtime.MirrorRunResult;
import com.leanowtech.bloge.gateway.testing.runtime.MirrorRunService;
import com.leanowtech.bloge.gateway.testing.runtime.MirrorRunEvidenceProjector;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MirrorRunIntegrationServiceTest {
    private static final Instant NOW = MirrorPersistenceTestFixtures.COMPILED_AT.plusSeconds(20);
    private static final CapabilitySnapshot.Scope SCOPE =
            MirrorPersistenceTestFixtures.scope("org-a");

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private MirrorPlanIntegrationService plans;
    private MirrorRunService runtime;
    private MirrorRunRequestRepository requests;
    private MirrorEvidenceRepository evidence;
    private MirrorRunCommitService commits;
    private MirrorRunIntegrationService service;
    private MirrorPlan plan;
    private CompiledMirrorPlan generation;
    private MirrorEvidenceBundle bundle;

    @BeforeEach
    void setUp() {
        plans = mock(MirrorPlanIntegrationService.class);
        runtime = mock(MirrorRunService.class);
        requests = mock(MirrorRunRequestRepository.class);
        evidence = mock(MirrorEvidenceRepository.class);
        commits = mock(MirrorRunCommitService.class);
        service = new MirrorRunIntegrationService(plans, runtime, requests, evidence,
                commits, mapper, Clock.fixed(NOW, ZoneOffset.UTC));
        plan = MirrorPersistenceTestFixtures.plan(mapper, SCOPE, "plan-1", 'a');
        generation = mock(CompiledMirrorPlan.class);
        String contextFingerprint = ProtocolFingerprint.of(mapper, Map.of(
                "customerId", "C-1",
                ReservedKeys.TENANT_ID, SCOPE.tenantId(),
                ReservedKeys.NAMESPACE, SCOPE.projectId()));
        bundle = MirrorPersistenceTestFixtures.evidence(mapper,
                new InMemoryVisualEvidenceSigner(), plan, "run-1", 'b',
                "request-1", contextFingerprint);
        when(plans.find("plan-1", identity())).thenReturn(plan);
        when(plans.materialize(plan, identity())).thenReturn(generation);
    }

    @Test
    void bindsAuthenticatedScopeExecutesOnceAndReturnsOnlyPayloadFreeSummary() {
        when(requests.claim(any(), anyString(), any())).thenAnswer(invocation ->
                acquired(invocation.getArgument(0), "owner-a", 1));
        MirrorRunResult result = mock(MirrorRunResult.class);
        when(result.evidenceBundle()).thenReturn(bundle);
        when(runtime.execute(any())).thenReturn(result);
        when(commits.commit(any(), any())).thenReturn(bundle);

        MirrorRunSummary summary = service.execute(request(), identity());

        assertThat(summary.runId()).isEqualTo("run-1");
        assertThat(summary.requestContextFingerprint())
                .isEqualTo(bundle.evidence().requestContextFingerprint());
        assertThat(summary.nodeTraceCount()).isZero();
        ArgumentCaptor<MirrorRunRequest> runtimeRequest =
                ArgumentCaptor.forClass(MirrorRunRequest.class);
        verify(runtime).execute(runtimeRequest.capture());
        assertThat(runtimeRequest.getValue().context().asMap())
                .containsEntry("customerId", "C-1")
                .containsEntry(ReservedKeys.TENANT_ID, SCOPE.tenantId())
                .containsEntry(ReservedKeys.NAMESPACE, SCOPE.projectId());
        ArgumentCaptor<MirrorRunRequestRepository.Registration> registration =
                ArgumentCaptor.forClass(MirrorRunRequestRepository.Registration.class);
        verify(requests).claim(registration.capture(), anyString(), any());
        assertThat(registration.getValue().contextFingerprint())
                .isEqualTo(bundle.evidence().requestContextFingerprint());
        assertThat(registration.getValue().retainUntil())
                .isEqualTo(NOW.plus(MirrorRunIntegrationService.REQUEST_RETENTION));
    }

    @Test
    void completedRetryReadsVerifiedEvidenceWithoutRecompilingOrReexecuting() {
        when(requests.claim(any(), anyString(), any())).thenAnswer(invocation -> {
            MirrorRunRequestRepository.Registration registration = invocation.getArgument(0);
            return completed(registration, bundle);
        });
        when(evidence.find(SCOPE, "run-1")).thenReturn(Optional.of(bundle));

        MirrorRunSummary summary = service.execute(request(), identity());

        assertThat(summary.evidenceBundleFingerprint()).isEqualTo(bundle.bundleFingerprint());
        verify(plans, never()).materialize(any(), any());
        verify(runtime, never()).execute(any());
        verify(commits, never()).commit(any(), any());
    }

    @Test
    void reportsBusyLeaseAndIdempotencyConflictWithoutExecuting() {
        when(requests.claim(any(), anyString(), any())).thenAnswer(invocation -> {
            MirrorRunRequestRepository.Registration registration = invocation.getArgument(0);
            var state = state(registration, MirrorRunRequestRepository.Status.ACTIVE,
                    "owner-existing", 3, NOW.plusSeconds(19), "", "");
            return new MirrorRunRequestRepository.Claim(
                    MirrorRunRequestRepository.Outcome.IN_PROGRESS, state, null, 19);
        });
        assertProblem(() -> service.execute(request(), identity()), 409,
                "RG.MIRROR.RUN_REQUEST_IN_PROGRESS", true);
        verify(runtime, never()).execute(any());

        doThrow(new MirrorRunRequestConflictException()).when(requests)
                .claim(any(), anyString(), any());
        assertProblem(() -> service.execute(request(), identity()), 409,
                "RG.MIRROR.RUN_IDEMPOTENCY_CONFLICT", false);
    }

    @Test
    void rejectsCallerOwnedScopeAndInternalNodeOutputKeysBeforeClaiming() {
        for (String reserved : new String[]{ReservedKeys.TENANT_ID,
                ReservedKeys.NAMESPACE, "__nodeOutput:3:old:value"}) {
            MirrorExecutionRequest invalid = new MirrorExecutionRequest("", "request-1",
                    "plan-1", plan.planFingerprint(), Map.of(reserved, "forged"));
            assertProblem(() -> service.execute(invalid, identity()), 400,
                    "RG.MIRROR.CONTEXT_RESERVED_KEY", false);
        }
        verify(requests, never()).claim(any(), anyString(), any());
    }

    @Test
    void releasesRejectedAttemptAndHidesEvidenceOutsideFullScope() {
        when(requests.claim(any(), anyString(), any())).thenAnswer(invocation ->
                acquired(invocation.getArgument(0), "owner-a", 1));
        when(runtime.execute(any())).thenThrow(new MirrorRunRejectedException(
                "RG.MIRROR.RUN_EXPIRED", java.util.List.of()));

        assertProblem(() -> service.execute(request(), identity()), 410,
                "RG.MIRROR.RUN_EXPIRED", false);
        verify(requests).release(any(),
                org.mockito.ArgumentMatchers.eq("RG.MIRROR.RUN_EXPIRED"));

        IntegrationRequestContext other = identity("org-b");
        when(evidence.find(new CapabilitySnapshot.Scope(
                "tenant-a", "org-b", "support", "test", "sg"), "run-1"))
                .thenReturn(Optional.empty());
        assertProblem(() -> service.find("run-1", other), 404,
                "RG.MIRROR.RUN_NOT_FOUND", false);
    }

    @Test
    void rejectsAPlanFingerprintChangeBeforeDurableClaim() {
        MirrorExecutionRequest stale = new MirrorExecutionRequest("", "request-1", "plan-1",
                MirrorPersistenceTestFixtures.fingerprint('f'), Map.of("customerId", "C-1"));

        assertProblem(() -> service.execute(stale, identity()), 409,
                "RG.MIRROR.PLAN_FINGERPRINT_CONFLICT", false);
        verify(requests, never()).claim(any(), anyString(), any());
    }

    @Test
    void rejectsAnEffectiveContextThatCannotFitTheEvidenceFingerprintBoundary() {
        MirrorExecutionRequest oversized = new MirrorExecutionRequest("", "request-1", "plan-1",
                plan.planFingerprint(), Map.of("value",
                "x".repeat(MirrorRunEvidenceProjector.MAXIMUM_PAYLOAD_BYTES)));

        assertProblem(() -> service.execute(oversized, identity()), 400,
                "RG.MIRROR.CONTEXT_TOO_LARGE", false);
        verify(requests, never()).claim(any(), anyString(), any());
    }

    private MirrorExecutionRequest request() {
        return new MirrorExecutionRequest("", "request-1", "plan-1",
                plan.planFingerprint(), Map.of("customerId", "C-1"));
    }

    private static MirrorRunRequestRepository.Claim acquired(
            MirrorRunRequestRepository.Registration registration,
            String owner,
            long epoch) {
        var state = state(registration, MirrorRunRequestRepository.Status.ACTIVE,
                owner, epoch, NOW.plusSeconds(60), "", "");
        return new MirrorRunRequestRepository.Claim(MirrorRunRequestRepository.Outcome.ACQUIRED,
                state, new MirrorRunRequestRepository.Lease(
                registration.scope(), registration.requestId(), owner, epoch), 0);
    }

    private static MirrorRunRequestRepository.Claim completed(
            MirrorRunRequestRepository.Registration registration,
            MirrorEvidenceBundle evidence) {
        var state = state(registration, MirrorRunRequestRepository.Status.COMPLETED,
                "owner-a", 1, NOW, evidence.evidence().runId(),
                evidence.bundleFingerprint());
        return new MirrorRunRequestRepository.Claim(MirrorRunRequestRepository.Outcome.COMPLETED,
                state, null, 0);
    }

    private static MirrorRunRequestRepository.State state(
            MirrorRunRequestRepository.Registration registration,
            MirrorRunRequestRepository.Status status,
            String owner,
            long epoch,
            Instant leaseExpiresAt,
            String runId,
            String bundleFingerprint) {
        return new MirrorRunRequestRepository.State(registration, status, owner, epoch,
                leaseExpiresAt, runId, bundleFingerprint, "", NOW.minusSeconds(1), NOW);
    }

    private static IntegrationRequestContext identity() {
        return identity("org-a");
    }

    private static IntegrationRequestContext identity(String organization) {
        return new IntegrationRequestContext("tenant-a", organization, "support", "test", "sg",
                "SERVICE", "mirror-client", "", "MIRROR_REHEARSAL", "corr-1",
                Set.of("quality"), "CONFIDENTIAL", "");
    }

    private static void assertProblem(
            Runnable action, int status, String code, boolean retryable) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(IntegrationProblemException.class, failure -> {
                    assertThat(failure.problem().status()).isEqualTo(status);
                    assertThat(failure.problem().code()).isEqualTo(code);
                    assertThat(failure.problem().retryable()).isEqualTo(retryable);
                });
    }
}
