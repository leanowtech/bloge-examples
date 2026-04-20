package com.leanowtech.bloge.graphengine.ai.prompt;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FewShotExampleSelectorTest {

    @Test
    void selectPrefersStateMachineExamplesForStateMachineRequests() {
        FewShotExampleSelector selector = new FewShotExampleSelector(
                PromptResourceLoader.loadRequired("ai/few-shot-examples.md")
        );

        List<FewShotExample> selected = selector.select(
                "Create a state machine with timeout transitions for an order lifecycle",
                2
        );

        assertFalse(selected.isEmpty());
        assertTrue(selected.stream().anyMatch(example ->
                example.category().toLowerCase().contains("state machine")
                        && example.dslSource().contains("state_machine")));
    }
}
