package com.leanowtech.bloge.gateway.testing.function;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FunctionControlAssetCodecTest {
    private static final String TARGET = "sha256:" + "a".repeat(64);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void governedAssetRoundTripsExplicitNullWithoutExposingPayloadInToString() {
        FunctionControlRule rule = new FunctionControlRule(
                "return-null",
                new FunctionControlRule.Selector("/root", "node", "clock", 1, 2),
                List.of(), FunctionControlRule.Behavior.RETURN, null, "", Duration.ZERO,
                FunctionControlRule.Consumption.exactly(1), false, 0);
        FunctionControlAsset asset = new FunctionControlAsset(TARGET, List.of(declaration()), List.of(rule));

        ObjectNode encoded = FunctionControlAssetCodec.encode(MAPPER, asset);
        FunctionControlAsset decoded = FunctionControlAssetCodec.decode(MAPPER, encoded);

        assertThat(decoded.assetFingerprint()).isEqualTo(asset.assetFingerprint());
        assertThat(decoded.targetFingerprint()).isEqualTo(TARGET);
        assertThat(decoded.rules().getFirst().returnValueProvided()).isTrue();
        assertThat(decoded.rules().getFirst().returnValueFingerprint()).isNotBlank();
        assertThat(asset.toString()).doesNotContain("secret");
        assertThatThrownBy(() -> decoded.rules().add(rule)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void tamperingOrUnknownFieldsFailsClosed() {
        FunctionControlAsset asset = new FunctionControlAsset(
                TARGET, List.of(declaration()), List.of(new FunctionControlRule(
                        "return", new FunctionControlRule.Selector("/root", "node", "clock", 1, 2),
                        FunctionControlRule.Behavior.RETURN, "ok", "", Duration.ZERO,
                        FunctionControlRule.Consumption.exactly(1), false, 0)));
        ObjectNode encoded = FunctionControlAssetCodec.encode(MAPPER, asset);
        encoded.put("assetFingerprint", "sha256:" + "b".repeat(64));
        assertThatThrownBy(() -> FunctionControlAssetCodec.decode(MAPPER, encoded))
                .isInstanceOf(FunctionControlException.class)
                .hasMessage("RG.FUNCTION.INVALID_INPUT");

        ObjectNode unknown = FunctionControlAssetCodec.encode(MAPPER, asset);
        unknown.put("payload", "secret-payload");
        assertThatThrownBy(() -> FunctionControlAssetCodec.decode(MAPPER, unknown))
                .isInstanceOf(FunctionControlException.class)
                .hasMessage("RG.FUNCTION.INVALID_INPUT")
                .hasMessageNotContaining("secret-payload");
    }

    private static FunctionLibraryDeclaration declaration() {
        return new FunctionLibraryDeclaration(
                "clock", true, Set.of(), FunctionEffect.PURE_COMPUTATION,
                Map.of(), Map.of("type", "string"));
    }
}
