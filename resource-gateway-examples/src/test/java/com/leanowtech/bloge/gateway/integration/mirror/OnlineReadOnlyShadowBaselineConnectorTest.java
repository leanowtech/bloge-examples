package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OnlineReadOnlyShadowBaselineConnectorTest {
    private final ObjectMapper mapper =
            OnlineReadOnlyShadowBaselineTestFixtures
                    .mapper();
    private final OnlineReadOnlyShadowBaselineObservationIntegrity
            integrity =
            OnlineReadOnlyShadowBaselineTestFixtures
                    .integrity(mapper);

    @Test
    void sendsExactCommandAndAdaptsVerifiedObservation() {
        ReadOnlyShadowConnectorInvocation invocation =
                OnlineReadOnlyShadowBaselineTestFixtures
                        .invocation("connector-success");
        AtomicReference<OnlineReadOnlyShadowBaselineCommand>
                captured = new AtomicReference<>();
        OnlineReadOnlyShadowBaselineConnector connector =
                connector(command -> {
                    captured.set(command);
                    return integrity.sign(
                            OnlineReadOnlyShadowBaselineTestFixtures
                                    .unsigned(mapper, command));
                });

        ReadOnlyShadowConnectorObservation observed =
                connector.observe(invocation);

        assertThat(connector.ready()).isTrue();
        assertThat(captured.get().executionId())
                .isEqualTo(invocation.executionId());
        assertThat(captured.get().accessGrant())
                .isEqualTo(
                        invocation.request()
                                .accessGrant());
        assertThat(observed.source().artifactRef().kind())
                .isEqualTo(
                        OnlineReadOnlyShadowBaselineObservation
                                .ARTIFACT_KIND);
        assertThat(observed.source().role())
                .isEqualTo(
                        ReadOnlyShadowComparison.SourceRole
                                .BASELINE);
        assertThat(observed.writeCredentialExposed())
                .isFalse();
        assertThat(observed.writeAttemptCount())
                .isZero();
    }

    @Test
    void preservesMeasuredWriteViolationsForGovernedDataPlaneRejection() {
        ReadOnlyShadowConnectorInvocation invocation =
                OnlineReadOnlyShadowBaselineTestFixtures
                        .invocation("connector-write");
        OnlineReadOnlyShadowBaselineConnector connector =
                connector(command -> integrity.sign(
                        OnlineReadOnlyShadowBaselineTestFixtures
                                .unsigned(
                                        mapper,
                                        command,
                                        true,
                                        2)));

        ReadOnlyShadowConnectorObservation observed =
                connector.observe(invocation);

        assertThat(observed.writeCredentialExposed())
                .isTrue();
        assertThat(observed.writeAttemptCount())
                .isEqualTo(2);
    }

    @Test
    void rejectsCorrectlySignedObservationForDifferentCommand() {
        ReadOnlyShadowConnectorInvocation invocation =
                OnlineReadOnlyShadowBaselineTestFixtures
                        .invocation("connector-drift");
        OnlineReadOnlyShadowBaselineCommand other =
                OnlineReadOnlyShadowBaselineTestFixtures
                        .command(mapper);
        OnlineReadOnlyShadowBaselineConnector connector =
                connector(command -> integrity.sign(
                        OnlineReadOnlyShadowBaselineTestFixtures
                                .unsigned(mapper, other)));

        assertThatThrownBy(() ->
                connector.observe(invocation))
                .isInstanceOf(
                        ReadOnlyShadowDataPlane.Failure.class)
                .extracting("reason")
                .isEqualTo(
                        ReadOnlyShadowDataPlane.FailureReason
                                .SOURCE_VERIFICATION_FAILED);
    }

    @Test
    void rejectsExpiredInvocationBeforeCallingAuthority() {
        ReadOnlyShadowConnectorInvocation invocation =
                OnlineReadOnlyShadowBaselineTestFixtures
                        .invocation("connector-expired");
        OnlineReadOnlyShadowBaselineConnector connector =
                new OnlineReadOnlyShadowBaselineConnector(
                        authority(command -> {
                            throw new AssertionError(
                                    "authority must not be called");
                        }),
                        integrity,
                        mapper,
                        Clock.fixed(
                                invocation.deadlineAt()
                                        .plusSeconds(1),
                                ZoneOffset.UTC));

        assertThatThrownBy(() ->
                connector.observe(invocation))
                .isInstanceOf(
                        ReadOnlyShadowDataPlane.Failure.class)
                .extracting("reason")
                .isEqualTo(
                        ReadOnlyShadowDataPlane.FailureReason
                                .BASELINE_SOURCE_UNAVAILABLE);
    }

    private OnlineReadOnlyShadowBaselineConnector connector(
            java.util.function.Function<
                    OnlineReadOnlyShadowBaselineCommand,
                    OnlineReadOnlyShadowBaselineObservation>
                    observer) {
        return new OnlineReadOnlyShadowBaselineConnector(
                authority(observer),
                integrity,
                mapper,
                OnlineReadOnlyShadowBaselineTestFixtures
                        .CLOCK);
    }

    private static OnlineReadOnlyShadowBaselineAuthority authority(
            java.util.function.Function<
                    OnlineReadOnlyShadowBaselineCommand,
                    OnlineReadOnlyShadowBaselineObservation>
                    observer) {
        return new OnlineReadOnlyShadowBaselineAuthority() {
            @Override
            public boolean ready() {
                return true;
            }

            @Override
            public OnlineReadOnlyShadowBaselineObservation
            observe(
                    OnlineReadOnlyShadowBaselineCommand command) {
                return observer.apply(command);
            }

            @Override
            public OnlineReadOnlyShadowBaselineObservation
            resolve(
                    CapabilitySnapshot.Scope scope,
                    MirrorArtifactRef observationRef) {
                throw new UnsupportedOperationException();
            }
        };
    }
}
