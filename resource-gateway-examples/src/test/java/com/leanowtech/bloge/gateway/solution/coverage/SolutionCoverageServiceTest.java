package com.leanowtech.bloge.gateway.solution.coverage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddMutationService;
import com.leanowtech.bloge.gateway.agenttdd.InMemoryAgentTddStateRepository;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.solution.FeatureContract;
import com.leanowtech.bloge.gateway.solution.InstructionContract;
import com.leanowtech.bloge.gateway.solution.ScenarioContract;
import com.leanowtech.bloge.gateway.solution.SolutionContract;
import com.leanowtech.bloge.gateway.solution.SolutionEntityRegistry;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.TargetKind;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CoverageInventory;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CoverageInventory.ObligationDimension;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CoverageInventory.ObligationSource;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.CoverageInventoryRepository;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.StoredCoverageInventory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies the Solution coverage denominator is exact, recursive and payload-safe. */
class SolutionCoverageServiceTest {
    private static final String SCOPE = "tenant-a|org-a|project-a|test|sg";
    private static final IntegrationRequestContext CALLER = new IntegrationRequestContext(
            "tenant-a", "org-a", "project-a", "test", "sg",
            "HUMAN", "reviewer-a", "reviewer-a", "AGENT_TDD_READ",
            "corr-a", java.util.Set.of(), "INTERNAL", "");

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final InMemoryAgentTddStateRepository states = new InMemoryAgentTddStateRepository();
    private final SolutionEntityRegistry registry = new SolutionEntityRegistry(states, mapper);
    private final MemoryInventoryRepository inventories = new MemoryInventoryRepository(mapper);
    private final SolutionCoverageService service = new SolutionCoverageService(
            registry, states, inventories, mapper);

    @BeforeEach
    void defineRecursiveSolution() {
        registry.upsertFeature(SCOPE, new FeatureContract(
                "feature:party", mapper.valueToTree(Map.of("type", "string")),
                FeatureContract.EvaluationKind.API, FeatureContract.Determinism.DETERMINISTIC,
                mapper.valueToTree(Map.of()), "resource:party", "", ""));
        registry.upsertInstruction(SCOPE, writeInstruction("ins:refund"));
        registry.upsertInstruction(SCOPE, readInstruction("ins:lookup"));
        registry.upsertScenario(SCOPE, new ScenarioContract(
                "scn:child", List.of("party"), ScenarioContract.HitPolicy.UNIQUE,
                List.of(new ScenarioContract.Rule("R2",
                        mapper.valueToTree(Map.of("party", Map.of("eq", "passenger"))),
                        outlet(ScenarioContract.OutletKind.INSTRUCTION, "ins:refund", ""))),
                outlet(ScenarioContract.OutletKind.INSTRUCTION, "ins:lookup", "")));
        registry.upsertScenario(SCOPE, new ScenarioContract(
                "scn:root", List.of("party"), ScenarioContract.HitPolicy.UNIQUE,
                List.of(new ScenarioContract.Rule("R1",
                        mapper.valueToTree(Map.of("party", Map.of("in", List.of("passenger", "driver")))),
                        outlet(ScenarioContract.OutletKind.SUB_SCENARIO, "scn:child", ""))),
                outlet(ScenarioContract.OutletKind.TERMINAL, "", "MANUAL_REVIEW")));
        registry.upsertSolution(SCOPE, new SolutionContract(
                "sol:cancel", "Resolve cancellation dispute.",
                Map.of("party", "feature:party"), "scn:root",
                List.of("ins:refund", "ins:lookup"), "caseSet:cancel"), true);
    }

    @Test
    void derivesRuleOtherwiseAndWriteFaultObligationsFromExactFourEntityClosure() {
        StoredCoverageInventory stored = service.derive(CALLER, "sol:cancel");

        CoverageInventory inventory = stored.inventory();
        assertThat(inventory.target().kind()).isEqualTo(TargetKind.SOLUTION);
        assertThat(inventory.obligations()).hasSize(6);
        assertThat(inventory.obligations())
                .extracting(CoverageInventory.CoverageObligation::dimension)
                .containsExactlyInAnyOrder(
                        ObligationDimension.RULE, ObligationDimension.RULE,
                        ObligationDimension.OTHERWISE, ObligationDimension.OTHERWISE,
                        ObligationDimension.DEPENDENCY_FAULT,
                        ObligationDimension.DEPENDENCY_FAULT);
        assertThat(inventory.obligations())
                .allSatisfy(obligation -> assertThat(obligation.source())
                        .isEqualTo(ObligationSource.SOLUTION_DECISION));
        assertThat(inventory.derivationSources())
                .extracting(source -> source.kind() + ":" + source.id())
                .containsExactlyInAnyOrder(
                        "SOLUTION:sol:cancel", "FEATURE:feature:party",
                        "SCENARIO:scn:root", "SCENARIO:scn:child",
                        "INSTRUCTION:ins:refund", "INSTRUCTION:ins:lookup");
        assertThat(inventory.obligations())
                .extracting(CoverageInventory.CoverageObligation::obligationId)
                .noneMatch(id -> id.contains("ins:lookup") && id.startsWith("fault:"));
    }

    @Test
    void reportsApprovedGoldenCoverageAndKeepsTheAgentProjectionPayloadFree() {
        var data = mapper.createObjectNode();
        data.put("toolRef", "sol:cancel");
        var row = data.putArray("rows").addObject();
        row.put("caseId", "G-passenger-unavailable");
        row.put("category", "GOLDEN");
        row.put("lifecycle", "ACTIVE");
        row.putObject("proposedOracle").put("status", "APPROVED");
        row.set("given", mapper.valueToTree(Map.of("party", "passenger")));
        row.set("controlledAssumptions", mapper.valueToTree(Map.of(
                "ins:refund", Map.of(
                        "assetKind", "INSTRUCTION", "outcome", "UNAVAILABLE"))));
        row.set("expect", mapper.valueToTree(Map.of(
                "result", Map.of("dependencyStatus", "UNAVAILABLE"))));
        states.save(SCOPE, AgentTddMutationService.CASE_SET, "caseSet:cancel", data);

        SolutionCoverageService.CoverageStatus status = service.status(CALLER, "sol:cancel");

        assertThat(status.obligations()).filteredOn(SolutionCoverageService.CoverageItem::covered)
                .extracting(SolutionCoverageService.CoverageItem::id)
                .containsExactlyInAnyOrder(
                        "rule:scn:root:R1", "rule:scn:child:R2",
                        "fault:ins:refund:UNAVAILABLE");
        assertThat(status.summary().total()).isEqualTo(6);
        assertThat(status.summary().covered()).isEqualTo(3);
        JsonNode agent = mapper.valueToTree(status.agentProjection());
        assertThat(agent.toString())
                .contains("obligationFingerprint", "dimension", "risk", "covered", "summary")
                .doesNotContain("rule:scn", "ins:refund", "G-passenger", "byCaseIds",
                        "given", "expect", "payload");
    }

    private InstructionContract writeInstruction(String ref) {
        return new InstructionContract(ref, mapper.valueToTree(Map.of()),
                mapper.valueToTree(Map.of("result", Map.of("type", "string"),
                        "reasoning", "required")), InstructionContract.Effect.WRITE,
                "operator:" + ref,
                new InstructionContract.WriteGovernance("refund", "requestId", "recon:refund"));
    }

    private InstructionContract readInstruction(String ref) {
        return new InstructionContract(ref, mapper.valueToTree(Map.of()),
                mapper.valueToTree(Map.of("result", Map.of("type", "string"),
                        "reasoning", "required")), InstructionContract.Effect.READ,
                "operator:" + ref, null);
    }

    private static ScenarioContract.Outlet outlet(
            ScenarioContract.OutletKind kind, String ref, String terminalKind) {
        return new ScenarioContract.Outlet(kind, ref, Map.of(), terminalKind);
    }

    private static final class MemoryInventoryRepository implements CoverageInventoryRepository {
        private final ObjectMapper mapper;
        private final Map<String, List<StoredCoverageInventory>> values = new LinkedHashMap<>();

        private MemoryInventoryRepository(ObjectMapper mapper) {
            this.mapper = mapper;
        }

        @Override
        public Optional<StoredCoverageInventory> findHead(EnterpriseScope scope, String inventoryId) {
            List<StoredCoverageInventory> revisions = values.get(key(scope, inventoryId));
            return revisions == null || revisions.isEmpty()
                    ? Optional.empty() : Optional.of(revisions.getLast());
        }

        @Override
        public Optional<StoredCoverageInventory> findRevision(
                EnterpriseScope scope, String inventoryId, long revision) {
            return revisions(scope, inventoryId).stream()
                    .filter(value -> value.inventory().revision() == revision).findFirst();
        }

        @Override
        public List<StoredCoverageInventory> revisions(EnterpriseScope scope, String inventoryId) {
            return List.copyOf(values.getOrDefault(key(scope, inventoryId), List.of()));
        }

        @Override
        public Optional<StoredCoverageInventory> saveIfRevision(
                long expectedRevision, CoverageInventory candidate, PrincipalRef actor) {
            StoredCoverageInventory head = findHead(candidate.scope(), candidate.inventoryId()).orElse(null);
            if ((head == null && expectedRevision != 0)
                    || (head != null && head.inventory().revision() != expectedRevision)) {
                return Optional.empty();
            }
            Instant now = Instant.parse("2026-09-06T00:00:00Z");
            var metadata = head == null
                    ? new com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.AuditMetadata(
                            now, now, actor, actor)
                    : new com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.AuditMetadata(
                            head.inventory().metadata().createdAt(), now,
                            head.inventory().metadata().createdBy(), actor);
            CoverageInventory persisted = candidate.persistedAs(expectedRevision + 1, metadata);
            StoredCoverageInventory stored = new StoredCoverageInventory(
                    StoredCoverageInventory.SCHEMA_VERSION,
                    CorrectnessProtocolFingerprint.fingerprint(mapper, persisted), persisted);
            values.computeIfAbsent(key(candidate.scope(), candidate.inventoryId()),
                    ignored -> new ArrayList<>()).add(stored);
            return Optional.of(stored);
        }

        private static String key(EnterpriseScope scope, String inventoryId) {
            return scope + "|" + inventoryId;
        }
    }
}
