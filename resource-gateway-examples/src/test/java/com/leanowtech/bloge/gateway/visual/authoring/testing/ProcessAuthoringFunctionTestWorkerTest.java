package com.leanowtech.bloge.gateway.visual.authoring.testing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringFunctionWorkerProtocol.InvocationOutcome;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessAuthoringFunctionTestWorkerTest {

    private final ProcessAuthoringFunctionTestWorker worker =
            new ProcessAuthoringFunctionTestWorker(
                    new ObjectMapper().findAndRegisterModules());

    @Test
    void invokesAnAttestedCoreFunctionInASeparateBoundedJvm() {
        TrustedCoreFunctionRuntime.Resolution runtime =
                TrustedCoreFunctionRuntime.resolve("trim");

        var response = worker.invoke(
                "trim",
                runtime.runtimeFingerprint(),
                List.of("  isolated  "));

        assertThat(response.outcome()).isEqualTo(InvocationOutcome.SUCCESS);
        assertThat(response.actual()).isEqualTo("isolated");
        assertThat(response.executionProfile())
                .isEqualTo(AuthoringFunctionWorkerProtocol.EXECUTION_PROFILE);
        assertThat(response.runtimeFingerprint()).isEqualTo(runtime.runtimeFingerprint());
        assertThat(response.durationMicros()).isPositive();
    }

    @Test
    void failsClosedWhenTheGatewayAndWorkerRuntimeFingerprintsDiffer() {
        var response = worker.invoke(
                "trim",
                "sha256:stale-runtime",
                List.of("value"));

        assertThat(response.outcome()).isEqualTo(InvocationOutcome.WORKER_FAILED);
        assertThat(response.errorCode()).isEqualTo("WORKER_PROTOCOL_FAILURE");
        assertThat(response.actual()).isNull();
    }

    @Test
    void killsAResourceHeavyWorkerWithoutPoisoningTheNextInvocation() {
        TrustedCoreFunctionRuntime.Resolution range =
                TrustedCoreFunctionRuntime.resolve("range");

        var exhausted = worker.invoke(
                "range",
                range.runtimeFingerprint(),
                List.of(0, 100_000_000));

        assertThat(exhausted.outcome()).isIn(
                InvocationOutcome.TIMEOUT,
                InvocationOutcome.RESOURCE_EXHAUSTED);
        assertThat(exhausted.actual()).isNull();

        TrustedCoreFunctionRuntime.Resolution trim =
                TrustedCoreFunctionRuntime.resolve("trim");
        var recovered = worker.invoke(
                "trim",
                trim.runtimeFingerprint(),
                List.of("  healthy  "));
        assertThat(recovered.outcome()).isEqualTo(InvocationOutcome.SUCCESS);
        assertThat(recovered.actual()).isEqualTo("healthy");
    }
}
