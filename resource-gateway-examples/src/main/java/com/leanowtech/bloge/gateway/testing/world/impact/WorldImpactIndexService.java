package com.leanowtech.bloge.gateway.testing.world.impact;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.TestRunRecord;
import com.leanowtech.bloge.gateway.testing.evidence.TestEvidenceIntegrityService;
import com.leanowtech.bloge.gateway.testing.world.ResourceWorldModel;
import com.leanowtech.bloge.gateway.testing.world.Scenario;
import com.leanowtech.bloge.gateway.testing.world.WorldScenarioCompilation;

import java.time.Instant;

/** Server-owned orchestration boundary for rebuild and verified runtime indexing. */
public final class WorldImpactIndexService {
    private final WorldImpactSnapshotRepository repository;
    private final WorldStaticDependencySnapshotBuilder staticBuilder;
    private final WorldRuntimeConsumptionSnapshotBuilder runtimeBuilder;

    public WorldImpactIndexService(WorldImpactSnapshotRepository repository) {
        if (repository == null) throw WorldImpactSupport.fail(WorldImpactException.Code.INVALID_INPUT);
        this.repository = repository;
        this.staticBuilder = new WorldStaticDependencySnapshotBuilder();
        this.runtimeBuilder = new WorldRuntimeConsumptionSnapshotBuilder();
    }

    public WorldImpactSnapshotRepository.IndexedStatic rebuildStatic(Scenario scenario,
                                                                       ResourceWorldModel world,
                                                                       WorldScenarioCompilation compilation,
                                                                       long sourceWatermark,
                                                                       Instant generatedAt) {
        return repository.upsertStatic(staticBuilder.build(scenario, world, compilation,
                sourceWatermark, generatedAt));
    }

    public WorldImpactSnapshotRepository.IndexedRuntime indexVerifiedRuntime(TestRunRecord record,
                                                                               ObjectMapper mapper,
                                                                               TestEvidenceIntegrityService integrity,
                                                                               Scenario scenario,
                                                                               WorldScenarioCompilation compilation,
                                                                               long sourceWatermark,
                                                                               Instant generatedAt) {
        return repository.upsertRuntime(runtimeBuilder.build(record, mapper, integrity, scenario,
                compilation, sourceWatermark, generatedAt));
    }

    public WorldImpactSnapshotRepository repository() {
        return repository;
    }
}
