package com.leanowtech.bloge.gateway.testing.world;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorldStateSpecTest {
    @Test
    void normalizesOrderAndProducesStableFingerprint() {
        StateKeySpec balance = key("/balance", StateKeySpec.Access.WRITE, 100);
        StateKeySpec status = key("/status", StateKeySpec.Access.READ_WRITE, "OPEN");
        WorldStateSpec left = StateSpecV2.of(List.of(status, balance));
        WorldStateSpec right = StateSpecV2.of(List.of(balance, status));

        assertThat(left).isEqualTo(right);
        assertThat(left.fingerprint()).isEqualTo(right.fingerprint());
        assertThat(left.declarations()).extracting(StateKeySpec::key)
                .containsExactly("/balance", "/status");
    }

    @Test
    void enforcesConsistentKeysAndOneNonBlankWriterPerKey() {
        assertThatThrownBy(() -> new StateKeySpec("balance", StateKeySpec.Access.WRITE,
                Map.of("type", "integer"), 100)).isInstanceOf(WorldModelException.class);
        assertThatThrownBy(() -> new StateKeySpec("/balance", StateKeySpec.Access.WRITE,
                Map.of("type", "bad"), 100)).isInstanceOf(WorldModelException.class);
        assertThatThrownBy(() -> new StateKeySpec("/balance", StateKeySpec.Access.WRITE,
                Map.of("type", "integer"), "bad"))
                .isInstanceOf(WorldModelException.class);
    }

    @Test
    void normalizesEscapedPointersAndRejectsInvalidOrPrefixAmbiguousAssets() {
        StateKeySpec escaped = key("/account~1balance", StateKeySpec.Access.WRITE, 1);
        assertThat(escaped.key()).isEqualTo("/account~1balance");
        assertThat(StatePointer.decode(escaped.key())).containsExactly("account/balance");
        assertThat(StatePointer.encode(StatePointer.decode(escaped.key())))
                .isEqualTo("/account~1balance");
        assertThatThrownBy(() -> key("/account~2balance", StateKeySpec.Access.WRITE, 1))
                .isInstanceOf(WorldModelException.class);
        assertThatThrownBy(() -> StateSpecV2.of(List.of(
                        key("/account", StateKeySpec.Access.WRITE, 1),
                        key("/account/balance", StateKeySpec.Access.WRITE, 1))))
                .isInstanceOf(WorldModelException.class);
    }

    @Test
    void acceptsNullDefaultsAndComplexSchemasThroughSharedValidator() {
        StateKeySpec nullable = new StateKeySpec("/optional", StateKeySpec.Access.WRITE,
                Map.of("type", "null"), null);
        assertThat(nullable.accepts(null)).isTrue();
        assertThat(nullable.fingerprintMaterial()).containsEntry("defaultValue", null);
        assertThat(StateSpecV2.of(List.of(nullable)).fingerprint()).startsWith("sha256:");

        Map<String, Object> item = Map.of("type", "object",
                "properties", Map.of("code", Map.of("type", "string")),
                "required", List.of("code"), "additionalProperties", false);
        Map<String, Object> schema = Map.of("type", "object",
                "properties", Map.of("items", Map.of("type", "array", "items", item)),
                "required", List.of("items"), "additionalProperties", false);
        Object validDefault = Map.of("items", List.of(Map.of("code", "A")));
        StateKeySpec complex = new StateKeySpec("/catalog", StateKeySpec.Access.WRITE, schema, validDefault);
        assertThat(complex.accepts(validDefault)).isTrue();
        assertThatThrownBy(() -> new StateKeySpec("/catalog", StateKeySpec.Access.WRITE, schema,
                Map.of("items", List.of(Map.of("code", 1)))))
                .isInstanceOf(WorldModelException.class);
    }

    @Test
    void rejectsStateStructuresBeyondTheS2DepthLimit() {
        Map<String, Object> nested = Map.of("type", "null");
        for (int i = 0; i < StateSpecV2.MAX_DEPTH + 2; i++) {
            nested = Map.of("type", "array", "items", nested);
        }
        Map<String, Object> tooDeep = nested;
        assertThatThrownBy(() -> new StateKeySpec("/deep", StateKeySpec.Access.WRITE, tooDeep, null))
                .isInstanceOf(WorldModelException.class);
    }

    @Test
    void v2RoundTripRevalidatesFingerprintAndLegacyEmptyRemainsCompatible() {
        StateSpecV2 state = StateSpecV2.of(List.of(key("/balance", StateKeySpec.Access.WRITE, 100)));
        StateSpecV2 wire = new StateSpecV2(state.schemaVersion(), state.keys());
        assertThat(wire).isEqualTo(state);
        assertThat(StateSpec.empty()).isEqualTo(StateSpec.empty());
        assertThat(StateSpec.empty().keys()).isEmpty();
        assertThat(StateSpec.empty().defaults()).isEmpty();
        assertThat(Scenario.WorldStateInit.EMPTY.isEmpty()).isTrue();
        assertThat(Scenario.WorldStateInit.of(Map.of("/balance", 0)).overrides())
                .containsEntry("/balance", 0);
        assertThatThrownBy(() -> new StateSpecV2(StateSpecV2.SCHEMA_VERSION, List.of()))
                .isInstanceOf(WorldModelException.class);
        assertThatThrownBy(() -> Scenario.WorldStateInit.of(null))
                .isInstanceOf(ScenarioException.class);
    }

    @Test
    void overrideMustUseDeclaredKeysAndCompatibleValueShapes() {
        WorldStateSpec declaration = StateSpecV2.of(List.of(key("/balance", StateKeySpec.Access.WRITE, 100)));
        declaration.validateOverrides(Map.of("/balance", 0));
        assertThatThrownBy(() -> declaration.validateOverrides(Map.of("/missing", 1)))
                .isInstanceOf(WorldModelException.class);
        assertThatThrownBy(() -> declaration.validateOverrides(Map.of("/balance", "bad")))
                .isInstanceOf(WorldModelException.class);
    }

    private static StateKeySpec key(String name, StateKeySpec.Access access, Object value) {
        String type = value instanceof Integer ? "integer" : "string";
        return new StateKeySpec(name, access, Map.of("type", type), value);
    }
}
