package com.leanowtech.bloge.gateway.visual.authoring.connection.persistence;

import com.leanowtech.bloge.gateway.visual.authoring.connection.ApiConnectionSpec;
import com.leanowtech.bloge.gateway.visual.authoring.connection.ApiConnectionView;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;

import java.util.Objects;

/** Committed payload-free Connection revision and its opaque commit etag. */
public final class StoredApiConnection {
    private final AuthoringScope scope;
    private final ApiConnectionView view;
    private final String metadataFingerprint;
    private final String strongEtag;
    private final String commandId;

    public StoredApiConnection(AuthoringScope scope, ApiConnectionView view, String metadataFingerprint,
                               String strongEtag, String commandId) {
        this.scope = Objects.requireNonNull(scope, "scope");
        this.view = Objects.requireNonNull(view, "view");
        this.metadataFingerprint = Objects.requireNonNull(metadataFingerprint, "metadataFingerprint");
        this.strongEtag = Objects.requireNonNull(strongEtag, "strongEtag");
        this.commandId = Objects.requireNonNull(commandId, "commandId");
    }

    public AuthoringScope scope() { return scope; }
    public ApiConnectionView view() { return view; }
    public String metadataFingerprint() { return metadataFingerprint; }
    public String strongEtag() { return strongEtag; }
    public String commandId() { return commandId; }

    @Override public boolean equals(Object other) {
        if (!(other instanceof StoredApiConnection that)) return false;
        return scope.equals(that.scope) && view.equals(that.view)
                && metadataFingerprint.equals(that.metadataFingerprint)
                && strongEtag.equals(that.strongEtag) && commandId.equals(that.commandId);
    }
    @Override public int hashCode() { return Objects.hash(scope, view, metadataFingerprint, strongEtag, commandId); }
    @Override public String toString() {
        return "StoredApiConnection[connectionId=" + view.connectionId() + ", revision="
                + view.revision() + ", metadataFingerprint=" + metadataFingerprint + "]";
    }
}
