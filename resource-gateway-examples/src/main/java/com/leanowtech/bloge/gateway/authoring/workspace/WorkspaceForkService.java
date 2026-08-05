package com.leanowtech.bloge.gateway.authoring.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.authoring.scenario.ScenarioDraftSet;
import com.leanowtech.bloge.gateway.authoring.scenario.ScenarioDraftSetAuthoringService;
import com.leanowtech.bloge.gateway.authoring.scenario.StoredScenarioDraftSet;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorCatalogQuery;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.VisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visual.contract.ContractDraft;
import com.leanowtech.bloge.gateway.visual.contract.ContractDraftProjectionService;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftRepository;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;
import com.leanowtech.bloge.gateway.visual.validation.GraphDraftValidator;
import com.leanowtech.bloge.gateway.visual.validation.VisualValidationResult;

import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Transactional aggregate boundary that turns one runnable seed into an internally current
 * durable Workspace without exposing an intermediate stale state to the author.
 */
public class WorkspaceForkService {

    private static final int MAX_CANONICAL_BYTES = 16 * 1_048_576;
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 160;

    private final GraphDraftRepository graphs;
    private final GraphDraftValidator graphValidator;
    private final VisualOperatorCatalog operators;
    private final ContractDraftProjectionService contracts;
    private final ScenarioDraftSetAuthoringService scenarios;
    private final WorkspaceForkReceiptRepository receipts;
    private final ObjectMapper mapper;

    public WorkspaceForkService(
            GraphDraftRepository graphs,
            GraphDraftValidator graphValidator,
            VisualOperatorCatalog operators,
            ContractDraftProjectionService contracts,
            ScenarioDraftSetAuthoringService scenarios,
            WorkspaceForkReceiptRepository receipts,
            ObjectMapper mapper) {
        this.graphs = Objects.requireNonNull(graphs, "graphs");
        this.graphValidator = Objects.requireNonNull(graphValidator, "graphValidator");
        this.operators = Objects.requireNonNull(operators, "operators");
        this.contracts = Objects.requireNonNull(contracts, "contracts");
        this.scenarios = Objects.requireNonNull(scenarios, "scenarios");
        this.receipts = Objects.requireNonNull(receipts, "receipts");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /**
     * Forks exactly one seed or returns the winning receipt for an identical retry.
     *
     * <p>The synchronized guard prevents duplicate materialization within one lightweight host;
     * the durable unique receipt is the cross-restart authority. Production multi-replica hosts
     * should route this command through the same single-writer authoring partition used by Graph
     * draft mutations.</p>
     */
    @Transactional
    public synchronized WorkspaceForkReceipt fork(
            String idempotencyKey,
            WorkspaceForkCommand command,
            IntegrationRequestContext identity) {
        requireIdentity(identity);
        String key = requireIdempotencyKey(idempotencyKey);
        validateCommand(command);
        ScenarioDraftSet.EnterpriseScope scope = scope(identity);
        String requestFingerprint = fingerprint(command);
        Optional<StoredWorkspaceForkReceipt> previous = receipts.find(scope, key);
        if (previous.isPresent()) {
            if (!previous.get().requestFingerprint().equals(requestFingerprint)) {
                throw new IllegalStateException(
                        "Idempotency key was already used for a different Workspace fork request");
            }
            return previous.get().receipt().asReplay();
        }

        WorkspaceSeedBundle seed = command.seed();
        GraphDraft candidate = prepareGraph(seed.graphDraft(), command, identity);
        VisualValidationResult graphValidation = graphValidator.validate(candidate);
        if (!graphValidation.valid()) {
            throw new IllegalArgumentException("Workspace seed Graph is invalid: "
                    + graphValidation.diagnostics().stream().map(diagnostic -> diagnostic.code())
                    .distinct().sorted().toList());
        }

        GraphDraft storedGraph = null;
        try {
            storedGraph = graphs.save(candidate);
            String graphFingerprint = fingerprint(storedGraph);
            ContractDraft contract = contracts.project(storedGraph, graphFingerprint);
            String contractFingerprint = contract.fingerprint(mapper);
            String workspaceId = workspaceId(scope, key);
            ScenarioDraftSet sourceScenarios = seed.scenarioDraftSets().getFirst();
            String scenarioId = portableId(workspaceId + "-scenarios");
            ScenarioDraftSet rebound = rebind(
                    sourceScenarios, scenarioId, scope, contract, contractFingerprint, identity);
            StoredScenarioDraftSet storedScenarios = scenarios.save(
                    scenarioId, 0, rebound, identity);

            String sourceFingerprint = fingerprint(seed);
            List<WorkspaceForkReceipt.AssetCoordinate> fixtureCoordinates = seed.fixtureRefs().stream()
                    .map(ref -> new WorkspaceForkReceipt.AssetCoordinate(
                            "INLINE_FIXTURE", ref, 0, fingerprint(Map.of(
                                    "seed", sourceFingerprint, "fixtureRef", ref))))
                    .toList();
            WorkspaceForkReceipt.GraphCoordinate graphCoordinate =
                    new WorkspaceForkReceipt.GraphCoordinate(
                            storedGraph.draftId(), storedGraph.revision(), graphFingerprint);
            WorkspaceForkReceipt.ContractCoordinate contractCoordinate =
                    new WorkspaceForkReceipt.ContractCoordinate(contract.target(), contractFingerprint);
            List<WorkspaceForkReceipt.AssetCoordinate> scenarioCoordinates = List.of(
                    new WorkspaceForkReceipt.AssetCoordinate(
                            "SCENARIO_SUITE",
                            storedScenarios.scenarioDraftSetId(),
                            storedScenarios.revision(),
                            storedScenarios.fingerprint()));
            String workspaceFingerprint = fingerprint(Map.of(
                    "workspaceId", workspaceId,
                    "graph", graphCoordinate,
                    "contract", contractCoordinate,
                    "scenarios", scenarioCoordinates,
                    "fixtures", fixtureCoordinates));
            WorkspaceForkReceipt receipt = new WorkspaceForkReceipt(
                    "",
                    workspaceId,
                    graphCoordinate,
                    contractCoordinate,
                    scenarioCoordinates,
                    fixtureCoordinates,
                    sourceFingerprint,
                    workspaceFingerprint,
                    seed.runtimeProfile().mode(),
                    seed.proofStrength(),
                    seed.missingCapabilities().isEmpty()
                            ? List.of()
                            : List.of("Some optional template capabilities are unavailable: "
                                    + String.join(", ", seed.missingCapabilities())),
                    false);
            StoredWorkspaceForkReceipt winner = receipts.saveIfAbsent(
                    scope, key, new StoredWorkspaceForkReceipt(requestFingerprint, receipt));
            if (!winner.requestFingerprint().equals(requestFingerprint)) {
                throw new IllegalStateException(
                        "Idempotency key was concurrently used for another Workspace fork request");
            }
            return winner.receipt().workspaceId().equals(receipt.workspaceId())
                    ? receipt
                    : winner.receipt().asReplay();
        } catch (RuntimeException failure) {
            if (storedGraph != null) {
                graphs.delete(storedGraph.draftId(), GraphDraft.RevisionMetadata.patch(
                        identity.actorId(), "workspace-fork-rollback",
                        "Rolled back incomplete Workspace fork.", List.of("/"), failure.getClass().getSimpleName()));
            }
            throw failure;
        }
    }

    private GraphDraft prepareGraph(
            GraphDraft source,
            WorkspaceForkCommand command,
            IntegrationRequestContext identity) {
        Map<String, OperatorDefinition> snapshots = new LinkedHashMap<>();
        Map<String, OperatorDefinition> scoped = new LinkedHashMap<>();
        operators.list(new OperatorCatalogQuery(
                "", List.of(), false, false,
                identity.tenantId(), source.namespace(), identity.environmentId()))
                .forEach(operator -> scoped.put(operator.operatorRef(), operator));
        source.nodes().forEach(node -> Optional.ofNullable(scoped.get(node.operatorRef()))
                .or(() -> operators.find(node.operatorRef()))
                .ifPresent(operator -> snapshots.put(node.id(), operator)));
        Map<String, String> fingerprints = new LinkedHashMap<>();
        snapshots.forEach((nodeId, operator) -> fingerprints.put(nodeId, operator.fingerprint()));
        return new GraphDraft(
                source.schemaVersion(),
                "",
                0,
                source.graphName(),
                identity.tenantId(),
                source.namespace(),
                identity.environmentId(),
                source.status(),
                source.inputSchema(),
                source.outputSchema(),
                source.nodes(),
                source.edges(),
                source.visualLayout(),
                source.nodeFixtures(),
                source.output(),
                fingerprints,
                snapshots,
                GraphDraft.RevisionMetadata.patch(
                        identity.actorId(), command.changeSource(),
                        "Forked Workspace seed '" + command.seed().template().templateId() + "'.",
                        List.of("/"), "Create a complete runnable authoring Workspace."));
    }

    private static ScenarioDraftSet rebind(
            ScenarioDraftSet source,
            String id,
            ScenarioDraftSet.EnterpriseScope scope,
            ContractDraft contract,
            String contractFingerprint,
            IntegrationRequestContext identity) {
        ScenarioDraftSet.Metadata metadata = new ScenarioDraftSet.Metadata(
                source.metadata().owner().isBlank() ? identity.actorId() : source.metadata().owner(),
                source.metadata().classification(),
                null,
                null,
                source.metadata().provenance());
        return new ScenarioDraftSet(
                source.schemaVersion(), id, 0, scope, contract.target(), contractFingerprint,
                source.scenarios(), metadata);
    }

    private static void validateCommand(WorkspaceForkCommand command) {
        if (command == null || !WorkspaceForkCommand.SCHEMA_VERSION.equals(command.schemaVersion())
                || command.seed() == null
                || !WorkspaceSeedBundle.SCHEMA_VERSION.equals(command.seed().schemaVersion())
                || command.seed().graphDraft() == null
                || command.seed().template().templateId().isBlank()
                || command.seed().scenarioDraftSets().size() != 1
                || command.seed().scenarioDraftSets().getFirst().scenarios().isEmpty()) {
            throw new IllegalArgumentException(
                    "Workspace fork v1 requires one Graph and one non-empty primary Scenario suite");
        }
    }

    private static void requireIdentity(IntegrationRequestContext identity) {
        Objects.requireNonNull(identity, "identity").requireComplete();
        if (!("test".equalsIgnoreCase(identity.environmentId())
                || "staging".equalsIgnoreCase(identity.environmentId()))) {
            throw new IllegalArgumentException("Workspace forks are restricted to test or staging");
        }
    }

    private static String requireIdempotencyKey(String value) {
        String key = value == null ? "" : value.trim();
        if (key.isBlank() || key.length() > MAX_IDEMPOTENCY_KEY_LENGTH
                || !key.matches("[A-Za-z0-9][A-Za-z0-9._:-]*")) {
            throw new IllegalArgumentException("A bounded portable Idempotency-Key is required");
        }
        return key;
    }

    private static ScenarioDraftSet.EnterpriseScope scope(IntegrationRequestContext identity) {
        return new ScenarioDraftSet.EnterpriseScope(
                identity.tenantId(), identity.organizationId(), identity.projectId(),
                identity.environmentId(), identity.region());
    }

    private String fingerprint(Object value) {
        return VisualBundleFingerprint.fromCanonicalValue(mapper, value, MAX_CANONICAL_BYTES);
    }

    private static String workspaceId(
            ScenarioDraftSet.EnterpriseScope scope,
            String idempotencyKey) {
        String seed = String.join("\u001f", scope.tenantId(), scope.organizationId(),
                scope.projectId(), scope.environment(), scope.region(), idempotencyKey);
        return "workspace-" + UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
    }

    private static String portableId(String value) {
        String normalized = value.replaceAll("[^A-Za-z0-9._-]", "-");
        return normalized.length() <= 255 ? normalized : normalized.substring(0, 255);
    }
}
