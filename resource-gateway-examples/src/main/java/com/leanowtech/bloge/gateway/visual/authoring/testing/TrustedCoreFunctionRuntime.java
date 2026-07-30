package com.leanowtech.bloge.gateway.visual.authoring.testing;

import com.leanowtech.bloge.core.spi.BuiltInFunctions;
import com.leanowtech.bloge.core.spi.ExpressionFunction;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Exact trusted function inventory shared by the gateway and the isolated worker.
 */
final class TrustedCoreFunctionRuntime {

    private static final Map<String, ExpressionFunction> FUNCTIONS = functions();

    private TrustedCoreFunctionRuntime() {
    }

    static Resolution resolve(String functionName) {
        String normalizedName = functionName == null ? "" : functionName.trim();
        ExpressionFunction function = FUNCTIONS.get(normalizedName);
        if (function == null) {
            return new Resolution(State.UNBOUND, null, "");
        }
        String fingerprint = VisualBundleFingerprint.fromMaterial(Map.of(
                "workerProtocol",
                AuthoringFunctionWorkerProtocol.InvocationRequest.SCHEMA_VERSION,
                "executionProfile",
                AuthoringFunctionWorkerProtocol.EXECUTION_PROFILE,
                "lookupName",
                normalizedName,
                "runtimeName",
                function.name(),
                "implementation", function.getClass().getName(),
                "pure", function.isPure(),
                "requiredExecutionServices", function.requiredExecutionServices().stream()
                        .map(Enum::name)
                        .sorted()
                        .toList()
        ));
        if (!function.isPure() || !function.requiredExecutionServices().isEmpty()) {
            return new Resolution(State.BLOCKED_BY_POLICY, null, fingerprint);
        }
        return new Resolution(State.BOUND, function, fingerprint);
    }

    private static Map<String, ExpressionFunction> functions() {
        Map<String, ExpressionFunction> functions = new LinkedHashMap<>();
        BuiltInFunctions.registerAll(functions);
        return Map.copyOf(functions);
    }

    enum State {
        BOUND,
        UNBOUND,
        BLOCKED_BY_POLICY
    }

    record Resolution(State state, ExpressionFunction function, String runtimeFingerprint) {
        Resolution {
            state = state == null ? State.UNBOUND : state;
            runtimeFingerprint = runtimeFingerprint == null ? "" : runtimeFingerprint;
        }
    }
}
