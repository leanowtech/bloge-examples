package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Resolves exact compiled plans into one immutable queue-independent batch manifest.
 *
 * <p>Compilation performs every mutable registry read before queue admission. Workers later
 * execute only exact plan references and deterministic aggregate identities from the manifest.</p>
 */
public final class ScenarioRehearsalBatchCompiler {
    private static final String PURPOSE = "MIRROR_REHEARSAL";

    private final ScenarioRehearsalIntegrationService rehearsals;
    private final ObjectMapper mapper;

    /**
     * @param rehearsals exact compiled-plan authority
     * @param mapper canonical protocol mapper
     */
    public ScenarioRehearsalBatchCompiler(
            ScenarioRehearsalIntegrationService rehearsals,
            ObjectMapper mapper) {
        this.rehearsals = Objects.requireNonNull(
                rehearsals, "rehearsals");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /**
     * Resolves and seals one complete batch closure.
     *
     * @param request strict payload-free submission
     * @param identity authenticated full enterprise mirror identity
     * @return deterministic content-addressed manifest
     */
    public ScenarioRehearsalBatchManifest compile(
            ScenarioRehearsalBatchRequest request,
            IntegrationRequestContext identity) {
        Objects.requireNonNull(request, "request");
        if (identity == null
                || !PURPOSE.equals(identity.purpose())) {
            throw new IntegrationProblemException(
                    IntegrationProblem.forbidden(
                            "RG.MIRROR.REHEARSAL_BATCH.PURPOSE_REQUIRED",
                            "Scenario batch compilation requires MIRROR_REHEARSAL purpose.",
                            identity == null
                                    ? ""
                                    : identity.correlationId(),
                            Map.of()));
        }
        CapabilitySnapshot.Scope scope =
                MirrorPlanIntegrationService
                        .requireMirrorIdentity(identity);
        String batchId = ScenarioRehearsalBatchIdentity.derive(
                mapper, scope, request.requestId());
        List<ScenarioRehearsalBatchManifest.Entry> entries =
                new ArrayList<>(request.entries().size());
        long totalCases = 0;
        for (int index = 0;
             index < request.entries().size();
             index++) {
            ScenarioRehearsalBatchRequest.Entry requested =
                    request.entries().get(index);
            MirrorArtifactRef ref = requested.compiledPlanRef();
            CompiledScenarioRehearsalPlan plan =
                    rehearsals.find(
                            ref.id(),
                            ref.revision(),
                            ref.fingerprint(),
                            identity);
            if (!scope.equals(plan.scope())) {
                throw conflict(
                        identity,
                        "RG.MIRROR.REHEARSAL_BATCH.PLAN_SCOPE_INVALID",
                        "Compiled Scenario plan is outside the batch scope.");
            }
            totalCases = Math.addExact(
                    totalCases, plan.cases().size());
            if (totalCases
                    > ScenarioRehearsalBatchRequest
                    .MAXIMUM_TOTAL_CASES) {
                throw conflict(
                        identity,
                        "RG.MIRROR.REHEARSAL_BATCH.CASE_CAPACITY_EXCEEDED",
                        "Scenario batch exceeds the maximum total case count.");
            }
            String aggregateRequestId =
                    request.requestId()
                            + ":plan:"
                            + String.format("%03d", index);
            entries.add(
                    new ScenarioRehearsalBatchManifest.Entry(
                            index,
                            requested.entryId(),
                            ref,
                            aggregateRequestId,
                            ScenarioRehearsalRunIdentity.derive(
                                    mapper,
                                    scope,
                                    aggregateRequestId),
                            plan.cases().size(),
                            plan.policy().totalTimeout()));
        }
        ScenarioRehearsalBatchManifest material =
                new ScenarioRehearsalBatchManifest(
                        "",
                        batchId,
                        "",
                        scope,
                        request.requestId(),
                        entries,
                        Math.toIntExact(totalCases));
        return ScenarioRehearsalBatchManifestIntegrity.seal(
                mapper, material);
    }

    private static IntegrationProblemException conflict(
            IntegrationRequestContext identity,
            String code,
            String title) {
        return new IntegrationProblemException(
                IntegrationProblem.conflict(
                        code,
                        title,
                        identity.correlationId(),
                        Map.of()));
    }
}
