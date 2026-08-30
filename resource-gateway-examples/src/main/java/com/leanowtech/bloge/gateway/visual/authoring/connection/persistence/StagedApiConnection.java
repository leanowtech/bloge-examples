package com.leanowtech.bloge.gateway.visual.authoring.connection.persistence;

import com.leanowtech.bloge.gateway.visual.authoring.connection.ApiConnectionSpec;
import com.leanowtech.bloge.gateway.visual.authoring.connection.ApiConnectionView;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.CommandLease;

import java.util.Objects;

/** An invisible, immutable Connection revision waiting for commit. */
public final class StagedApiConnection {
    private final CommandLease lease;
    private final ApiConnectionSpec spec;
    private final String strongEtag;

    StagedApiConnection(CommandLease lease, ApiConnectionSpec spec, String strongEtag) {
        this.lease = Objects.requireNonNull(lease, "lease");
        this.spec = Objects.requireNonNull(spec, "spec");
        this.strongEtag = Objects.requireNonNull(strongEtag, "strongEtag");
    }

    public CommandLease lease() { return lease; }
    public ApiConnectionView view() { return spec.view(); }
    public String metadataFingerprint() { return spec.fingerprint(); }
    public String strongEtag() { return strongEtag; }
    ApiConnectionSpec spec() { return spec; }

    @Override public String toString() {
        return "StagedApiConnection[connectionId=" + spec.connectionId() + ", revision="
                + spec.revision() + ", metadataFingerprint=" + spec.fingerprint() + "]";
    }
}
