package com.leanowtech.bloge.gateway.visual.authoring.connection.secret.persistence;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.Objects;

/** One complete, fenced staged batch selected for provider compensation. */
public record SecretAbortCandidate(PendingSecretBatch batch, String recoveryClaimToken) {
    /** Compatibility constructor for unclaimed candidates; such a value can never complete. */
    public SecretAbortCandidate(PendingSecretBatch batch) { this(batch, null); }

    public SecretAbortCandidate {
        Objects.requireNonNull(batch, "batch");
        if (recoveryClaimToken != null && recoveryClaimToken.isBlank()) {
            throw new IllegalArgumentException("recoveryClaimToken is invalid");
        }
    }

    /** Candidate internals contain opaque provider values and are not serialized. */
    @JsonIgnore @Override public PendingSecretBatch batch() { return batch; }

    /** Recovery fencing token is persistence-only and never serialized. */
    @JsonIgnore @Override public String recoveryClaimToken() { return recoveryClaimToken; }

    @Override public String toString() {
        return "SecretAbortCandidate[lease=" + batch.lease() + ", slots="
                + batch.operations().stream().map(PendingSecretOperation::slot).toList() + "]";
    }
}
