package com.leanowtech.bloge.gateway.visual.authoring.discovery;

import com.leanowtech.bloge.gateway.visual.catalog.BuiltInFunctionContract;
import com.leanowtech.bloge.gateway.visual.catalog.JavaOperatorInventoryProjector;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibrary;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Compares declared authoring contracts with the exact process-local operator/function inventory.
 */
@Service
public final class RuntimeParityService {

    private final JavaOperatorInventoryProjector operatorInventory;
    private final FrameworkFunctionInventory functionInventory;

    public RuntimeParityService(JavaOperatorInventoryProjector operatorInventory,
                                FrameworkFunctionInventory functionInventory) {
        this.operatorInventory = operatorInventory == null
                ? JavaOperatorInventoryProjector.forRegistry(null)
                : operatorInventory;
        this.functionInventory = functionInventory == null
                ? new FrameworkFunctionInventory(List.of())
                : functionInventory;
    }

    /** Evaluates all declarations in one canonical library candidate. */
    public Snapshot evaluate(OperatorLibrary library) {
        List<OperatorDefinition> runtimeOperators = operatorInventory.project();
        FrameworkFunctionInventory.Snapshot runtimeFunctions = functionInventory.snapshot();
        List<AuthoringFactProjection.RuntimeParity> parity = new ArrayList<>();
        if (library != null) {
            library.operators().stream()
                    .filter(java.util.Objects::nonNull)
                    .sorted(Comparator.comparing(OperatorDefinition::operatorRef))
                    .map(operator -> operatorParity(operator, runtimeOperators))
                    .forEach(parity::add);
            library.builtInFunctions().stream()
                    .filter(java.util.Objects::nonNull)
                    .sorted(Comparator.comparing(OperatorLibrary.BuiltInFunction::name))
                    .map(function -> functionParity(function, runtimeFunctions))
                    .forEach(parity::add);
        }
        return snapshot(runtimeOperators, runtimeFunctions, parity);
    }

    /**
     * Evaluates schema-neutral references found in DSL. Since no declaration contract exists, a
     * matching runtime can only reach RUNTIME_DISCOVERED.
     */
    public Snapshot evaluateReferences(Set<String> operatorRefs, Set<String> functionRefs) {
        List<OperatorDefinition> runtimeOperators = operatorInventory.project();
        FrameworkFunctionInventory.Snapshot runtimeFunctions = functionInventory.snapshot();
        Map<String, OperatorDefinition> runtimeByRef = byOperatorRef(runtimeOperators);
        List<AuthoringFactProjection.RuntimeParity> parity = new ArrayList<>();
        safeSet(operatorRefs).stream().sorted().forEach(ref -> {
            OperatorDefinition runtime = runtimeByRef.get(ref);
            parity.add(runtime == null
                    ? unresolved("OPERATOR", ref, "DOCUMENTED_ONLY",
                    "RG.AUTHORING.RUNTIME_OPERATOR_MISSING",
                    "No exact operator was found in the target runtime inventory.")
                    : new AuthoringFactProjection.RuntimeParity(
                    "OPERATOR", ref, "process-local", "RUNTIME_DISCOVERED", false,
                    "", runtime.fingerprint(),
                    "RG.AUTHORING.RUNTIME_OPERATOR_CONTRACT_UNKNOWN",
                    "The operator exists at runtime, but the DSL does not declare a contract to compare."));
        });
        safeSet(functionRefs).stream().sorted().forEach(ref -> {
            List<FrameworkFunctionInventory.FunctionRuntime> runtimes = runtimeFunctions.resolve(ref);
            parity.add(runtimes.isEmpty()
                    ? unresolved("FUNCTION", ref, "DOCUMENTED_ONLY",
                    "RG.AUTHORING.RUNTIME_FUNCTION_MISSING",
                    "No exact callable was found in the target runtime inventory.")
                    : new AuthoringFactProjection.RuntimeParity(
                    "FUNCTION", ref, joinedProfiles(runtimes), "RUNTIME_DISCOVERED", false,
                    "", combinedRuntimeFingerprint(runtimes),
                    "RG.AUTHORING.RUNTIME_FUNCTION_CONTRACT_UNKNOWN",
                    "The callable exists at runtime, but the DSL does not declare a signature to compare."));
        });
        return snapshot(runtimeOperators, runtimeFunctions, parity);
    }

    /** Returns the process-local runtime facts without comparing a declared source. */
    public Inventory inventory() {
        List<OperatorDefinition> operators = operatorInventory.project().stream()
                .sorted(Comparator.comparing(OperatorDefinition::operatorRef))
                .toList();
        FrameworkFunctionInventory.Snapshot functions = functionInventory.snapshot();
        String fingerprint = runtimeInventoryFingerprint(operators, functions);
        return new Inventory(fingerprint, operators, functions.functions());
    }

    private static AuthoringFactProjection.RuntimeParity operatorParity(
            OperatorDefinition declared,
            List<OperatorDefinition> runtimeOperators) {
        Map<String, OperatorDefinition> runtimeByRef = byOperatorRef(runtimeOperators);
        String executableRef = executableRef(declared);
        if (executableRef.isBlank()) {
            return unresolved("OPERATOR", declared.operatorRef(), "DOCUMENTED_ONLY",
                    "RG.AUTHORING.RUNTIME_OPERATOR_BINDING_MISSING",
                    "The operator contract has no executable operatorRef binding.");
        }
        OperatorDefinition runtime = runtimeByRef.get(executableRef);
        if (runtime == null) {
            return new AuthoringFactProjection.RuntimeParity(
                    "OPERATOR", declared.operatorRef(), "process-local", "DOCUMENTED_ONLY", false,
                    operatorContractFingerprint(declared), "",
                    "RG.AUTHORING.RUNTIME_OPERATOR_MISSING",
                    "Executable operatorRef '%s' is absent from the target runtime inventory."
                            .formatted(executableRef));
        }
        String declaredFingerprint = operatorContractFingerprint(declared);
        String runtimeFingerprint = operatorContractFingerprint(runtime);
        if (!declared.operatorRef().equals(executableRef)) {
            return new AuthoringFactProjection.RuntimeParity(
                    "OPERATOR", declared.operatorRef(), "process-local", "RUNTIME_DISCOVERED", false,
                    declaredFingerprint, runtimeFingerprint,
                    "RG.AUTHORING.RUNTIME_OPERATOR_LOWERING_UNVERIFIED",
                    "The lowering target exists, but wrapper and runtime contracts require an explicit adapter proof.");
        }
        if (!declaredFingerprint.equals(runtimeFingerprint)) {
            return new AuthoringFactProjection.RuntimeParity(
                    "OPERATOR", declared.operatorRef(), "process-local", "DRIFTED", false,
                    declaredFingerprint, runtimeFingerprint,
                    "RG.AUTHORING.RUNTIME_OPERATOR_DRIFT",
                    "The declared operator ports or capability traits differ from the runtime inventory.");
        }
        return new AuthoringFactProjection.RuntimeParity(
                "OPERATOR", declared.operatorRef(), "process-local", "BOUND", true,
                declaredFingerprint, runtimeFingerprint,
                "", "The declared operator contract matches the process-local runtime inventory.");
    }

    private static AuthoringFactProjection.RuntimeParity functionParity(
            OperatorLibrary.BuiltInFunction declared,
            FrameworkFunctionInventory.Snapshot inventory) {
        List<FrameworkFunctionInventory.FunctionRuntime> runtimes = inventory.resolve(declared.name());
        String declaredFingerprint = BuiltInFunctionContract.callableFingerprint(declared);
        if (runtimes.isEmpty()) {
            return new AuthoringFactProjection.RuntimeParity(
                    "FUNCTION", declared.name(), "", "DOCUMENTED_ONLY", false,
                    declaredFingerprint, "",
                    "RG.AUTHORING.RUNTIME_FUNCTION_MISSING",
                    "No exact callable was found in the target runtime inventory.");
        }
        Set<String> runtimeFingerprints = runtimes.stream()
                .map(FrameworkFunctionInventory.FunctionRuntime::runtimeFingerprint)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (runtimeFingerprints.size() > 1) {
            return new AuthoringFactProjection.RuntimeParity(
                    "FUNCTION", declared.name(), joinedProfiles(runtimes), "DRIFTED", false,
                    declaredFingerprint, combinedRuntimeFingerprint(runtimes),
                    "RG.AUTHORING.RUNTIME_FUNCTION_AMBIGUOUS",
                    "Multiple non-identical runtime implementations claim this callable name.");
        }
        FrameworkFunctionInventory.FunctionRuntime runtime = runtimes.getFirst();
        if (!runtime.pure() || !runtime.requiredExecutionServices().isEmpty()) {
            return new AuthoringFactProjection.RuntimeParity(
                    "FUNCTION", declared.name(), runtime.runtimeProfile(), "BLOCKED_BY_POLICY", false,
                    declaredFingerprint, runtime.runtimeFingerprint(),
                    "RG.AUTHORING.RUNTIME_FUNCTION_POLICY_BLOCKED",
                    "The runtime function is not pure or requires execution services.");
        }
        if (runtime.declaredContract() == null) {
            return new AuthoringFactProjection.RuntimeParity(
                    "FUNCTION", declared.name(), runtime.runtimeProfile(), "RUNTIME_DISCOVERED", false,
                    declaredFingerprint, runtime.runtimeFingerprint(),
                    "RG.AUTHORING.RUNTIME_FUNCTION_SIGNATURE_UNKNOWN",
                    "The framework runtime exposes the callable implementation but no authoritative signature metadata.");
        }
        if (!BuiltInFunctionContract.compatible(declared, runtime.declaredContract())) {
            return new AuthoringFactProjection.RuntimeParity(
                    "FUNCTION", declared.name(), runtime.runtimeProfile(), "DRIFTED", false,
                    declaredFingerprint, runtime.runtimeFingerprint(),
                    "RG.AUTHORING.RUNTIME_FUNCTION_SIGNATURE_DRIFT",
                    "The declared callable signature differs from the runtime inventory contract.");
        }
        return new AuthoringFactProjection.RuntimeParity(
                "FUNCTION", declared.name(), runtime.runtimeProfile(), "BOUND", true,
                declaredFingerprint, runtime.runtimeFingerprint(),
                "", "The declared callable signature matches the bound runtime profile.");
    }

    private static Snapshot snapshot(
            List<OperatorDefinition> operators,
            FrameworkFunctionInventory.Snapshot functions,
            List<AuthoringFactProjection.RuntimeParity> parity) {
        List<AuthoringFactProjection.RuntimeParity> sorted = parity.stream()
                .sorted(Comparator.comparing(AuthoringFactProjection.RuntimeParity::assetKind)
                        .thenComparing(AuthoringFactProjection.RuntimeParity::assetRef))
                .toList();
        return new Snapshot(runtimeInventoryFingerprint(operators, functions), sorted);
    }

    private static String runtimeInventoryFingerprint(
            List<OperatorDefinition> operators,
            FrameworkFunctionInventory.Snapshot functions) {
        return VisualBundleFingerprint.fromMaterial(Map.of(
                "operators", operators.stream()
                        .sorted(Comparator.comparing(OperatorDefinition::operatorRef))
                        .map(operator -> Map.of(
                                "operatorRef", operator.operatorRef(),
                                "fingerprint", operator.fingerprint()))
                        .toList(),
                "functionInventoryFingerprint", functions.inventoryFingerprint()
        ));
    }

    private static Map<String, OperatorDefinition> byOperatorRef(List<OperatorDefinition> operators) {
        Map<String, OperatorDefinition> byRef = new LinkedHashMap<>();
        operators.stream()
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparing(OperatorDefinition::operatorRef))
                .forEach(operator -> byRef.putIfAbsent(operator.operatorRef(), operator));
        return Map.copyOf(byRef);
    }

    private static String executableRef(OperatorDefinition operator) {
        if (operator == null || operator.lowering() == null) {
            return "";
        }
        if (!operator.lowering().operatorRef().isBlank()) {
            return operator.lowering().operatorRef();
        }
        Object parameter = operator.lowering().parameters().get("executableOperatorRef");
        return parameter == null ? "" : parameter.toString().trim();
    }

    private static String operatorContractFingerprint(OperatorDefinition operator) {
        if (operator == null) {
            return "";
        }
        return VisualBundleFingerprint.fromMaterial(Map.of(
                "operatorRef", operator.operatorRef(),
                "operatorVersion", operator.operatorVersion(),
                "ports", operator.ports(),
                "configSchema", operator.configSchema(),
                "capabilities", operator.capabilities()
        ));
    }

    private static AuthoringFactProjection.RuntimeParity unresolved(
            String assetKind,
            String assetRef,
            String state,
            String code,
            String message) {
        return new AuthoringFactProjection.RuntimeParity(
                assetKind, assetRef, "", state, false, "", "", code, message);
    }

    private static Set<String> safeSet(Set<String> values) {
        return values == null ? Set.of() : Set.copyOf(values);
    }

    private static String joinedProfiles(List<FrameworkFunctionInventory.FunctionRuntime> runtimes) {
        return String.join(",", runtimes.stream()
                .map(FrameworkFunctionInventory.FunctionRuntime::runtimeProfile)
                .distinct()
                .sorted()
                .toList());
    }

    private static String combinedRuntimeFingerprint(
            List<FrameworkFunctionInventory.FunctionRuntime> runtimes) {
        return VisualBundleFingerprint.fromMaterial(Map.of(
                "runtimeFingerprints", runtimes.stream()
                        .map(FrameworkFunctionInventory.FunctionRuntime::runtimeFingerprint)
                        .distinct()
                        .sorted()
                        .toList()
        ));
    }

    public record Snapshot(
            String inventoryFingerprint,
            List<AuthoringFactProjection.RuntimeParity> parity
    ) {
        public Snapshot {
            inventoryFingerprint = inventoryFingerprint == null ? "" : inventoryFingerprint;
            parity = parity == null ? List.of() : List.copyOf(parity);
        }

        public boolean runtimeReady() {
            return !parity.isEmpty()
                    && parity.stream().allMatch(AuthoringFactProjection.RuntimeParity::executableReady);
        }
    }

    public record Inventory(
            String inventoryFingerprint,
            List<OperatorDefinition> operators,
            List<FrameworkFunctionInventory.FunctionRuntime> functions
    ) {
        public Inventory {
            inventoryFingerprint = inventoryFingerprint == null ? "" : inventoryFingerprint;
            operators = operators == null ? List.of() : List.copyOf(operators);
            functions = functions == null ? List.of() : List.copyOf(functions);
        }
    }
}
