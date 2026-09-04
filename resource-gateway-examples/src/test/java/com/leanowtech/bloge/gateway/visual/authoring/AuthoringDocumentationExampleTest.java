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

/** Verifies that checked-in authoring examples remain executable documentation. */
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

    @Test
    void cancellationDemoStartsWithOneCompilableDesignOnlyContractLibrary() throws Exception {
        AuthoringCompileResult result = compile("ride-cancellation-agent-tdd-library.yaml");

        assertThat(result.importable()).isTrue();
        assertThat(result.canonicalLibrary().libraryId()).isEqualTo("ride-cancellation");
        assertThat(result.canonicalLibrary().operators())
                .extracting(operator -> operator.operatorRef())
                .containsExactlyInAnyOrder(
                        "ride:order-lookup",
                        "ride:responsibility-decide",
                        "ride:city-pricing-policy",
                        "ride:compensation-history");
        assertThat(result.canonicalLibrary().operators())
                .allSatisfy(operator -> assertThat(operator.runtimeReadiness().executable()).isFalse());
        assertThat(result.readiness().state()).isEqualTo("DESIGN_READY");
    }

    @Test
    void cancellationDemoRequiresTheContractLibraryBeforeBindingAndComposition() throws Exception {
        String script = Files.readString(Path.of(
                "..", "docs", "resource-gateway-agent-tdd-demo-script.md"));

        int createLibrary = script.indexOf("在平台中创建 `ride-cancellation` 契约库");
        int bindSameLibrary = script.indexOf("更新第 1 幕的同一个 `ride-cancellation` 契约库");
        int composeFromLibrary = script.indexOf("只使用前两幕已经形成并接入的 `ride-cancellation` 契约库");

        assertThat(createLibrary).isNotNegative();
        assertThat(bindSameLibrary).isGreaterThan(createLibrary);
        assertThat(composeFromLibrary).isGreaterThan(bindSameLibrary);
        assertThat(script).contains(
                "没有契约库，不进入第二幕",
                "不要把该 YAML 粘进小李的提示词",
                "前一幕的交接门未通过时不得继续");
        assertThat(script).doesNotContain("并在后台保存为后续设计可复用的业务契约");
    }

    private AuthoringCompileResult compile(String name) throws Exception {
        byte[] source = Files.readAllBytes(Path.of(
                "..", "docs", "examples", name));
        AuthoringDocumentDecoder.DecodeResult decoded = decoder.decode(source);
        assertThat(decoded.failure()).isNull();
        return compiler.compile(decoded.document());
    }
}
