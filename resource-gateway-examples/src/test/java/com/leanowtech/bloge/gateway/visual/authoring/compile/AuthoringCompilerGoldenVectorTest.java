package com.leanowtech.bloge.gateway.visual.authoring.compile;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.leanowtech.bloge.gateway.visual.authoring.model.AuthoringCompileResult;
import com.leanowtech.bloge.gateway.visual.authoring.model.AuthoringDiagnostic;
import com.leanowtech.bloge.gateway.visual.authoring.model.VisualLibraryAuthoringDocument;
import com.leanowtech.bloge.gateway.visual.authoring.parse.AuthoringDocumentDecoder;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibraryValidator;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.assertj.core.api.SoftAssertions;
import org.assertj.core.api.junit.jupiter.SoftAssertionsExtension;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(SoftAssertionsExtension.class)
class AuthoringCompilerGoldenVectorTest {

    private final ObjectMapper yaml = new YAMLMapper().findAndRegisterModules();
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true);
    private final AuthoringDocumentDecoder decoder = new AuthoringDocumentDecoder();
    private final AuthoringCompiler compiler =
            new AuthoringCompiler(json, new OperatorLibraryValidator());

    @Test
    @Timeout(10)
    void sharedGoldenVectorsRemainByteStable(SoftAssertions softly) throws Exception {
        GoldenSuite suite = suite();

        assertThat(suite.schemaVersion())
                .isEqualTo("bloge.visualLibraryAuthoringGoldenSuite.v1");
        assertThat(suite.compilerVersion()).isEqualTo(AuthoringCompiler.COMPILER_VERSION);
        assertThat(suite.archetypeCatalogVersion()).isEqualTo(OperatorArchetypeRegistry.VERSION);
        assertThat(suite.vectors()).hasSize(20);
        Set<String> names = new HashSet<>();

        for (GoldenVector vector : suite.vectors()) {
            assertThat(names.add(vector.name()))
                    .as("unique vector name %s", vector.name())
                    .isTrue();
            AuthoringDocumentDecoder.DecodeResult decoded = decoder.decode(
                    vector.source().getBytes(StandardCharsets.UTF_8));
            assertThat(decoded.failure())
                    .as("decode %s", vector.name())
                    .isNull();

            AuthoringCompileResult first = compiler.compile(decoded.document());
            AuthoringCompileResult second = compiler.compile(decoded.document());
            softly.assertThat(actual(first))
                    .as(vector.name())
                    .isEqualTo(vector.expected());
            softly.assertThat(json.writeValueAsBytes(first.canonicalLibrary()))
                    .as("%s canonical bytes", vector.name())
                    .containsExactly(json.writeValueAsBytes(second.canonicalLibrary()));
            softly.assertThat(first.sourceMap())
                    .as("%s source map", vector.name())
                    .containsExactlyElementsOf(second.sourceMap());
            softly.assertThat(first.diagnostics())
                    .as("%s diagnostics", vector.name())
                    .containsExactlyElementsOf(second.diagnostics());
        }
    }

    private Expected actual(AuthoringCompileResult result) {
        return new Expected(
                result.importable(),
                result.readiness().state(),
                result.diagnostics().stream()
                        .map(AuthoringDiagnostic::code)
                        .toList(),
                result.authoringFingerprint(),
                result.compileFingerprint(),
                result.canonicalFingerprint(),
                VisualBundleFingerprint.fromCanonicalValue(
                        json, result.sourceMap(), 2 * 1024 * 1024)
        );
    }

    private GoldenSuite suite() throws Exception {
        try (InputStream input = getClass().getResourceAsStream(
                "/visual-authoring/golden-vectors.yaml")) {
            assertThat(input).isNotNull();
            return yaml.readValue(input, GoldenSuite.class);
        }
    }

    private record GoldenSuite(
            String schemaVersion,
            String compilerVersion,
            String archetypeCatalogVersion,
            List<GoldenVector> vectors
    ) {
    }

    private record GoldenVector(
            String name,
            String source,
            Expected expected
    ) {
    }

    private record Expected(
            boolean importable,
            String readinessState,
            List<String> diagnosticCodes,
            String authoringFingerprint,
            String compileFingerprint,
            String canonicalFingerprint,
            String sourceMapFingerprint
    ) {
        private Expected {
            diagnosticCodes = diagnosticCodes == null ? List.of() : List.copyOf(diagnosticCodes);
        }
    }
}
