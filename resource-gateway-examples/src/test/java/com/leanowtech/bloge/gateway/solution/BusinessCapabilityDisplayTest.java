package com.leanowtech.bloge.gateway.solution;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Verifies the bounded, immutable business discovery contract. */
class BusinessCapabilityDisplayTest {

    @Test
    void freezesNormalizedClosedDisplayCollections() {
        ArrayList<String> aliases = new ArrayList<>(List.of("取消归责"));
        BusinessCapabilityDisplay display = new BusinessCapabilityDisplay(
                "", "  取消责任方  ", " 判断取消责任 ", aliases,
                List.of("取消费"), List.of("计算取消费前"), List.of("事故责任"));
        aliases.add("不应泄漏");

        assertThat(display.schemaVersion()).isEqualTo(BusinessCapabilityDisplay.SCHEMA_VERSION);
        assertThat(display.businessName()).isEqualTo("取消责任方");
        assertThat(display.aliases()).containsExactly("取消归责");
    }

    @Test
    void rejectsDuplicateOrUnboundedAliases() {
        assertThatThrownBy(() -> display(List.of("取消归责", " 取消归责 ")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> display(java.util.stream.IntStream.rangeClosed(
                1, BusinessCapabilityDisplay.MAX_VALUES + 1).mapToObj(value -> "别名" + value).toList()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsTechnicalBindingsUrlsAndCredentialLikeMaterial() {
        assertThatThrownBy(() -> display(List.of("https://internal.example/resource")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> display(List.of("bindingRef: tool:private")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> display(List.of("password: should-never-persist")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static BusinessCapabilityDisplay display(List<String> aliases) {
        return new BusinessCapabilityDisplay("", "取消责任方", "判断取消责任", aliases,
                List.of(), List.of(), List.of());
    }
}
