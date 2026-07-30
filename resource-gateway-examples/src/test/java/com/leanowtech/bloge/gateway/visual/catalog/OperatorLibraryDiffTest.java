package com.leanowtech.bloge.gateway.visual.catalog;

import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for operator-library revision diff classification.
 */
class OperatorLibraryDiffTest {

    @Test
    void classifiesAddedRemovedAndChangedOperatorSurface() {
        OperatorLibraryRevision base = OperatorLibraryRevision.record(
                library("1.0.0", List.of(
                        operator("risk:eligibility", "integer"),
                        operator("risk:legacyCheck", "integer")
                )),
                1,
                OperatorLibraryRevision.ACTION_CREATE
        );
        OperatorLibraryRevision target = OperatorLibraryRevision.record(
                library("2.0.0", List.of(
                        operator("risk:eligibility", "string"),
                        operator("risk:newCheck", "integer")
                )),
                2,
                OperatorLibraryRevision.ACTION_REPLACE
        );

        OperatorLibraryDiff diff = OperatorLibraryDiff.between(base, target);

        assertThat(diff.schemaVersion()).isEqualTo(OperatorLibraryDiff.SCHEMA_VERSION);
        assertThat(diff.libraryId()).isEqualTo("risk-policy");
        assertThat(diff.changed()).isTrue();
        assertThat(diff.changeRisk()).isEqualTo(OperatorDefinitionChangeSummary.RISK_BREAKING_SCHEMA);
        assertThat(diff.changeCategories())
                .contains(OperatorDefinitionChangeSummary.RISK_BREAKING_SCHEMA,
                        OperatorDefinitionChangeSummary.RISK_COMPATIBLE_SCHEMA,
                        OperatorDefinitionChangeSummary.RISK_METADATA);
        assertThat(diff.addedOperatorCount()).isEqualTo(1);
        assertThat(diff.removedOperatorCount()).isEqualTo(1);
        assertThat(diff.changedOperatorCount()).isEqualTo(1);
        assertThat(diff.libraryChanges())
                .extracting(OperatorLibraryDiff.LibraryChange::field)
                .contains("revisionAction", "version");
        assertThat(diff.operatorChanges())
                .extracting(OperatorLibraryDiff.OperatorChange::operatorRef,
                        OperatorLibraryDiff.OperatorChange::changeKind,
                        OperatorLibraryDiff.OperatorChange::risk)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("risk:eligibility",
                                "CHANGED",
                                OperatorDefinitionChangeSummary.RISK_BREAKING_SCHEMA),
                        org.assertj.core.groups.Tuple.tuple("risk:legacyCheck",
                                "REMOVED",
                                OperatorDefinitionChangeSummary.RISK_BREAKING_SCHEMA),
                        org.assertj.core.groups.Tuple.tuple("risk:newCheck",
                                "ADDED",
                                OperatorDefinitionChangeSummary.RISK_COMPATIBLE_SCHEMA)
                );
        assertThat(diff.operatorChanges().getFirst().schemaChanges())
                .singleElement()
                .satisfies(change -> {
                    assertThat(change.surface()).isEqualTo("input");
                    assertThat(change.portName()).isEqualTo("inputs");
                    assertThat(change.compatibility()).isEqualTo("breaking");
                    assertThat(change.path()).isEqualTo("score");
                    assertThat(change.message()).contains("type");
                });
        assertThat(diff.changeSummary())
                .contains("revision action changed")
                .contains("operatorRef 'risk:legacyCheck' removed");
    }

    @Test
    void reportsNoSurfaceChangeForEquivalentSnapshots() {
        OperatorLibrary library = library("1.0.0", List.of(operator("risk:eligibility", "integer")));

        OperatorLibraryDiff diff = OperatorLibraryDiff.between(
                OperatorLibraryRevision.record(library, 1, OperatorLibraryRevision.ACTION_CREATE),
                OperatorLibraryRevision.record(library, 1, OperatorLibraryRevision.ACTION_CREATE)
        );

        assertThat(diff.changed()).isFalse();
        assertThat(diff.operatorChanges()).isEmpty();
        assertThat(diff.libraryChanges()).isEmpty();
        assertThat(diff.changeSummary()).isEqualTo("No library or operator surface changes.");
    }

    @Test
    void classifiesFunctionContractChangesAsBreaking() {
        OperatorLibrary baseLibrary = functionLibrary(
                "1.0.0",
                function("risk.normalize", "risk", "integer")
        );
        OperatorLibrary targetLibrary = functionLibrary(
                "2.0.0",
                function("risk.normalize", "shared", "string")
        );

        OperatorLibraryDiff diff = OperatorLibraryDiff.between(
                OperatorLibraryRevision.record(baseLibrary, 1, OperatorLibraryRevision.ACTION_CREATE),
                OperatorLibraryRevision.record(targetLibrary, 2, OperatorLibraryRevision.ACTION_REPLACE)
        );

        assertThat(diff.changed()).isTrue();
        assertThat(diff.changeRisk()).isEqualTo(OperatorDefinitionChangeSummary.RISK_BREAKING_SCHEMA);
        assertThat(diff.libraryChanges())
                .anySatisfy(change -> {
                    assertThat(change.field()).isEqualTo("builtInFunctions/risk.normalize");
                    assertThat(change.risk()).isEqualTo(OperatorDefinitionChangeSummary.RISK_BREAKING_SCHEMA);
                    assertThat(change.summary()).contains("contract changed");
                    assertThat(change.baseValue()).startsWith("sha256:");
                    assertThat(change.targetValue()).startsWith("sha256:");
                });
    }

    @Test
    void classifiesFunctionAdditionRemovalAndMetadataChanges() {
        OperatorLibrary baseLibrary = new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "risk-functions",
                "Risk functions",
                "1.0.0",
                "risk-team",
                "ACTIVE",
                List.of(
                        function("risk.removed", "risk", "integer"),
                        function("risk.documented", "risk", "integer")
                ),
                List.of()
        );
        OperatorLibrary.BuiltInFunction documented = function("risk.documented", "shared", "integer");
        OperatorLibrary targetLibrary = new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "risk-functions",
                "Risk functions",
                "2.0.0",
                "risk-team",
                "ACTIVE",
                List.of(
                        documented,
                        function("risk.added", "risk", "integer")
                ),
                List.of()
        );

        OperatorLibraryDiff diff = OperatorLibraryDiff.betweenSnapshots(
                "risk-functions", 1, baseLibrary, 2, targetLibrary);

        assertThat(diff.libraryChanges())
                .filteredOn(change -> change.field().startsWith("builtInFunctions/"))
                .extracting(OperatorLibraryDiff.LibraryChange::field,
                        OperatorLibraryDiff.LibraryChange::risk,
                        OperatorLibraryDiff.LibraryChange::summary)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                "builtInFunctions/risk.removed",
                                OperatorDefinitionChangeSummary.RISK_BREAKING_SCHEMA,
                                "callable function 'risk.removed' removed"),
                        org.assertj.core.groups.Tuple.tuple(
                                "builtInFunctions/risk.documented",
                                OperatorDefinitionChangeSummary.RISK_METADATA,
                                "callable function 'risk.documented' metadata changed"),
                        org.assertj.core.groups.Tuple.tuple(
                                "builtInFunctions/risk.added",
                                OperatorDefinitionChangeSummary.RISK_COMPATIBLE_SCHEMA,
                                "callable function 'risk.added' added")
                );
    }

    @Test
    void ignoresCatalogLibraryOwnerMetadataWhenClassifyingOperatorSurfaceChange() {
        OperatorDefinition previous = operator("risk:eligibility", "integer");
        OperatorDefinition replacement = withLibraryOwner("risk-policy",
                operator("risk:eligibility", "string"));

        OperatorDefinitionChangeSummary.ChangeReport report = OperatorDefinitionChangeSummary.analyze(
                previous,
                replacement
        );

        assertThat(report.risk()).isEqualTo(OperatorDefinitionChangeSummary.RISK_BREAKING_SCHEMA);
        assertThat(report.categories()).containsExactly(OperatorDefinitionChangeSummary.RISK_BREAKING_SCHEMA);
        assertThat(report.summary()).contains("input port 'inputs' schema changed")
                .doesNotContain("source metadata changed");
    }

    private static OperatorLibrary library(String version, List<OperatorDefinition> operators) {
        return new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "risk-policy",
                "Risk policy operators",
                version,
                "risk-team",
                "ACTIVE",
                operators
        );
    }

    private static OperatorLibrary functionLibrary(String version,
                                                    OperatorLibrary.BuiltInFunction function) {
        return new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "risk-functions",
                "Risk functions",
                version,
                "risk-team",
                "ACTIVE",
                List.of(function),
                List.of()
        );
    }

    private static OperatorLibrary.BuiltInFunction function(String name,
                                                            String namespace,
                                                            String returnType) {
        return new OperatorLibrary.BuiltInFunction(
                name,
                namespace,
                name,
                "Risk expression helper.",
                "risk",
                List.of(new OperatorLibrary.Signature(
                        name + "(value)",
                        "",
                        List.of(new OperatorLibrary.Parameter("value", "any", null, false, false, "")),
                        new OperatorLibrary.ReturnValue(returnType, null, "")
                )),
                List.of(name + "(inputs.value)")
        );
    }

    private static OperatorDefinition operator(String operatorRef, String scoreType) {
        return new OperatorDefinition(
                "bloge.visualOperator.v1",
                operatorRef,
                "1.0.0",
                new OperatorDefinition.Display(operatorRef, "Risk operator.", List.of("risk")),
                new OperatorDefinition.Source("user-library", "", "", "", true),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("inputs",
                                SchemaEnvelope.object(Map.of("score", Map.of("type", scoreType)),
                                        List.of("score")),
                                true,
                                "Risk input.")),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(Map.of("eligible", Map.of("type", "boolean")), List.of()),
                                true,
                                "Risk output."))
                ),
                SchemaEnvelope.opaque(),
                OperatorDefinition.Capabilities.pure(),
                OperatorDefinition.Policy.unrestricted(),
                new OperatorDefinition.Lowering("design", "", Map.of()),
                List.of()
        );
    }

    private static OperatorDefinition withLibraryOwner(String libraryId, OperatorDefinition operator) {
        OperatorDefinition.Source source = operator.source();
        return new OperatorDefinition(
                operator.schemaVersion(),
                operator.operatorRef(),
                operator.operatorVersion(),
                operator.fingerprint(),
                operator.display(),
                new OperatorDefinition.Source(
                        source.kind(),
                        source.resourceId(),
                        source.method(),
                        source.urlTemplate(),
                        source.virtual(),
                        libraryId
                ),
                operator.ports(),
                operator.configSchema(),
                operator.capabilities(),
                operator.policy(),
                operator.lowering(),
                operator.diagnostics(),
                operator.runtimeReadiness()
        );
    }
}
