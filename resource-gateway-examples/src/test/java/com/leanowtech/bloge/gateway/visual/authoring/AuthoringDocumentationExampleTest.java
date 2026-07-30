package com.leanowtech.bloge.gateway.visual.authoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.authoring.compile.AuthoringCompiler;
import com.leanowtech.bloge.gateway.visual.authoring.model.AuthoringCompileResult;
import com.leanowtech.bloge.gateway.visual.authoring.parse.AuthoringDocumentDecoder;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibraryValidator;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AuthoringDocumentationExampleTest {

    private final AuthoringDocumentDecoder decoder = new AuthoringDocumentDecoder();
    private final AuthoringCompiler compiler = new AuthoringCompiler(
            new ObjectMapper().findAndRegisterModules(),
            new OperatorLibraryValidator()
    );

    @Test
    void customerServiceExampleCompilesAsAUsableMixedLibrary() throws Exception {
        AuthoringCompileResult result = compile("customer-service-library-authoring.yaml");

        assertThat(result.importable()).isTrue();
        assertThat(result.canonicalLibrary().operators()).hasSize(2);
        assertThat(result.canonicalLibrary().builtInFunctions()).hasSize(2);
        assertThat(result.readiness().state()).isEqualTo("DESIGN_READY");
    }

    @Test
    void functionOnlyExampleCompilesWithoutSyntheticOperators() throws Exception {
        AuthoringCompileResult result = compile("function-only-library-authoring.yaml");

        assertThat(result.importable()).isTrue();
        assertThat(result.canonicalLibrary().operators()).isEmpty();
        assertThat(result.canonicalLibrary().builtInFunctions()).hasSize(3);
        assertThat(result.readiness().state()).isEqualTo("DESIGN_READY");
    }

    private AuthoringCompileResult compile(String name) throws Exception {
        byte[] source = Files.readAllBytes(Path.of(
                "..", "docs", "examples", name));
        AuthoringDocumentDecoder.DecodeResult decoded = decoder.decode(source);
        assertThat(decoded.failure()).isNull();
        return compiler.compile(decoded.document());
    }
}
