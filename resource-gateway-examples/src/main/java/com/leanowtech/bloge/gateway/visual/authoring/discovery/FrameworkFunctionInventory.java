package com.leanowtech.bloge.gateway.visual.authoring.discovery;

import com.leanowtech.bloge.core.spi.ExpressionFunction;
import com.leanowtech.bloge.gateway.visual.catalog.BuiltInFunctionContract;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibrary;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic snapshot of framework expression functions visible to this gateway process.
 */
@Service
public final class FrameworkFunctionInventory {

    private final List<FrameworkFunctionInventoryProvider> providers;

    @Autowired
    public FrameworkFunctionInventory(
            ObjectProvider<FrameworkFunctionInventoryProvider> providers) {
        this(providers == null ? List.of() : providers.orderedStream().toList());
    }

    public FrameworkFunctionInventory(
            Collection<FrameworkFunctionInventoryProvider> providers) {
        this.providers = providers == null
                ? List.of()
                : providers.stream()
                        .filter(java.util.Objects::nonNull)
                        .sorted(Comparator.comparing(FrameworkFunctionInventoryProvider::providerId)
                                .thenComparing(FrameworkFunctionInventoryProvider::runtimeProfile))
                        .toList();
    }

    /** Returns a bounded, stable runtime snapshot. */
    public Snapshot snapshot() {
        List<FunctionRuntime> functions = new ArrayList<>();
        for (FrameworkFunctionInventoryProvider provider : providers) {
            Collection<FrameworkFunctionInventoryProvider.FunctionBinding> bindings;
            try {
                bindings = provider.functions();
            } catch (RuntimeException failure) {
                continue;
            }
            if (bindings == null) {
                continue;
            }
            for (FrameworkFunctionInventoryProvider.FunctionBinding binding : bindings) {
                FunctionRuntime runtime = runtime(provider, binding);
                if (runtime != null) {
                    functions.add(runtime);
                }
            }
        }
        functions.sort(Comparator.comparing(FunctionRuntime::callableName)
                .thenComparing(FunctionRuntime::runtimeProfile)
                .thenComparing(FunctionRuntime::providerId)
                .thenComparing(FunctionRuntime::runtimeFingerprint));
        String fingerprint = VisualBundleFingerprint.fromMaterial(Map.of(
                "functions", functions.stream().map(FunctionRuntime::fingerprintMaterial).toList()
        ));
        return new Snapshot(fingerprint, functions);
    }

    private static FunctionRuntime runtime(
            FrameworkFunctionInventoryProvider provider,
            FrameworkFunctionInventoryProvider.FunctionBinding binding) {
        if (binding == null || binding.function() == null || binding.callableName().isBlank()) {
            return null;
        }
        ExpressionFunction function = binding.function();
        List<String> requiredServices;
        try {
            requiredServices = function.requiredExecutionServices().stream()
                    .map(Enum::name)
                    .sorted()
                    .toList();
        } catch (RuntimeException failure) {
            requiredServices = List.of("INVENTORY_UNAVAILABLE");
        }
        boolean pure;
        try {
            pure = function.isPure();
        } catch (RuntimeException failure) {
            pure = false;
        }
        String returnType;
        try {
            returnType = normalized(function.returnType(), "Unknown");
        } catch (RuntimeException failure) {
            returnType = "Unknown";
        }
        OperatorLibrary.BuiltInFunction declared = binding.declaredContract();
        String declaredFingerprint = declared == null
                ? ""
                : BuiltInFunctionContract.callableFingerprint(declared);
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("callableName", binding.callableName());
        material.put("runtimeName", normalized(function.name(), binding.callableName()));
        material.put("providerId", normalized(provider.providerId(), "unknown-provider"));
        material.put("runtimeProfile", normalized(provider.runtimeProfile(), "default"));
        material.put("implementationClass", function.getClass().getName());
        material.put("pure", pure);
        material.put("requiredExecutionServices", requiredServices);
        material.put("returnTypeHint", returnType);
        material.put("declaredContractFingerprint", declaredFingerprint);
        return new FunctionRuntime(
                binding.callableName(),
                normalized(function.name(), binding.callableName()),
                normalized(provider.providerId(), "unknown-provider"),
                normalized(provider.runtimeProfile(), "default"),
                function.getClass().getName(),
                pure,
                requiredServices,
                returnType,
                VisualBundleFingerprint.fromMaterial(material),
                declared
        );
    }

    public record Snapshot(
            String inventoryFingerprint,
            List<FunctionRuntime> functions
    ) {
        public Snapshot {
            inventoryFingerprint = normalized(inventoryFingerprint, "");
            functions = functions == null ? List.of() : List.copyOf(functions);
        }

        public List<FunctionRuntime> resolve(String callableName) {
            String name = normalized(callableName, "");
            return functions.stream()
                    .filter(runtime -> runtime.callableName().equals(name))
                    .toList();
        }
    }

    public record FunctionRuntime(
            String callableName,
            String runtimeName,
            String providerId,
            String runtimeProfile,
            String implementationClass,
            boolean pure,
            List<String> requiredExecutionServices,
            String returnTypeHint,
            String runtimeFingerprint,
            OperatorLibrary.BuiltInFunction declaredContract
    ) {
        public FunctionRuntime {
            callableName = normalized(callableName, "");
            runtimeName = normalized(runtimeName, callableName);
            providerId = normalized(providerId, "unknown-provider");
            runtimeProfile = normalized(runtimeProfile, "default");
            implementationClass = normalized(implementationClass, "");
            requiredExecutionServices = requiredExecutionServices == null
                    ? List.of()
                    : List.copyOf(requiredExecutionServices);
            returnTypeHint = normalized(returnTypeHint, "Unknown");
            runtimeFingerprint = normalized(runtimeFingerprint, "");
        }

        Map<String, Object> fingerprintMaterial() {
            Map<String, Object> material = new LinkedHashMap<>();
            material.put("callableName", callableName);
            material.put("runtimeName", runtimeName);
            material.put("providerId", providerId);
            material.put("runtimeProfile", runtimeProfile);
            material.put("implementationClass", implementationClass);
            material.put("pure", pure);
            material.put("requiredExecutionServices", requiredExecutionServices);
            material.put("returnTypeHint", returnTypeHint);
            material.put("runtimeFingerprint", runtimeFingerprint);
            material.put("declaredContractFingerprint", declaredContract == null
                    ? ""
                    : BuiltInFunctionContract.callableFingerprint(declaredContract));
            return material;
        }
    }

    private static String normalized(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
