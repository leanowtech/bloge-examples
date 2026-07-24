package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemHandler;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.integration.MirrorSessionController;
import com.leanowtech.bloge.gateway.integration.MirrorSessionRequestDecoder;
import com.leanowtech.bloge.gateway.testing.runtime.MirrorStateBaselineResolver;
import com.leanowtech.bloge.gateway.testing.runtime.MirrorStateTransactionEngine;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MirrorSessionControllerTest {
    private static final Instant NOW =
            Instant.parse("2026-07-24T02:00:00Z");
    private final ObjectMapper mapper =
            new ObjectMapper().findAndRegisterModules()
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void authenticatesEveryRouteWithItsDedicatedOperation() {
        Fixture fixture = fixture();
        MirrorSessionIntegrationService service =
                mock(MirrorSessionIntegrationService.class);
        IntegrationRequestAuthenticator authenticator =
                mock(IntegrationRequestAuthenticator.class);
        MirrorSessionRequestDecoder decoder =
                mock(MirrorSessionRequestDecoder.class);
        IntegrationRequestContext identity = identity();
        HttpHeaders headers = new HttpHeaders();
        byte[] raw = "{}".getBytes(StandardCharsets.UTF_8);
        when(authenticator.authenticate(headers,
                IntegrationOperation.MIRROR_SESSION_CREATE))
                .thenReturn(identity);
        when(authenticator.authenticate(headers,
                IntegrationOperation.MIRROR_SESSION_READ))
                .thenReturn(identity);
        when(authenticator.authenticate(headers,
                IntegrationOperation.MIRROR_SESSION_COMMAND))
                .thenReturn(identity);
        when(authenticator.authenticate(headers,
                IntegrationOperation.MIRROR_SESSION_CHECKPOINT))
                .thenReturn(identity);
        when(authenticator.authenticate(headers,
                IntegrationOperation.MIRROR_SESSION_RECOVER))
                .thenReturn(identity);
        when(authenticator.authenticate(headers,
                IntegrationOperation.MIRROR_SESSION_DESTROY))
                .thenReturn(identity);
        when(decoder.decodeCreate(raw, identity))
                .thenReturn(fixture.create());
        when(decoder.decodeCommand(raw, identity))
                .thenReturn(fixture.command());
        MirrorSessionCheckpointBundle checkpoint =
                mock(MirrorSessionCheckpointBundle.class);
        MirrorSessionRecoveryResult recovery =
                mock(MirrorSessionRecoveryResult.class);
        when(decoder.decodeCheckpoint(raw, identity))
                .thenReturn(checkpoint);
        when(service.create(fixture.create(), identity))
                .thenReturn(fixture.descriptor());
        when(service.find("refund-session-1", identity))
                .thenReturn(fixture.descriptor());
        when(service.command(
                "refund-session-1", fixture.command(), identity))
                .thenReturn(fixture.result());
        when(service.checkpoint("refund-session-1", identity))
                .thenReturn(checkpoint);
        when(service.recover(
                "refund-session-1", checkpoint, identity))
                .thenReturn(recovery);
        when(service.destroy("refund-session-1", identity))
                .thenReturn(fixture.descriptor());
        MirrorSessionController controller = new MirrorSessionController(
                service, authenticator, decoder);

        assertThat(controller.create(raw, headers).payload())
                .isEqualTo(fixture.descriptor());
        assertThat(controller.find("refund-session-1", headers).payload())
                .isEqualTo(fixture.descriptor());
        assertThat(controller.command(
                "refund-session-1", raw, headers).payload())
                .isEqualTo(fixture.result());
        assertThat(controller.checkpoint(
                "refund-session-1", headers).payload())
                .isEqualTo(checkpoint);
        assertThat(controller.recover(
                "refund-session-1", raw, headers).payload())
                .isEqualTo(recovery);
        assertThat(controller.destroy(
                "refund-session-1", headers).payload())
                .isEqualTo(fixture.descriptor());
        verify(authenticator).authenticate(
                headers, IntegrationOperation.MIRROR_SESSION_CREATE);
        verify(authenticator).authenticate(
                headers, IntegrationOperation.MIRROR_SESSION_READ);
        verify(authenticator).authenticate(
                headers, IntegrationOperation.MIRROR_SESSION_COMMAND);
        verify(authenticator).authenticate(
                headers, IntegrationOperation.MIRROR_SESSION_CHECKPOINT);
        verify(authenticator).authenticate(
                headers, IntegrationOperation.MIRROR_SESSION_RECOVER);
        verify(authenticator).authenticate(
                headers, IntegrationOperation.MIRROR_SESSION_DESTROY);
    }

    @Test
    void authenticationFailureHappensBeforeRawPayloadDecoding()
            throws Exception {
        MirrorSessionIntegrationService service =
                mock(MirrorSessionIntegrationService.class);
        IntegrationRequestAuthenticator authenticator =
                mock(IntegrationRequestAuthenticator.class);
        MirrorSessionRequestDecoder decoder =
                mock(MirrorSessionRequestDecoder.class);
        when(authenticator.authenticate(
                any(HttpHeaders.class),
                eq(IntegrationOperation.MIRROR_SESSION_CREATE)))
                .thenThrow(new IntegrationProblemException(
                        IntegrationProblem.unauthorized(
                                "RG.INTEGRATION.AUTHENTICATION_REQUIRED",
                                "Authentication is required.",
                                "corr-1", Map.of())));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                        new MirrorSessionController(
                                service, authenticator, decoder))
                .setControllerAdvice(new IntegrationProblemHandler())
                .build();

        mvc.perform(post("/api/mirror/sessions")
                        .contentType(APPLICATION_JSON)
                        .content("{not-json"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(
                        "RG.INTEGRATION.AUTHENTICATION_REQUIRED"));
        verifyNoInteractions(decoder, service);
    }

    @Test
    void recoveryAuthenticationFailureHappensBeforeCheckpointDecoding()
            throws Exception {
        MirrorSessionIntegrationService service =
                mock(MirrorSessionIntegrationService.class);
        IntegrationRequestAuthenticator authenticator =
                mock(IntegrationRequestAuthenticator.class);
        MirrorSessionRequestDecoder decoder =
                mock(MirrorSessionRequestDecoder.class);
        when(authenticator.authenticate(
                any(HttpHeaders.class),
                eq(IntegrationOperation.MIRROR_SESSION_RECOVER)))
                .thenThrow(new IntegrationProblemException(
                        IntegrationProblem.unauthorized(
                                "RG.INTEGRATION.AUTHENTICATION_REQUIRED",
                                "Authentication is required.",
                                "corr-1", Map.of())));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                        new MirrorSessionController(
                                service, authenticator, decoder))
                .setControllerAdvice(new IntegrationProblemHandler())
                .build();

        mvc.perform(post(
                        "/api/mirror/sessions/refund-session-1/recoveries")
                        .contentType(APPLICATION_JSON)
                        .content("{not-json"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(
                        "RG.INTEGRATION.AUTHENTICATION_REQUIRED"));
        verifyNoInteractions(decoder, service);
    }

    @Test
    void retryableSessionProblemsPublishBoundedRetryAfter()
            throws Exception {
        Fixture fixture = fixture();
        MirrorSessionIntegrationService service =
                mock(MirrorSessionIntegrationService.class);
        IntegrationRequestAuthenticator authenticator =
                mock(IntegrationRequestAuthenticator.class);
        MirrorSessionRequestDecoder decoder =
                mock(MirrorSessionRequestDecoder.class);
        when(authenticator.authenticate(
                any(HttpHeaders.class),
                eq(IntegrationOperation.MIRROR_SESSION_COMMAND)))
                .thenReturn(identity());
        when(decoder.decodeCommand(any(byte[].class), eq(identity())))
                .thenReturn(fixture.command());
        when(service.command(
                "refund-session-1", fixture.command(), identity()))
                .thenThrow(new IntegrationProblemException(
                        IntegrationProblem.retryableConflict(
                                "RG.MIRROR.SESSION.LEASE_BUSY",
                                "Session is busy.", "corr-1",
                                Map.of("retryAfterSeconds", 3))));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                        new MirrorSessionController(
                                service, authenticator, decoder))
                .setControllerAdvice(new IntegrationProblemHandler())
                .build();

        mvc.perform(post(
                        "/api/mirror/sessions/refund-session-1/commands")
                        .contentType(APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "3"))
                .andExpect(jsonPath("$.retryable").value(true));
    }

    @Test
    void capacityRejectionPublishesStable429Contract()
            throws Exception {
        Fixture fixture = fixture();
        MirrorSessionIntegrationService service =
                mock(MirrorSessionIntegrationService.class);
        IntegrationRequestAuthenticator authenticator =
                mock(IntegrationRequestAuthenticator.class);
        MirrorSessionRequestDecoder decoder =
                mock(MirrorSessionRequestDecoder.class);
        when(authenticator.authenticate(
                any(HttpHeaders.class),
                eq(IntegrationOperation.MIRROR_SESSION_CREATE)))
                .thenReturn(identity());
        when(decoder.decodeCreate(any(byte[].class), eq(identity())))
                .thenReturn(fixture.create());
        when(service.create(fixture.create(), identity()))
                .thenThrow(new IntegrationProblemException(
                        IntegrationProblem.tooManyRequests(
                                "RG.MIRROR.SESSION.CAPACITY_EXCEEDED",
                                "The mirror state data plane is at its admission limit.",
                                "corr-1",
                                Map.of("retryAfterSeconds", 1))));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                        new MirrorSessionController(
                                service, authenticator, decoder))
                .setControllerAdvice(new IntegrationProblemHandler())
                .build();

        mvc.perform(post("/api/mirror/sessions")
                        .contentType(APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "1"))
                .andExpect(jsonPath("$.code").value(
                        "RG.MIRROR.SESSION.CAPACITY_EXCEEDED"))
                .andExpect(jsonPath("$.retryable").value(true));
    }

    private Fixture fixture() {
        StateModel model = StateModelIntegrity.seal(
                mapper, StatefulMirrorProtocolTest.stateModel());
        WriteEffectSpec effect = WriteEffectSpecIntegrity.seal(
                mapper, StatefulMirrorProtocolTest.refundEffect(model));
        SessionStateSpace initial = StatefulMirrorProtocolTest.initialState(
                mapper, model, effect);
        MirrorSessionPayload payload =
                MirrorSessionProtocolIntegrity.sealInitial(
                        mapper,
                        new MirrorSessionPayload(
                                "", model, List.of(effect), initial, ""),
                        NOW);
        MirrorStateTransactionEngine engine =
                new MirrorStateTransactionEngine(
                        mapper, model, initial,
                        MirrorStateBaselineResolver.none(),
                        Clock.fixed(NOW, ZoneOffset.UTC),
                        (expected, candidate) -> {
                        });
        SessionStateSpace.TransactionReceipt receipt =
                engine.execute(effect, Map.of(
                        "requestId", "REQ-1",
                        "orderId", "O-100",
                        "amount", 100));
        SessionStateSpace committed = engine.snapshot();
        MirrorSessionDescriptor descriptor =
                MirrorSessionProtocolIntegrity.sealDescriptor(
                        mapper, new MirrorSessionDescriptor(
                                MirrorSessionDescriptor.SCHEMA_VERSION,
                                initial.sessionId(),
                                initial.scope(),
                                initial.planFingerprint(),
                                initial.stateModelRef(),
                                initial.writeEffectRefs(),
                                committed.stateRevision(),
                                MirrorSessionDescriptor.Status.ACTIVE,
                                committed.worldFingerprint(),
                                committed.fingerprint(),
                                NOW, NOW,
                                initial.expiresAt(),
                                null, ""));
        MirrorSessionCreateRequest create =
                new MirrorSessionCreateRequest(
                        MirrorSessionCreateRequest.SCHEMA_VERSION,
                        "create-1", payload);
        MirrorSessionCommandRequest command =
                new MirrorSessionCommandRequest(
                        MirrorSessionCommandRequest.SCHEMA_VERSION,
                        WriteEffectSpecIntegrity.reference(effect),
                        "", Map.of(
                                "requestId", "REQ-1",
                                "orderId", "O-100",
                                "amount", 100));
        MirrorSessionCommandResult result =
                new MirrorSessionCommandResult(
                        MirrorSessionCommandResult.SCHEMA_VERSION,
                        descriptor, receipt, false);
        return new Fixture(
                create, command, descriptor, result);
    }

    private static IntegrationRequestContext identity() {
        return new IntegrationRequestContext(
                "tenant-a", "org-a", "tool-studio", "test", "sg",
                "SERVICE", "mirror-client", "", "MIRROR_REHEARSAL",
                "corr-1", Set.of(), "CONFIDENTIAL", "");
    }

    private record Fixture(
            MirrorSessionCreateRequest create,
            MirrorSessionCommandRequest command,
            MirrorSessionDescriptor descriptor,
            MirrorSessionCommandResult result) {
    }
}
