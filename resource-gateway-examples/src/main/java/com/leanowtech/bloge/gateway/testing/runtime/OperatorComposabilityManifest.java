package com.leanowtech.bloge.gateway.testing.runtime;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Versioned, credential-free declaration of the dependencies that can affect one operator binding.
 *
 * <p>The manifest is an input to server-side testability classification, never a caller-controlled
 * certification switch. Resource Gateway v1 can certify only a self-contained synchronous binding
 * whose declaration is tied to a fingerprinted conformance suite. Declared dependency ports and
 * unsupported execution services remain visible inventory until the runtime can control those
 * boundaries. Time, random, UUID, identity, and feature-flag services are conditionally certifiable
 * when the run fixture supplies their required deterministic controls. Secrets remain unsupported
 * until an opaque-reference test authority exists.</p>
 *
 * @param schemaVersion manifest schema version
 * @param dependencyMode whether external dependencies are absent, declared, or opaque
 * @param dependencies stable external dependency inventory
 * @param executionServices execution-scoped nondeterminism services consumed by the binding
 * @param globalStateFree binding attestation that no undeclared mutable global state is accessed
 * @param conformanceSuiteRef stable repository or registry reference for the conformance suite
 * @param conformanceFingerprint immutable conformance suite artifact fingerprint
 */
public record OperatorComposabilityManifest(
        String schemaVersion,
        DependencyMode dependencyMode,
        List<Dependency> dependencies,
        List<ExecutionService> executionServices,
        boolean globalStateFree,
        String conformanceSuiteRef,
        String conformanceFingerprint
) {
    /** Current operator composability manifest version. */
    public static final String SCHEMA_VERSION = "bloge.operatorComposabilityManifest.v1";

    /** Normalizes ordering so equivalent declarations have one canonical fingerprint. */
    public OperatorComposabilityManifest {
        schemaVersion = normalized(schemaVersion).isBlank() ? SCHEMA_VERSION : normalized(schemaVersion);
        dependencyMode = dependencyMode == null ? DependencyMode.OPAQUE : dependencyMode;
        dependencies = dependencies == null ? List.of() : dependencies.stream()
                .filter(java.util.Objects::nonNull)
                .distinct()
                .sorted(Comparator.comparing(Dependency::ref)
                        .thenComparing(dependency -> dependency.kind().name())
                        .thenComparing(dependency -> dependency.controlBoundary().name()))
                .toList();
        executionServices = executionServices == null ? List.of() : executionServices.stream()
                .filter(java.util.Objects::nonNull)
                .distinct()
                .sorted(Comparator.comparing(Enum::name))
                .toList();
        conformanceSuiteRef = normalized(conformanceSuiteRef);
        conformanceFingerprint = normalized(conformanceFingerprint).toLowerCase(Locale.ROOT);
        if (dependencyMode == DependencyMode.NONE && !dependencies.isEmpty()) {
            throw new IllegalArgumentException("NONE dependency mode cannot declare dependencies");
        }
        if (dependencyMode == DependencyMode.DECLARED && dependencies.isEmpty()) {
            throw new IllegalArgumentException("DECLARED dependency mode requires dependencies");
        }
    }

    /**
     * Creates the only non-resource manifest shape eligible for v1 certification.
     *
     * @param conformanceSuiteRef stable conformance suite reference
     * @param conformanceFingerprint immutable suite artifact fingerprint
     * @return self-contained manifest
     */
    public static OperatorComposabilityManifest selfContained(String conformanceSuiteRef,
                                                               String conformanceFingerprint) {
        return new OperatorComposabilityManifest(SCHEMA_VERSION, DependencyMode.NONE, List.of(),
                List.of(), true, conformanceSuiteRef, conformanceFingerprint);
    }

    /** @return deterministic public protocol projection containing no runtime credentials */
    public Map<String, Object> toProtocolMap() {
        List<Map<String, Object>> projectedDependencies = dependencies.stream()
                .map(Dependency::toProtocolMap)
                .toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", schemaVersion);
        result.put("dependencyMode", dependencyMode.name());
        result.put("dependencies", projectedDependencies);
        result.put("executionServices", executionServices.stream().map(Enum::name).toList());
        result.put("globalStateFree", globalStateFree);
        result.put("conformanceSuiteRef", conformanceSuiteRef);
        result.put("conformanceFingerprint", conformanceFingerprint);
        return Map.copyOf(result);
    }

    /** External dependency inventory mode. */
    public enum DependencyMode {
        NONE,
        DECLARED,
        OPAQUE
    }

    /** Ambient authority or nondeterminism source that must be supplied through execution scope. */
    public enum ExecutionService {
        TIME,
        RANDOM,
        UUID,
        IDENTITY,
        FEATURE_FLAG,
        SECRET
    }

    /** Supported external dependency categories. */
    public enum DependencyKind {
        RESOURCE,
        HTTP,
        DATABASE,
        MESSAGE,
        FILESYSTEM,
        SECRET,
        OTHER
    }

    /** Boundary through which a dependency can be controlled during a test run. */
    public enum ControlBoundary {
        RESOURCE_BINDING,
        EXECUTION_PROVIDER,
        TRANSPORT_PORT,
        UNMANAGED
    }

    /**
     * One stable external dependency declaration.
     *
     * @param ref non-secret dependency reference
     * @param kind dependency category
     * @param controlBoundary injectable boundary exposed by the binding
     */
    public record Dependency(String ref, DependencyKind kind, ControlBoundary controlBoundary) {
        /** Normalizes and validates one dependency declaration. */
        public Dependency {
            ref = normalized(ref);
            kind = kind == null ? DependencyKind.OTHER : kind;
            controlBoundary = controlBoundary == null ? ControlBoundary.UNMANAGED : controlBoundary;
            if (ref.isBlank()) {
                throw new IllegalArgumentException("Dependency ref must not be blank");
            }
        }

        private Map<String, Object> toProtocolMap() {
            return Map.of(
                    "ref", ref,
                    "kind", kind.name(),
                    "controlBoundary", controlBoundary.name());
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
