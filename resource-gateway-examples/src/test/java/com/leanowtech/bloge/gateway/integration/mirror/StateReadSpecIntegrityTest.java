package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StateReadSpecIntegrityTest {
    private final ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();
    private final StateModel model = StateModelIntegrity.seal(
            mapper, StatefulMirrorProtocolTest.stateModel());

    @Test
    void sealsAnExactBusinessKeyLookupAndResponseProjection() {
        StateReadSpec sealed = StateReadSpecIntegrity.seal(mapper, queryOrder());

        StateReadSpecIntegrity.verify(mapper, sealed, model);

        assertThat(sealed.fingerprint()).matches("sha256:[a-f0-9]{64}");
        assertThat(StateReadSpecIntegrity.reference(sealed))
                .isEqualTo(new MirrorArtifactRef(
                        "STATE_READ_SPEC", "query-order", 1, sealed.fingerprint()));
    }

    @Test
    void rejectsUnknownBusinessKeyAndNonInputLookupExpression() {
        StateReadSpec unknownKey = copy(
                "missing-key",
                List.of(BoundedStateExpression.input("/orderId")),
                queryOrder().responseProjection());
        StateReadSpec nondeterministicLookup = copy(
                "order-id",
                List.of(new BoundedStateExpression(
                        BoundedStateExpression.Operator.LOGICAL_TIME,
                        null, "", "", List.of(), Map.of())),
                queryOrder().responseProjection());

        assertThatThrownBy(() -> StateReadSpecIntegrity.verify(
                mapper, StateReadSpecIntegrity.seal(mapper, unknownKey), model))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("business key");
        assertThatThrownBy(() -> StateReadSpecIntegrity.seal(
                mapper, nondeterministicLookup))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lookup");
    }

    @Test
    void rejectsResponseProjectionThatReferencesAnotherEntityAlias() {
        StateReadSpec invalid = copy(
                "order-id",
                List.of(BoundedStateExpression.input("/orderId")),
                new BoundedStateExpression(
                        BoundedStateExpression.Operator.ENTITY_POINTER,
                        null, "/orderId", "another", List.of(), Map.of()));

        assertThatThrownBy(() -> StateReadSpecIntegrity.seal(mapper, invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("result");
    }

    private StateReadSpec queryOrder() {
        return copy(
                "order-id",
                List.of(BoundedStateExpression.input("/orderId")),
                new BoundedStateExpression(
                        BoundedStateExpression.Operator.ENTITY_POINTER,
                        null, "", StateReadSpec.RESULT_ALIAS, List.of(), Map.of()));
    }

    private StateReadSpec copy(
            String keyName,
            List<BoundedStateExpression> components,
            BoundedStateExpression projection) {
        return new StateReadSpec(
                StateReadSpec.SCHEMA_VERSION,
                "query-order",
                1,
                "",
                StatefulMirrorProtocolTest.scope(),
                StatefulMirrorProtocolTest.capabilityRef("query-order"),
                StateModelIntegrity.reference(model),
                "order",
                keyName,
                components,
                projection,
                StatefulMirrorProtocolTest.ownerProvenance(),
                CapabilitySnapshot.Lifecycle.ACTIVE,
                Instant.parse("2026-07-24T02:00:00Z"));
    }
}
