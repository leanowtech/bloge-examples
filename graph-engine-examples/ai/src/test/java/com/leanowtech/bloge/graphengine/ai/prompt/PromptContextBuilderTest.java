package com.leanowtech.bloge.graphengine.ai.prompt;

import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptContextBuilderTest {

    @Test
    void buildIncludesSyntaxOperatorsExamplesAndRenderedPrompt() {
        DefaultOperatorRegistry registry = new DefaultOperatorRegistry();
        registry.register("echo", new EchoOperator());

        PromptContext context = new PromptContextBuilder(registry)
                .build("Create a simple hello world graph", 2);

        assertFalse(context.syntaxReference().isBlank());
        assertFalse(context.operatorCatalog().isEmpty());
        assertFalse(context.fewShotExamples().isEmpty());
        assertTrue(context.systemPrompt().contains("<bloge-syntax-reference>"));
        assertTrue(context.systemPrompt().contains("<available-operators>"));
    }

    private static final class EchoOperator implements Operator<String, String> {
        @Override
        public String execute(String input, com.leanowtech.bloge.core.operator.OperatorContext ctx) {
            return input;
        }
    }
}
