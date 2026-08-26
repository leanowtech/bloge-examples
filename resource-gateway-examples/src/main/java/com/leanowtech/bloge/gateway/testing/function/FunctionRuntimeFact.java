package com.leanowtech.bloge.gateway.testing.function;

import com.leanowtech.bloge.core.spi.ExpressionFunction;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Server-owned immutable runtime fact captured from a BLOGE function registration. */
public record FunctionRuntimeFact(
        String registryName,
        String runtimeName,
        boolean pure,
        List<String> requiredExecutionServices,
        String runtimeFingerprint
) {

    public FunctionRuntimeFact {
        registryName = FunctionValueSupport.text(registryName, true,
                FunctionControlException.Code.RUNTIME_INVALID);
        runtimeName = FunctionValueSupport.text(runtimeName, true,
                FunctionControlException.Code.RUNTIME_INVALID);
        requiredExecutionServices = requiredExecutionServices == null
                ? List.of()
                : requiredExecutionServices.stream().map(service -> FunctionValueSupport.text(service, true,
                        FunctionControlException.Code.RUNTIME_INVALID)).distinct().sorted().toList();
        if (runtimeFingerprint == null || runtimeFingerprint.isBlank()) {
            runtimeFingerprint = FunctionValueSupport.fingerprint(Map.of(
                    "registryName", registryName,
                    "runtimeName", runtimeName,
                    "pure", pure,
                    "requiredExecutionServices", requiredExecutionServices));
        } else {
            String supplied = runtimeFingerprint.trim();
            String computed = FunctionValueSupport.fingerprint(Map.of(
                    "registryName", registryName,
                    "runtimeName", runtimeName,
                    "pure", pure,
                    "requiredExecutionServices", requiredExecutionServices));
            if (!supplied.equals(computed)) {
                throw new FunctionControlException(FunctionControlException.Code.RUNTIME_INVALID);
            }
            runtimeFingerprint = supplied;
        }
    }

    public static FunctionRuntimeFact from(String registryName, ExpressionFunction function) {
        if (function == null) {
            throw new FunctionControlException(FunctionControlException.Code.RUNTIME_INVALID);
        }
        try {
            return new FunctionRuntimeFact(registryName, function.name(), function.isPure(),
                    function.requiredExecutionServices() == null ? List.of()
                            : function.requiredExecutionServices().stream().map(Enum::name).toList(), "");
        } catch (RuntimeException failure) {
            throw new FunctionControlException(FunctionControlException.Code.RUNTIME_INVALID, failure);
        }
    }

    public Map<String, Object> fingerprintMaterial() {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("registryName", registryName);
        material.put("runtimeName", runtimeName);
        material.put("pure", pure);
        material.put("requiredExecutionServices", requiredExecutionServices);
        material.put("runtimeFingerprint", runtimeFingerprint);
        return material;
    }

    @Override
    public String toString() {
        return "FunctionRuntimeFact[name=" + registryName + ", pure=" + pure + "]";
    }
}
