package com.leanowtech.bloge.gateway.visual.authoring.connection.secret.persistence;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.Objects;

/** One complete, fenced staged batch selected for provider compensation. */
public record SecretAbortCandidate(PendingSecretBatch batch) {
    public SecretAbortCandidate { Objects.requireNonNull(batch, "batch"); }

    /** Candidate internals contain opaque provider values and are not serialized. */
    @JsonIgnore @Override public PendingSecretBatch batch() { return batch; }

    @Override public String toString() {
        return "SecretAbortCandidate[lease=" + batch.lease() + ", slots="
                + batch.operations().stream().map(PendingSecretOperation::slot).toList() + "]";
    }
}
