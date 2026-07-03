package com.leanowtech.bloge.gateway.visual.catalog;

import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for server-derived operator library profiles.
 */
class OperatorLibraryProfileTest {

    @Test
    void fieldProfilesExposeSchemaAnnotationsForAuthoringReview() {
        OperatorDefinition base = VisualCatalogTestSupport.eligibilityOperator("integer");
        OperatorDefinition annotated = new OperatorDefinition(
                base.schemaVersion(),
                base.operatorRef(),
                base.operatorVersion(),
                base.display(),
                base.source(),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("inputs",
                                SchemaEnvelope.object(Map.of("score", Map.of(
                                        "type", "integer",
                        "title", "Bureau score",
                        "description", "Normalized external bureau score.",
                        "examples", List.of(720, 760),
                        "default", 700,
                        "$comment", "Reviewed by the bureau data steward."
                )), List.of("score")),
                                true,
                                "Eligibility inputs.")),
                        base.ports().outputs()
                ),
                SchemaEnvelope.object(Map.of("threshold", Map.of(
                        "type", "number",
                        "title", "Risk threshold",
                        "description", "Minimum accepted score.",
                        "examples", List.of(0.72, 0.86, 0.91),
                        "default", 0.5,
                        "$comment", "Tune only during risk policy review."
                )), List.of()),
                base.capabilities(),
                base.policy(),
                base.lowering(),
                base.diagnostics()
        );

        OperatorLibraryProfile profile = OperatorLibraryProfile.from(new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "risk-policy",
                "Risk policy operators",
                "1.0.0",
                "risk-team",
                "ACTIVE",
                List.of(annotated)
        ));

        OperatorLibraryProfile.FieldProfile inputField = profile.operators().getFirst().inputFields().getFirst();
        OperatorLibraryProfile.FieldProfile configField = profile.operators().getFirst().configFields().getFirst();

        assertThat(inputField.title()).isEqualTo("Bureau score");
        assertThat(inputField.description()).isEqualTo("Normalized external bureau score.");
        assertThat(inputField.examplesSummary()).isEqualTo("720, 760");
        assertThat(inputField.defaultSummary()).isEqualTo("700");
        assertThat(inputField.commentSummary()).isEqualTo("Reviewed by the bureau data steward.");
        assertThat(configField.title()).isEqualTo("Risk threshold");
        assertThat(configField.description()).isEqualTo("Minimum accepted score.");
        assertThat(configField.examplesSummary()).isEqualTo("0.72, 0.86 +1 more");
        assertThat(configField.defaultSummary()).isEqualTo("0.5");
        assertThat(configField.commentSummary()).isEqualTo("Tune only during risk policy review.");
    }
}
