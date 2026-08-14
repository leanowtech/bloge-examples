package com.leanowtech.bloge.gateway.integration.mirror;

import jakarta.annotation.PostConstruct;

import java.util.Objects;

/** Fail-closed startup registration for one deployment-owned live source baseline. */
public final class AuthoritativeOutcomeSourceBootstrap {
    private final AuthoritativeOutcomeSourceCheckpointRepository checkpoints;
    private final AuthoritativeOutcomeSource source;
    private volatile AuthoritativeOutcomeSourceCheckpointRepository.Registration registration;
    private volatile boolean ready;

    /** Creates a bootstrap that never rewinds an existing cursor. */
    public AuthoritativeOutcomeSourceBootstrap(
            AuthoritativeOutcomeSourceCheckpointRepository checkpoints,
            AuthoritativeOutcomeSource source) {
        this.checkpoints = Objects.requireNonNull(checkpoints, "checkpoints");
        this.source = Objects.requireNonNull(source, "source");
    }

    /** Registers or exactly replays the configured live baseline. */
    @PostConstruct
    public void initialize() {
        AuthoritativeOutcomeSourceCheckpointRepository.Registration exact =
                Objects.requireNonNull(source.liveRegistration(), "liveRegistration");
        if (exact.key().streamKind() != AuthoritativeOutcomeSourcePage.StreamKind.LIVE) {
            throw new IllegalArgumentException("outcome source bootstrap requires a live stream");
        }
        checkpoints.registerLive(exact);
        registration = exact;
        ready = verify(exact);
        if (!ready) {
            throw new IllegalStateException("outcome source live baseline did not become durable");
        }
    }

    /** @return whether the exact deployment baseline exists in durable state */
    public boolean ready() {
        AuthoritativeOutcomeSourceCheckpointRepository.Registration exact = registration;
        return ready && exact != null && verify(exact);
    }

    private boolean verify(
            AuthoritativeOutcomeSourceCheckpointRepository.Registration exact) {
        try {
            return checkpoints.find(exact.key())
                    .filter(value -> value.baselinePageFingerprint().equals(
                            exact.baselinePageFingerprint()))
                    .filter(value -> value.baselineCursorRef().equals(
                            exact.baselineCursorRef()))
                    .isPresent();
        } catch (RuntimeException unavailable) {
            return false;
        }
    }
}
