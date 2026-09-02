package com.leanowtech.bloge.gateway.visualadapter.authoring.simulation;

import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;
import com.leanowtech.bloge.gateway.visual.authoring.simulation.ExactFixtureSubjectRefV2;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibrary;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibraryRegistry;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibraryRevision;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CatalogComponentSimulationAuthorityV2Test {
    private static final AuthoringScope SCOPE = new AuthoringScope("tenant", "project", "test");

    @Test
    void resolvesOnlyTheExactOperatorRevisionAndContractFingerprint() {
        OperatorDefinition operator = operator();
        OperatorLibrary library = new OperatorLibrary(null, "risk-library", "Risk", "3.0.0",
                "risk-team", "ACTIVE", List.of(operator));
        OperatorLibraryRegistry registry = mock(OperatorLibraryRegistry.class);
        when(registry.findRevision("risk-library", 3)).thenReturn(Optional.of(
                new OperatorLibraryRevision(OperatorLibraryRevision.SCHEMA_VERSION, "risk-library", 3,
                        OperatorLibraryRevision.ACTION_REPLACE, Instant.EPOCH, library, null, null)));
        CatalogComponentSimulationAuthorityV2 authority = new CatalogComponentSimulationAuthorityV2(registry);
        ExactFixtureSubjectRefV2.OperatorVersion exact = new ExactFixtureSubjectRefV2.OperatorVersion(
                "risk-library", 3, operator.operatorRef(), operator.fingerprint());

        var resolved = authority.resolve(SCOPE, exact);

        assertThat(resolved).isPresent();
        assertThat(resolved.orElseThrow().input().required()).containsExactly("customerId");
        assertThat(resolved.orElseThrow().output().properties()).containsKey("score");
        assertThat(authority.resolve(SCOPE, new ExactFixtureSubjectRefV2.OperatorVersion(
                "risk-library", 3, operator.operatorRef(), "sha256:" + "0".repeat(64)))).isEmpty();
        assertThat(authority.resolve(SCOPE, new ExactFixtureSubjectRefV2.OperatorVersion(
                "risk-library", 4, operator.operatorRef(), operator.fingerprint()))).isEmpty();
    }

    @Test
    void publishesAnExactBuiltinSignatureAndRuntimeGeneration() {
        CatalogComponentSimulationAuthorityV2 authority = new CatalogComponentSimulationAuthorityV2(
                OperatorLibraryRegistry.empty());
        ExactFixtureSubjectRefV2.BuiltinFunctionVersion subject =
                CatalogComponentSimulationAuthorityV2.builtinSubject("coalesce");

        assertThat(authority.resolve(SCOPE, subject)).isPresent();
        assertThat(authority.resolve(SCOPE, new ExactFixtureSubjectRefV2.BuiltinFunctionVersion(
                subject.catalogId(), subject.catalogRevision(), subject.functionName(),
                "sha256:" + "0".repeat(64), subject.runtimeFingerprint()))).isEmpty();
        assertThat(authority.resolve(SCOPE, new ExactFixtureSubjectRefV2.BuiltinFunctionVersion(
                subject.catalogId(), subject.catalogRevision(), subject.functionName(),
                subject.signatureFingerprint(), "sha256:" + "1".repeat(64)))).isEmpty();
    }

    private static OperatorDefinition operator() {
        SchemaEnvelope text = new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12",
                Map.of("type", "string"));
        SchemaEnvelope number = new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12",
                Map.of("type", "number"));
        return new OperatorDefinition(null, "risk:score", "3.0.0", "",
                new OperatorDefinition.Display("Score", "", List.of()),
                new OperatorDefinition.Source("native", "", "", "", false, "risk-library"),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("customerId", text, true, "")),
                        List.of(new OperatorDefinition.Port("score", number, true, ""))),
                SchemaEnvelope.opaque(), OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("native", "risk:score", Map.of()), List.of());
    }
}
