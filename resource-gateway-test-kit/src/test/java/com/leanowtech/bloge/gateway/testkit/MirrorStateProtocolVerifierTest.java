package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MirrorStateProtocolVerifierTest {

    private static final ObjectMapper JSON = new ObjectMapper();
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

    @Test
    void verifiesTheCompleteSessionHttpProtocolWithoutReturningBusinessPayloads() {
        ObjectNode payload = payload();
        ObjectNode create = JSON.createObjectNode()
                .put("schemaVersion",
                        CapabilityMirrorProtocol.MIRROR_SESSION_CREATE_REQUEST_V1)
                .put("requestId", "create-refund-1");
        create.set("payload", payload);
        ObjectNode descriptor = descriptor(payload, 1, "ACTIVE", null);
        ObjectNode command = command(payload);
        ObjectNode result = commandResult(descriptor);

        MirrorStateProtocolVerifier.VerifiedSessionCreateRequest verifiedCreate =
                verifier.verifySessionCreateRequest(create);
        MirrorStateProtocolVerifier.VerifiedSessionDescriptor verifiedDescriptor =
                verifier.verifySessionDescriptor(descriptor);
        MirrorStateProtocolVerifier.VerifiedSessionCommandRequest verifiedCommand =
                verifier.verifySessionCommandRequest(command, descriptor);
        MirrorStateProtocolVerifier.VerifiedSessionCommandResult verifiedResult =
                verifier.verifySessionCommandResult(result);

        assertThat(verifiedCreate.requestId()).isEqualTo("create-refund-1");
        assertThat(verifiedCreate.payload().sessionId())
                .isEqualTo("refund-session-1");
        assertThat(verifiedCreate.payload().writeEffectCount()).isEqualTo(1);
        assertThat(verifiedDescriptor.status()).isEqualTo("ACTIVE");
        assertThat(verifiedDescriptor.writeEffectCoordinates())
                .containsExactly(verifiedCommand.writeEffectCoordinate());
        assertThat(verifiedResult.idempotencyKey()).isEqualTo("refund-command-1");
        assertThat(verifiedResult.revisionAfter()).isEqualTo(1);
        assertThat(verifiedResult.replayed()).isFalse();
    }

    @Test
    void rejectsUnsafeSessionIdsLifecycleContradictionsAndUnadmittedEffects() {
        ObjectNode unsafePayload = payload();
        ((ObjectNode) unsafePayload.path("state")).put("sessionId", "tenant/a");
        unsafePayload.put("fingerprint", "");
        unsafePayload.put(
                "fingerprint", EvidenceVerificationSupport.sha256(unsafePayload));
        assertThatThrownBy(() -> verifier.verifySessionPayload(unsafePayload))
                .hasMessage("RG.MIRROR.CLIENT.SESSION_PAYLOAD_SCHEMA_INVALID");

        ObjectNode payload = payload();
        ObjectNode impossible = descriptor(
                payload, 0, "DESTROYED", null);
        assertThatThrownBy(() -> verifier.verifySessionDescriptor(impossible))
                .hasMessage("RG.MIRROR.CLIENT.SESSION_DESCRIPTOR_TIME_INVALID");

        ObjectNode descriptor = descriptor(payload, 0, "ACTIVE", null);
        ObjectNode command = command(payload);
        ((ObjectNode) command.path("writeEffectRef")).put("id", "other-effect");
        assertThatThrownBy(() ->
                verifier.verifySessionCommandRequest(command, descriptor))
                .hasMessage("RG.MIRROR.CLIENT.SESSION_COMMAND_EFFECT_INVALID");
    }

    @Test
    void rejectsAggregateAndCommandResultTampering() {
        ObjectNode payload = payload();
        payload.put("fingerprint", "sha256:" + "f".repeat(64));
        assertThatThrownBy(() -> verifier.verifySessionPayload(payload))
                .hasMessage(
                        "RG.MIRROR.CLIENT.SESSION_PAYLOAD_FINGERPRINT_MISMATCH");

        ObjectNode descriptor = descriptor(payload(), 1, "ACTIVE", null);
        ObjectNode result = commandResult(descriptor);
        ObjectNode receipt = (ObjectNode) result.path("receipt");
        ((ObjectNode) receipt.path("response")).put("refundId", "tampered");
        receipt.put("fingerprint", "");
        receipt.put("fingerprint", EvidenceVerificationSupport.sha256(receipt));
        assertThatThrownBy(() -> verifier.verifySessionCommandResult(result))
                .hasMessage(
                        "RG.MIRROR.CLIENT.SESSION_RESPONSE_FINGERPRINT_MISMATCH")
                .hasMessageNotContaining("tampered");
    }

    private static ObjectNode payload() {
        JsonNode fixture = CapabilityMirrorProtocol.statefulRefundFixture();
        ObjectNode value = JSON.createObjectNode()
                .put("schemaVersion",
                        CapabilityMirrorProtocol.MIRROR_SESSION_PAYLOAD_V1);
        value.set("stateModel", fixture.path("stateModel").deepCopy());
        value.putArray("writeEffects")
                .add(fixture.path("writeEffect").deepCopy());
        value.set("state", fixture.path("initialState").deepCopy());
        return seal(value);
    }

    private static ObjectNode descriptor(
            ObjectNode payload,
            long stateRevision,
            String status,
            String destroyedAt) {
        JsonNode state = payload.path("state");
        ObjectNode value = JSON.createObjectNode()
                .put("schemaVersion",
                        CapabilityMirrorProtocol.MIRROR_SESSION_DESCRIPTOR_V1)
                .put("sessionId", state.path("sessionId").asText())
                .put("planFingerprint", state.path("planFingerprint").asText())
                .put("stateRevision", stateRevision)
                .put("status", status)
                .put("worldFingerprint", state.path("worldFingerprint").asText())
                .put("stateFingerprint", state.path("fingerprint").asText())
                .put("createdAt", "2026-07-24T00:00:00Z")
                .put("updatedAt", "2026-07-24T00:00:01Z")
                .put("expiresAt", "2026-07-24T01:00:00Z");
        value.set("scope", state.path("scope").deepCopy());
        value.set("stateModelRef", state.path("stateModelRef").deepCopy());
        value.set("writeEffectRefs", state.path("writeEffectRefs").deepCopy());
        if (destroyedAt == null) {
            value.putNull("destroyedAt");
        } else {
            value.put("destroyedAt", destroyedAt);
        }
        return seal(value);
    }

    private static ObjectNode command(ObjectNode payload) {
        JsonNode state = payload.path("state");
        ObjectNode value = JSON.createObjectNode()
                .put("schemaVersion",
                        CapabilityMirrorProtocol.MIRROR_SESSION_COMMAND_REQUEST_V1)
                .put("expectedStateFingerprint",
                        state.path("fingerprint").asText());
        value.set(
                "writeEffectRef", state.path("writeEffectRefs").get(0).deepCopy());
        value.putObject("input").put("orderId", "O-100");
        return value;
    }

    private static ObjectNode commandResult(ObjectNode descriptor) {
        ObjectNode response = JSON.createObjectNode()
                .put("refundId", "R-100")
                .put("status", "CREATED");
        ObjectNode receipt = JSON.createObjectNode()
                .put("idempotencyKey", "refund-command-1")
                .put("commandFingerprint", "sha256:" + "1".repeat(64))
                .put("revisionBefore", 0)
                .put("revisionAfter", 1)
                .put("responseFingerprint",
                        EvidenceVerificationSupport.sha256(response))
                .put("resultingWorldFingerprint",
                        descriptor.path("worldFingerprint").asText())
                .put("committedAt", "2026-07-24T00:00:01Z");
        receipt.putArray("eventIds").add("refund-created-1");
        receipt.set("response", response);
        seal(receipt);
        ObjectNode value = JSON.createObjectNode()
                .put("schemaVersion",
                        CapabilityMirrorProtocol.MIRROR_SESSION_COMMAND_RESULT_V1)
                .put("replayed", false);
        value.set("descriptor", descriptor);
        value.set("receipt", receipt);
        return value;
    }

    private static ObjectNode seal(ObjectNode value) {
        value.put("fingerprint", "");
        value.put("fingerprint", EvidenceVerificationSupport.sha256(value));
        return value;
    }
}
