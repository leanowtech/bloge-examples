package com.leanowtech.bloge.gateway.visual.draft;

import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.validation.VisualValidationResult;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for portable visual graph draft export bundles.
 */
class GraphDraftExportBundleTest {

    @Test
    void carriesNodeFixturesButExcludesThemFromBundleFingerprint() {
        GraphDraft first = draft().withNodeFixtures(Map.of(
                "eligibility", new GraphDraft.NodeFixture(Map.of("eligible", true, "ruleId", "PIN_A"))));
        GraphDraft second = draft().withNodeFixtures(Map.of(
                "eligibility", new GraphDraft.NodeFixture(Map.of("eligible", false, "ruleId", "PIN_B"))));

        GraphDraftExportBundle firstBundle = GraphDraftExportBundle.from(
                first, List.of(), new VisualValidationResult(true, List.of()), GraphDraftDependencyReport.empty());
        GraphDraftExportBundle secondBundle = GraphDraftExportBundle.from(
                second, List.of(), new VisualValidationResult(true, List.of()), GraphDraftDependencyReport.empty());

        assertThat(firstBundle.draft().nodeFixtures())
                .containsEntry("eligibility",
                        new GraphDraft.NodeFixture(Map.of("eligible", true, "ruleId", "PIN_A")));
        assertThat(secondBundle.draft().nodeFixtures())
                .containsEntry("eligibility",
                        new GraphDraft.NodeFixture(Map.of("eligible", false, "ruleId", "PIN_B")));
        assertThat(firstBundle.bundleFingerprint()).isEqualTo(secondBundle.bundleFingerprint());
        assertThat(firstBundle.bundleFingerprintVerified()).isTrue();
        assertThat(secondBundle.bundleFingerprintVerified()).isTrue();
    }

    private static GraphDraft draft() {
        return new GraphDraft(
                GraphDraft.SCHEMA_VERSION,
                "draft-risk",
                7,
                "riskGraph",
                "demo-tenant",
                "local",
                "browser",
                GraphDraft.STATUS_DRAFT,
                SchemaEnvelope.object(Map.of("score", Map.of("type", "integer")), List.of("score")),
                List.of(new GraphDraft.DraftNode(
                        "eligibility",
                        "risk:eligibility",
                        "",
                        Map.of("score", GraphDraft.Binding.contextPath("score")),
                        Map.of(),
                        new GraphDraft.Position(80, 80)
                )),
                List.of(),
                Map.of(),
                new GraphDraft.OutputSelection("eligibility", ""),
                Map.of("eligibility", "fingerprint-v1")
        );
    }
}
