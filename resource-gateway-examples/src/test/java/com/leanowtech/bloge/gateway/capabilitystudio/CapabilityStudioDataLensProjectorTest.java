package com.leanowtech.bloge.gateway.capabilitystudio;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.domain.TestRunEvidence;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.leanowtech.bloge.gateway.capabilitystudio.CapabilityStudioDataLensProjection.PermissionMode.PAYLOAD_VISIBLE;
import static com.leanowtech.bloge.gateway.capabilitystudio.CapabilityStudioDataLensProjection.PermissionMode.STRUCTURE_ONLY;
import static org.assertj.core.api.Assertions.assertThat;

class CapabilityStudioDataLensProjectorTest {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private final CapabilityStudioDataLensProjector projector =
            new CapabilityStudioDataLensProjector(JSON);

    @Test
    void structureOnlyHidesValuesButRetainsFingerprintsAndCoordinates() throws Exception {
        CapabilityStudioDataLensProjection result = projector.project(evidence(), STRUCTURE_ONLY);
        CapabilityStudioDataLensProjection.Node node = result.nodes().getFirst();
        CapabilityStudioDataLensProjection.Edge edge = result.edges().getFirst();

        assertThat(node.input()).isNull();
        assertThat(node.output()).isNull();
        assertThat(node.inputFingerprint()).startsWith("sha256:");
        assertThat(node.outputFingerprint()).startsWith("sha256:");
        assertThat(node.graphPath()).isEqualTo("/root");
        assertThat(node.invocationSite()).isEqualTo("site-a");
        assertThat(node.correlation()).isEqualTo("order-1");
        assertThat(node.occurrence()).isEqualTo(2);
        assertThat(edge.value()).isNull();
        assertThat(edge.valueFingerprint()).startsWith("sha256:");
        assertThat(result.firstDifference().expected()).isNull();
        assertThat(result.firstDifference().actual()).isNull();
        assertThat(result.firstDifference().expectedFingerprint()).startsWith("sha256:");
        assertThat(JSON.writeValueAsString(result)).doesNotContain("secret-payload", "customer-42");
    }

    @Test
    void payloadVisibleShowsValuesButStillExcludesDiagnosticText() throws Exception {
        CapabilityStudioDataLensProjection result = projector.project(evidence(), PAYLOAD_VISIBLE);

        assertThat(result.nodes().getFirst().input()).isEqualTo(Map.of("secret", "secret-payload"));
        assertThat(result.nodes().getFirst().output()).isEqualTo(Map.of("customer", "customer-42"));
        assertThat(result.firstDifference().expected()).isEqualTo(Map.of("ok", true));
        assertThat(result.firstDifference().actual()).isEqualTo(Map.of("ok", false));
        assertThat(JSON.writeValueAsString(result)).doesNotContain("do not expose this payload");
        assertThat(JSON.writeValueAsString(result)).doesNotContain("metadata");
    }

    @Test
    void retainsRetryAndExactFallbackWithoutInventingPartial() {
        CapabilityStudioDataLensProjection result = projector.project(evidence(), STRUCTURE_ONLY);
        CapabilityStudioDataLensProjection.Node node = result.nodes().getFirst();

        assertThat(node.status()).isEqualTo("SUCCESS");
        assertThat(node.retryCount()).isEqualTo(1);
        assertThat(node.fallbackStatus()).isEqualTo("FALLBACK");
        assertThat(result.nodes()).noneMatch(value -> "PARTIAL".equals(value.status()));
        assertThat(projector.project(evidenceWith(List.of(
                new TestRunEvidence.NodeTrace("partial", "operator", "PARTIAL", "REAL",
                        null, null, "", 0, "site", "/root", "", 1, 1, List.of())), List.of()),
                STRUCTURE_ONLY).nodes().getFirst().status()).isEqualTo("PARTIAL");
    }

    @Test
    void infersFallbackOnlyWhenEngineCompletedAfterTerminalFailedAttempt() {
        TestRunEvidence.AttemptTrace timeout = new TestRunEvidence.AttemptTrace(
                1, "TIMEOUT", "OUTPUT_LEVEL", Map.of("request", "x"), null,
                "UPSTREAM_TIMEOUT", 12);
        TestRunEvidence.NodeTrace recoveredFallback = new TestRunEvidence.NodeTrace(
                "fallback", "lookup", "MOCKED", "OUTPUT_LEVEL", Map.of("request", "x"),
                Map.of("availability", "TIMEOUT"), "", 37, "fallback-site", "/root/child", "",
                1, 1, List.of(timeout));
        TestRunEvidence.AttemptTrace failed = new TestRunEvidence.AttemptTrace(
                1, "FAILED", "REAL", "input", null, "TRANSIENT", 4);
        TestRunEvidence.AttemptTrace success = new TestRunEvidence.AttemptTrace(
                2, "SUCCESS", "REAL", "input", "recovered", "", 8);
        TestRunEvidence.NodeTrace retrySuccess = new TestRunEvidence.NodeTrace(
                "retry", "lookup", "SUCCESS", "REAL", "input", "recovered", "", 12,
                "retry-site", "/root", "", 1, 1, List.of(failed, success));

        CapabilityStudioDataLensProjection result = projector.project(
                evidenceWith(List.of(recoveredFallback, retrySuccess), List.of()), STRUCTURE_ONLY);

        assertThat(result.nodes()).filteredOn(node -> "fallback".equals(node.nodeId()))
                .singleElement().satisfies(node ->
                        assertThat(node.fallbackStatus()).isEqualTo("FALLBACK"));
        assertThat(result.nodes()).filteredOn(node -> "retry".equals(node.nodeId()))
                .singleElement().satisfies(node ->
                        assertThat(node.fallbackStatus()).isNull());
    }

    @Test
    void sortsInputDeterministicallyAndCapsHighCardinalityWithSignal() {
        List<TestRunEvidence.NodeTrace> nodes = new ArrayList<>();
        for (int index = 0; index < CapabilityStudioDataLensProjector.MAX_NODES + 3; index++) {
            nodes.add(new TestRunEvidence.NodeTrace("node-" + (999 - index), "operator", "SUCCESS",
                    "REAL", Map.of("index", index), Map.of("index", index), "", 1,
                    "site-" + (999 - index), "/root", "", 1, 1, List.of()));
        }
        TestRunEvidence many = evidenceWith(nodes, List.of());
        CapabilityStudioDataLensProjection result = projector.project(many, STRUCTURE_ONLY);
        CapabilityStudioDataLensProjection repeated = projector.project(many, STRUCTURE_ONLY);

        assertThat(result.nodes()).hasSize(CapabilityStudioDataLensProjector.MAX_NODES);
        assertThat(result.truncation().nodesTruncated()).isTrue();
        assertThat(result.truncation().omittedNodes()).isEqualTo(3);
        assertThat(result.fingerprint()).isEqualTo(repeated.fingerprint());
        assertThat(result.nodes()).extracting(CapabilityStudioDataLensProjection.Node::invocationSite)
                .isSorted();
    }

    @Test
    void capsAttemptsAndPreservesEdgeStatusesWithoutDerivingThem() {
        List<TestRunEvidence.AttemptTrace> attempts = new ArrayList<>();
        for (int index = 0; index < CapabilityStudioDataLensProjector.MAX_ATTEMPTS_PER_NODE + 2; index++) {
            attempts.add(new TestRunEvidence.AttemptTrace(index + 1, "FAILED", "MOCKED",
                    Map.of("index", index), null, "E" + index, 1));
        }
        TestRunEvidence.NodeTrace node = new TestRunEvidence.NodeTrace("node", "operator", "TIMEOUT",
                "MOCKED", null, null, "TIMEOUT", 1, "site", "/root", "", 1, 1, attempts);
        TestRunEvidence.EdgeTrace edge = new TestRunEvidence.EdgeTrace("edge", "PARTIAL", null,
                "/root", "", 1, "site", "site-2");
        CapabilityStudioDataLensProjection result = projector.project(
                evidenceWith(List.of(node), List.of(edge)), STRUCTURE_ONLY);

        assertThat(result.nodes().getFirst().status()).isEqualTo("TIMEOUT");
        assertThat(result.nodes().getFirst().attempts())
                .hasSize(CapabilityStudioDataLensProjector.MAX_ATTEMPTS_PER_NODE);
        assertThat(result.truncation().attemptsTruncated()).isTrue();
        assertThat(result.truncation().omittedAttempts()).isEqualTo(2);
        assertThat(result.edges().getFirst().status()).isEqualTo("PARTIAL");
        assertThat(result.nodes().getFirst().fallbackStatus()).isNull();
    }

    @Test
    void locatesTheFirstRuntimeAssertionDifferenceInsteadOfReorderingItLexically() {
        TestRunEvidence.AssertionResult first = new TestRunEvidence.AssertionResult(
                "OUTPUT", "/z-first-in-runtime", false, 1, 2, "first");
        TestRunEvidence.AssertionResult second = new TestRunEvidence.AssertionResult(
                "OUTPUT", "/a-second-in-runtime", false, 3, 4, "second");
        TestRunEvidence evidence = new TestRunEvidence("", "ordered", TestRunEvidence.Status.ASSERTION_FAILED,
                TestRunEvidence.EvidenceClass.EXPLORATORY, "CAPABILITY", "", "", "", "", null, null,
                List.of(), List.of(), List.of(), List.of(first, second), List.of(), Map.of());

        CapabilityStudioDataLensProjection result = projector.project(evidence, STRUCTURE_ONLY);

        assertThat(result.firstDifference().locator()).isEqualTo("/assertions/0");
        assertThat(result.firstDifference().path()).isEqualTo("/z-first-in-runtime");
    }

    @Test
    void projectionDetachesPayloadContainersFromCallerMutation() {
        Map<String, Object> mutable = new LinkedHashMap<>();
        mutable.put("value", "before");
        CapabilityStudioDataLensProjection.Node node = new CapabilityStudioDataLensProjection.Node(
                "node", "operator", "SUCCESS", "MOCKED", "/root", "site", "", 1, 1,
                mutable, "sha256:" + "a".repeat(64), null, "", "", 0, List.of(), 0, null);

        mutable.put("value", "after");

        assertThat(node.input()).isEqualTo(Map.of("value", "before"));
    }

    private TestRunEvidence evidence() {
        TestRunEvidence.AttemptTrace failed = new TestRunEvidence.AttemptTrace(1, "FAILED", "MOCKED",
                Map.of("secret", "secret-payload"), null, "DEPENDENCY_FAILED", 3);
        TestRunEvidence.AttemptTrace fallback = new TestRunEvidence.AttemptTrace(2, "FALLBACK", "MOCKED",
                Map.of("secret", "secret-payload"), Map.of("customer", "customer-42"), "", 4);
        TestRunEvidence.NodeTrace node = new TestRunEvidence.NodeTrace("lookup", "customer.lookup", "SUCCESS",
                "MOCKED", Map.of("secret", "secret-payload"), Map.of("customer", "customer-42"), "", 7,
                "site-a", "/root", "order-1", 2, 1, List.of(failed, fallback));
        TestRunEvidence.EdgeTrace edge = new TestRunEvidence.EdgeTrace("lookup->decision", "TRANSFERRED",
                Map.of("customer", "customer-42"), "/root", "order-1", 1, "site-a", "site-b");
        TestRunEvidence.AssertionResult assertion = new TestRunEvidence.AssertionResult("OUTPUT", "/ok",
                false, Map.of("ok", true), Map.of("ok", false), "do not expose this payload");
        return new TestRunEvidence("", "run-1", TestRunEvidence.Status.ASSERTION_FAILED,
                TestRunEvidence.EvidenceClass.EXPLORATORY, "CAPABILITY", "", "", "", "", null, null,
                List.of(node), List.of(edge), List.of(), List.of(assertion), List.of("payload diagnostic"),
                Map.of("payload", "secret-payload"));
    }

    private TestRunEvidence evidenceWith(List<TestRunEvidence.NodeTrace> nodes,
                                         List<TestRunEvidence.EdgeTrace> edges) {
        return new TestRunEvidence("", "many", TestRunEvidence.Status.PASSED,
                TestRunEvidence.EvidenceClass.EXPLORATORY, "CAPABILITY", "", "", "", "", null, null,
                nodes, edges, List.of(), List.of(), List.of(), Map.of());
    }
}
