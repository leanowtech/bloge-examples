package com.leanowtech.bloge.gateway.visual.authoring.connection.secret.persistence;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.List;
import java.util.Objects;

/** One complete, fenced batch selected for provider compensation. */
public record SecretAbortCandidate(PendingSecretBatch batch, List<ActivatedSecretSlot> activated) {
    public SecretAbortCandidate {
        Objects.requireNonNull(batch, "batch");
        activated = List.copyOf(Objects.requireNonNull(activated, "activated"));
    }

    /** Candidate internals contain opaque provider values and are not serialized. */
    @JsonIgnore @Override public PendingSecretBatch batch() { return batch; }
    /** Candidate internals contain opaque provider values and are not serialized. */
    @JsonIgnore @Override public List<ActivatedSecretSlot> activated() { return activated; }

    @Override public String toString() {
        return "SecretAbortCandidate[lease=" + batch.lease() + ", slots="
                + batch.operations().stream().map(PendingSecretOperation::slot).sorted().toList() + "]";
    }
}
