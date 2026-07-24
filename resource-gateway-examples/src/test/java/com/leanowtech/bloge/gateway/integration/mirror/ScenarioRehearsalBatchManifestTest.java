package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ScenarioRehearsalBatchManifestTest {
    private static final CapabilitySnapshot.Scope SCOPE =
            new CapabilitySnapshot.Scope(
                    "tenant-a", "org-a", "support", "test", "sg");
    private final ObjectMapper mapper =
            new ObjectMapper().findAndRegisterModules();

    @Test
    void compilerFreezesExactPlansAndStableAggregateIdentities() {
        ScenarioRehearsalIntegrationService rehearsals =
                mock(ScenarioRehearsalIntegrationService.class);
        MirrorArtifactRef first = planRef("refund", 'a');
        MirrorArtifactRef second = planRef("escalation", 'b');
        CompiledScenarioRehearsalPlan firstPlan =
                plan(SCOPE, 2, Duration.ofMinutes(2));
        CompiledScenarioRehearsalPlan secondPlan =
                plan(SCOPE, 3, Duration.ofMinutes(3));
        when(rehearsals.find(
                first.id(), first.revision(),
                first.fingerprint(), identity()))
                .thenReturn(firstPlan);
        when(rehearsals.find(
                second.id(), second.revision(),
                second.fingerprint(), identity()))
                .thenReturn(secondPlan);
        ScenarioRehearsalBatchCompiler compiler =
                new ScenarioRehearsalBatchCompiler(
                        rehearsals, mapper);
        ScenarioRehearsalBatchRequest request =
                request("batch-001", first, second);

        ScenarioRehearsalBatchManifest firstCompilation =
                compiler.compile(request, identity());
        ScenarioRehearsalBatchManifest replay =
                compiler.compile(request, identity());

        assertThat(replay).isEqualTo(firstCompilation);
        assertThat(firstCompilation.totalCases()).isEqualTo(5);
        assertThat(firstCompilation.entries())
                .extracting(
                        ScenarioRehearsalBatchManifest.Entry::entryIndex,
                        ScenarioRehearsalBatchManifest.Entry::aggregateRequestId,
                        ScenarioRehearsalBatchManifest.Entry::caseCount)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                0, "batch-001:plan:000", 2),
                        org.assertj.core.groups.Tuple.tuple(
                                1, "batch-001:plan:001", 3));
        assertThat(firstCompilation.entries())
                .allSatisfy(entry -> assertThat(
                        ScenarioRehearsalRunIdentity.derive(
                                mapper,
                                SCOPE,
                                entry.aggregateRequestId()))
                        .isEqualTo(entry.aggregateRunId()));
        ScenarioRehearsalBatchManifestIntegrity.verify(
                mapper, firstCompilation);
        assertThat(firstCompilation.reference().fingerprint())
                .isEqualTo(firstCompilation.manifestFingerprint());
    }

    @Test
    void fullEnterpriseScopeParticipatesInBatchAndRunIdentities() {
        MirrorArtifactRef ref = planRef("refund", 'a');
        ScenarioRehearsalBatchRequest request =
                request("batch-001", ref);
        CapabilitySnapshot.Scope other =
                new CapabilitySnapshot.Scope(
                        "tenant-a", "org-b", "support", "test", "sg");
        ScenarioRehearsalBatchManifest first =
                manifest(request, SCOPE);
        ScenarioRehearsalBatchManifest second =
                manifest(request, other);

        assertThat(first.batchId()).isNotEqualTo(second.batchId());
        assertThat(first.entries().getFirst().aggregateRunId())
                .isNotEqualTo(
                        second.entries().getFirst().aggregateRunId());
    }

    @Test
    void integrityVerificationRejectsFingerprintOrDerivedIdentityDrift() {
        ScenarioRehearsalBatchManifest exact =
                manifest(
                        request(
                                "batch-001",
                                planRef("refund", 'a')),
                        SCOPE);
        ScenarioRehearsalBatchManifest fingerprintDrift =
                exact.withFingerprint("sha256:" + "f".repeat(64));
        ScenarioRehearsalBatchManifest.Entry source =
                exact.entries().getFirst();
        ScenarioRehearsalBatchManifest identityDrift =
                new ScenarioRehearsalBatchManifest(
                        exact.schemaVersion(),
                        exact.batchId(),
                        "",
                        exact.scope(),
                        exact.requestId(),
                        List.of(new ScenarioRehearsalBatchManifest.Entry(
                                source.entryIndex(),
                                source.entryId(),
                                source.compiledPlanRef(),
                                source.aggregateRequestId() + "-drift",
                                source.aggregateRunId(),
                                source.caseCount(),
                                source.executionTimeout())),
                        exact.totalCases());
        identityDrift = ScenarioRehearsalBatchManifestIntegrity.seal(
                mapper, identityDrift);

        assertThatThrownBy(() ->
                ScenarioRehearsalBatchManifestIntegrity.verify(
                        mapper, fingerprintDrift))
                .isInstanceOf(IllegalArgumentException.class);
        ScenarioRehearsalBatchManifest finalIdentityDrift =
                identityDrift;
        assertThatThrownBy(() ->
                ScenarioRehearsalBatchManifestIntegrity.verify(
                        mapper, finalIdentityDrift))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requestRejectsDuplicateEntryOrPlanAndCarriesNoRuntimeOverride() {
        MirrorArtifactRef ref = planRef("refund", 'a');

        assertThatThrownBy(() -> new ScenarioRehearsalBatchRequest(
                "",
                "batch-001",
                List.of(
                        new ScenarioRehearsalBatchRequest.Entry(
                                "refund-a", ref),
                        new ScenarioRehearsalBatchRequest.Entry(
                                "refund-b", ref))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScenarioRehearsalBatchRequest(
                "",
                "batch-001",
                List.of(
                        new ScenarioRehearsalBatchRequest.Entry(
                                "same", ref),
                        new ScenarioRehearsalBatchRequest.Entry(
                                "same",
                                planRef("other", 'b')))))
                .isInstanceOf(IllegalArgumentException.class);

        String json = write(request("batch-001", ref));
        assertThat(json)
                .doesNotContain(
                        "fixture",
                        "context",
                        "payload",
                        "priority",
                        "retry",
                        "worker",
                        "credential");
    }

    @Test
    void compilerRequiresRehearsalPurposeAndExactScope() {
        ScenarioRehearsalIntegrationService rehearsals =
                mock(ScenarioRehearsalIntegrationService.class);
        MirrorArtifactRef ref = planRef("refund", 'a');
        CompiledScenarioRehearsalPlan foreignPlan =
                plan(
                        new CapabilitySnapshot.Scope(
                                "tenant-a", "org-b",
                                "support", "test", "sg"),
                        1,
                        Duration.ofMinutes(1));
        when(rehearsals.find(
                ref.id(), ref.revision(), ref.fingerprint(), identity()))
                .thenReturn(foreignPlan);
        ScenarioRehearsalBatchCompiler compiler =
                new ScenarioRehearsalBatchCompiler(
                        rehearsals, mapper);

        assertThatThrownBy(() -> compiler.compile(
                request("batch-001", ref),
                identity("MIRROR_READ")))
                .isInstanceOf(IntegrationProblemException.class);
        assertThatThrownBy(() -> compiler.compile(
                request("batch-001", ref), identity()))
                .isInstanceOf(IntegrationProblemException.class)
                .satisfies(failure -> assertThat(
                        ((IntegrationProblemException) failure)
                                .problem().code())
                        .isEqualTo(
                                "RG.MIRROR.REHEARSAL_BATCH.PLAN_SCOPE_INVALID"));
    }

    private ScenarioRehearsalBatchManifest manifest(
            ScenarioRehearsalBatchRequest request,
            CapabilitySnapshot.Scope scope) {
        String aggregateRequest =
                request.requestId() + ":plan:000";
        ScenarioRehearsalBatchManifest material =
                new ScenarioRehearsalBatchManifest(
                        "",
                        ScenarioRehearsalBatchIdentity.derive(
                                mapper, scope, request.requestId()),
                        "",
                        scope,
                        request.requestId(),
                        List.of(new ScenarioRehearsalBatchManifest.Entry(
                                0,
                                request.entries().getFirst().entryId(),
                                request.entries().getFirst()
                                        .compiledPlanRef(),
                                aggregateRequest,
                                ScenarioRehearsalRunIdentity.derive(
                                        mapper, scope, aggregateRequest),
                                1,
                                Duration.ofMinutes(1))),
                        1);
        return ScenarioRehearsalBatchManifestIntegrity.seal(
                mapper, material);
    }

    private ScenarioRehearsalBatchRequest request(
            String requestId,
            MirrorArtifactRef... refs) {
        java.util.ArrayList<ScenarioRehearsalBatchRequest.Entry>
                entries = new java.util.ArrayList<>();
        for (int index = 0; index < refs.length; index++) {
            entries.add(new ScenarioRehearsalBatchRequest.Entry(
                    "entry-" + index, refs[index]));
        }
        return new ScenarioRehearsalBatchRequest(
                "", requestId, entries);
    }

    private CompiledScenarioRehearsalPlan plan(
            CapabilitySnapshot.Scope scope,
            int cases,
            Duration timeout) {
        CompiledScenarioRehearsalPlan plan =
                mock(CompiledScenarioRehearsalPlan.class);
        ScenarioPack.RehearsalPolicy policy =
                mock(ScenarioPack.RehearsalPolicy.class);
        when(plan.scope()).thenReturn(scope);
        when(plan.cases()).thenReturn(
                java.util.Collections.nCopies(
                        cases,
                        mock(CompiledScenarioRehearsalPlan
                                .CaseBinding.class)));
        when(plan.policy()).thenReturn(policy);
        when(policy.totalTimeout()).thenReturn(timeout);
        return plan;
    }

    private MirrorArtifactRef planRef(
            String id,
            char fingerprint) {
        return new MirrorArtifactRef(
                "COMPILED_REHEARSAL_PLAN",
                id,
                1,
                "sha256:" + String.valueOf(fingerprint).repeat(64));
    }

    private IntegrationRequestContext identity() {
        return identity("MIRROR_REHEARSAL");
    }

    private IntegrationRequestContext identity(String purpose) {
        return new IntegrationRequestContext(
                SCOPE.tenantId(),
                SCOPE.organizationId(),
                SCOPE.projectId(),
                SCOPE.environmentId(),
                SCOPE.region(),
                "USER",
                "owner-a",
                "",
                purpose,
                "corr-batch");
    }

    private String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }
}
