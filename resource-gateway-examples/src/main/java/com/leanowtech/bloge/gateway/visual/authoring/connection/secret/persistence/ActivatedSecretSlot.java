package com.leanowtech.bloge.gateway.visual.authoring.connection.secret.persistence;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.leanowtech.bloge.gateway.visual.authoring.connection.secret.ActivatedExternalSecret;

import java.util.Objects;

/** Provider activation output for one newly written secret slot. */
public record ActivatedSecretSlot(String slot, ActivatedExternalSecret activated) {
    public ActivatedSecretSlot {
        PendingSecretOperation.SlotRules.require(slot);
        Objects.requireNonNull(activated, "activated");
    }

    /** Provider locator and lease are internal opaque values, never wire fields. */
    @JsonIgnore @Override public ActivatedExternalSecret activated() { return activated; }

    @Override public String toString() {
        return "ActivatedSecretSlot[slot=" + slot + "]";
    }
}
