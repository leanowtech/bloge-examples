package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Portable, payload-free evidence for one terminal execution of an exact mirror generation.
 *
 * <p>Business inputs, outputs, edge values, and resolver outputs are represented only by canonical
 * SHA-256 fingerprints. The protocol intentionally duplicates the minimum stable execution facts
 * needed by an external verifier instead of embedding the internal {@code TestRunEvidence}, whose
 * payload-bearing shape and lifecycle are owned by the testing subsystem.</p>
 *
 * @param schemaVersion mirror run-evidence protocol version
 * @param runId terminal run identity
 * @param requestId caller idempotency or correlation identity
 * @param requestContextFingerprint canonical fingerprint of the detached effective input context,
 *                                  including BLOGE reserved tenant and namespace coordinates
 * @param planId admitted mirror plan identity
 * @param planFingerprint exact sealed mirror plan
 * @param capabilityClosureFingerprint exact capability closure admitted by the plan
 * @param executionControlFingerprint exact effective execution-control generation
 * @param rootCapability exact composed capability executed by this run
 * @param fixtureBundleRef exact fixture bundle revision used by the resolver chain
 * @param externalBindings payload-free external dependency-to-invocation closure
 * @param scope authenticated enterprise namespace
 * @param authorizedPurpose server-authorized non-production purpose
 * @param status normalized terminal run status
 * @param evidenceClass exploratory or certifiable evidence classification
 * @param semanticResultFingerprint canonical business-result identity from the shared test kernel
 * @param startedAt observed execution start
 * @param completedAt observed terminal time
 * @param nodeTraces payload-free node and delegate-attempt facts
 * @param edgeTraces payload-free edge transfer facts
 * @param resolutions sealed resolver provenance for every external delegate attempt
 * @param stateEvidence payload-free Session state closure for stateful v3 or v4 runs
 * @param isolation structural and deployment isolation facts observed for this run
 * @param limitations bounded reasons the evidence cannot claim higher fidelity or certification
 */
public record MirrorRunEvidence(
        String schemaVersion,
        String runId,
        String requestId,
        String requestContextFingerprint,
        String planId,
        String planFingerprint,
        String capabilityClosureFingerprint,
        String executionControlFingerprint,
        MirrorArtifactRef rootCapability,
        MirrorArtifactRef fixtureBundleRef,
        List<ExternalBinding> externalBindings,
        CapabilitySnapshot.Scope scope,
        String authorizedPurpose,
        Status status,
        EvidenceClass evidenceClass,
        String semanticResultFingerprint,
        Instant startedAt,
        Instant completedAt,
        List<NodeTrace> nodeTraces,
        List<EdgeTrace> edgeTraces,
        List<MirrorResolution> resolutions,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        MirrorStateEvidence stateEvidence,
        IsolationFacts isolation,
        List<String> limitations
) {
    /** Legacy payload-free mirror evidence protocol version. */
    public static final String SCHEMA_VERSION_V1 = "resourceGateway.mirrorRunEvidence.v1";
    /** Current evidence version carrying double-observed deployment trust. */
    public static final String SCHEMA_VERSION = "resourceGateway.mirrorRunEvidence.v2";
    /** Stateful evidence version carrying one complete Session access closure. */
    public static final String STATEFUL_SCHEMA_VERSION =
            "resourceGateway.mirrorRunEvidence.v3";
    /** Read/write evidence version carrying one complete Session transition closure. */
    public static final String READ_WRITE_SCHEMA_VERSION =
            "resourceGateway.mirrorRunEvidence.v4";
    /** Maximum nodes or edges admitted to one portable bundle. */
    public static final int MAXIMUM_TRACE_ITEMS = 100_000;
    /** Maximum external resolver outcomes admitted to one portable bundle. */
    public static final int MAXIMUM_RESOLUTIONS = 100_000;
    /** Maximum attempts admitted under one node occurrence. */
    public static final int MAXIMUM_ATTEMPTS_PER_NODE = 10_000;
    /** Maximum bounded evidence limitations. */
    public static final int MAXIMUM_LIMITATIONS = 256;
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    /** Terminal state aligned with the shared test execution kernel. */
    public enum Status {
        PASSED,
        ASSERTION_FAILED,
        EXECUTION_FAILED,
        CONTROL_PLAN_REJECTED,
        FIXTURE_UNMATCHED,
        FIXTURE_UNUSED,
        CONTROL_PLAN_UNAVAILABLE,
        EVIDENCE_INCOMPLETE,
        CANCELLED,
        TIMED_OUT
    }

    /** Trust class consumed by correctness workbooks and release gates. */
    public enum EvidenceClass {
        EXPLORATORY,
        CERTIFIABLE
    }

    /** Validates exact identity, deterministic ordering, and payload omission. */
    public MirrorRunEvidence {
        schemaVersion = version(schemaVersion);
        runId = required(runId, "runId", 512);
        requestId = required(requestId, "requestId", 512);
        requestContextFingerprint = fingerprint(requestContextFingerprint,
                "requestContextFingerprint");
        planId = required(planId, "planId", 512);
        planFingerprint = fingerprint(planFingerprint, "planFingerprint");
        capabilityClosureFingerprint = fingerprint(capabilityClosureFingerprint,
                "capabilityClosureFingerprint");
        executionControlFingerprint = fingerprint(executionControlFingerprint,
                "executionControlFingerprint");
        rootCapability = requireKind(rootCapability, "CAPABILITY", "rootCapability");
        fixtureBundleRef = requireKind(fixtureBundleRef, "FIXTURE_BUNDLE", "fixtureBundleRef");
        externalBindings = orderedExternalBindings(externalBindings);
        scope = Objects.requireNonNull(scope, "scope");
        authorizedPurpose = required(authorizedPurpose, "authorizedPurpose", 256);
        if (authorizedPurpose.toUpperCase(java.util.Locale.ROOT).contains("PRODUCTION")) {
            throw new IllegalArgumentException("mirror evidence purpose must not be production");
        }
        status = Objects.requireNonNull(status, "status");
        evidenceClass = evidenceClass == null ? EvidenceClass.EXPLORATORY : evidenceClass;
        semanticResultFingerprint = fingerprint(semanticResultFingerprint,
                "semanticResultFingerprint");
        startedAt = Objects.requireNonNull(startedAt, "startedAt");
        completedAt = Objects.requireNonNull(completedAt, "completedAt");
        if (completedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("completedAt must not precede startedAt");
        }
        nodeTraces = orderedNodes(nodeTraces);
        edgeTraces = orderedEdges(edgeTraces);
        resolutions = orderedResolutions(resolutions, runId, planFingerprint);
        validateResolutionBindings(externalBindings, resolutions);
        boolean readOnlyStatefulVersion =
                STATEFUL_SCHEMA_VERSION.equals(schemaVersion);
        boolean readWriteStatefulVersion =
                READ_WRITE_SCHEMA_VERSION.equals(schemaVersion);
        boolean statefulVersion =
                readOnlyStatefulVersion || readWriteStatefulVersion;
        if (statefulVersion) {
            stateEvidence = Objects.requireNonNull(
                    stateEvidence, "stateEvidence");
            if (!runId.equals(stateEvidence.runId())
                    || !planFingerprint.equals(
                    stateEvidence.planFingerprint())
                    || stateEvidence.stateEvidenceFingerprint().isBlank()) {
                throw new IllegalArgumentException(
                        "stateful mirror evidence requires one sealed exact state closure");
            }
            if (readOnlyStatefulVersion
                    != MirrorStateRunEvidence.SCHEMA_VERSION.equals(
                    stateEvidence.schemaVersion())
                    || readWriteStatefulVersion
                    != MirrorStateTransitionRunEvidence.SCHEMA_VERSION.equals(
                    stateEvidence.schemaVersion())) {
                throw new IllegalArgumentException(
                        "mirror run evidence and nested state evidence versions differ");
            }
        } else if (stateEvidence != null) {
            throw new IllegalArgumentException(
                    "legacy mirror evidence cannot carry state evidence");
        }
        isolation = Objects.requireNonNull(isolation, "isolation");
        limitations = orderedStrings(limitations, "limitations", MAXIMUM_LIMITATIONS, 512);
        if ((SCHEMA_VERSION.equals(schemaVersion) || statefulVersion)
                && isolation.deploymentEgressEnforced()
                != (isolation.deploymentTrustBinding() != null)) {
            throw new IllegalArgumentException(
                    "v2 deployment egress enforcement requires double-observed run trust");
        }
        if (evidenceClass == EvidenceClass.CERTIFIABLE
                && (!isolation.deploymentEgressEnforced() || !isolation.limitations().isEmpty()
                || !limitations.isEmpty()
                || (SCHEMA_VERSION.equals(schemaVersion) || statefulVersion)
                && isolation.deploymentTrustBinding() == null)) {
            throw new IllegalArgumentException(
                    "certifiable mirror evidence requires proven egress isolation and no limitations");
        }
        if (SCHEMA_VERSION_V1.equals(schemaVersion)
                && isolation.deploymentTrustBinding() != null) {
            throw new IllegalArgumentException(
                    "v1 mirror evidence cannot claim deployment run trust");
        }
    }

    /**
     * Compatibility constructor for stateless v1 and v2 evidence.
     *
     * <p>Stateful evidence must use the canonical constructor and
     * {@link #STATEFUL_SCHEMA_VERSION}; this overload cannot attach a Session closure.</p>
     */
    public MirrorRunEvidence(
            String schemaVersion,
            String runId,
            String requestId,
            String requestContextFingerprint,
            String planId,
            String planFingerprint,
            String capabilityClosureFingerprint,
            String executionControlFingerprint,
            MirrorArtifactRef rootCapability,
            MirrorArtifactRef fixtureBundleRef,
            List<ExternalBinding> externalBindings,
            CapabilitySnapshot.Scope scope,
            String authorizedPurpose,
            Status status,
            EvidenceClass evidenceClass,
            String semanticResultFingerprint,
            Instant startedAt,
            Instant completedAt,
            List<NodeTrace> nodeTraces,
            List<EdgeTrace> edgeTraces,
            List<MirrorResolution> resolutions,
            IsolationFacts isolation,
            List<String> limitations) {
        this(schemaVersion, runId, requestId,
                requestContextFingerprint, planId, planFingerprint,
                capabilityClosureFingerprint,
                executionControlFingerprint, rootCapability,
                fixtureBundleRef, externalBindings, scope,
                authorizedPurpose, status, evidenceClass,
                semanticResultFingerprint, startedAt, completedAt,
                nodeTraces, edgeTraces, resolutions, null, isolation,
                limitations);
    }

    /**
     * Payload-free external dependency binding required for independent resolution-closure checks.
     *
     * @param parentCapabilityRef exact composed parent capability
     * @param dependencyNodeId dependency node declared by the parent capability
     * @param capabilityRef exact external capability
     * @param invocationSiteId stable BLOGE invocation site
     * @param graphPath stable path of the graph that owns the invocation
     */
    public record ExternalBinding(
            MirrorArtifactRef parentCapabilityRef,
            String dependencyNodeId,
            MirrorArtifactRef capabilityRef,
            String invocationSiteId,
            String graphPath
    ) {
        /** Validates one exact payload-free external execution boundary. */
        public ExternalBinding {
            parentCapabilityRef = requireKind(parentCapabilityRef, "CAPABILITY",
                    "parentCapabilityRef");
            dependencyNodeId = required(dependencyNodeId, "dependencyNodeId", 512);
            capabilityRef = requireKind(capabilityRef, "CAPABILITY", "capabilityRef");
            invocationSiteId = required(invocationSiteId, "invocationSiteId", 2_048);
            graphPath = normalizeGraphPath(graphPath);
        }
    }

    /**
     * Payload-free node occurrence.
     *
     * @param nodeId graph-local node id
     * @param operatorRef resolved operator identity
     * @param status normalized node status
     * @param fidelity observed execution fidelity
     * @param inputFingerprint canonical node input fingerprint
     * @param outputFingerprint canonical node output fingerprint
     * @param errorCode stable payload-free failure code
     * @param durationMs observed duration
     * @param invocationSiteId stable structural invocation site
     * @param graphPath stable owning graph path
     * @param correlationKey foreach, loop, or business correlation coordinate
     * @param occurrence one-based invocation occurrence
     * @param graphOccurrence one-based containing-graph occurrence
     * @param attempts ordered delegate attempts
     */
    public record NodeTrace(
            String nodeId,
            String operatorRef,
            String status,
            String fidelity,
            String inputFingerprint,
            String outputFingerprint,
            String errorCode,
            long durationMs,
            String invocationSiteId,
            String graphPath,
            String correlationKey,
            int occurrence,
            int graphOccurrence,
            List<AttemptTrace> attempts
    ) {
        /** Validates one complete payload-free node coordinate. */
        public NodeTrace {
            nodeId = required(nodeId, "nodeId", 512);
            operatorRef = required(operatorRef, "operatorRef", 1_024);
            status = required(status, "node status", 64);
            fidelity = required(fidelity, "node fidelity", 64);
            inputFingerprint = fingerprint(inputFingerprint, "node inputFingerprint");
            outputFingerprint = fingerprint(outputFingerprint, "node outputFingerprint");
            errorCode = bounded(errorCode, "node errorCode", 256);
            if (durationMs < 0) {
                throw new IllegalArgumentException("node durationMs must be non-negative");
            }
            invocationSiteId = required(invocationSiteId, "invocationSiteId", 2_048);
            graphPath = normalizeGraphPath(graphPath);
            correlationKey = bounded(correlationKey, "correlationKey", 1_024);
            if (occurrence < 1 || graphOccurrence < 1) {
                throw new IllegalArgumentException(
                        "node occurrence and graphOccurrence must be positive");
            }
            attempts = orderedAttempts(attempts);
        }
    }

    /**
     * Payload-free delegate attempt under one node occurrence.
     *
     * @param attempt one-based attempt number
     * @param status normalized attempt status
     * @param fidelity observed attempt fidelity
     * @param inputFingerprint canonical attempt input fingerprint
     * @param outputFingerprint canonical attempt output fingerprint
     * @param errorCode stable payload-free failure code
     * @param durationMs observed duration
     */
    public record AttemptTrace(
            int attempt,
            String status,
            String fidelity,
            String inputFingerprint,
            String outputFingerprint,
            String errorCode,
            long durationMs
    ) {
        /** Validates one complete payload-free delegate attempt. */
        public AttemptTrace {
            if (attempt < 1) {
                throw new IllegalArgumentException("attempt must be positive");
            }
            status = required(status, "attempt status", 64);
            fidelity = required(fidelity, "attempt fidelity", 64);
            inputFingerprint = fingerprint(inputFingerprint, "attempt inputFingerprint");
            outputFingerprint = fingerprint(outputFingerprint, "attempt outputFingerprint");
            errorCode = bounded(errorCode, "attempt errorCode", 256);
            if (durationMs < 0) {
                throw new IllegalArgumentException("attempt durationMs must be non-negative");
            }
        }
    }

    /**
     * Payload-free edge transfer.
     *
     * @param edgeId stable edge identity
     * @param status normalized transfer status
     * @param valueFingerprint canonical transferred-value fingerprint
     * @param graphPath stable owning graph path
     * @param correlationKey foreach, loop, or business correlation coordinate
     * @param graphOccurrence one-based containing-graph occurrence
     * @param fromInvocationSiteId stable source invocation site
     * @param toInvocationSiteId stable target invocation site
     */
    public record EdgeTrace(
            String edgeId,
            String status,
            String valueFingerprint,
            String graphPath,
            String correlationKey,
            int graphOccurrence,
            String fromInvocationSiteId,
            String toInvocationSiteId
    ) {
        /** Validates one complete payload-free edge coordinate. */
        public EdgeTrace {
            edgeId = required(edgeId, "edgeId", 2_048);
            status = required(status, "edge status", 64);
            valueFingerprint = fingerprint(valueFingerprint, "edge valueFingerprint");
            graphPath = normalizeGraphPath(graphPath);
            correlationKey = bounded(correlationKey, "correlationKey", 1_024);
            if (graphOccurrence < 1) {
                throw new IllegalArgumentException("edge graphOccurrence must be positive");
            }
            fromInvocationSiteId = required(fromInvocationSiteId,
                    "fromInvocationSiteId", 2_048);
            toInvocationSiteId = required(toInvocationSiteId,
                    "toInvocationSiteId", 2_048);
        }
    }

    /**
     * Structural isolation facts signed with the run evidence.
     *
     * @param engineMode isolated execution-engine construction mode
     * @param interceptorTypes exact configured interceptor class names
     * @param listenerTypes exact configured listener class names
     * @param durableStoresAttached whether production durable stores were attached
     * @param productionContextCarriersAttached whether ambient production context was attached
     * @param productionExtensionListenersAttached whether production extension listeners were attached
     * @param realExternalCallsAllowed whether any external leaf could fall through to a real binding
     * @param externalCredentialsAllowed whether external credentials could be resolved
     * @param networkEgressAllowed whether the immutable plan allowed network egress
     * @param deploymentEgressEnforced whether an out-of-process deployment control proved egress denial
     * @param deploymentIsolationRef exact attestation proving deployment egress denial, when enforced
     * @param deploymentTrustBinding double-observed agent trust signed into v2 evidence
     * @param limitations bounded isolation facts not yet independently proven
     */
    public record IsolationFacts(
            EngineMode engineMode,
            List<String> interceptorTypes,
            List<String> listenerTypes,
            boolean durableStoresAttached,
            boolean productionContextCarriersAttached,
            boolean productionExtensionListenersAttached,
            boolean realExternalCallsAllowed,
            boolean externalCredentialsAllowed,
            boolean networkEgressAllowed,
            boolean deploymentEgressEnforced,
            MirrorArtifactRef deploymentIsolationRef,
            @JsonInclude(JsonInclude.Include.NON_NULL)
            MirrorDeploymentIsolationRunTrust.Binding deploymentTrustBinding,
            List<String> limitations
    ) {
        /** Supported execution-engine isolation mode. */
        public enum EngineMode {
            INDEPENDENT_TEST_ENGINE
        }

        /** Rejects any production authority or real-external path in mirror evidence. */
        public IsolationFacts {
            engineMode = Objects.requireNonNull(engineMode, "engineMode");
            interceptorTypes = orderedStrings(interceptorTypes, "interceptorTypes", 256, 1_024);
            listenerTypes = orderedStrings(listenerTypes, "listenerTypes", 256, 1_024);
            limitations = orderedStrings(limitations, "isolation limitations", 256, 512);
            if (durableStoresAttached || productionContextCarriersAttached
                    || productionExtensionListenersAttached || realExternalCallsAllowed
                    || externalCredentialsAllowed || networkEgressAllowed) {
                throw new IllegalArgumentException(
                        "mirror isolation facts must not carry production authority");
            }
            if (deploymentIsolationRef != null
                    && !"DEPLOYMENT_ISOLATION_ATTESTATION".equals(deploymentIsolationRef.kind())) {
                throw new IllegalArgumentException(
                        "deploymentIsolationRef must reference DEPLOYMENT_ISOLATION_ATTESTATION");
            }
            if (deploymentEgressEnforced != (deploymentIsolationRef != null)) {
                throw new IllegalArgumentException(
                        "deployment egress enforcement requires an exact isolation attestation");
            }
            if (!deploymentEgressEnforced && deploymentTrustBinding != null
                    || deploymentTrustBinding != null
                    && !deploymentIsolationRef.equals(deploymentTrustBinding.attestationRef())) {
                throw new IllegalArgumentException(
                        "deployment egress enforcement requires exact double-observed run trust");
            }
            if (!deploymentEgressEnforced && limitations.stream()
                    .noneMatch("DEPLOYMENT_EGRESS_NOT_ATTESTED"::equals)) {
                throw new IllegalArgumentException(
                        "unproven deployment egress requires an explicit limitation");
            }
        }

        /** Compatibility constructor for exploratory v1-style isolation facts. */
        public IsolationFacts(
                EngineMode engineMode,
                List<String> interceptorTypes,
                List<String> listenerTypes,
                boolean durableStoresAttached,
                boolean productionContextCarriersAttached,
                boolean productionExtensionListenersAttached,
                boolean realExternalCallsAllowed,
                boolean externalCredentialsAllowed,
                boolean networkEgressAllowed,
                boolean deploymentEgressEnforced,
                MirrorArtifactRef deploymentIsolationRef,
                List<String> limitations) {
            this(engineMode, interceptorTypes, listenerTypes, durableStoresAttached,
                    productionContextCarriersAttached, productionExtensionListenersAttached,
                    realExternalCallsAllowed, externalCredentialsAllowed, networkEgressAllowed,
                    deploymentEgressEnforced, deploymentIsolationRef, null, limitations);
        }
    }

    /** Prevents identifiers and fingerprint lists from expanding generic logs. */
    @Override
    public String toString() {
        return "MirrorRunEvidence[runId=" + runId + ", planFingerprint=" + planFingerprint
                + ", status=" + status + ", evidenceClass=" + evidenceClass
                + ", nodeCount=" + nodeTraces.size() + ", edgeCount=" + edgeTraces.size()
                + ", resolutionCount=" + resolutions.size()
                + ", stateEvidence=" + (stateEvidence != null) + "]";
    }

    private static List<NodeTrace> orderedNodes(List<NodeTrace> values) {
        List<NodeTrace> result = values == null ? List.of() : values.stream()
                .map(value -> Objects.requireNonNull(value, "nodeTrace"))
                .sorted(Comparator.comparing(NodeTrace::invocationSiteId)
                        .thenComparing(NodeTrace::graphPath)
                        .thenComparing(NodeTrace::correlationKey)
                        .thenComparingInt(NodeTrace::graphOccurrence)
                        .thenComparingInt(NodeTrace::occurrence)
                        .thenComparing(NodeTrace::nodeId))
                .toList();
        boundedSize(result, "nodeTraces", MAXIMUM_TRACE_ITEMS);
        requireUnique(result.stream().map(value -> value.invocationSiteId() + '\0'
                + value.graphPath() + '\0' + value.correlationKey() + '\0'
                + value.graphOccurrence() + '\0' + value.occurrence()).toList(), "nodeTraces");
        return result;
    }

    private static List<ExternalBinding> orderedExternalBindings(List<ExternalBinding> values) {
        List<ExternalBinding> result = values == null ? List.of() : values.stream()
                .map(value -> Objects.requireNonNull(value, "externalBinding"))
                .sorted(Comparator.comparing(ExternalBinding::invocationSiteId)
                        .thenComparing(ExternalBinding::dependencyNodeId)
                        .thenComparing(value -> value.capabilityRef().id()))
                .toList();
        boundedSize(result, "externalBindings", MirrorPlan.MAXIMUM_EXTERNAL_BINDINGS);
        requireUnique(result.stream().map(ExternalBinding::invocationSiteId).toList(),
                "externalBindings");
        return result;
    }

    private static void validateResolutionBindings(
            List<ExternalBinding> bindings, List<MirrorResolution> resolutions) {
        Map<String, ExternalBinding> bySite = new java.util.LinkedHashMap<>();
        bindings.forEach(binding -> bySite.put(binding.invocationSiteId(), binding));
        for (MirrorResolution resolution : resolutions) {
            ExternalBinding binding = bySite.get(resolution.invocationSiteId());
            if (binding == null || !binding.graphPath().equals(resolution.graphPath())
                    || !binding.capabilityRef().equals(resolution.capabilityRef())) {
                throw new IllegalArgumentException(
                        "mirror resolution must match an exact external binding");
            }
        }
    }

    private static List<AttemptTrace> orderedAttempts(List<AttemptTrace> values) {
        List<AttemptTrace> result = values == null ? List.of() : values.stream()
                .map(value -> Objects.requireNonNull(value, "attemptTrace"))
                .sorted(Comparator.comparingInt(AttemptTrace::attempt))
                .toList();
        boundedSize(result, "attempts", MAXIMUM_ATTEMPTS_PER_NODE);
        requireUnique(result.stream().map(value -> Integer.toString(value.attempt())).toList(),
                "attempts");
        return result;
    }

    private static List<EdgeTrace> orderedEdges(List<EdgeTrace> values) {
        List<EdgeTrace> result = values == null ? List.of() : values.stream()
                .map(value -> Objects.requireNonNull(value, "edgeTrace"))
                .sorted(Comparator.comparing(EdgeTrace::graphPath)
                        .thenComparing(EdgeTrace::correlationKey)
                        .thenComparingInt(EdgeTrace::graphOccurrence)
                        .thenComparing(EdgeTrace::edgeId))
                .toList();
        boundedSize(result, "edgeTraces", MAXIMUM_TRACE_ITEMS);
        requireUnique(result.stream().map(value -> value.graphPath() + '\0'
                + value.correlationKey() + '\0' + value.graphOccurrence() + '\0'
                + value.edgeId()).toList(), "edgeTraces");
        return result;
    }

    private static List<MirrorResolution> orderedResolutions(
            List<MirrorResolution> values, String runId, String planFingerprint) {
        List<MirrorResolution> result = values == null ? List.of() : values.stream()
                .map(value -> Objects.requireNonNull(value, "resolution"))
                .sorted(Comparator.comparing(MirrorResolution::invocationSiteId)
                        .thenComparing(MirrorResolution::correlationKey)
                        .thenComparingInt(MirrorResolution::occurrence)
                        .thenComparingInt(MirrorResolution::attempt))
                .toList();
        boundedSize(result, "resolutions", MAXIMUM_RESOLUTIONS);
        Set<String> coordinates = new HashSet<>();
        for (MirrorResolution resolution : result) {
            if (!runId.equals(resolution.runId())
                    || !planFingerprint.equals(resolution.planFingerprint())) {
                throw new IllegalArgumentException(
                        "mirror resolutions must belong to the exact run and plan");
            }
            if (resolution.resolutionFingerprint().isBlank()) {
                throw new IllegalArgumentException("mirror resolutions must be sealed");
            }
            if (resolution.outputIncluded() || resolution.output() != null
                    || (resolution.payloadVisibility() != MirrorResolution.PayloadVisibility.HASH_ONLY
                    && resolution.payloadVisibility() != MirrorResolution.PayloadVisibility.NONE)) {
                throw new IllegalArgumentException(
                        "portable mirror evidence must not include resolver payloads");
            }
            String coordinate = resolution.invocationSiteId() + '\0'
                    + resolution.correlationKey() + '\0' + resolution.occurrence() + '\0'
                    + resolution.attempt();
            if (!coordinates.add(coordinate)) {
                throw new IllegalArgumentException("mirror resolution coordinates must be unique");
            }
        }
        return result;
    }

    private static List<String> orderedStrings(
            List<String> values, String field, int maximumItems, int maximumLength) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        boundedSize(values, field, maximumItems);
        Set<String> normalized = new java.util.TreeSet<>();
        for (String value : values) {
            if (!normalized.add(required(value, field + " item", maximumLength))) {
                throw new IllegalArgumentException(field + " must be unique");
            }
        }
        return List.copyOf(normalized);
    }

    private static void boundedSize(List<?> values, String field, int maximum) {
        if (values.size() > maximum) {
            throw new IllegalArgumentException(field + " exceeds its item limit");
        }
    }

    private static void requireUnique(List<String> values, String field) {
        if (new HashSet<>(values).size() != values.size()) {
            throw new IllegalArgumentException(field + " coordinates must be unique");
        }
    }

    private static MirrorArtifactRef requireKind(
            MirrorArtifactRef value, String kind, String field) {
        Objects.requireNonNull(value, field);
        if (!kind.equals(value.kind())) {
            throw new IllegalArgumentException(field + " must reference " + kind);
        }
        return value;
    }

    private static String version(String value) {
        String normalized = value == null || value.isBlank() ? SCHEMA_VERSION : value.trim();
        if (!SCHEMA_VERSION.equals(normalized)
                && !SCHEMA_VERSION_V1.equals(normalized)
                && !STATEFUL_SCHEMA_VERSION.equals(normalized)
                && !READ_WRITE_SCHEMA_VERSION.equals(normalized)) {
            throw new IllegalArgumentException("unsupported mirror run-evidence schemaVersion");
        }
        return normalized;
    }

    private static String fingerprint(String value, String field) {
        String normalized = required(value, field, 71);
        if (!FINGERPRINT.matcher(normalized).matches()) {
            throw new IllegalArgumentException(field + " must be a canonical SHA-256 value");
        }
        return normalized;
    }

    private static String normalizeGraphPath(String value) {
        String normalized = required(value, "graphPath", 2_048);
        if (!normalized.startsWith("/")) {
            throw new IllegalArgumentException("graphPath must start with /");
        }
        return normalized;
    }

    private static String required(String value, String field, int maximumLength) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank() || normalized.length() > maximumLength) {
            throw new IllegalArgumentException(field + " must be non-blank and bounded");
        }
        return normalized;
    }

    private static String bounded(String value, String field, int maximumLength) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() > maximumLength) {
            throw new IllegalArgumentException(field + " exceeds its length limit");
        }
        return normalized;
    }
}
