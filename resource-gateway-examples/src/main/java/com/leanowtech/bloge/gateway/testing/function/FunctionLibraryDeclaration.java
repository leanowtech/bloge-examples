package com.leanowtech.bloge.gateway.testing.function;

import com.leanowtech.bloge.core.spi.ExecutionServiceKind;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Frozen governance declaration for one callable BLOGE function. */
public record FunctionLibraryDeclaration(
        String functionName,
        String runtimeName,
        boolean pure,
        Set<String> requiredExecutionServices,
        FunctionEffect effect,
        Map<String, Object> parameterSchema,
        Map<String, Object> returnSchema,
        FunctionDeclarationStatus status,
        String functionFingerprint
) {

    public FunctionLibraryDeclaration(
            String functionName,
            boolean pure,
            Set<String> requiredExecutionServices,
            FunctionEffect effect,
            Map<String, Object> parameterSchema,
            Map<String, Object> returnSchema
    ) {
        this(functionName, "", pure, requiredExecutionServices, effect, parameterSchema, returnSchema,
                FunctionDeclarationStatus.CERTAIN, "");
    }

    public FunctionLibraryDeclaration(
            String functionName,
            String runtimeName,
            boolean pure,
            Set<String> requiredExecutionServices,
            FunctionEffect effect,
            Map<String, Object> parameterSchema,
            Map<String, Object> returnSchema
    ) {
        this(functionName, runtimeName, pure, requiredExecutionServices, effect, parameterSchema,
                returnSchema, FunctionDeclarationStatus.CERTAIN, "");
    }

    public FunctionLibraryDeclaration(
            String functionName,
            boolean pure,
            Set<String> requiredExecutionServices,
            FunctionEffect effect,
            Map<String, Object> parameterSchema,
            Map<String, Object> returnSchema,
            FunctionDeclarationStatus status,
            String functionFingerprint
    ) {
        this(functionName, "", pure, requiredExecutionServices, effect, parameterSchema, returnSchema,
                status, functionFingerprint);
    }

    public FunctionLibraryDeclaration {
        functionName = FunctionValueSupport.text(functionName, true,
                FunctionControlException.Code.DECLARATION_INVALID);
        runtimeName = runtimeName == null || runtimeName.isBlank()
                ? functionName
                : FunctionValueSupport.text(runtimeName, true,
                FunctionControlException.Code.DECLARATION_INVALID);
        requiredExecutionServices = normalizeServices(requiredExecutionServices);
        effect = effect == null ? FunctionEffect.PURE_COMPUTATION : effect;
        status = status == null ? FunctionDeclarationStatus.CERTAIN : status;
        parameterSchema = FunctionValueSupport.schema(parameterSchema);
        returnSchema = FunctionValueSupport.schema(returnSchema);
        if (pure && (!requiredExecutionServices.isEmpty() || effect != FunctionEffect.PURE_COMPUTATION)
                || !pure && effect == FunctionEffect.PURE_COMPUTATION
                || effect == FunctionEffect.ENVIRONMENT_FACT
                && (pure || requiredExecutionServices.isEmpty())
                || effect == FunctionEffect.EXTERNAL_QUERY && pure) {
            throw new FunctionControlException(FunctionControlException.Code.DECLARATION_INVALID);
        }
        String computed = FunctionValueSupport.fingerprint(fingerprintMaterial(
                functionName, runtimeName, pure, requiredExecutionServices, effect, parameterSchema,
                returnSchema, status));
        if (functionFingerprint == null || functionFingerprint.isBlank()) {
            functionFingerprint = computed;
        } else if (!functionFingerprint.trim().equals(computed)) {
            throw new FunctionControlException(FunctionControlException.Code.DECLARATION_INVALID);
        } else {
            functionFingerprint = functionFingerprint.trim();
        }
    }

    public boolean certifiable() {
        return status == FunctionDeclarationStatus.CERTAIN;
    }

    public Map<String, Object> fingerprintMaterial() {
        return fingerprintMaterial(functionName, runtimeName, pure, requiredExecutionServices, effect,
                parameterSchema, returnSchema, status);
    }

    @Override
    public String toString() {
        return "FunctionLibraryDeclaration[name=" + functionName + ", runtime=" + runtimeName + ", pure=" + pure
                + ", effect=" + effect + ", status=" + status + "]";
    }

    private static Map<String, Object> fingerprintMaterial(
            String name, String runtimeName, boolean pure, Set<String> services, FunctionEffect effect,
            Map<String, Object> parameterSchema, Map<String, Object> returnSchema,
            FunctionDeclarationStatus status) {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("functionName", name);
        material.put("runtimeName", runtimeName);
        material.put("pure", pure);
        material.put("requiredExecutionServices", services.stream().sorted().toList());
        material.put("effect", effect.name());
        material.put("parameterSchema", parameterSchema);
        material.put("returnSchema", returnSchema);
        material.put("status", status.name());
        return material;
    }

    private static Set<String> normalizeServices(Set<String> services) {
        if (services == null || services.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String service : services) {
            String value = FunctionValueSupport.text(service, true,
                    FunctionControlException.Code.DECLARATION_INVALID);
            normalized.add(value);
        }
        return Set.copyOf(normalized);
    }

    /** Convenience adapter for BLOGE's typed execution-service names. */
    public static Set<String> serviceNames(Set<ExecutionServiceKind> services) {
        if (services == null) {
            return Set.of();
        }
        return services.stream().map(Enum::name).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
