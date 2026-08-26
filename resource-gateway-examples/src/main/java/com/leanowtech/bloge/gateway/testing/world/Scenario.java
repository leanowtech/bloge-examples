package com.leanowtech.bloge.gateway.testing.world;

import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;
import com.leanowtech.bloge.gateway.testing.domain.ProtocolJsonValue;
import com.leanowtech.bloge.gateway.testing.domain.TestSuite;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Immutable Stage 1 business scenario bound to an exact target and stateless world revision. */
public final class Scenario {
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Set<String> TARGET_KINDS = Set.of("GRAPH", "OPERATOR");
    private static final Set<String> SCOPES = Set.of(
            "GRAPH_SUCCESS", "NODE_STATUS", "FIXTURE_USES", "NODE_OUTPUT", "OUTPUT_PATH");
    private static final Set<String> OPERATORS = Set.of(
            "EQUALS", "NOT_EQUALS", "EXISTS", "ABSENT", "MATCHES_SCHEMA",
            "GREATER_THAN", "GREATER_OR_EQUAL", "LESS_THAN", "LESS_OR_EQUAL", "CONTAINS");
    private static final Set<String> NUMERIC_OPERATORS = Set.of(
            "EQUALS", "GREATER_THAN", "GREATER_OR_EQUAL", "LESS_THAN", "LESS_OR_EQUAL");

    private final String scenarioId;
    private final String tenantId;
    private final long revision;
    private final TargetRef target;
    private final WorldModelRef world;
    private final Map<String, Object> context;
    private final WorldStateInit stateInit;
    private final List<Expectation> expect;
    private final List<ContractDependency> contractDependencies;
    private final String fingerprint;

    /**
     * Creates a scenario whose world reference is proven against the supplied model.
     * Expectation order is set-like and is therefore canonicalized; assertion evaluation order is
     * not part of the Scenario contract.
     */
    public Scenario(String scenarioId,
                    String tenantId,
                    long revision,
                    TargetRef target,
                    WorldModelRef world,
                    ResourceWorldModel worldModel,
                    Map<String, Object> context,
                    WorldStateInit stateInit,
                    List<Expectation> expect,
                    List<ContractDependency> contractDependencies) {
        this.scenarioId = required(scenarioId, ScenarioException.Code.INVALID_SCENARIO);
        this.tenantId = required(tenantId, ScenarioException.Code.INVALID_SCENARIO);
        if (revision <= 0) {
            throw new ScenarioException(ScenarioException.Code.INVALID_SCENARIO);
        }
        this.revision = revision;
        this.target = requireTarget(target);
        this.world = requireWorld(world, worldModel, this.tenantId);
        this.context = freezeMap(context);
        if (stateInit == null) {
            throw new ScenarioException(ScenarioException.Code.STATE_NOT_SUPPORTED);
        }
        try {
            worldModel.stateSpec().validateOverrides(stateInit.overrides());
        } catch (WorldModelException invalidState) {
            throw new ScenarioException(ScenarioException.Code.STATE_NOT_SUPPORTED);
        }
        this.stateInit = stateInit;
        this.expect = canonicalExpectations(expect);
        this.contractDependencies = canonicalDependencies(contractDependencies);
        this.fingerprint = VisualBundleFingerprint.fromMaterial(fingerprintMaterial());
    }

    /** Convenience constructor that derives the exact world reference from the model. */
    public Scenario(String scenarioId,
                    String tenantId,
                    long revision,
                    TargetRef target,
                    ResourceWorldModel worldModel,
                    Map<String, Object> context,
                    WorldStateInit stateInit,
                    List<Expectation> expect,
                    List<ContractDependency> contractDependencies) {
        this(scenarioId, tenantId, revision, target, WorldModelRef.from(worldModel), worldModel,
                context, stateInit, expect, contractDependencies);
    }

    public Scenario(String scenarioId,
                    String tenantId,
                    long revision,
                    TargetRef target,
                    WorldModelRef world,
                    ResourceWorldModel worldModel,
                    Map<String, Object> context,
                    WorldStateInit stateInit,
                    List<Expectation> expect) {
        this(scenarioId, tenantId, revision, target, world, worldModel, context, stateInit, expect,
                List.of());
    }

    public Scenario(String scenarioId,
                    String tenantId,
                    long revision,
                    TargetRef target,
                    ResourceWorldModel worldModel,
                    Map<String, Object> context,
                    WorldStateInit stateInit,
                    List<Expectation> expect) {
        this(scenarioId, tenantId, revision, target, WorldModelRef.from(worldModel), worldModel,
                context, stateInit, expect, List.of());
    }

    public String scenarioId() { return scenarioId; }
    public String tenantId() { return tenantId; }
    public long revision() { return revision; }
    public TargetRef target() { return target; }
    public WorldModelRef world() { return world; }
    public Map<String, Object> context() { return context; }
    public WorldStateInit stateInit() { return stateInit; }
    public List<Expectation> expect() { return expect; }
    public List<ContractDependency> contractDependencies() { return contractDependencies; }
    public List<ContractDependency> dependencies() { return contractDependencies; }
    public String fingerprint() { return fingerprint; }

    /**
     * Validates an explicit baseline/candidate pair. The baseline must first match the immutable
     * dependency address; only then is the existing structural compatibility analyzer consulted.
     */
    public CompatibilityValidation validateCompatibility(LogicalResourceContract baseline,
                                                         LogicalResourceContract candidate) {
        if (baseline == null || candidate == null || !baseline.contractId().equals(candidate.contractId())) {
            throw new ScenarioException(ScenarioException.Code.CONTRACT_INCOMPATIBLE);
        }
        ContractDependency dependency = contractDependencies.stream()
                .filter(value -> value.contractId().equals(baseline.contractId()))
                .findFirst()
                .orElseThrow(() -> new ScenarioException(ScenarioException.Code.CONTRACT_NOT_DECLARED));
        if (!dependency.baselineFingerprint().equals(baseline.contractFingerprint())) {
            throw new ScenarioException(ScenarioException.Code.CONTRACT_INCOMPATIBLE);
        }
        LogicalResourceContractCompatibility.Report report =
                LogicalResourceContractCompatibility.analyze(baseline, candidate);
        return new CompatibilityValidation(report.status(), report.automaticUseAllowed(), report);
    }

    /**
     * Validates only what a serialized dependency can prove. A matching fingerprint is compatible;
     * every different candidate is unresolved and therefore rejected closed for automatic use.
     */
    public CompatibilityValidation validateCompatibility(LogicalResourceContract candidate) {
        if (candidate == null) {
            throw new ScenarioException(ScenarioException.Code.CONTRACT_INCOMPATIBLE);
        }
        ContractDependency dependency = contractDependencies.stream()
                .filter(value -> value.contractId().equals(candidate.contractId()))
                .findFirst()
                .orElseThrow(() -> new ScenarioException(ScenarioException.Code.CONTRACT_NOT_DECLARED));
        boolean sameFingerprint = dependency.baselineFingerprint().equals(candidate.contractFingerprint());
        return new CompatibilityValidation(
                sameFingerprint ? LogicalResourceContractCompatibility.Status.COMPATIBLE
                        : LogicalResourceContractCompatibility.Status.REVIEW_REQUIRED,
                sameFingerprint, null);
    }

    public CompatibilityValidation validateCompatibility(String contractId,
                                                         LogicalResourceContract candidate) {
        String id = normalized(contractId);
        if (id.isEmpty() || candidate == null || !id.equals(candidate.contractId())) {
            throw new ScenarioException(ScenarioException.Code.CONTRACT_INCOMPATIBLE);
        }
        return validateCompatibility(candidate);
    }

    public boolean isCompatibleWith(LogicalResourceContract candidate) {
        return validateCompatibility(candidate).valid();
    }

    /** Exact graph/operator target identity; a provider binding is deliberately absent. */
    public record TargetRef(String kind, String id, String fingerprint) {
        public TargetRef {
            kind = normalized(kind).toUpperCase(Locale.ROOT);
            id = normalized(id);
            fingerprint = normalized(fingerprint);
            if (!TARGET_KINDS.contains(kind)) {
                throw new ScenarioException(ScenarioException.Code.TARGET_KIND_UNSUPPORTED);
            }
            if (id.isEmpty() || isUnknownOrLatest(id)) {
                throw new ScenarioException(ScenarioException.Code.TARGET_ID_INVALID);
            }
            if (!validFingerprint(fingerprint) || isUnknownOrLatest(fingerprint)) {
                throw new ScenarioException(ScenarioException.Code.TARGET_FINGERPRINT_INVALID);
            }
        }

        public TargetRef(TestSuite.Target target) {
            this(target == null ? null : target.kind(), target == null ? null : target.id(),
                    target == null ? null : target.fingerprint());
        }

        public TestSuite.Target toTestSuiteTarget() {
            return new TestSuite.Target(kind, id, fingerprint);
        }
    }

    /** Exact content-addressed reference to one ResourceWorldModel revision. */
    public static final class WorldModelRef {
        private final String worldModelId;
        private final long revision;
        private final String fingerprint;

        public WorldModelRef(String worldModelId, long revision, String fingerprint) {
            this.worldModelId = normalized(worldModelId);
            this.revision = revision;
            this.fingerprint = normalized(fingerprint);
            if (this.worldModelId.isEmpty() || isUnknownOrLatest(this.worldModelId)
                    || revision <= 0 || !validFingerprint(this.fingerprint)) {
                throw new ScenarioException(ScenarioException.Code.INVALID_WORLD_REF);
            }
        }

        public static WorldModelRef from(ResourceWorldModel model) {
            if (model == null) {
                throw new ScenarioException(ScenarioException.Code.WORLD_MODEL_REQUIRED);
            }
            return new WorldModelRef(model.worldModelId(), model.revision(), model.fingerprint());
        }

        public String worldModelId() { return worldModelId; }
        public long revision() { return revision; }
        public String fingerprint() { return fingerprint; }

        private boolean matches(ResourceWorldModel model) {
            return model != null && worldModelId.equals(model.worldModelId()) && revision == model.revision()
                    && fingerprint.equals(model.fingerprint());
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof WorldModelRef ref && worldModelId.equals(ref.worldModelId)
                    && revision == ref.revision && fingerprint.equals(ref.fingerprint);
        }

        @Override
        public int hashCode() {
            return Objects.hash(worldModelId, revision, fingerprint);
        }
    }

    /** Compatible initial-state value that can evolve without breaking the Stage 1 EMPTY token. */
    public static final class WorldStateInit {
        public static final String SCHEMA_VERSION = "bloge.worldStateInit.v1";
        public static final WorldStateInit EMPTY = new WorldStateInit(Map.of());
        private final Map<String, Object> overrides;

        private WorldStateInit(Map<String, ?> overrides) {
            try {
                this.overrides = com.leanowtech.bloge.gateway.testing.domain.ProtocolJsonValue.freezeMap(overrides);
            } catch (RuntimeException invalid) {
                throw new ScenarioException(ScenarioException.Code.STATE_NOT_SUPPORTED);
            }
        }

        public static WorldStateInit of(Map<String, ?> overrides) {
            if (overrides == null) {
                throw new ScenarioException(ScenarioException.Code.STATE_NOT_SUPPORTED);
            }
            if (overrides.isEmpty()) return EMPTY;
            return new WorldStateInit(overrides);
        }

        public String schemaVersion() { return SCHEMA_VERSION; }
        public Map<String, Object> overrides() { return overrides; }
        public boolean isEmpty() { return overrides.isEmpty(); }
        public String name() { return isEmpty() ? "EMPTY" : "V1"; }

        @Override
        public boolean equals(Object other) {
            return other instanceof WorldStateInit init && overrides.equals(init.overrides);
        }

        @Override
        public int hashCode() { return overrides.hashCode(); }
    }

    /** Lossless mapping of the existing FixtureBundle assertion contract. */
    public record Expectation(
            String scope,
            String nodeId,
            String path,
            String operator,
            Object expected,
            Double numericTolerance
    ) {
        public Expectation {
            scope = normalized(scope).isEmpty() ? "OUTPUT_PATH" : normalized(scope).toUpperCase(Locale.ROOT);
            nodeId = normalized(nodeId);
            path = normalized(path);
            operator = normalized(operator).isEmpty() ? "EQUALS" : normalized(operator).toUpperCase(Locale.ROOT);
            expected = freeze(expected);
            if (!SCOPES.contains(scope)) {
                throw new ScenarioException(ScenarioException.Code.EXPECTATION_SCOPE_UNSUPPORTED);
            }
            if (!OPERATORS.contains(operator)) {
                throw new ScenarioException(ScenarioException.Code.EXPECTATION_OPERATOR_UNSUPPORTED);
            }
            if (!validJsonPointer(path)) {
                throw new ScenarioException(ScenarioException.Code.EXPECTATION_PATH_INVALID);
            }
            if ((scope.equals("NODE_STATUS") || scope.equals("NODE_OUTPUT") || scope.equals("FIXTURE_USES"))
                    && nodeId.isEmpty()) {
                throw new ScenarioException(ScenarioException.Code.EXPECTATION_NODE_REQUIRED);
            }
            if (NUMERIC_OPERATORS.contains(operator) && !operator.equals("EQUALS")
                    && !(expected instanceof Number)) {
                throw new ScenarioException(ScenarioException.Code.EXPECTATION_NUMERIC_VALUE_REQUIRED);
            }
            if (operator.equals("MATCHES_SCHEMA") && !(expected instanceof Map<?, ?>)) {
                throw new ScenarioException(ScenarioException.Code.EXPECTATION_SCHEMA_REQUIRED);
            }
            if (numericTolerance != null && (!Double.isFinite(numericTolerance) || numericTolerance < 0
                    || !operator.equals("EQUALS") || !(expected instanceof Number))) {
                throw new ScenarioException(ScenarioException.Code.EXPECTATION_TOLERANCE_INVALID);
            }
        }

        public static Expectation from(FixtureBundle.Assertion assertion) {
            if (assertion == null) {
                throw new ScenarioException(ScenarioException.Code.EXPECTATION_INVALID);
            }
            try {
                return new Expectation(assertion.scope(), assertion.nodeId(), assertion.path(),
                        assertion.operator(), assertion.expected(), assertion.numericTolerance());
            } catch (ScenarioException invalid) {
                throw invalid;
            } catch (RuntimeException invalid) {
                throw new ScenarioException(ScenarioException.Code.EXPECTATION_INVALID);
            }
        }

        public FixtureBundle.Assertion toFixtureAssertion() {
            return new FixtureBundle.Assertion(scope, nodeId, path, operator, expected, numericTolerance);
        }
    }

    /** Explicit logical-contract dependency; provider/API binding is intentionally not stored. */
    public static final class ContractDependency {
        private final String contractId;
        private final String baselineFingerprint;

        public ContractDependency(String contractId, String baselineFingerprint) {
            this.contractId = normalized(contractId);
            this.baselineFingerprint = normalized(baselineFingerprint);
            if (this.contractId.isEmpty() || isUnknownOrLatest(this.contractId)
                    || !validFingerprint(this.baselineFingerprint)) {
                throw new ScenarioException(ScenarioException.Code.CONTRACT_DEPENDENCY_INVALID);
            }
        }

        public static ContractDependency of(LogicalResourceContract baseline) {
            if (baseline == null) {
                throw new ScenarioException(ScenarioException.Code.CONTRACT_DEPENDENCY_INVALID);
            }
            return new ContractDependency(baseline.contractId(), baseline.contractFingerprint());
        }

        public String contractId() { return contractId; }
        public String baselineFingerprint() { return baselineFingerprint; }

        @Override
        public boolean equals(Object other) {
            return other instanceof ContractDependency dependency
                    && contractId.equals(dependency.contractId)
                    && baselineFingerprint.equals(dependency.baselineFingerprint);
        }

        @Override
        public int hashCode() {
            return Objects.hash(contractId, baselineFingerprint);
        }
    }

    /** Compatibility result that treats unresolved review as invalid. */
    public record CompatibilityValidation(
            LogicalResourceContractCompatibility.Status status,
            boolean valid,
            LogicalResourceContractCompatibility.Report report
    ) {
        public CompatibilityValidation {
            if (status == null || valid != (status == LogicalResourceContractCompatibility.Status.COMPATIBLE)) {
                throw new ScenarioException(ScenarioException.Code.INVALID_SCENARIO);
            }
        }

        public boolean isValid() { return valid; }
        public boolean automaticUseAllowed() { return valid; }
    }

    private static TargetRef requireTarget(TargetRef target) {
        if (target == null) {
            throw new ScenarioException(ScenarioException.Code.INVALID_TARGET);
        }
        return target;
    }

    private static WorldModelRef requireWorld(WorldModelRef reference,
                                              ResourceWorldModel model,
                                              String tenantId) {
        if (reference == null) {
            throw new ScenarioException(ScenarioException.Code.INVALID_WORLD_REF);
        }
        if (model == null || !reference.matches(model)) {
            throw new ScenarioException(model == null ? ScenarioException.Code.WORLD_MODEL_REQUIRED
                    : ScenarioException.Code.WORLD_MODEL_MISMATCH);
        }
        if (!tenantId.equals(model.tenantId())) {
            throw new ScenarioException(ScenarioException.Code.TENANT_DRIFT);
        }
        return reference;
    }

    private static Map<String, Object> freezeMap(Map<String, Object> value) {
        if (value == null) {
            throw new ScenarioException(ScenarioException.Code.INVALID_SCENARIO);
        }
        try {
            return ProtocolJsonValue.freezeMap(value);
        } catch (RuntimeException invalid) {
            throw new ScenarioException(ScenarioException.Code.INVALID_SCENARIO);
        }
    }

    private static Object freeze(Object value) {
        try {
            return ProtocolJsonValue.freeze(value);
        } catch (RuntimeException invalid) {
            throw new ScenarioException(ScenarioException.Code.EXPECTATION_INVALID);
        }
    }

    private static List<Expectation> canonicalExpectations(List<Expectation> values) {
        if (values == null || values.stream().anyMatch(Objects::isNull)) {
            throw new ScenarioException(ScenarioException.Code.EXPECTATION_INVALID);
        }
        List<Expectation> copy = new ArrayList<>(values);
        copy.sort(Comparator.comparing(Expectation::scope)
                .thenComparing(Expectation::nodeId)
                .thenComparing(Expectation::path)
                .thenComparing(Expectation::operator)
                .thenComparing(Scenario::expectationFingerprint));
        return List.copyOf(copy);
    }

    private static List<ContractDependency> canonicalDependencies(List<ContractDependency> values) {
        if (values == null || values.stream().anyMatch(Objects::isNull)) {
            throw new ScenarioException(ScenarioException.Code.CONTRACT_DEPENDENCY_INVALID);
        }
        List<ContractDependency> copy = new ArrayList<>(values);
        copy.sort(Comparator.comparing(ContractDependency::contractId)
                .thenComparing(ContractDependency::baselineFingerprint));
        for (int index = 1; index < copy.size(); index++) {
            if (copy.get(index - 1).contractId().equals(copy.get(index).contractId())) {
                throw new ScenarioException(ScenarioException.Code.CONTRACT_DEPENDENCY_INVALID);
            }
        }
        return List.copyOf(copy);
    }

    private Map<String, Object> fingerprintMaterial() {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("scenarioId", scenarioId);
        material.put("tenantId", tenantId);
        material.put("revision", revision);
        material.put("target", Map.of("kind", target.kind(), "id", target.id(),
                "fingerprint", target.fingerprint()));
        material.put("world", Map.of("worldModelId", world.worldModelId(), "revision", world.revision(),
                "fingerprint", world.fingerprint()));
        material.put("context", context);
        material.put("stateInit", stateInit.name());
        if (!stateInit.isEmpty()) {
            material.put("stateInitSchemaVersion", stateInit.schemaVersion());
            material.put("stateInitOverrides", stateInit.overrides());
        }
        material.put("expect", expect.stream().map(Scenario::expectationMaterial).toList());
        material.put("contractDependencies", contractDependencies.stream()
                .map(dependency -> Map.of("contractId", dependency.contractId(),
                        "baselineFingerprint", dependency.baselineFingerprint())).toList());
        return material;
    }

    private static String expectationFingerprint(Expectation value) {
        return VisualBundleFingerprint.fromMaterial(expectationMaterial(value));
    }

    private static Map<String, Object> expectationMaterial(Expectation value) {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("scope", value.scope());
        material.put("nodeId", value.nodeId());
        material.put("path", value.path());
        material.put("operator", value.operator());
        material.put("expected", value.expected());
        material.put("numericTolerance", value.numericTolerance());
        return material;
    }

    private static String required(String value, ScenarioException.Code code) {
        String normalized = normalized(value);
        if (normalized.isEmpty() || isUnknownOrLatest(normalized)) {
            throw new ScenarioException(code);
        }
        return normalized;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean validFingerprint(String value) {
        return value != null && FINGERPRINT.matcher(value).matches();
    }

    private static boolean isUnknownOrLatest(String value) {
        return "unknown".equalsIgnoreCase(value) || "latest".equalsIgnoreCase(value);
    }

    private static boolean validJsonPointer(String path) {
        if (path == null || path.isEmpty() || "/".equals(path)) {
            return true;
        }
        if (!path.startsWith("/")) {
            return false;
        }
        for (int index = 1; index < path.length(); index++) {
            if (path.charAt(index) == '~') {
                if (index + 1 >= path.length()
                        || (path.charAt(index + 1) != '0' && path.charAt(index + 1) != '1')) {
                    return false;
                }
                index++;
            }
        }
        return true;
    }
}
