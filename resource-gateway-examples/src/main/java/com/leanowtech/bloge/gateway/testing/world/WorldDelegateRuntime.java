package com.leanowtech.bloge.gateway.testing.world;

import com.leanowtech.bloge.core.model.NodeSpec;
import com.leanowtech.bloge.core.schema.OpaqueSchema;
import com.leanowtech.bloge.core.schema.SchemaValidator;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Run-scoped, pure fragment runtime for one verified world compilation. */
public final class WorldDelegateRuntime {
    public static final String RUNTIME_UNAVAILABLE = "WORLD_DELEGATE_RUNTIME_UNAVAILABLE";
    public static final String BINDING_UNAVAILABLE = "WORLD_DELEGATE_BINDING_UNAVAILABLE";
    public static final String EXECUTION_FAILED = "WORLD_DELEGATE_EXECUTION_FAILED";
    public static final String SCHEMA_MISMATCH = "WORLD_DELEGATE_SCHEMA_MISMATCH";

    private final WorldFragmentTestKit fragmentTestKit;
    private final Map<String, BlogeFragmentRef> fragmentsByRuleId;

    public WorldDelegateRuntime(WorldScenarioCompilation compilation,
                                WorldFragmentTestKit fragmentTestKit) {
        if (compilation == null || fragmentTestKit == null) {
            throw new WorldScenarioCompilationException(
                    WorldScenarioCompilationException.Code.INVALID_INPUT);
        }
        compilation.verifyFingerprint();
        this.fragmentTestKit = fragmentTestKit;
        this.fragmentsByRuleId = freezeBindings(compilation);
    }

    /** Invokes the exact frozen fragment and validates its result against the graph node shape. */
    public Object invoke(String ruleId, NodeSpec node, Object input) {
        String requiredRuleId = required(ruleId);
        NodeSpec requiredNode = Objects.requireNonNull(node, "node");
        BlogeFragmentRef fragment = fragmentsByRuleId.get(requiredRuleId);
        if (fragment == null) {
            throw failure(BINDING_UNAVAILABLE);
        }
        final Object output;
        try {
            output = fragmentTestKit.execute(fragment, input);
        } catch (RuntimeException failure) {
            throw failure(EXECUTION_FAILED);
        }
        if (!(requiredNode.outputSchema() instanceof OpaqueSchema)) {
            List<?> violations = SchemaValidator.validateInstance(
                    requiredNode.id(), output, requiredNode.outputSchema());
            if (!violations.isEmpty()) {
                throw failure(SCHEMA_MISMATCH);
            }
        }
        return output;
    }

    public BlogeFragmentRef fragmentFor(String ruleId) {
        BlogeFragmentRef fragment = fragmentsByRuleId.get(required(ruleId));
        if (fragment == null) {
            throw failure(BINDING_UNAVAILABLE);
        }
        return fragment;
    }

    private static Map<String, BlogeFragmentRef> freezeBindings(
            WorldScenarioCompilation compilation) {
        Map<String, BlogeFragmentRef> result = new LinkedHashMap<>();
        compilation.bundle().rules().forEach(rule -> {
            if (result.containsKey(rule.ruleId())) {
                throw failure(WorldScenarioCompilationException.Code.INVALID_BINDING);
            }
            result.put(rule.ruleId(), null);
        });
        compilation.bindings().forEach(binding -> {
            if (!result.containsKey(binding.ruleId())
                    || result.get(binding.ruleId()) != null) {
                throw failure(WorldScenarioCompilationException.Code.INVALID_BINDING);
            }
            String fragmentSource = WorldScenarioSourceMap.coordinate("fragment",
                    binding.fragment().artifactId() + "@" + binding.fragment().revision()
                            + "@" + binding.fragment().fingerprint());
            String ruleOutput = WorldScenarioSourceMap.coordinate("fixture-rule", binding.ruleId());
            if (!compilation.sourceMap().sourceToOutputs(fragmentSource).contains(ruleOutput)) {
                throw failure(WorldScenarioCompilationException.Code.INVALID_BINDING);
            }
            result.put(binding.ruleId(), binding.fragment());
        });
        if (result.values().stream().anyMatch(Objects::isNull)) {
            throw failure(WorldScenarioCompilationException.Code.INVALID_BINDING);
        }
        return Map.copyOf(result);
    }

    private static String required(String value) {
        if (value == null || value.isBlank()) {
            throw failure(BINDING_UNAVAILABLE);
        }
        return value.trim();
    }

    private static WorldDelegateRuntimeException failure(String code) {
        return new WorldDelegateRuntimeException(code);
    }

    private static WorldScenarioCompilationException failure(
            WorldScenarioCompilationException.Code code) {
        return new WorldScenarioCompilationException(code);
    }

    /** Sanitized runtime failure; no fragment source, context, or business payload is retained. */
    public static final class WorldDelegateRuntimeException extends IllegalStateException {
        private final String code;

        public WorldDelegateRuntimeException(String code) {
            super(code == null || code.isBlank() ? EXECUTION_FAILED : code.trim());
            this.code = getMessage();
        }

        public String code() {
            return code;
        }
    }
}
