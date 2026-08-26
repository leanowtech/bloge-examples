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
    private final Map<String, WorldDelegateBinding> bindingsByRuleId;
    private final StateAccessPlan stateAccessPlan;
    private final WorldRunStateDescriptor runStateDescriptor;

    public WorldDelegateRuntime(WorldScenarioCompilation compilation,
                                WorldFragmentTestKit fragmentTestKit) {
        if (compilation == null || fragmentTestKit == null) {
            throw new WorldScenarioCompilationException(
                    WorldScenarioCompilationException.Code.INVALID_INPUT);
        }
        compilation.verifyFingerprint();
        this.fragmentTestKit = fragmentTestKit;
        this.fragmentsByRuleId = freezeBindings(compilation);
        this.bindingsByRuleId = freezeBindingDescriptors(compilation);
        this.stateAccessPlan = compilation.stateAccessPlan();
        this.runStateDescriptor = compilation.runStateDescriptor();
    }

    /** Invokes the exact frozen fragment and validates its result against the graph node shape. */
    public Object invoke(String ruleId, NodeSpec node, Object input) {
        String requiredRuleId = required(ruleId);
        NodeSpec requiredNode = Objects.requireNonNull(node, "node");
        WorldDelegateBinding binding = bindingsByRuleId.get(requiredRuleId);
        BlogeFragmentRef fragment = fragmentsByRuleId.get(requiredRuleId);
        if (fragment == null || binding == null) {
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

    /** Invokes one stateful world fragment through the run-scoped atomic session. */
    public Object invoke(String ruleId, NodeSpec node, Object input,
                         WorldInvocationCoordinate coordinate, WorldStateSession session) {
        String requiredRuleId = required(ruleId);
        WorldDelegateBinding binding = bindingsByRuleId.get(requiredRuleId);
        BlogeFragmentRef fragment = fragmentsByRuleId.get(requiredRuleId);
        if (fragment == null || binding == null) {
            throw failure(BINDING_UNAVAILABLE);
        }
        // A stateful aggregate may contain legacy/stateless slices. Those slices retain the
        // original execution path and must not be forced through the state session.
        if (!runStateDescriptor.stateful() || binding.stateSpec().isEmpty()) {
            return invoke(ruleId, node, input);
        }
        if (session == null || coordinate == null) {
            throw failure(RUNTIME_UNAVAILABLE);
        }
        WorldStateSession.Binding sessionBinding = session.binding();
        if (!runStateDescriptor.scenarioFingerprint().equals(sessionBinding.scenarioFingerprint())
                || !runStateDescriptor.worldFingerprint().equals(sessionBinding.worldFingerprint())
                || !runStateDescriptor.graphArtifactFingerprint()
                .equals(sessionBinding.graphArtifactFingerprint())) {
            throw failure(WorldModelException.Code.STATE_SNAPSHOT_WRONG_BINDING);
        }
        StateAccessPlan.Access access = stateAccessPlan.access(
                coordinate.structuralInvocationSiteId());
        if (access == null || !requiredRuleId.equals(access.ruleId())) {
            throw failure(BINDING_UNAVAILABLE);
        }
        try {
            return session.transition(coordinate, access, before -> {
                        WorldFragmentTestKit.StatefulReplayResult result =
                                fragmentTestKit.executeStateful(fragment,
                                        stateSpec(binding), input, before.values(), 1);
                        validateNodeOutput(node, result.response());
                        return new WorldStateSession.StateTransition<>(result.response(),
                                result.stateWrites());
                    });
        } catch (WorldModelException failure) {
            throw failure(failure.code().name());
        } catch (RuntimeException failure) {
            throw failure(EXECUTION_FAILED);
        }
    }

    private static void validateNodeOutput(NodeSpec node, Object output) {
        if (!(node.outputSchema() instanceof OpaqueSchema)
                && !SchemaValidator.validateInstance(node.id(), output, node.outputSchema()).isEmpty()) {
            throw new WorldModelException(WorldModelException.Code.STATE_SCHEMA_MISMATCH);
        }
    }

    private static StateSpecV2 stateSpec(WorldDelegateBinding binding) {
        if (binding.stateSpec() instanceof StateSpecV2 stateSpec) {
            return stateSpec;
        }
        throw new WorldModelException(WorldModelException.Code.STATE_NOT_SUPPORTED);
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

    private static Map<String, WorldDelegateBinding> freezeBindingDescriptors(
            WorldScenarioCompilation compilation) {
        Map<String, WorldDelegateBinding> result = new LinkedHashMap<>();
        for (WorldDelegateBinding binding : compilation.bindings()) {
            if (result.put(binding.ruleId(), binding) != null) {
                throw failure(WorldScenarioCompilationException.Code.INVALID_BINDING);
            }
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

    private static WorldDelegateRuntimeException failure(WorldModelException.Code code) {
        return new WorldDelegateRuntimeException(code == null ? EXECUTION_FAILED : code.name());
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
