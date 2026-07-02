package com.leanowtech.bloge.gateway.visual.draft;

import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinitionChangeSummary;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for visual graph draft revision diff classification.
 */
class GraphDraftDiffTest {

    @Test
    void classifiesGraphNodeAndEdgeExecutionSurfaceChanges() {
        GraphDraft base = draft(
                1,
                "riskGraph",
                graphInputSchema("integer"),
                List.of(eligibilityNode("eligibility", "risk:eligibility", "score")),
                List.of(),
                new GraphDraft.OutputSelection("eligibility", ""),
                Map.of("eligibility", "fingerprint-v1"),
                Map.of("schemaVersion", "bloge.visualLayout.v1", "rootId", "riskGraph")
        );
        GraphDraft target = draft(
                2,
                "riskGraphV2",
                graphInputSchema("string"),
                List.of(
                        eligibilityNode("eligibility", "risk:eligibility", "customer.score"),
                        eligibilityNode("audit", "risk:audit", "score")
                ),
                List.of(new GraphDraft.DraftEdge("eligibility-to-audit",
                        "data",
                        new GraphDraft.Endpoint("eligibility", "output", "eligible"),
                        new GraphDraft.Endpoint("audit", "inputs", "eligible"))),
                new GraphDraft.OutputSelection("audit", ""),
                Map.of(
                        "eligibility", "fingerprint-v2",
                        "audit", "audit-fingerprint"
                ),
                Map.of("schemaVersion", "bloge.visualLayout.v1", "rootId", "riskGraphV2")
        );

        GraphDraftDiff diff = GraphDraftDiff.between(base, target);

        assertThat(diff.schemaVersion()).isEqualTo(GraphDraftDiff.SCHEMA_VERSION);
        assertThat(diff.draftId()).isEqualTo("draft-risk");
        assertThat(diff.baseRevision()).isEqualTo(1);
        assertThat(diff.targetRevision()).isEqualTo(2);
        assertThat(diff.changed()).isTrue();
        assertThat(diff.changeRisk()).isEqualTo(OperatorDefinitionChangeSummary.RISK_BREAKING_SCHEMA);
        assertThat(diff.changeCategories())
                .contains(OperatorDefinitionChangeSummary.RISK_BREAKING_SCHEMA,
                        OperatorDefinitionChangeSummary.RISK_RUNTIME_BINDING,
                        OperatorDefinitionChangeSummary.RISK_METADATA);
        assertThat(diff.addedNodeCount()).isEqualTo(1);
        assertThat(diff.removedNodeCount()).isZero();
        assertThat(diff.changedNodeCount()).isEqualTo(1);
        assertThat(diff.addedEdgeCount()).isEqualTo(1);
        assertThat(diff.graphChanges())
                .extracting(GraphDraftDiff.GraphChange::field)
                .contains("graphName", "inputSchema", "output", "visualLayout");
        assertThat(diff.nodeChanges())
                .extracting(GraphDraftDiff.NodeChange::nodeId,
                        GraphDraftDiff.NodeChange::changeKind,
                        GraphDraftDiff.NodeChange::risk)
                .contains(
                        org.assertj.core.groups.Tuple.tuple("eligibility",
                                "CHANGED",
                                OperatorDefinitionChangeSummary.RISK_RUNTIME_BINDING),
                        org.assertj.core.groups.Tuple.tuple("audit",
                                "ADDED",
                                OperatorDefinitionChangeSummary.RISK_RUNTIME_BINDING)
                );
        assertThat(diff.nodeChanges())
                .filteredOn(change -> "eligibility".equals(change.nodeId()))
                .singleElement()
                .satisfies(change -> assertThat(change.changedFields())
                        .contains("inputs", "operatorFingerprint"));
        assertThat(diff.edgeChanges())
                .singleElement()
                .satisfies(change -> {
                    assertThat(change.edgeId()).isEqualTo("eligibility-to-audit");
                    assertThat(change.changeKind()).isEqualTo("ADDED");
                    assertThat(change.targetSignature()).contains("eligibility.output.eligible->audit.inputs.eligible");
                });
        assertThat(diff.changeSummary())
                .contains("graph input schema changed")
                .contains("node 'audit' added");
    }

    @Test
    void reportsMetadataRiskForLayoutOnlyChanges() {
        GraphDraft base = draft(
                1,
                "riskGraph",
                graphInputSchema("integer"),
                List.of(eligibilityNode("eligibility", "risk:eligibility", "score")),
                List.of(),
                new GraphDraft.OutputSelection("eligibility", ""),
                Map.of("eligibility", "fingerprint-v1"),
                Map.of("schemaVersion", "bloge.visualLayout.v1", "rootId", "riskGraph")
        );
        GraphDraft target = draft(
                2,
                "riskGraph",
                graphInputSchema("integer"),
                List.of(new GraphDraft.DraftNode(
                        "eligibility",
                        "risk:eligibility",
                        "",
                        Map.of("score", GraphDraft.Binding.contextPath("score")),
                        Map.of(),
                        new GraphDraft.Position(240, 120)
                )),
                List.of(),
                new GraphDraft.OutputSelection("eligibility", ""),
                Map.of("eligibility", "fingerprint-v1"),
                Map.of("schemaVersion", "bloge.visualLayout.v1", "rootId", "riskGraph",
                        "nodes", List.of(Map.of("id", "eligibility", "x", 240, "y", 120)))
        );

        GraphDraftDiff diff = GraphDraftDiff.between(base, target);

        assertThat(diff.changed()).isTrue();
        assertThat(diff.changeRisk()).isEqualTo(OperatorDefinitionChangeSummary.RISK_METADATA);
        assertThat(diff.addedNodeCount()).isZero();
        assertThat(diff.changedNodeCount()).isEqualTo(1);
        assertThat(diff.nodeChanges())
                .singleElement()
                .satisfies(change -> assertThat(change.changedFields()).containsExactly("position"));
        assertThat(diff.graphChanges())
                .extracting(GraphDraftDiff.GraphChange::field)
                .containsExactly("visualLayout");
    }

    @Test
    void reportsNoSurfaceChangeForEquivalentSnapshots() {
        GraphDraft draft = draft(
                1,
                "riskGraph",
                graphInputSchema("integer"),
                List.of(eligibilityNode("eligibility", "risk:eligibility", "score")),
                List.of(),
                new GraphDraft.OutputSelection("eligibility", ""),
                Map.of("eligibility", "fingerprint-v1"),
                Map.of()
        );

        GraphDraftDiff diff = GraphDraftDiff.between(draft, draft);

        assertThat(diff.changed()).isFalse();
        assertThat(diff.graphChanges()).isEmpty();
        assertThat(diff.nodeChanges()).isEmpty();
        assertThat(diff.edgeChanges()).isEmpty();
        assertThat(diff.changeSummary()).isEqualTo("No graph draft surface changes.");
    }

    private static GraphDraft draft(long revision,
                                    String graphName,
                                    SchemaEnvelope inputSchema,
                                    List<GraphDraft.DraftNode> nodes,
                                    List<GraphDraft.DraftEdge> edges,
                                    GraphDraft.OutputSelection output,
                                    Map<String, String> fingerprints,
                                    Map<String, Object> visualLayout) {
        return new GraphDraft(
                GraphDraft.SCHEMA_VERSION,
                "draft-risk",
                revision,
                graphName,
                "demo-tenant",
                "local",
                "browser",
                GraphDraft.STATUS_DRAFT,
                inputSchema,
                nodes,
                edges,
                visualLayout,
                output,
                fingerprints
        );
    }

    private static GraphDraft.DraftNode eligibilityNode(String nodeId, String operatorRef, String scorePath) {
        return new GraphDraft.DraftNode(
                nodeId,
                operatorRef,
                "",
                Map.of("score", GraphDraft.Binding.contextPath(scorePath)),
                Map.of(),
                new GraphDraft.Position(80, 80)
        );
    }

    private static SchemaEnvelope graphInputSchema(String scoreType) {
        return SchemaEnvelope.object(Map.of(
                "score", Map.of("type", scoreType),
                "amount", Map.of("type", "number")
        ), List.of("score", "amount"));
    }
}
