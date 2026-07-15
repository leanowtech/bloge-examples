package com.leanowtech.bloge.gateway.testing.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OperatorComposabilityManifestTest {

    @Test
    void canonicalizesDependencyAndExecutionServiceOrdering() {
        OperatorComposabilityManifest manifest = new OperatorComposabilityManifest(
                OperatorComposabilityManifest.SCHEMA_VERSION,
                OperatorComposabilityManifest.DependencyMode.DECLARED,
                List.of(
                        new OperatorComposabilityManifest.Dependency("z-db",
                                OperatorComposabilityManifest.DependencyKind.DATABASE,
                                OperatorComposabilityManifest.ControlBoundary.EXECUTION_PROVIDER),
                        new OperatorComposabilityManifest.Dependency("a-http",
                                OperatorComposabilityManifest.DependencyKind.HTTP,
                                OperatorComposabilityManifest.ControlBoundary.TRANSPORT_PORT)),
                List.of(OperatorComposabilityManifest.ExecutionService.UUID,
                        OperatorComposabilityManifest.ExecutionService.TIME,
                        OperatorComposabilityManifest.ExecutionService.UUID),
                true, "suite:declared", "sha256:" + "a".repeat(64));

        assertThat(manifest.dependencies()).extracting(OperatorComposabilityManifest.Dependency::ref)
                .containsExactly("a-http", "z-db");
        assertThat(manifest.executionServices()).containsExactly(
                OperatorComposabilityManifest.ExecutionService.TIME,
                OperatorComposabilityManifest.ExecutionService.UUID);
        assertThat(manifest.toProtocolMap()).containsEntry("dependencyMode", "DECLARED");
    }

    @Test
    void rejectsDependencyModesThatContradictTheInventory() {
        OperatorComposabilityManifest.Dependency dependency =
                new OperatorComposabilityManifest.Dependency("database:customer",
                        OperatorComposabilityManifest.DependencyKind.DATABASE,
                        OperatorComposabilityManifest.ControlBoundary.UNMANAGED);

        assertThatThrownBy(() -> new OperatorComposabilityManifest(
                "", OperatorComposabilityManifest.DependencyMode.NONE, List.of(dependency), List.of(),
                true, "suite:none", "sha256:" + "b".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NONE dependency mode");
        assertThatThrownBy(() -> new OperatorComposabilityManifest(
                "", OperatorComposabilityManifest.DependencyMode.DECLARED, List.of(), List.of(),
                true, "suite:declared", "sha256:" + "b".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires dependencies");
    }
}
