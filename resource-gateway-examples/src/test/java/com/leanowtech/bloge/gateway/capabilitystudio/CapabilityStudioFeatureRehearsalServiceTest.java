package com.leanowtech.bloge.gateway.capabilitystudio;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static com.leanowtech.bloge.gateway.capabilitystudio.CapabilityStudioDataLensProjection.PermissionMode;

class CapabilityStudioFeatureRehearsalServiceTest {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    private final CapabilityStudioGoldenDemoPack pack =
            new CapabilityStudioGoldenDemoPackLoader().load(JSON);
    private final CapabilityStudioFeatureRehearsalService service =
            new CapabilityStudioFeatureRehearsalService(pack, JSON, new DefaultOperatorRegistry());

    @Test
    void allCanonicalCasesRunThroughTheRealTestRunEvidencePath() {
        assertThat(pack.scenarios()).hasSize(9);

        for (CapabilityStudioGoldenDemoPack.TestScenario scenario : pack.scenarios()) {
            CapabilityStudioFeatureRehearsalProjection projection = service.rehearse(
                    scenario.id(), PermissionMode.STRUCTURE_ONLY);

            assertThat(projection.schemaVersion())
                    .isEqualTo(CapabilityStudioFeatureRehearsalProjection.SCHEMA_VERSION);
            assertThat(projection.scenario().id()).isEqualTo(scenario.id());
            assertThat(projection.graph().id()).isEqualTo("feature-cancellation-dispute-context");
            assertThat(projection.graph().fingerprint()).startsWith("sha256:");
            assertThat(projection.run().runId()).startsWith("test-run-");
            assertThat(projection.run().semanticFingerprint()).startsWith("sha256:");
            assertThat(projection.run().realExternalCallCount()).isZero();
            assertThat(projection.run().bindingMode())
                    .isEqualTo(CapabilityStudioFeatureRehearsalService.BINDING_MODE);
            assertThat(projection.dataLens().nodes())
                    .as("four HTTP calls plus aggregate and decision for %s", scenario.id())
                    .hasSize(6);
            assertThat(projection.dataLens().nodes())
                    .extracting(CapabilityStudioDataLensProjection.Node::operatorRef)
                    .filteredOn(value -> value.equals("httpResource"))
                    .hasSize(4);
            assertThat(projection.dataLens().nodes())
                    .allSatisfy(node -> {
                        assertThat(node.input()).isNull();
                        assertThat(node.output()).isNull();
                    });
            assertThat(projection.dataLens().fingerprint()).startsWith("sha256:");

            String expectedStatus = scenario.dependencyBehaviors().stream()
                    .anyMatch(behavior -> "TIMEOUT".equals(behavior.behavior()))
                    ? "TIMED_OUT" : "PASSED";
            assertThat(projection.run().status())
                    .as("terminal status for %s", scenario.id())
                    .isEqualTo(expectedStatus);
        }
    }

    @Test
    void payloadVisibleModeExposesOnlyControlledDemoMaterial() {
        CapabilityStudioFeatureRehearsalProjection projection = service.rehearse(
                "case-standard-cancellation-fee", PermissionMode.PAYLOAD_VISIBLE);

        assertThat(projection.dataLens().nodes())
                .anySatisfy(node -> assertThat(String.valueOf(node.input()))
                        .contains("DEMO-ORDER-20260818-001"));
        assertThat(JSON.valueToTree(projection.dataLens()).toString())
                .contains("DEMO-ORDER-20260818-001")
                .doesNotContain("REAL_EXTERNAL_CALL_FORBIDDEN");
    }

    @Test
    void semanticFingerprintAndGraphFingerprintAreStableForRepeatedCase() {
        List<CapabilityStudioFeatureRehearsalProjection> runs = List.of(
                service.rehearse("case-compensation-history-empty", PermissionMode.STRUCTURE_ONLY),
                service.rehearse("case-compensation-history-empty", PermissionMode.STRUCTURE_ONLY),
                service.rehearse("case-compensation-history-empty", PermissionMode.STRUCTURE_ONLY));

        assertThat(runs).extracting(value -> value.run().semanticFingerprint())
                .containsOnly(runs.getFirst().run().semanticFingerprint());
        assertThat(runs).extracting(value -> value.graph().fingerprint())
                .containsOnly(runs.getFirst().graph().fingerprint());
    }

    @Test
    void timeoutCasePreservesTimeoutAndDownstreamSkipInTheDataLens() {
        CapabilityStudioFeatureRehearsalProjection projection = service.rehearse(
                "case-compensation-history-timeout", PermissionMode.STRUCTURE_ONLY);

        assertThat(projection.run().status()).isEqualTo("TIMED_OUT");
        assertThat(projection.dataLens().nodes())
                .filteredOn(node -> node.nodeId().equals("compensationHistoryLookup"))
                .singleElement()
                .satisfies(node -> {
                    assertThat(node.status()).isEqualTo("TIMEOUT");
                    assertThat(node.errorCode()).isEqualTo("COMPENSATION_HISTORY_TIMEOUT");
                });
        assertThat(projection.dataLens().nodes())
                .filteredOn(node -> node.nodeId().equals("aggregateCancellationContext")
                        || node.nodeId().equals("cancellationDecision"))
                .allSatisfy(node -> assertThat(node.status()).isIn("CANCELLED", "SKIPPED", "NOT_RUN"));
    }

    @Test
    void concurrentRehearsalsKeepRunIdentityAndEgressCountersIsolated() throws Exception {
        List<Callable<CapabilityStudioFeatureRehearsalProjection>> tasks = IntStream.range(0, 24)
                .mapToObj(ignored -> (Callable<CapabilityStudioFeatureRehearsalProjection>) () ->
                        service.rehearse("case-standard-cancellation-fee",
                                PermissionMode.STRUCTURE_ONLY))
                .toList();

        List<CapabilityStudioFeatureRehearsalProjection> runs;
        try (var executor = Executors.newFixedThreadPool(8)) {
            runs = executor.invokeAll(tasks).stream().map(future -> {
                try {
                    return future.get();
                } catch (Exception failure) {
                    throw new AssertionError("Concurrent Feature rehearsal failed", failure);
                }
            }).toList();
        }

        assertThat(runs).hasSize(24)
                .allSatisfy(run -> {
                    assertThat(run.run().status()).isEqualTo("PASSED");
                    assertThat(run.run().realExternalCallCount()).isZero();
                });
        assertThat(runs).extracting(run -> run.run().runId()).doesNotHaveDuplicates();
        assertThat(runs).extracting(run -> run.graph().fingerprint())
                .containsOnly(runs.getFirst().graph().fingerprint());
        assertThat(runs).extracting(run -> run.run().semanticFingerprint())
                .containsOnly(runs.getFirst().run().semanticFingerprint());
    }

    @Test
    void unknownCaseFailsClosedBeforeAnyRuntimeExecution() {
        assertThatThrownBy(() -> service.rehearse("case-does-not-exist", PermissionMode.STRUCTURE_ONLY))
                .isInstanceOf(CapabilityStudioFeatureRehearsalService.UnknownScenarioException.class)
                .hasMessage("Unknown Capability Studio Feature Rehearsal case");
    }
}
