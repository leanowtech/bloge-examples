package com.leanowtech.bloge.gateway.solution.journey;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.agenttdd.InMemoryAgentTddStateRepository;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.solution.FeatureContract;
import com.leanowtech.bloge.gateway.solution.InstructionContract;
import com.leanowtech.bloge.gateway.solution.ScenarioContract;
import com.leanowtech.bloge.gateway.solution.SolutionContract;
import com.leanowtech.bloge.gateway.solution.SolutionEntityRegistry;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.AuditMetadata;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactSchemaRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalKind;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureAssetDescriptor;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.FixtureAssetRepository;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.StoredFixtureAsset;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Verifies the payload-free Solution-to-Fixture catalog projection. */
class BusinessFixtureIndexServiceTest {
    private static final String SCOPE_KEY = "tenant-a|org-a|project-a|test|sg";
    private static final EnterpriseScope SCOPE = new EnterpriseScope(
            "tenant-a", "org-a", "project-a", "test", "sg");
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void groupsDescriptorsByFrozenFeatureAndInstructionBindingsWithoutMaterialCoordinates()
            throws Exception {
        InMemoryAgentTddStateRepository states = new InMemoryAgentTddStateRepository();
        SolutionEntityRegistry registry = new SolutionEntityRegistry(states, mapper);
        storeClosure(registry);
        FixtureAssetRepository fixtures = mock(FixtureAssetRepository.class);
        StoredFixtureAsset featureFixture = fixture(
                "fixture-party", "责任方样本", "graph:party", 'a');
        StoredFixtureAsset instructionFixture = fixture(
                "fixture-refund", "退款返回", "unrelated", 'b');
        when(fixtures.listHeads(SCOPE, false, 100, 0))
                .thenReturn(List.of(featureFixture, instructionFixture));
        when(fixtures.usages(SCOPE, featureFixture.exactRef(), 1_000)).thenReturn(List.of());
        when(fixtures.usages(SCOPE, instructionFixture.exactRef(), 1_000)).thenReturn(List.of(
                new FixtureAssetRepository.FixtureUsage(instructionFixture.exactRef(),
                        new ExactAssetRef("INSTRUCTION", "ins:refund", 1, fp('c')))));
        when(fixtures.countUsages(SCOPE, featureFixture.exactRef())).thenReturn(0);
        when(fixtures.countUsages(SCOPE, instructionFixture.exactRef())).thenReturn(1);

        var result = new BusinessFixtureIndexService(registry, fixtures)
                .listForSolution("sol:cancel", identity());

        assertThat(result).extracting(BusinessFixtureIndexService.CapabilityFixtures::capabilityRef)
                .containsExactly("responsibility.party", "ins:refund");
        assertThat(result.get(0).fixtures()).singleElement().satisfies(summary -> {
            assertThat(summary.fixtureAssetId()).isEqualTo("fixture-party");
            assertThat(summary.usageCount()).isZero();
        });
        assertThat(result.get(1).fixtures()).singleElement().satisfies(summary -> {
            assertThat(summary.fixtureAssetId()).isEqualTo("fixture-refund");
            assertThat(summary.usageCount()).isEqualTo(1);
        });
        String json = mapper.writeValueAsString(result);
        assertThat(json).doesNotContain("FIXTURE_MATERIAL", "material-party", "material-refund",
                "given", "expected", "payload");
        verify(fixtures).usages(SCOPE, instructionFixture.exactRef(), 1_000);
    }

    private void storeClosure(SolutionEntityRegistry registry) {
        registry.upsertFeature(SCOPE_KEY, new FeatureContract(
                "responsibility.party", mapper.valueToTree(Map.of("type", "string")),
                FeatureContract.EvaluationKind.DAG, FeatureContract.Determinism.DETERMINISTIC,
                mapper.valueToTree(Map.of("orderId", "string")), "graph:party", "", "",
                "取消责任方"));
        registry.upsertInstruction(SCOPE_KEY, new InstructionContract(
                "ins:refund", mapper.valueToTree(Map.of("orderId", "string")),
                mapper.valueToTree(Map.of("result", Map.of("type", "string"),
                        "reasoning", "required")), InstructionContract.Effect.READ,
                "binding:refund", null, "计算退款结果"));
        registry.upsertScenario(SCOPE_KEY, new ScenarioContract(
                "scn:cancel", List.of("party"), ScenarioContract.HitPolicy.UNIQUE,
                List.of(new ScenarioContract.Rule("R1",
                        mapper.valueToTree(Map.of("party", Map.of("eq", "passenger"))),
                        new ScenarioContract.Outlet(ScenarioContract.OutletKind.INSTRUCTION,
                                "ins:refund", Map.of(), ""))),
                new ScenarioContract.Outlet(ScenarioContract.OutletKind.TERMINAL,
                        "", Map.of(), "MANUAL_REVIEW")));
        registry.upsertSolution(SCOPE_KEY, new SolutionContract(
                "sol:cancel", "一致处理取消争议", Map.of("party", "responsibility.party"),
                "scn:cancel", List.of("ins:refund"), "caseSet:cancel"), false);
    }

    private StoredFixtureAsset fixture(String id, String name, String sourceId, char marker) {
        PrincipalRef owner = new PrincipalRef("owner", PrincipalKind.USER, "");
        FixtureAssetDescriptor descriptor = new FixtureAssetDescriptor("", id, 1, SCOPE, name,
                new FixtureAssetDescriptor.FixtureSource(
                        FixtureAssetDescriptor.SourceKind.SCENARIO,
                        new ExactAssetRef("SOURCE", sourceId, 1, fp(marker))),
                new ExactAssetRef("FIXTURE_MATERIAL", "material-" + id, 1, fp(marker)),
                new ExactSchemaRef("schema:" + id, 1, fp(marker)), "default",
                FixtureAssetDescriptor.FixtureLifecycle.ACTIVE, "INTERNAL", owner,
                new FixtureAssetDescriptor.RedactionDescriptor("r1", List.of(), true),
                new FixtureAssetDescriptor.RetentionDescriptor(
                        "t1", 30, Instant.parse("2026-10-01T00:00:00Z")),
                new FixtureAssetDescriptor.QualityProfile(true, true, 0, 0), List.of(),
                new AuditMetadata(Instant.EPOCH, Instant.EPOCH, owner, owner));
        return StoredFixtureAsset.verified(mapper, descriptor);
    }

    private static IntegrationRequestContext identity() {
        return new IntegrationRequestContext("tenant-a", "org-a", "project-a", "test", "sg",
                "USER", "reviewer", "", "AGENT_TDD_READ", "corr-1");
    }

    private static String fp(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
