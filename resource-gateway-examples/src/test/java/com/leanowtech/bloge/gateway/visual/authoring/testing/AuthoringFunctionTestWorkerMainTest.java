package com.leanowtech.bloge.gateway.visual.authoring.testing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringFunctionWorkerProtocol.InvocationRequest;
import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringFunctionWorkerProtocol.InvocationResponse;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AuthoringFunctionTestWorkerMainTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void acceptsOneVersionedAttestedInvocationWithoutStartingSpring() throws Exception {
        TrustedCoreFunctionRuntime.Resolution runtime =
                TrustedCoreFunctionRuntime.resolve("trim");
        InvocationRequest request = new InvocationRequest(
                InvocationRequest.SCHEMA_VERSION,
                "request-1",
                "trim",
                runtime.runtimeFingerprint(),
                List.of("  hello  "));
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exitCode = AuthoringFunctionTestWorkerMain.run(
                new ByteArrayInputStream(mapper.writeValueAsBytes(request)),
                output,
                mapper);

        assertThat(exitCode).isZero();
        InvocationResponse response =
                mapper.readValue(output.toByteArray(), InvocationResponse.class);
        assertThat(response.requestId()).isEqualTo("request-1");
        assertThat(response.actual()).isEqualTo("hello");
        assertThat(response.runtimeFingerprint()).isEqualTo(runtime.runtimeFingerprint());
    }

    @Test
    void rejectsMissingProtocolVersionAndOversizedInputWithoutEchoingPayload() throws Exception {
        byte[] missingVersion = """
                {"requestId":"request-2","functionName":"trim",
                 "expectedRuntimeFingerprint":"sha256:value","args":["secret"]}
                """.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        assertThat(AuthoringFunctionTestWorkerMain.run(
                new ByteArrayInputStream(missingVersion), output, mapper))
                .isEqualTo(AuthoringFunctionTestWorkerMain.EXIT_PROTOCOL_FAILURE);
        assertThat(output.toByteArray()).isEmpty();

        byte[] oversized =
                new byte[AuthoringFunctionWorkerProtocol.MAXIMUM_REQUEST_BYTES + 1];
        assertThat(AuthoringFunctionTestWorkerMain.run(
                new ByteArrayInputStream(oversized),
                new ByteArrayOutputStream(),
                mapper)).isEqualTo(AuthoringFunctionTestWorkerMain.EXIT_PROTOCOL_FAILURE);
    }
}
