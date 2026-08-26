package com.leanowtech.bloge.gateway.testing.world.impact;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.TestRunRecord;
import com.leanowtech.bloge.gateway.testing.api.TestRunRecordIntegrity;
import com.leanowtech.bloge.gateway.testing.domain.TestRunEvidence;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.evidence.TestEvidenceIntegrityService;
import com.leanowtech.bloge.gateway.testing.world.Scenario;
import com.leanowtech.bloge.gateway.testing.world.WorldDelegateBinding;
import com.leanowtech.bloge.gateway.testing.world.WorldScenarioCompilation;
import com.leanowtech.bloge.gateway.testing.world.WorldScenarioSourceMap;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Builds observed world usage from a verified run; declarations never become observations. */
public final class WorldRuntimeConsumptionSnapshotBuilder {
    public WorldRuntimeConsumptionSnapshot build(TestRunRecord record, ObjectMapper mapper,
                                                  TestEvidenceIntegrityService integrity,
                                                  Scenario scenario, WorldScenarioCompilation compilation,
                                                  long sourceWatermark, Instant generatedAt) {
        if (record == null || mapper == null || integrity == null || scenario == null || compilation == null
                || sourceWatermark < 1 || generatedAt == null) throw fail(WorldImpactException.Code.INVALID_INPUT);
        final TestRunRecord verified;
        try {
            verified = TestRunRecordIntegrity.verifiedSnapshot(mapper, integrity, record);
            if (integrity.verify(verified.evidence(), verified.integrity())
                    != TestEvidenceIntegrityService.Verification.VERIFIED) {
                throw fail(WorldImpactException.Code.EVIDENCE_UNVERIFIED);
            }
        } catch (WorldImpactException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw fail(WorldImpactException.Code.EVIDENCE_UNVERIFIED);
        }
        try {
            compilation.verifyFingerprint();
            WorldImpactSourceMapIntegrity.Verified sourceMap =
                    WorldImpactSourceMapIntegrity.verify(compilation);
            TestRunEvidence evidence = verified.evidence();
            String bundleFingerprint = ProtocolFingerprint.of(mapper, compilation.bundle());
            if (!scenario.tenantId().equals(verified.tenantId())
                    || !scenario.target().fingerprint().equals(verified.target().fingerprint())
                    || !scenario.target().fingerprint().equals(evidence.targetFingerprint())
                    || !bundleFingerprint.equals(evidence.fixtureBundleFingerprint())
                    || !bundleFingerprint.equals(verified.fixtureBundleRef().fingerprint())
                    || !verified.runId().equals(evidence.runId())
                    || !WorldImpactSupport.FINGERPRINT.matcher(verified.integrity().evidenceFingerprint()).matches()) {
                throw fail(WorldImpactException.Code.SOURCE_INTEGRITY);
            }
            Map<String, Set<String>> observedSitesByRule = new HashMap<>();
            for (TestRunEvidence.NodeTrace trace : evidence.nodeTrace()) {
                if (trace == null || trace.invocationSiteId().isBlank()) continue;
                String ruleId = sourceMap.siteToRule().get(trace.invocationSiteId());
                if (ruleId == null) throw fail(WorldImpactException.Code.SOURCE_INTEGRITY);
                observedSitesByRule.computeIfAbsent(ruleId, ignored -> new HashSet<>())
                        .add(trace.invocationSiteId());
            }
            Set<String> consumedRules = new HashSet<>();
            for (TestRunEvidence.FixtureConsumption usage : evidence.fixtureConsumptions()) {
                if (usage == null || usage.uses() < 0) throw fail(WorldImpactException.Code.SOURCE_INTEGRITY);
                if (usage.uses() == 0) continue;
                WorldDelegateBinding binding = sourceMap.byRule().get(usage.ruleId());
                if (binding == null) throw fail(WorldImpactException.Code.MAPPING_MISSING);
                consumedRules.add(binding.ruleId());
            }
            Set<String> rulesWithFacts = new HashSet<>(consumedRules);
            rulesWithFacts.addAll(observedSitesByRule.keySet());
            List<WorldRuntimeConsumptionSnapshot.Consumption> consumptions = new ArrayList<>();
            for (String ruleId : rulesWithFacts.stream().sorted().toList()) {
                WorldDelegateBinding binding = sourceMap.byRule().get(ruleId);
                Set<String> observed = observedSitesByRule.getOrDefault(ruleId, Set.of());
                if (consumedRules.contains(ruleId) && observed.isEmpty()) {
                    throw fail(WorldImpactException.Code.MAPPING_MISSING);
                }
                if (observed.isEmpty()) continue;
                String logicalSource = WorldScenarioSourceMap.coordinate("logical-contract",
                        binding.logicalContractId() + "@" + binding.contractFingerprint());
                if (!sourceMap.siteToLogical().entrySet().stream()
                        .filter(entry -> entry.getValue().equals(logicalSource))
                        .map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet())
                        .containsAll(observed)) {
                    throw fail(WorldImpactException.Code.SOURCE_INTEGRITY);
                }
                String sliceFingerprint = findSliceFingerprint(sourceMap, binding);
                consumptions.add(new WorldRuntimeConsumptionSnapshot.Consumption(binding.ruleId(), binding.ruleId(),
                        binding.logicalContractId(), binding.contractFingerprint(), sliceFingerprint,
                        binding.fragment().fingerprint(), observed.stream().sorted().toList()));
            }
            return WorldRuntimeConsumptionSnapshot.create(scenario.tenantId(), scenario.scenarioId(),
                    scenario.revision(), scenario.fingerprint(), verified.runId(),
                    verified.integrity().evidenceFingerprint(), scenario.target().fingerprint(),
                    compilation.fingerprint(), sourceWatermark, evidence.startedAt(), evidence.completedAt(),
                    generatedAt, consumptions);
        } catch (WorldImpactException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw fail(WorldImpactException.Code.SOURCE_INTEGRITY);
        }
    }

    private static String findSliceFingerprint(WorldImpactSourceMapIntegrity.Verified sourceMap,
                                               WorldDelegateBinding binding) {
        String prefix = "world-slice:" + binding.logicalContractId() + "@";
        String source = sourceMap.worldSourceByRule().get(binding.ruleId());
        if (source == null || !source.startsWith(prefix)) {
            throw fail(WorldImpactException.Code.SOURCE_INTEGRITY);
        }
        String fingerprint = source.substring(prefix.length());
        if (!WorldImpactSupport.FINGERPRINT.matcher(fingerprint).matches()) {
            throw fail(WorldImpactException.Code.SOURCE_INTEGRITY);
        }
        return fingerprint;
    }

    private static WorldImpactException fail(WorldImpactException.Code code) {
        return WorldImpactSupport.fail(code);
    }
}
