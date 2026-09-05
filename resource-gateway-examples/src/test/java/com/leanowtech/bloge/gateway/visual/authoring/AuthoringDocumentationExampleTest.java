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
    void cancellationDemoUsesMultiTurnBusinessIntentToDefineFeaturesBeforeRules() throws Exception {
        String script = Files.readString(Path.of(
                "..", "docs", "resource-gateway-agent-tdd-demo-script.md"));

        int inventory = script.indexOf("### 第一轮：提出目标并盘点能力库");
        int defineResponsibility = script.indexOf("### 第二轮：定义「责任方」");
        int defineFreeWindow = script.indexOf("### 第三轮：定义「免费取消时段」");
        int confirmFeatures = script.indexOf("### 第四轮：确认三项事实并形成契约");
        int discoverFacts = script.indexOf("建立“责任方”“是否在免费取消时段”“当前选择的争议订单”三项事实契约");
        int defineRules = script.indexOf("建立含四条规则和兜底分支的场景");
        int approveGolden = script.indexOf("提议四条完整 GOLDEN 标准案例");

        assertThat(inventory).isNotNegative();
        assertThat(defineResponsibility).isGreaterThan(inventory);
        assertThat(defineFreeWindow).isGreaterThan(defineResponsibility);
        assertThat(confirmFeatures).isGreaterThan(defineFreeWindow);
        assertThat(discoverFacts).isGreaterThan(confirmFeatures);
        assertThat(defineRules).isGreaterThan(discoverFacts);
        assertThat(approveGolden).isGreaterThan(defineRules);
        assertThat(script).contains(
                "这一幕不是一次性填写表单",
                "读取当前作用域的业务能力库概览",
                "三项事实的业务定义卡",
                "不能只凭名称相似复用",
                "不能把尚未判责、资料缺失或多个结论冲突当成无人担责",
                "判断的是取消发生当时，而不是客服现在处理工单的时间",
                "客服切换订单后",
                "等待工程接入",
                "待业务定义",
                "缺少系统实现时请建立工程交接",
                "Codex 没有询问接口地址、字段类型、DSL 或 YAML",
                "不能因为写实现尚未完成而让 Codex 自行填一个“看起来像”的实现引用",
                "底层算子库只在这个幕后层出现",
                "工程履约只能补实现，不能修改业务契约",
                "只证明获取实现可运行且结果符合契约形状",
                "不能证明生产系统已经正确实现“选择最新生效判责”或“按取消时点匹配历史政策”");
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
