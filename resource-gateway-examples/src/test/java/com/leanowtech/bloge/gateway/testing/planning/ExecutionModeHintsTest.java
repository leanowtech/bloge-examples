package com.leanowtech.bloge.gateway.testing.planning;

import com.leanowtech.bloge.gateway.testing.domain.ExecutionMode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExecutionModeHintsTest {

    @Test
    void freezesMultipleExactHintsAndBuilderChangesDoNotLeak() {
        ExecutionModeHints.Builder builder = ExecutionModeHints.builder()
                .schemaStandin(" /root/a#PRIMARY ", " rule-a ")
                .schemaStandin("/root/b#PRIMARY", "rule-b");
        ExecutionModeHints hints = builder.build();
        builder.schemaStandin("/root/c#PRIMARY", "rule-c");

        assertThat(hints.modeFor("/root/a#PRIMARY", "rule-a"))
                .contains(ExecutionMode.SCHEMA_STANDIN);
        assertThat(hints.modeFor("/root/b#PRIMARY", "rule-b"))
                .contains(ExecutionMode.SCHEMA_STANDIN);
        assertThat(hints.modeFor("/root/c#PRIMARY", "rule-c")).isEmpty();
        assertThat(hints.modeFor(" /root/a#PRIMARY ", " rule-a "))
                .contains(ExecutionMode.SCHEMA_STANDIN);
    }

    @Test
    void rejectsBlankAndDuplicatePairs() {
        assertThatThrownBy(() -> ExecutionModeHints.builder().schemaStandin(" ", "rule"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExecutionModeHints.builder().schemaStandin("site", null))
                .isInstanceOf(IllegalArgumentException.class);

        ExecutionModeHints.Builder builder = ExecutionModeHints.builder()
                .schemaStandin("site", "rule");
        assertThatThrownBy(() -> builder.schemaStandin(" site ", " rule "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void legacySingleHintFactoryUsesTheSameFrozenSemantics() {
        ExecutionModeHints hints = ExecutionModeHints.schemaStandin("site", "rule");

        assertThat(hints.modeFor("site", "rule")).contains(ExecutionMode.SCHEMA_STANDIN);
        assertThat(hints.modeFor("", "rule")).isEmpty();
        assertThat(hints.modeFor("site", "")).isEmpty();
    }

    @Test
    void worldDelegateFactoryAndBuilderResolveOnlyTheExactSiteAndRule() {
        ExecutionModeHints factoryHints = ExecutionModeHints.worldDelegate(
                " site#PRIMARY ", " rule ");

        assertThat(factoryHints.modeFor("site#PRIMARY", "rule"))
                .contains(ExecutionMode.WORLD_DELEGATE);
        assertThat(factoryHints.modeFor("other#PRIMARY", "rule")).isEmpty();
        assertThat(factoryHints.modeFor("site#PRIMARY", "other-rule")).isEmpty();

        ExecutionModeHints.Builder builder = ExecutionModeHints.builder()
                .worldDelegate(" site#PRIMARY ", " rule ");
        assertThat(builder.build().modeFor("site#PRIMARY", "rule"))
                .contains(ExecutionMode.WORLD_DELEGATE);
        assertThatThrownBy(() -> builder.worldDelegate("site#PRIMARY", "rule"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
