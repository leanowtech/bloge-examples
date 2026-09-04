package com.leanowtech.bloge.gateway.agenttdd;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.resource.ResourceDescriptor;
import com.leanowtech.bloge.gateway.resource.ResourceRegistry;
import com.leanowtech.bloge.gateway.visual.catalog.GraphDraftOperatorSnapshotCatalog;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.VisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftRepository;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphRunResponse;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphRunService;
import com.leanowtech.bloge.gateway.visual.runtime.VisualNodeExecutionFact;
import com.leanowtech.bloge.gateway.visual.resource.VisualResourceDescriptor;
import com.leanowtech.bloge.gateway.visualadapter.ResourceRegistryVisualAdapter;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Platform-only boundary that attests an exact logical GREEN line against real read-only resources.
 *
 * <p>The service is deliberately absent from the MCP tool catalog. A successful logical baseline
 * hands its immutable identity to this service, which derives an internal platform identity with
 * the dedicated {@code AGENT_TDD_ATTEST} purpose. The service admits only sandbox environments,
 * exact allowlisted HTTP resources, and read-only effects. Persisted evidence contains structural
 * call counts and Oracle booleans; inputs, outputs, diagnostics, URLs, and exception messages never
 * cross this boundary.</p>
 */
@Service
public final class AgentTddAttestationService {
    static final String ATTESTATION = "ATTESTATION";
    private static final String ATTESTATION_EXECUTE = "ATTESTATION_EXECUTE";
    private static final int MAX_FINGERPRINT_BYTES = 1024 * 1024;
    private static final Set<String> SANDBOX_ENVIRONMENTS = Set.of("local", "test", "sandbox");
    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() { };

    private final AgentTddStateRepository states;
    private final GraphDraftRepository drafts;
    private final VisualOperatorCatalog catalog;
    private final ResourceRegistry resources;
    private final VisualGraphRunService runner;
    private final AgentTddEgressHostPolicy egress;
    private final ObjectMapper mapper;

    /** Creates the real-execution boundary from authoritative graph, catalog, resource, and state stores. */
    public AgentTddAttestationService(AgentTddStateRepository states,
                                      GraphDraftRepository drafts,
                                      VisualOperatorCatalog catalog,
                                      ResourceRegistry resources,
                                      VisualGraphRunService runner,
                                      AgentTddEgressHostPolicy egress,
                                      ObjectMapper mapper) {
        this.states = Objects.requireNonNull(states, "states");
        this.drafts = Objects.requireNonNull(drafts, "drafts");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.resources = Objects.requireNonNull(resources, "resources");
        this.runner = Objects.requireNonNull(runner, "runner");
        this.egress = Objects.requireNonNull(egress, "egress");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /**
     * Automatically starts platform attestation for one newly persisted logical GREEN baseline.
     *
     * @param green exact baseline response already persisted by the workflow service
     * @param sourceIdentity authenticated Agent identity whose enterprise scope is retained
     * @return payload-free current attestation projection
     */
    public Map<String, Object> attestAfterGreen(Map<String, Object> green,
                                                IntegrationRequestContext sourceIdentity) {
        Objects.requireNonNull(sourceIdentity, "sourceIdentity");
        return attestOnce(green, platformIdentity(sourceIdentity), "AUTO", 0);
    }

    /**
     * Re-runs the current logical GREEN line after an explicitly authenticated human request.
     *
     * <p>This is the sole manual exception path. The reviewer supplies only {@code toolRef}; the
     * server reloads the current GREEN identity and still executes under the internal platform
     * purpose. Workload identities cannot turn this recovery endpoint into a real-call tool.</p>
     */
    public Map<String, Object> rerun(String toolRef, IntegrationRequestContext reviewer) {
        if (reviewer == null
                || !("HUMAN".equals(reviewer.actorType()) || "USER".equals(reviewer.actorType()))
                || !IntegrationOperation.AGENT_TDD_GOVERNED_WRITE.accepts(reviewer.purpose())) {
            throw new AgentTddToolException(
                    "FORBIDDEN_PURPOSE", "A human governance identity is required to rerun attestation.");
        }
        String normalizedToolRef = toolRef == null ? "" : toolRef.trim();
        JsonNode latest = states.find(AgentTddMutationService.scopeKey(reviewer),
                        AgentTddWorkflowService.VERDICT, normalizedToolRef)
                .map(AgentTddStoredAsset::data).map(data -> data.path("latest"))
                .orElseThrow(() -> new AgentTddToolException(
                        "GREEN_BASELINE_ABSENT", "A current logical GREEN baseline is required."));
        LinkedHashMap<String, Object> green = new LinkedHashMap<>(mapper.convertValue(latest, OBJECT_MAP));
        green.put("toolRef", normalizedToolRef);
        long currentAttestationRevision = reserveManualAttempt(Map.copyOf(green), reviewer).revision();
        return attestOnce(Map.copyOf(green), platformIdentity(reviewer),
                "MANUAL", currentAttestationRevision);
    }

    /**
     * Commits one human-authorized attempt revision before its external execution starts.
     *
     * <p>If the process exits after this commit, a later human confirmation advances the revision
     * and therefore receives a different durable execution key. Concurrent confirmations still
     * race on the same revision and only one can reserve the next attempt.</p>
     */
    private AgentTddStoredAsset reserveManualAttempt(Map<String, Object> green,
                                                     IntegrationRequestContext reviewer) {
        String scope = AgentTddMutationService.scopeKey(reviewer);
        String toolRef = text(green, "toolRef");
        java.util.Optional<AgentTddStoredAsset> current = states.find(scope, ATTESTATION, toolRef);
        if (current.map(AgentTddStoredAsset::data)
                .filter(data -> sameSubject(data, green))
                .filter(data -> "ATTESTED".equals(data.path("status").asText()))
                .isPresent()) {
            throw new AgentTddToolException(
                    "GATE_REJECTED", "The current logical GREEN line is already attested.");
        }
        long expectedRevision = current.map(AgentTddStoredAsset::revision).orElse(0L);
        return states.saveIfRevision(scope, ATTESTATION, toolRef, expectedRevision,
                mapper.valueToTree(recoveryProjection(green, reviewer.environmentId())));
    }

    /**
     * Verifies that a stored attestation still names the current graph, case set, bindings, and
     * resource descriptors.
     *
     * <p>This method performs no network call. Publication and readiness use it so replacing a
     * descriptor after a successful run invalidates the old attestation before release.</p>
     */
    public boolean isCurrent(JsonNode evidence, IntegrationRequestContext scopedIdentity) {
        if (evidence == null || scopedIdentity == null
                || !"ATTESTED".equals(evidence.path("status").asText())) return false;
        try {
            Map<String, Object> green = new LinkedHashMap<>(mapper.convertValue(evidence, OBJECT_MAP));
            green.put("side", "GREEN");
            green.put("status", "GO");
            AttestationPlan current = plan(Map.copyOf(green), platformIdentity(scopedIdentity));
            return current.caseSetRevision() == evidence.path("caseSetRevision").asLong(-1)
                    && current.contractFingerprint().equals(
                            evidence.path("contractFingerprint").asText())
                    && current.implementationFingerprint().equals(
                            evidence.path("implementationFingerprint").asText());
        } catch (RuntimeException staleOrUnavailable) {
            return false;
        }
    }

    /**
     * Executes one attestation under the non-Agent platform purpose.
     *
     * <p>Callers cannot choose alternate rows, bindings, output nodes, or a real/simulated mode.
     * Every executable input is re-derived from the exact durable GREEN identity.</p>
     *
     * @throws AgentTddToolException when the caller is not the internal attestation principal
     */
    public Map<String, Object> attest(Map<String, Object> green,
                                      IntegrationRequestContext identity) {
        requirePlatformAuthority(identity);
        return attestOnce(green, identity, "AUTO", 0);
    }

    /**
     * Reserves one exact attestation attempt before any network request is allowed to run.
     *
     * <p>The automatic key is stable for one GREEN fingerprint. Its reservation commits before
     * any network request, and completion commits afterwards, so concurrent calls cannot duplicate
     * the external read and process loss leaves an explicit recovery marker. A manual recovery key
     * additionally binds the current attestation revision: two clicks race on one reservation,
     * while a later deliberate retry receives a new key.</p>
     */
    private Map<String, Object> attestOnce(Map<String, Object> green,
                                           IntegrationRequestContext identity,
                                           String trigger,
                                           long priorAttestationRevision) {
        requirePlatformAuthority(identity);
        String toolRef = text(green, "toolRef");
        if (toolRef.isBlank()) {
            throw new AgentTddToolException("GATE_REJECTED", "Logical GREEN tool identity is missing.");
        }
        String scope = AgentTddMutationService.scopeKey(identity);
        Map<String, Object> requestMaterial = Map.of(
                "toolRef", toolRef,
                "environment", identity.environmentId(),
                "trigger", trigger,
                "priorAttestationRevision", priorAttestationRevision,
                "green", green == null ? Map.of() : green);
        String requestFingerprint = VisualBundleFingerprint.fromCanonicalValue(
                mapper, requestMaterial, MAX_FINGERPRINT_BYTES);
        AgentTddStateRepository.ExternalExecutionReservation reservation =
                states.reserveExternalExecution(scope, ATTESTATION_EXECUTE,
                        requestFingerprint, requestFingerprint);
        if (reservation.status() == AgentTddStateRepository.ExternalExecutionStatus.COMPLETED) {
            return mapper.convertValue(reservation.response(), OBJECT_MAP);
        }
        if (reservation.status() == AgentTddStateRepository.ExternalExecutionStatus.IN_PROGRESS) {
            return persistRecoveryRequired(green, identity);
        }
        JsonNode response = mapper.valueToTree(attestReserved(green, identity));
        return mapper.convertValue(states.completeExternalExecution(scope, ATTESTATION_EXECUTE,
                requestFingerprint, requestFingerprint, response), OBJECT_MAP);
    }

    private Map<String, Object> attestReserved(Map<String, Object> green,
                                                IntegrationRequestContext identity) {
        String toolRef = text(green, "toolRef");
        String scope = AgentTddMutationService.scopeKey(identity);
        String evidenceFingerprint = text(green, "evidenceFingerprint");
        Map<String, Object> replay = states.find(scope, ATTESTATION, toolRef)
                .map(AgentTddStoredAsset::data)
                .filter(data -> evidenceFingerprint.equals(data.path("evidenceFingerprint").asText()))
                .filter(data -> "ATTESTED".equals(data.path("status").asText()))
                .map(data -> mapper.convertValue(data, OBJECT_MAP))
                .orElse(null);
        if (replay != null) return replay;

        AttestationPlan plan;
        try {
            requireSandbox(identity);
            plan = plan(green, identity);
        } catch (AgentTddToolException failure) {
            return "ATTESTATION_STALE".equals(failure.code())
                    ? failureProjection(green, identity.environmentId(), failure.code())
                    : persistFailure(green, identity, failure.code());
        } catch (RuntimeException failure) {
            return persistFailure(green, identity, "ATTESTATION_PREPARATION_FAILED");
        }

        List<Map<String, Object>> cases = new ArrayList<>();
        Map<String, Integer> dependencyCalls = new LinkedHashMap<>();
        plan.dependencies().forEach(dependency -> dependencyCalls.put(dependency.nodeId(), 0));
        boolean allHeld = true;
        for (JsonNode row : plan.rows()) {
            CaseObservation observation = executeCase(plan, row);
            cases.add(observation.projection());
            allHeld &= observation.oracleHeld() && observation.allDependenciesCalled();
            observation.dependencyCalls().forEach((nodeId, count) ->
                    dependencyCalls.merge(nodeId, count, Integer::sum));
        }
        List<Map<String, Object>> dependencies = plan.dependencies().stream().map(dependency -> Map.<String, Object>of(
                "nodeId", dependency.nodeId(),
                "operatorRef", dependency.operatorRef(),
                "resourceId", dependency.resourceId(),
                "realCalled", dependencyCalls.getOrDefault(dependency.nodeId(), 0) > 0,
                "realCallCount", dependencyCalls.getOrDefault(dependency.nodeId(), 0))).toList();
        int realExternalCalls = dependencyCalls.values().stream().mapToInt(Integer::intValue).sum();
        String status = allHeld ? "ATTESTED" : "FAILED";
        String reasonCode = allHeld ? "" : failureReason(cases, dependencies);
        ObjectNode evidence = baseEvidence(plan, status, reasonCode);
        evidence.set("cases", mapper.valueToTree(cases));
        evidence.set("dependencies", mapper.valueToTree(dependencies));
        evidence.put("realExternalCalls", realExternalCalls);
        try {
            return persist(plan, evidence, identity);
        } catch (AgentTddToolException stale) {
            return failureProjection(green, identity.environmentId(), "ATTESTATION_STALE");
        }
    }

    private static IntegrationRequestContext platformIdentity(IntegrationRequestContext sourceIdentity) {
        return new IntegrationRequestContext(
                sourceIdentity.tenantId(), sourceIdentity.organizationId(), sourceIdentity.projectId(),
                sourceIdentity.environmentId(), sourceIdentity.region(), "PLATFORM",
                "agent-tdd-attestation-runner", "", "AGENT_TDD_ATTEST",
                sourceIdentity.correlationId());
    }

    private AttestationPlan plan(Map<String, Object> green, IntegrationRequestContext identity) {
        if (!"GREEN".equals(text(green, "side")) || !"GO".equals(text(green, "status"))) {
            throw new AgentTddToolException("GREEN_BASELINE_ABSENT", "A stable GREEN baseline is required.");
        }
        String toolRef = text(green, "toolRef");
        String caseSetRef = text(green, "caseSetRef");
        GraphDraft draft = drafts.find(toolRef).filter(identity::matchesDraftScope)
                .orElseThrow(() -> new AgentTddToolException(
                        "DRAFT_NOT_FOUND", "Tool draft was not found in the authorized scope."));
        AgentTddStoredAsset caseSet = states.find(AgentTddMutationService.scopeKey(identity),
                        AgentTddMutationService.CASE_SET, caseSetRef)
                .filter(asset -> toolRef.equals(asset.data().path("toolRef").asText()))
                .orElseThrow(() -> new AgentTddToolException(
                        "DRAFT_NOT_FOUND", "Approved case set was not found in the authorized scope."));
        List<JsonNode> rows = new ArrayList<>();
        caseSet.data().path("rows").forEach(row -> {
            if ("ACTIVE".equals(row.path("lifecycle").asText())) rows.add(row.deepCopy());
        });
        if (rows.isEmpty()) {
            throw new AgentTddToolException("GOLDEN_REQUIRES_APPROVAL", "No approved ACTIVE case exists.");
        }
        Map<String, OperatorDefinition> targets = catalog.findAll(
                AgentTddRuntimeBindingResolver.bindingLookupRefs(draft));
        GraphDraft executable = AgentTddRuntimeBindingResolver.materialize(
                draft, ref -> java.util.Optional.ofNullable(targets.get(ref)));
        GraphDraftOperatorSnapshotCatalog frozenCatalog = GraphDraftOperatorSnapshotCatalog.from(executable);
        List<Map<String, String>> bindings = AgentTddRuntimeBindingResolver.bindingIdentity(draft, executable);
        String currentEvidenceFingerprint = AgentTddExecutionService.evidenceFingerprint(
                mapper, toolRef, draft, rows, "GREEN", bindings);
        List<String> caseIds = rows.stream().map(row -> row.path("caseId").asText())
                .filter(value -> !value.isBlank()).sorted().toList();
        String goldenSetId = AgentTddExecutionService.goldenSetId(mapper, toolRef, draft, caseIds);
        if (draft.revision() != number(green, "draftRevision")
                || !goldenSetId.equals(text(green, "goldenSetId"))
                || !currentEvidenceFingerprint.equals(text(green, "evidenceFingerprint"))) {
            throw new AgentTddToolException("ATTESTATION_STALE", "Logical GREEN evidence is no longer current.");
        }
        List<Dependency> dependencies = executable.nodes().stream()
                .map(node -> dependency(executable, node))
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(Dependency::nodeId))
                .toList();
        String implementationFingerprint = implementationFingerprint(draft, bindings, dependencies);
        return new AttestationPlan(toolRef, caseSetRef, caseSet.revision(), draft, executable,
                frozenCatalog, List.copyOf(rows), bindings, dependencies, goldenSetId,
                currentEvidenceFingerprint,
                AgentTddExecutionService.contractFingerprint(mapper, draft),
                implementationFingerprint);
    }

    private String implementationFingerprint(GraphDraft draft,
                                               List<Map<String, String>> bindings,
                                               List<Dependency> dependencies) {
        List<Map<String, Object>> resourceIdentities = dependencies.stream()
                .map(dependency -> Map.<String, Object>of(
                        "nodeId", dependency.nodeId(),
                        "resourceId", dependency.resourceId(),
                        "descriptor", descriptorIdentity(dependency.descriptor())))
                .toList();
        return VisualBundleFingerprint.fromCanonicalValue(mapper, Map.of(
                "graphImplementation", AgentTddExecutionService.implementationFingerprint(
                        mapper, draft, bindings),
                "resources", resourceIdentities), MAX_FINGERPRINT_BYTES);
    }

    private Map<String, Object> descriptorIdentity(ResourceDescriptor descriptor) {
        LinkedHashMap<String, Object> identity = new LinkedHashMap<>();
        identity.put("resourceId", descriptor.resourceId());
        identity.put("urlTemplate", descriptor.urlTemplate());
        identity.put("method", descriptor.method());
        identity.put("defaultHeaders", descriptor.defaultHeaders());
        identity.put("authStrategy", mapper.valueToTree(descriptor.authStrategy()));
        identity.put("defaultTimeoutMillis", descriptor.defaultTimeout().toMillis());
        identity.put("parameterMapping", mapper.valueToTree(descriptor.parameterMapping()));
        identity.put("responseProtocol", mapper.valueToTree(descriptor.responseProtocol()));
        identity.put("payloadPath", Objects.toString(descriptor.payloadPath(), ""));
        identity.put("externalWriteContract", mapper.valueToTree(descriptor.externalWriteContract()));
        return Map.copyOf(identity);
    }

    private Dependency dependency(GraphDraft executable, GraphDraft.DraftNode node) {
        OperatorDefinition operator = executable.operatorSnapshots().get(node.id());
        if (operator == null || !operator.capabilities().effect().contains("EXTERNAL")) return null;
        String resourceId = operator.source().resourceId();
        if (!"READ_EXTERNAL".equals(operator.capabilities().effect()) || resourceId.isBlank()
                || !resources.contains(resourceId)) {
            throw new AgentTddToolException(
                    "WRITE_EFFECT_NOT_ALLOWED", "Attestation admits descriptor-backed reads only.");
        }
        ResourceDescriptor descriptor = resources.resolve(resourceId);
        if (descriptor.externalWrite()) {
            throw new AgentTddToolException(
                    "WRITE_EFFECT_NOT_ALLOWED", "Attestation does not execute external writes.");
        }
        AgentTddEgressHostPolicy.Resolution resolution = egress.resolveAllowed(descriptor.urlTemplate());
        return new Dependency(node.id(), operator.operatorRef(), resourceId,
                operator.capabilities().effect(), descriptor, resolution);
    }

    private CaseObservation executeCase(AttestationPlan plan, JsonNode row) {
        Map<String, Integer> calls = new LinkedHashMap<>();
        plan.dependencies().forEach(dependency -> calls.put(dependency.nodeId(), 0));
        try {
            Map<String, Object> given = mapper.convertValue(row.path("given"), OBJECT_MAP);
            Map<String, Object> runtimeInputs = projectRuntimeInputs(plan.executable(), given);
            Map<String, VisualResourceDescriptor> admittedDescriptors = new LinkedHashMap<>();
            plan.dependencies().forEach(dependency ->
                    admittedDescriptors.put(dependency.resourceId(),
                            ResourceRegistryVisualAdapter.toVisual(dependency.descriptor())));
            plan.dependencies().forEach(dependency -> egress.requireUnchanged(
                    dependency.descriptor().urlTemplate(), dependency.resolution()));
            VisualGraphRunResponse response = runner.runAgainst(
                    plan.executable(), runtimeInputs, "", plan.frozenCatalog(), admittedDescriptors);
            calls.replaceAll((nodeId, ignored) -> transportDispatchCount(response, nodeId));
            boolean dependenciesCalled = calls.values().stream().allMatch(count -> count > 0);
            boolean held = response.success() && AgentTddExecutionService.expectedMatches(
                    row.path("expect"), mapper.valueToTree(response.output()));
            return new CaseObservation(held, dependenciesCalled, Map.copyOf(calls), Map.of(
                    "caseId", row.path("caseId").asText(),
                    "executionSucceeded", response.success(),
                    "oracleHeld", held,
                    "allDependenciesCalled", dependenciesCalled,
                    "realExternalCalls", calls.values().stream().mapToInt(Integer::intValue).sum()));
        } catch (RuntimeException failure) {
            return new CaseObservation(false, false, Map.copyOf(calls), Map.of(
                    "caseId", row.path("caseId").asText(),
                    "executionSucceeded", false,
                    "oracleHeld", false,
                    "allDependenciesCalled", false,
                    "realExternalCalls", 0));
        }
    }

    /**
     * Projects a business golden row onto the graph's declared runtime input boundary.
     *
     * <p>Golden {@code given} values also carry decision-table fact columns for A4 coverage. Those
     * columns may describe facts produced by a real dependency rather than graph inputs. Closed
     * input schemas therefore receive only declared properties during attestation; open schemas
     * preserve every value.</p>
     *
     * @param draft executable graph whose input schema owns the runtime boundary
     * @param given approved golden facts
     * @return immutable runtime input projection
     */
    static Map<String, Object> projectRuntimeInputs(GraphDraft draft, Map<String, Object> given) {
        Map<String, Object> source = given == null ? Map.of() : given;
        if (draft == null || !Boolean.FALSE.equals(draft.inputSchema().schema().get("additionalProperties"))) {
            return Collections.unmodifiableMap(new LinkedHashMap<>(source));
        }
        Set<String> declared = draft.inputSchema().properties().keySet();
        LinkedHashMap<String, Object> projected = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (declared.contains(key)) projected.put(key, value);
        });
        return Collections.unmodifiableMap(projected);
    }

    private Map<String, Object> persist(AttestationPlan plan,
                                        ObjectNode evidence,
                                        IntegrationRequestContext identity) {
        return states.executeAtomically(() -> {
            String scope = AgentTddMutationService.scopeKey(identity);
            states.lockRevision(scope, AgentTddMutationService.CASE_SET,
                    plan.caseSetRef(), plan.caseSetRevision());
            JsonNode latest = states.find(scope, AgentTddWorkflowService.VERDICT, plan.toolRef())
                    .map(AgentTddStoredAsset::data).map(data -> data.path("latest"))
                    .orElseThrow(() -> new AgentTddToolException(
                            "ATTESTATION_STALE", "Logical GREEN evidence is no longer current."));
            GraphDraft currentDraft = drafts.find(plan.toolRef()).filter(identity::matchesDraftScope)
                    .orElseThrow(() -> new AgentTddToolException(
                            "ATTESTATION_STALE", "Tool draft is no longer current."));
            if (currentDraft.revision() != plan.draft().revision()
                    || !plan.evidenceFingerprint().equals(latest.path("evidenceFingerprint").asText())) {
                throw new AgentTddToolException("ATTESTATION_STALE", "Attestation subject changed during execution.");
            }
            AgentTddStoredAsset stored = states.save(scope, ATTESTATION, plan.toolRef(), evidence);
            return mapper.convertValue(stored.data(), OBJECT_MAP);
        });
    }

    private Map<String, Object> persistFailure(Map<String, Object> green,
                                               IntegrationRequestContext identity,
                                               String reasonCode) {
        Map<String, Object> projection = failureProjection(green, identity.environmentId(), reasonCode);
        String toolRef = text(green, "toolRef");
        if (!toolRef.isBlank()) {
            states.save(AgentTddMutationService.scopeKey(identity), ATTESTATION,
                    toolRef, mapper.valueToTree(projection));
        }
        return projection;
    }

    /** Persists a payload-free recovery state unless a concurrent runner already wrote the result. */
    private Map<String, Object> persistRecoveryRequired(Map<String, Object> green,
                                                        IntegrationRequestContext identity) {
        Map<String, Object> projection = recoveryProjection(green, identity.environmentId());
        String toolRef = text(green, "toolRef");
        if (toolRef.isBlank()) return projection;
        String scope = AgentTddMutationService.scopeKey(identity);
        java.util.Optional<AgentTddStoredAsset> current = states.find(scope, ATTESTATION, toolRef);
        if (current.map(AgentTddStoredAsset::data)
                .filter(data -> sameSubject(data, green))
                .filter(AgentTddAttestationService::isStableAttestationProjection)
                .isPresent()) {
            return mapper.convertValue(current.orElseThrow().data(), OBJECT_MAP);
        }
        try {
            return mapper.convertValue(states.saveIfRevision(scope, ATTESTATION, toolRef,
                    current.map(AgentTddStoredAsset::revision).orElse(0L), mapper.valueToTree(projection)).data(),
                    OBJECT_MAP);
        } catch (AgentTddToolException concurrentChange) {
            return states.find(scope, ATTESTATION, toolRef)
                    .filter(asset -> sameSubject(asset.data(), green))
                    .map(AgentTddStoredAsset::data)
                    .map(data -> mapper.convertValue(data, OBJECT_MAP))
                    .orElse(projection);
        }
    }

    private ObjectNode baseEvidence(AttestationPlan plan, String status, String reasonCode) {
        ObjectNode evidence = mapper.createObjectNode();
        evidence.put("toolRef", plan.toolRef());
        evidence.put("status", status);
        evidence.put("reasonCode", reasonCode);
        evidence.put("environment", plan.draft().environment());
        evidence.put("goldenSetId", plan.goldenSetId());
        evidence.put("evidenceFingerprint", plan.evidenceFingerprint());
        evidence.put("contractFingerprint", plan.contractFingerprint());
        evidence.put("implementationFingerprint", plan.implementationFingerprint());
        evidence.put("draftRevision", plan.draft().revision());
        evidence.put("caseSetRef", plan.caseSetRef());
        evidence.put("caseSetRevision", plan.caseSetRevision());
        return evidence;
    }

    private static Map<String, Object> failureProjection(Map<String, Object> green,
                                                         String environment,
                                                         String reasonCode) {
        LinkedHashMap<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("toolRef", text(green, "toolRef"));
        evidence.put("status", "FAILED");
        evidence.put("reasonCode", reasonCode);
        evidence.put("environment", environment == null ? "" : environment);
        evidence.put("goldenSetId", text(green, "goldenSetId"));
        evidence.put("evidenceFingerprint", text(green, "evidenceFingerprint"));
        evidence.put("draftRevision", number(green, "draftRevision"));
        evidence.put("caseSetRef", text(green, "caseSetRef"));
        evidence.put("caseSetRevision", number(green, "caseSetRevision"));
        evidence.put("cases", List.of());
        evidence.put("dependencies", List.of());
        evidence.put("realExternalCalls", 0);
        return Map.copyOf(evidence);
    }

    private static Map<String, Object> recoveryProjection(Map<String, Object> green,
                                                          String environment) {
        LinkedHashMap<String, Object> evidence = new LinkedHashMap<>(failureProjection(
                green, environment, "ATTESTATION_RECOVERY_REQUIRED"));
        evidence.put("status", "RECOVERY_REQUIRED");
        return Map.copyOf(evidence);
    }

    private static boolean sameSubject(JsonNode evidence, Map<String, Object> green) {
        return evidence != null
                && text(green, "toolRef").equals(evidence.path("toolRef").asText())
                && text(green, "goldenSetId").equals(evidence.path("goldenSetId").asText())
                && text(green, "evidenceFingerprint").equals(
                        evidence.path("evidenceFingerprint").asText())
                && number(green, "draftRevision") == evidence.path("draftRevision").asLong(-1);
    }

    /** Returns whether an exact subject already has evidence that recovery must not overwrite. */
    private static boolean isStableAttestationProjection(JsonNode evidence) {
        return Set.of("ATTESTED", "FAILED", "RECOVERY_REQUIRED")
                .contains(evidence.path("status").asText());
    }

    /** Counts only requests observed at the governed HTTP transport boundary. */
    private static int transportDispatchCount(VisualGraphRunResponse response, String nodeId) {
        Map<String, VisualNodeExecutionFact> facts = response.nodeExecutionFacts();
        VisualNodeExecutionFact fact = facts == null ? null : facts.get(nodeId);
        if (fact == null) return 0;
        return Math.toIntExact(fact.events().stream()
                .filter(event -> "HTTP_TRANSPORT_DISPATCHED".equals(event.type()))
                .count());
    }

    private static String failureReason(List<Map<String, Object>> cases,
                                        List<Map<String, Object>> dependencies) {
        if (cases.stream().anyMatch(row -> !Boolean.TRUE.equals(row.get("executionSucceeded")))) {
            return "ATTESTATION_EXECUTION_FAILED";
        }
        if (dependencies.stream().anyMatch(row -> !Boolean.TRUE.equals(row.get("realCalled")))) {
            return "ATTESTATION_DEPENDENCY_NOT_CALLED";
        }
        return "ATTESTATION_ORACLE_MISMATCH";
    }

    private static void requirePlatformAuthority(IntegrationRequestContext identity) {
        if (identity == null || !"PLATFORM".equals(identity.actorType())
                || !IntegrationOperation.AGENT_TDD_ATTEST.accepts(identity.purpose())) {
            throw new AgentTddToolException(
                    "FORBIDDEN_PURPOSE", "Only the platform attestation runner may execute real dependencies.");
        }
    }

    private static void requireSandbox(IntegrationRequestContext identity) {
        String environment = identity.environmentId().toLowerCase(Locale.ROOT);
        if (!SANDBOX_ENVIRONMENTS.contains(environment)) {
            throw new AgentTddToolException(
                    "ATTESTATION_ENVIRONMENT_NOT_ALLOWED", "Attestation is restricted to sandbox environments.");
        }
    }

    private static String text(Map<String, Object> values, String key) {
        Object value = values == null ? null : values.get(key);
        return value == null ? "" : value.toString().trim();
    }

    private static long number(Map<String, Object> values, String key) {
        Object value = values == null ? null : values.get(key);
        return value instanceof Number number ? number.longValue() : -1;
    }

    private record Dependency(String nodeId,
                              String operatorRef,
                              String resourceId,
                              String effect,
                              ResourceDescriptor descriptor,
                              AgentTddEgressHostPolicy.Resolution resolution) { }

    private record CaseObservation(boolean oracleHeld,
                                   boolean allDependenciesCalled,
                                   Map<String, Integer> dependencyCalls,
                                   Map<String, Object> projection) { }

    private record AttestationPlan(String toolRef,
                                   String caseSetRef,
                                   long caseSetRevision,
                                   GraphDraft draft,
                                   GraphDraft executable,
                                   GraphDraftOperatorSnapshotCatalog frozenCatalog,
                                   List<JsonNode> rows,
                                   List<Map<String, String>> bindings,
                                   List<Dependency> dependencies,
                                   String goldenSetId,
                                   String evidenceFingerprint,
                                   String contractFingerprint,
                                   String implementationFingerprint) { }
}
