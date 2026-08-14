package com.leanowtech.bloge.gateway.businessmirror.compilation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.gateway.GatewayGraphContract;
import com.leanowtech.bloge.gateway.gateway.GatewayGraphContractCatalog;
import com.leanowtech.bloge.gateway.gateway.GatewayGraphContractTestSuite;
import com.leanowtech.bloge.gateway.gateway.GatewayGraphContractTestSuiteRepository;
import com.leanowtech.bloge.gateway.integration.mirror.BuiltInCapabilityClosureService;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityClosure;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityClosureIntegrity;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityContract;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityProjectionContext;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorArtifactRef;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Exact read adapter over the shipped Graph DSL, graph Contract, test-suite, operator and Resource
 * authorities.
 *
 * <p>The adapter does not duplicate topology. It delegates graph materialization to the existing
 * built-in capability-closure service and derives only content-addressed references. A fixed
 * projection generation keeps an unchanged built-in asset stable across Package compile times.</p>
 */
public final class BuiltInGraphAssetAuthority {
    public static final long PROJECTION_REVISION = 1;
    public static final String GRAPH_ID_PREFIX = "built-in:";
    public static final String CONTRACT_ID_SUFFIX = ":contract";
    private static final Instant PROJECTION_EPOCH = Instant.parse("2026-08-14T00:00:00Z");
    private static final int MAXIMUM_ARTIFACT_BYTES = 16 * 1024 * 1024;

    private final BuiltInCapabilityClosureService closures;
    private final GatewayGraphContractCatalog contracts;
    private final GatewayGraphContractTestSuiteRepository testSuites;
    private final ObjectMapper mapper;

    public BuiltInGraphAssetAuthority(
            BuiltInCapabilityClosureService closures,
            GatewayGraphContractCatalog contracts,
            GatewayGraphContractTestSuiteRepository testSuites,
            ObjectMapper mapper) {
        this.closures = Objects.requireNonNull(closures, "closures");
        this.contracts = Objects.requireNonNull(contracts, "contracts");
        this.testSuites = Objects.requireNonNull(testSuites, "testSuites");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /** Returns all shipped graph names in stable order. */
    public List<String> graphNames() {
        return contracts.all().stream().map(GatewayGraphContract::graphName).sorted().toList();
    }

    /** Resolves one built-in graph and all immutable migration evidence under an exact scope. */
    public Snapshot resolve(CapabilitySnapshot.Scope scope, String graphName) {
        Objects.requireNonNull(scope, "scope");
        GatewayGraphContract contract = contracts.require(graphName);
        CapabilityClosure closure = closures.project(graphName, projectionContext(scope));
        CapabilityClosureIntegrity.verify(mapper, closure);
        CapabilitySnapshot root = closure.snapshots().stream()
                .filter(value -> closure.rootRef().equals(CapabilityClosureIntegrity.reference(value)))
                .findFirst().orElseThrow(() -> new IllegalStateException(
                        "built-in capability closure has no exact root"));
        if (root.source().sourceKind() != CapabilitySnapshot.SourceKind.GRAPH
                || !graphId(graphName).equals(root.source().sourceRef())) {
            throw new IllegalStateException("built-in graph projection source identity drifted");
        }

        MirrorArtifactRef graphRef = new MirrorArtifactRef(
                "GRAPH_DRAFT", root.source().sourceRef(), PROJECTION_REVISION,
                root.source().sourceFingerprint());
        MirrorArtifactRef contractRef = new MirrorArtifactRef(
                "CONTRACT", contractId(graphName), PROJECTION_REVISION,
                fingerprint(contract));
        List<MirrorArtifactRef> suiteRefs = testSuites.all().stream()
                .filter(value -> graphName.equals(value.request().graphName()))
                .sorted(Comparator.comparing(GatewayGraphContractTestSuite::suiteId))
                .map(value -> new MirrorArtifactRef(
                        "TEST_SUITE", value.suiteId(), PROJECTION_REVISION, fingerprint(value)))
                .toList();
        MirrorArtifactRef closureRef = new MirrorArtifactRef(
                "CAPABILITY_CLOSURE", closure.rootRef().id(), closure.rootRef().revision(),
                closure.fingerprint());
        return new Snapshot(scope, graphName, graphRef, contractRef, closure.rootRef(),
                closureRef, suiteRefs);
    }

    /** Converts a built-in graph name to the exact source id used by capability projection. */
    public static String graphId(String graphName) {
        return GRAPH_ID_PREFIX + required(graphName, "graphName");
    }

    /** Converts a built-in graph name to its formal graph-Contract identity. */
    public static String contractId(String graphName) {
        return graphId(graphName) + CONTRACT_ID_SUFFIX;
    }

    /** Parses a built-in GraphDraft source id, or returns an empty value for a foreign id. */
    public static String graphNameFromGraphId(String value) {
        String normalized = value == null ? "" : value.trim();
        return normalized.startsWith(GRAPH_ID_PREFIX)
                ? normalized.substring(GRAPH_ID_PREFIX.length()) : "";
    }

    /** Parses a built-in Contract id, or returns an empty value for a foreign id. */
    public static String graphNameFromContractId(String value) {
        String normalized = value == null ? "" : value.trim();
        if (!normalized.startsWith(GRAPH_ID_PREFIX)
                || !normalized.endsWith(CONTRACT_ID_SUFFIX)) {
            return "";
        }
        return normalized.substring(
                GRAPH_ID_PREFIX.length(), normalized.length() - CONTRACT_ID_SUFFIX.length());
    }

    private CapabilityProjectionContext projectionContext(CapabilitySnapshot.Scope scope) {
        List<String> regions = scope.region().isBlank() ? List.of() : List.of(scope.region());
        return new CapabilityProjectionContext(
                PROJECTION_REVISION, scope.tenantId(), scope.organizationId(), scope.projectId(),
                scope.environmentId(), scope.region(), "BUSINESS_MIRROR_LEGACY_PROJECTION",
                CapabilitySnapshot.Ownership.unassigned(), CapabilitySnapshot.Lifecycle.DRAFT,
                CapabilityContract.DataClassification.RESTRICTED, regions, false,
                "", null, null, PROJECTION_EPOCH);
    }

    private String fingerprint(Object value) {
        return VisualBundleFingerprint.fromCanonicalValue(
                mapper, value, MAXIMUM_ARTIFACT_BYTES);
    }

    private static String required(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }

    /** Payload-free exact refs resolved from the existing built-in authorities. */
    public record Snapshot(
            CapabilitySnapshot.Scope scope,
            String graphName,
            MirrorArtifactRef graphRef,
            MirrorArtifactRef contractRef,
            MirrorArtifactRef rootCapabilityRef,
            MirrorArtifactRef capabilityClosureRef,
            List<MirrorArtifactRef> testSuiteRefs
    ) {
        public Snapshot {
            scope = Objects.requireNonNull(scope, "scope");
            graphName = required(graphName, "graphName");
            graphRef = exactKind(graphRef, "GRAPH_DRAFT", "graphRef");
            contractRef = exactKind(contractRef, "CONTRACT", "contractRef");
            rootCapabilityRef = exactKind(rootCapabilityRef, "CAPABILITY", "rootCapabilityRef");
            capabilityClosureRef = exactKind(
                    capabilityClosureRef, "CAPABILITY_CLOSURE", "capabilityClosureRef");
            testSuiteRefs = testSuiteRefs == null ? List.of() : testSuiteRefs.stream()
                    .map(value -> exactKind(value, "TEST_SUITE", "testSuiteRefs"))
                    .sorted(Comparator.comparing(MirrorArtifactRef::id))
                    .toList();
        }

        private static MirrorArtifactRef exactKind(
                MirrorArtifactRef value, String kind, String field) {
            if (value == null || !kind.equals(value.kind())) {
                throw new IllegalArgumentException(field + " must reference " + kind);
            }
            return value;
        }
    }
}
