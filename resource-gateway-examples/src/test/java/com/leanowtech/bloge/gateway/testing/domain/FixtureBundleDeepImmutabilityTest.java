package com.leanowtech.bloge.gateway.testing.domain;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FixtureBundleDeepImmutabilityTest {

    @Test
    void recursivelyCopiesAndFreezesEveryJsonContainerOwnedByAFixture() {
        List<Object> metadataItems = new ArrayList<>(List.of("original"));
        Map<String, Object> metadataNested = new LinkedHashMap<>(Map.of("items", metadataItems));
        Map<String, Object> metadata = new LinkedHashMap<>(Map.of("nested", metadataNested));

        List<Object> returnItems = new ArrayList<>(List.of("approved"));
        Map<String, Object> returnValue = new LinkedHashMap<>(Map.of("items", returnItems));
        Map<String, Object> matchValue = new LinkedHashMap<>(Map.of("state", "ready"));
        Map<String, Object> schemaProperty = new LinkedHashMap<>(Map.of("type", "string"));
        Map<String, Object> schema = new LinkedHashMap<>(Map.of("state", schemaProperty));
        Map<String, Object> expected = new LinkedHashMap<>(Map.of(
                "decisions", new ArrayList<>(List.of("ALLOW"))));

        FixtureRule.Match match = new FixtureRule.Match(
                new LinkedHashMap<>(Map.of("subject", matchValue)),
                new LinkedHashMap<>(Map.of("/subject", matchValue)),
                List.of(), List.of(), schema, "", Map.of());
        FixtureRule rule = new FixtureRule("", "controlled",
                FixtureRule.Selector.node("subject").matching(match),
                FixtureRule.Behavior.returning(returnValue),
                FixtureRule.Consumption.once(), FixtureRule.SchemaCheck.strict());
        FixtureBundle bundle = new FixtureBundle("", "fixture-a", 1,
                "sha256:" + "a".repeat(64), "INTERNAL", null, null,
                List.of(rule), List.of(new FixtureBundle.Assertion(
                "OUTPUT_PATH", "subject", "/decision", "EQUALS", expected, null)), metadata);

        metadataItems.add("tampered");
        metadataNested.put("late", true);
        returnItems.add("DENY");
        returnValue.put("late", true);
        matchValue.put("state", "changed");
        schemaProperty.put("type", "number");
        expected.put("late", true);

        assertThat(asList(asMap(asMap(bundle.metadata()).get("nested")).get("items")))
                .containsExactly("original");
        assertThat(asMap(bundle.metadata()).get("late")).isNull();
        assertThat(asList(asMap(bundle.rules().getFirst().behavior().value()).get("items")))
                .containsExactly("approved");
        assertThat(asMap(bundle.rules().getFirst().selector().match().canonicalInput())
                .get("subject")).isEqualTo(Map.of("state", "ready"));
        assertThat(asMap(bundle.rules().getFirst().selector().match().schema()).get("state"))
                .isEqualTo(Map.of("type", "string"));
        assertThat(bundle.assertions().getFirst().expected())
                .isEqualTo(Map.of("decisions", List.of("ALLOW")));

        assertThatThrownBy(() -> asMap(bundle.metadata()).put("late", true))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> asList(asMap(bundle.rules().getFirst().behavior().value())
                .get("items")).add("late"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> asMap(bundle.assertions().getFirst().expected())
                .put("late", true))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsCyclicJsonContainersWithoutRecursingUntilStackExhaustion() {
        Map<String, Object> cycle = new LinkedHashMap<>();
        cycle.put("self", cycle);

        assertThatThrownBy(() -> new FixtureBundle("", "fixture-cycle", 1,
                "sha256:" + "a".repeat(64), "INTERNAL", null, null,
                List.of(), List.of(), cycle))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Fixture JSON value contains a cycle");
    }

    @Test
    void rejectsNonJsonObjectKeysAndExcessiveNesting() {
        Map<Object, Object> invalidKeys = new LinkedHashMap<>();
        invalidKeys.put(42, "not-a-json-object");
        assertThatThrownBy(() -> FixtureRule.Behavior.returning(invalidKeys))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Fixture JSON object key must be a string");

        Object nested = "leaf";
        for (int depth = 0; depth < 129; depth++) {
            nested = List.of(nested);
        }
        Object excessiveNesting = nested;
        assertThatThrownBy(() -> FixtureRule.Behavior.returning(excessiveNesting))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Fixture JSON value exceeds maximum nesting depth");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object value) {
        return (List<Object>) value;
    }
}
