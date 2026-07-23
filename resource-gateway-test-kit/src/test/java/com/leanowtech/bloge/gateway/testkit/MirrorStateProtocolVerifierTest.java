package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MirrorStateProtocolVerifierTest {

    private final MirrorStateProtocolVerifier verifier =
            new MirrorStateProtocolVerifier();

    @Test
    void independentlyVerifiesThePackagedRefundModelEffectAndInitialSession() {
        JsonNode fixture = CapabilityMirrorProtocol.statefulRefundFixture();
        MirrorStateProtocolVerifier.VerifiedStateModel model =
                verifier.verifyStateModel(fixture.path("stateModel"));
        MirrorStateProtocolVerifier.VerifiedWriteEffect effect =
                verifier.verifyWriteEffect(
                        fixture.path("writeEffect"), fixture.path("stateModel"));
        MirrorStateProtocolVerifier.VerifiedSession session =
                verifier.verifySession(
                        fixture.path("initialState"),
                        fixture.path("stateModel"),
                        List.of(fixture.path("writeEffect")));

        assertThat(model.stateModelId()).isEqualTo("refund-world");
        assertThat(model.entityTypeCount()).isEqualTo(2);
        assertThat(effect.mutationCount()).isEqualTo(2);
        assertThat(session.sessionId()).isEqualTo("refund-session-1");
        assertThat(session.stateRevision()).isZero();
        assertThat(session.entityCount()).isEqualTo(1);
    }

    @Test
    void rejectsNestedFingerprintTamperingWithoutReturningPayloads() {
        JsonNode fixture = CapabilityMirrorProtocol.statefulRefundFixture();
        ObjectNode session = fixture.path("initialState").deepCopy();
        ((ObjectNode) session.path("entities").get(0))
                .put("fingerprint", "sha256:" + "f".repeat(64));

        assertThatThrownBy(() -> verifier.verifySession(
                session,
                fixture.path("stateModel"),
                List.of(fixture.path("writeEffect"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("RG.MIRROR.CLIENT.SESSION_ENTITY_FINGERPRINT_MISMATCH")
                .hasMessageNotContaining("O-100");
    }

    @Test
    void rejectsCrossScopeEffectsAndIncompleteEffectClosure() {
        JsonNode fixture = CapabilityMirrorProtocol.statefulRefundFixture();
        ObjectNode effect = fixture.path("writeEffect").deepCopy();
        ((ObjectNode) effect.path("scope")).put("organizationId", "org-b");
        effect.put("fingerprint", "");
        effect.put("fingerprint", EvidenceVerificationSupport.sha256(effect));

        assertThatThrownBy(() -> verifier.verifyWriteEffect(
                effect, fixture.path("stateModel")))
                .hasMessage("RG.MIRROR.CLIENT.WRITE_EFFECT_SCOPE_MISMATCH");

        ObjectNode wrongKeyArity = fixture.path("writeEffect").deepCopy();
        ArrayNode components = (ArrayNode) wrongKeyArity.at(
                "/mutations/0/businessKeys/0/components");
        components.add(components.get(0).deepCopy());
        wrongKeyArity.put("fingerprint", "");
        wrongKeyArity.put(
                "fingerprint", EvidenceVerificationSupport.sha256(wrongKeyArity));
        assertThatThrownBy(() -> verifier.verifyWriteEffect(
                wrongKeyArity, fixture.path("stateModel")))
                .hasMessage("RG.MIRROR.CLIENT.WRITE_EFFECT_BUSINESS_KEY_MISMATCH");

        assertThatThrownBy(() -> verifier.verifySession(
                fixture.path("initialState"),
                fixture.path("stateModel"),
                List.of()))
                .hasMessage("RG.MIRROR.CLIENT.SESSION_WRITE_EFFECT_CLOSURE_INVALID");
    }

    @Test
    void rejectsUnknownMutationAliasesAndJournalGaps() {
        JsonNode fixture = CapabilityMirrorProtocol.statefulRefundFixture();
        ObjectNode effect = fixture.path("writeEffect").deepCopy();
        ObjectNode projection = effect.objectNode();
        projection.put("operator", "ENTITY_POINTER");
        projection.putNull("literal");
        projection.put("path", "/status");
        projection.put("reference", "missing-alias");
        projection.putArray("arguments");
        projection.putObject("fields");
        effect.set("responseProjection", projection);
        effect.put("fingerprint", "");
        effect.put("fingerprint", EvidenceVerificationSupport.sha256(effect));

        assertThatThrownBy(() ->
                verifier.verifyWriteEffect(effect, fixture.path("stateModel")))
                .hasMessage("RG.MIRROR.CLIENT.STATE_EXPRESSION_ALIAS_INVALID");

        ObjectNode session = fixture.path("initialState").deepCopy();
        session.put("stateRevision", 1);
        assertThatThrownBy(() -> verifier.verifySession(
                session,
                fixture.path("stateModel"),
                List.of(fixture.path("writeEffect"))))
                .hasMessage("RG.MIRROR.CLIENT.SESSION_JOURNAL_CLOSURE_INVALID");
    }

    @Test
    void rejectsUnknownFieldsAndExpressionsBeyondTheDepthBudget() {
        JsonNode fixture = CapabilityMirrorProtocol.statefulRefundFixture();
        ObjectNode model = fixture.path("stateModel").deepCopy();
        model.put("unexpected", true);

        assertThatThrownBy(() -> verifier.verifyStateModel(model))
                .hasMessage("RG.MIRROR.CLIENT.STATE_MODEL_SCHEMA_INVALID");

        ObjectNode openModel = fixture.path("stateModel").deepCopy();
        ((ObjectNode) openModel.at("/entityTypes/0/schema/schema"))
                .put("additionalProperties", true);
        openModel.put("fingerprint", "");
        openModel.put("fingerprint", EvidenceVerificationSupport.sha256(openModel));
        assertThatThrownBy(() -> verifier.verifyStateModel(openModel))
                .hasMessage("RG.MIRROR.CLIENT.STATE_MODEL_ENTITY_SCHEMA_UNSAFE");

        ObjectNode effect = fixture.path("writeEffect").deepCopy();
        ObjectNode expression = (ObjectNode) effect.path("responseProjection");
        for (int depth = 0; depth < 34; depth++) {
            ObjectNode nested = expression.objectNode();
            nested.put("operator", "NOT_NULL");
            nested.putNull("literal");
            nested.put("path", "");
            nested.put("reference", "");
            ArrayNode arguments = nested.putArray("arguments");
            arguments.add(expression.deepCopy());
            nested.putObject("fields");
            expression = nested;
        }
        effect.set("responseProjection", expression);
        effect.put("fingerprint", "");
        effect.put("fingerprint", EvidenceVerificationSupport.sha256(effect));

        ObjectNode tooDeep = effect;
        assertThatThrownBy(() -> verifier.verifyWriteEffect(
                tooDeep, fixture.path("stateModel")))
                .hasMessage("RG.MIRROR.CLIENT.STATE_EXPRESSION_BOUNDS_EXCEEDED");
    }
}
