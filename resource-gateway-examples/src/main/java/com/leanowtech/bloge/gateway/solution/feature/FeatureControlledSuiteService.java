package com.leanowtech.bloge.gateway.solution.feature;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddMutationService;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddStateRepository;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddStoredAsset;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddToolException;
import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.solution.FeatureContract;
import com.leanowtech.bloge.gateway.solution.SolutionEntityRegistry;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Owns the protected definition, zero-egress execution, and current evidence of one Feature suite.
 *
 * <p>The durable Agent TDD overlay stores only an exact material receipt and aggregate evidence.
 * The complete suite is resolved from authenticated encryption for the duration of a run and is
 * discarded after the payload-free evidence fingerprint is committed.</p>
 */
@Service
public final class FeatureControlledSuiteService {
    /** Durable Agent TDD overlay kind keyed by the scoped Feature reference. */
    public static final String FEATURE_CONTROLLED_SUITE = "FEATURE_CONTROLLED_SUITE";
    private static final String SCHEMA_VERSION = "rg.featureControlledSuite.v1";
    private static final int MAX_BYTES = 16 * 1024 * 1024;

    private final AgentTddStateRepository states;
    private final SolutionEntityRegistry registry;
    private final FeatureControlledMaterialStore materials;
    private final FeatureControlledCaseRunner runner;
    private final ObjectMapper mapper;
    private final FeatureControlledSuiteProperties properties;

    /** Creates the production module and fails suite execution closed until a runner adapter exists. */
    @Autowired
    public FeatureControlledSuiteService(
            AgentTddStateRepository states,
            SolutionEntityRegistry registry,
            FeatureControlledMaterialStore materials,
            ObjectProvider<FeatureControlledCaseRunner> runners,
            ObjectMapper mapper,
            FeatureControlledSuiteProperties properties) {
        this(states, registry, materials,
                runners.getIfAvailable(FeatureControlledCaseRunner::unavailable), mapper, properties);
    }

    /** Creates a focused module with an explicit controlled execution adapter. */
    public FeatureControlledSuiteService(
            AgentTddStateRepository states,
            SolutionEntityRegistry registry,
            FeatureControlledMaterialStore materials,
            FeatureControlledCaseRunner runner,
            ObjectMapper mapper,
            FeatureControlledSuiteProperties properties) {
        this.states = Objects.requireNonNull(states, "states");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.materials = Objects.requireNonNull(materials, "materials");
        this.runner = Objects.requireNonNull(runner, "runner");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    /**
     * Stores the next suite definition through the protected material vault.
     *
     * @return payload-free current suite summary
     */
    public SuiteSummary upsert(
            FeatureControlledSuiteDefinition definition, IntegrationRequestContext identity) {
        requirePurpose(identity, IntegrationOperation.AGENT_TDD_DRAFT_WRITE,
                "Feature suite authoring purpose is required.");
        Objects.requireNonNull(definition, "definition");
        String scope = AgentTddMutationService.scopeKey(identity);
        requireExpectedRevision(scope, definition.featureRef(), definition.expectedRevision());
        FeatureContract feature = requireFeature(scope, definition.featureRef());
        AgentTddStoredAsset featureAsset = featureAsset(scope, definition.featureRef());
        String featureFingerprint = featureContractFingerprint(featureAsset);
        String definitionFingerprint = fingerprint(
                mapper.convertValue(definition.protectedMaterial(), Object.class));
        long nextRevision = definition.expectedRevision() + 1;
        JsonNode receipt = materials.write(definition.featureRef(), featureAsset.revision(),
                featureFingerprint, nextRevision, definitionFingerprint, definition, identity);

        ObjectNode state = mapper.createObjectNode();
        state.put("schemaVersion", SCHEMA_VERSION);
        state.set("materialReceipt", receipt);
        state.put("definitionFingerprint", definitionFingerprint);
        state.put("featureContractFingerprint", featureFingerprint);
        state.put("caseCount", definition.cases().size());
        state.put("coverageTargetCount", definition.requiredCoverageTargets().size());
        state.put("status", "DRAFT");
        state.put("evidenceFingerprint", "");
        AgentTddStoredAsset stored = states.saveIfRevision(scope, FEATURE_CONTROLLED_SUITE,
                feature.featureRef(), definition.expectedRevision(), state);
        return summary(stored);
    }

    /**
     * Resolves and runs one exact suite revision, then commits only aggregate current evidence.
     *
     * <p>A runner-reported external call or insufficient observed coverage produces a
     * {@code FAILED_CLOSED} evidence revision that cannot satisfy Feature fulfillment.</p>
     */
    public FeatureControlledSuiteEvidence run(
            String featureRef, long expectedRevision, IntegrationRequestContext identity) {
        requirePurpose(identity, IntegrationOperation.AGENT_TDD_EXECUTE,
                "Feature suite execution purpose is required.");
        String scope = AgentTddMutationService.scopeKey(identity);
        AgentTddStoredAsset current = states.lockRevision(
                scope, FEATURE_CONTROLLED_SUITE, required(featureRef, "featureRef"), expectedRevision);
        AgentTddStoredAsset featureAsset = featureAsset(scope, featureRef);
        requireFeatureFingerprint(current, featureAsset);
        FeatureControlledSuiteDefinition definition = materials.read(
                current.data().path("materialReceipt"), identity);
        if (!featureRef.equals(definition.featureRef())) throw stale();

        FeatureControlledCaseRunner.RunResult run = runner.run(
                new FeatureControlledCaseRunner.RunRequest(
                        featureRef, definition.libraryRefs(), definition.cases()), identity);
        Map<String, FeatureControlledCaseRunner.CaseResult> results = indexedResults(run, definition);
        int passed = Math.toIntExact(results.values().stream()
                .filter(FeatureControlledCaseRunner.CaseResult::passed).count());
        int failed = definition.cases().size() - passed;
        LinkedHashSet<String> covered = new LinkedHashSet<>();
        for (FeatureControlledSuiteDefinition.Case testCase : definition.cases()) {
            FeatureControlledCaseRunner.CaseResult result = results.get(testCase.caseId());
            if (!result.passed()) continue;
            LinkedHashSet<String> observed = new LinkedHashSet<>(result.observedCoverageTargets());
            testCase.coverageTargets().stream().filter(observed::contains).forEach(covered::add);
        }
        int total = definition.requiredCoverageTargets().size();
        int coveragePercent = Math.toIntExact((100L * covered.size()) / total);
        int requiredPercent = properties.getMinimumCoveragePercent();
        boolean accepted = failed == 0 && run.realExternalCalls() == 0
                && coveragePercent >= requiredPercent
                && covered.containsAll(definition.requiredCoverageTargets());
        String status = accepted ? "PASSED" : "FAILED_CLOSED";
        long evidenceRevision = current.revision() + 1;
        String evaluationFingerprint = fingerprint(definition.evaluationRef());
        String libraryFingerprint = fingerprint(definition.libraryRefs());
        String evidenceFingerprint = fingerprint(Map.ofEntries(
                Map.entry("featureContractFingerprint", featureContractFingerprint(featureAsset)),
                Map.entry("evaluationRefFingerprint", evaluationFingerprint),
                Map.entry("libraryFingerprint", libraryFingerprint),
                Map.entry("suiteRevision", evidenceRevision),
                Map.entry("suiteDefinitionFingerprint", current.data().path("definitionFingerprint").asText()),
                Map.entry("suiteMaterialFingerprint", current.data().path("materialReceipt")
                        .path("payloadFingerprint").asText()),
                Map.entry("executionEvidenceFingerprint", run.executionEvidenceFingerprint()),
                Map.entry("graphRevision", run.graphRevision()),
                Map.entry("caseCount", definition.cases().size()),
                Map.entry("passedCount", passed),
                Map.entry("failedCount", failed),
                Map.entry("realExternalCalls", run.realExternalCalls()),
                Map.entry("coverageTargetsTotal", total),
                Map.entry("coverageTargetsCovered", covered.size()),
                Map.entry("coveragePercent", coveragePercent),
                Map.entry("requiredCoveragePercent", requiredPercent)));

        FeatureControlledSuiteEvidence evidence = new FeatureControlledSuiteEvidence(
                featureRef, evidenceRevision, status, evidenceFingerprint,
                run.executionEvidenceFingerprint(), evaluationFingerprint,
                definition.cases().size(), passed, failed, run.realExternalCalls(),
                new FeatureControlledSuiteEvidence.Coverage(
                        total, covered.size(), coveragePercent, requiredPercent));
        ObjectNode next = current.data().deepCopy();
        next.put("status", status);
        next.put("evidenceFingerprint", evidenceFingerprint);
        next.set("latestEvidence", evidenceState(evidence, libraryFingerprint, run.graphRevision()));
        AgentTddStoredAsset stored = states.saveIfRevision(scope, FEATURE_CONTROLLED_SUITE,
                featureRef, current.revision(), next);
        if (stored.revision() != evidence.suiteRevision()) {
            throw new IllegalStateException("Feature suite evidence revision was not committed atomically");
        }
        return evidence;
    }

    /** Returns one payload-free current suite summary for platform discovery. */
    public SuiteSummary summary(String featureRef, IntegrationRequestContext identity) {
        requirePurpose(identity, IntegrationOperation.AGENT_TDD_READ,
                "Feature suite read purpose is required.");
        String scope = AgentTddMutationService.scopeKey(identity);
        AgentTddStoredAsset stored = states.find(
                        scope, FEATURE_CONTROLLED_SUITE, required(featureRef, "featureRef"))
                .orElseThrow(() -> new AgentTddToolException(
                        "REFERENCE_UNRESOLVED", "A Feature controlled suite is unavailable."));
        return summary(stored);
    }

    /**
     * Resolves a current suite evidence for the separately authenticated engineering handoff.
     *
     * <p>The supplied evaluation reference is compared by fingerprint. The raw binding and protected
     * suite material never cross the returned evidence interface.</p>
     */
    public FeatureControlledSuiteEvidence requireCurrentEvidence(
            String featureRef,
            String evaluationRef,
            String suiteEvidenceRef,
            IntegrationRequestContext identity) {
        requirePurpose(identity, IntegrationOperation.AGENT_TDD_FEATURE_ENG,
                "Feature engineering purpose is required.");
        String scope = AgentTddMutationService.scopeKey(identity);
        AgentTddStoredAsset suite = states.find(
                        scope, FEATURE_CONTROLLED_SUITE, required(featureRef, "featureRef"))
                .orElseThrow(() -> new AgentTddToolException(
                        "FEATURE_SUITE_NOT_VERIFIED", "A verified Feature suite is required."));
        JsonNode evidenceNode = suite.data().path("latestEvidence");
        String suppliedEvidence = required(suiteEvidenceRef, "suiteEvidenceRef");
        if (!suppliedEvidence.equals(suite.data().path("evidenceFingerprint").asText())
                || evidenceNode.isMissingNode()
                || evidenceNode.path("suiteRevision").asLong() != suite.revision()) {
            throw stale();
        }
        AgentTddStoredAsset featureAsset = featureAsset(scope, featureRef);
        requireFeatureFingerprint(suite, featureAsset);
        if (!fingerprint(required(evaluationRef, "evaluationRef"))
                .equals(evidenceNode.path("evaluationRefFingerprint").asText())) {
            throw stale();
        }
        FeatureControlledSuiteEvidence evidence = evidence(featureRef, evidenceNode);
        if (!"PASSED".equals(evidence.status()) || evidence.realExternalCalls() != 0
                || evidence.failedCount() != 0
                || evidence.coverage().percent() < properties.getMinimumCoveragePercent()) {
            throw new AgentTddToolException(
                    "FEATURE_SUITE_NOT_VERIFIED", "Feature suite evidence does not satisfy verification gates.");
        }
        return evidence;
    }

    private Map<String, FeatureControlledCaseRunner.CaseResult> indexedResults(
            FeatureControlledCaseRunner.RunResult run,
            FeatureControlledSuiteDefinition definition) {
        Map<String, FeatureControlledCaseRunner.CaseResult> results;
        try {
            results = run.cases().stream().collect(Collectors.toMap(
                    FeatureControlledCaseRunner.CaseResult::caseId, Function.identity()));
        } catch (IllegalStateException duplicate) {
            throw new AgentTddToolException(
                    "FEATURE_SUITE_EVIDENCE_INVALID", "Controlled execution returned duplicate case evidence.");
        }
        List<String> expected = definition.cases().stream()
                .map(FeatureControlledSuiteDefinition.Case::caseId).sorted().toList();
        if (!results.keySet().stream().sorted().toList().equals(expected)) {
            throw new AgentTddToolException(
                    "FEATURE_SUITE_EVIDENCE_INVALID", "Controlled execution returned incomplete case evidence.");
        }
        return Map.copyOf(results);
    }

    private ObjectNode evidenceState(
            FeatureControlledSuiteEvidence evidence, String libraryFingerprint, long graphRevision) {
        ObjectNode state = mapper.createObjectNode();
        state.put("suiteRevision", evidence.suiteRevision());
        state.put("status", evidence.status());
        state.put("evidenceFingerprint", evidence.evidenceFingerprint());
        state.put("executionEvidenceFingerprint", evidence.executionEvidenceFingerprint());
        state.put("evaluationRefFingerprint", evidence.evaluationRefFingerprint());
        state.put("libraryFingerprint", libraryFingerprint);
        state.put("graphRevision", graphRevision);
        state.put("caseCount", evidence.caseCount());
        state.put("passedCount", evidence.passedCount());
        state.put("failedCount", evidence.failedCount());
        state.put("realExternalCalls", evidence.realExternalCalls());
        state.put("coverageTargetsTotal", evidence.coverage().targetsTotal());
        state.put("coverageTargetsCovered", evidence.coverage().targetsCovered());
        state.put("coveragePercent", evidence.coverage().percent());
        state.put("requiredCoveragePercent", evidence.coverage().requiredPercent());
        return state;
    }

    private FeatureControlledSuiteEvidence evidence(String featureRef, JsonNode node) {
        return new FeatureControlledSuiteEvidence(featureRef,
                node.path("suiteRevision").asLong(), node.path("status").asText(),
                node.path("evidenceFingerprint").asText(),
                node.path("executionEvidenceFingerprint").asText(),
                node.path("evaluationRefFingerprint").asText(),
                node.path("caseCount").asInt(), node.path("passedCount").asInt(),
                node.path("failedCount").asInt(), node.path("realExternalCalls").asInt(),
                new FeatureControlledSuiteEvidence.Coverage(
                        node.path("coverageTargetsTotal").asInt(),
                        node.path("coverageTargetsCovered").asInt(),
                        node.path("coveragePercent").asInt(),
                        node.path("requiredCoveragePercent").asInt()));
    }

    private SuiteSummary summary(AgentTddStoredAsset stored) {
        JsonNode data = stored.data();
        return new SuiteSummary(stored.assetRef(), stored.revision(), data.path("status").asText(),
                data.path("definitionFingerprint").asText(), data.path("evidenceFingerprint").asText(),
                data.path("caseCount").asInt(), data.path("coverageTargetCount").asInt());
    }

    private AgentTddStoredAsset featureAsset(String scope, String featureRef) {
        requireFeature(scope, featureRef);
        return states.find(scope, SolutionEntityRegistry.FEATURE, featureRef)
                .orElseThrow(() -> new AgentTddToolException(
                        "REFERENCE_UNRESOLVED", "A Feature is unavailable."));
    }

    /**
     * Rejects a stale authoring command before it can create or replace protected material.
     *
     * <p>The repository CAS remains the authoritative race fence at commit time. This preflight
     * fence prevents an already stale request from touching the vault and preserves the stable
     * {@code GATE_REJECTED} contract instead of leaking a downstream material-store failure.</p>
     */
    private void requireExpectedRevision(String scope, String featureRef, long expectedRevision) {
        long actualRevision = states.find(scope, FEATURE_CONTROLLED_SUITE,
                        required(featureRef, "featureRef"))
                .map(AgentTddStoredAsset::revision)
                .orElse(0L);
        if (actualRevision != expectedRevision) {
            throw new AgentTddToolException(
                    "GATE_REJECTED", "Asset changed after the reviewed revision.");
        }
    }

    private FeatureContract requireFeature(String scope, String featureRef) {
        try {
            return registry.requireFeature(scope, required(featureRef, "featureRef"));
        } catch (SolutionEntityRegistry.EntityUnavailableException failure) {
            throw new AgentTddToolException("REFERENCE_UNRESOLVED", "A Feature is unavailable.");
        }
    }

    private static String featureContractFingerprint(AgentTddStoredAsset featureAsset) {
        return featureAsset.data().path("contractFingerprint").asText();
    }

    private static void requireFeatureFingerprint(
            AgentTddStoredAsset suite, AgentTddStoredAsset featureAsset) {
        if (!suite.data().path("featureContractFingerprint").asText()
                .equals(featureContractFingerprint(featureAsset))) throw stale();
    }

    private String fingerprint(Object value) {
        return VisualBundleFingerprint.fromCanonicalValue(mapper, value, MAX_BYTES);
    }

    private static void requirePurpose(
            IntegrationRequestContext identity, IntegrationOperation operation, String message) {
        if (identity == null || !operation.accepts(identity.purpose())) {
            throw new AgentTddToolException("FORBIDDEN_PURPOSE", message);
        }
        identity.requireComplete();
    }

    private static AgentTddToolException stale() {
        return new AgentTddToolException(
                "FEATURE_SUITE_EVIDENCE_STALE", "Feature suite evidence is not current.");
    }

    private static String required(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) {
            throw new AgentTddToolException("SCHEMA_NONCONFORMANT", field + " is required.");
        }
        return normalized;
    }

    /** Payload-free suite discovery projection. */
    public record SuiteSummary(
            String featureRef,
            long revision,
            String status,
            String definitionFingerprint,
            String evidenceFingerprint,
            int caseCount,
            int coverageTargetCount
    ) { }
}
