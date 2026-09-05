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
    void cancellationDemoIntroducesBusinessFactsBeforeRulesAndEngineeringHandoffs() throws Exception {
        String script = Files.readString(Path.of(
                "..", "docs", "resource-gateway-agent-tdd-demo-script.md"));

        int discoverFacts = script.indexOf("建立“责任方”“是否在免费取消时段”“当前选择的争议订单”三项事实契约");
        int defineRules = script.indexOf("建立含四条规则和兜底分支的场景");
        int approveGolden = script.indexOf("提议四条 GOLDEN 标准案例");

        assertThat(discoverFacts).isNotNegative();
        assertThat(defineRules).isGreaterThan(discoverFacts);
        assertThat(approveGolden).isGreaterThan(defineRules);
        assertThat(script).contains(
                "先读取当前作用域的业务积木概览",
                "缺少系统实现时请建立工程交接",
                "Codex 没有询问接口地址、字段类型、DSL 或 YAML",
                "不能因为写实现尚未完成而让 Codex自行填一个“看起来像”的实现引用");
        assertThat(script).doesNotContain("ride-cancellation` 契约库");
    }

    private AuthoringCompileResult compile(String name) throws Exception {
        byte[] source = Files.readAllBytes(Path.of(
                "..", "docs", "examples", name));
        AuthoringDocumentDecoder.DecodeResult decoded = decoder.decode(source);
        assertThat(decoded.failure()).isNull();
        return compiler.compile(decoded.document());
    }
}
