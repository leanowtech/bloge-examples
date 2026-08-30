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

    /** Creates a committed immutable snapshot after validating its closure and opaque etag. */
    public StoredApiConnection(AuthoringScope scope, ApiConnectionView view, String metadataFingerprint,
                               String strongEtag, String commandId) {
        this.scope = Objects.requireNonNull(scope, "scope");
        this.view = Objects.requireNonNull(view, "view");
        this.metadataFingerprint = requireText(metadataFingerprint, "metadataFingerprint");
        this.strongEtag = requireEtag(strongEtag);
        this.commandId = requireText(commandId, "commandId");
        if (!ApiConnectionView.SCHEMA_VERSION.equals(view.schemaVersion())
                || view.connectionId() == null || view.connectionId().isBlank() || view.revision() < 1
                || view.auth() == null) throw new IllegalArgumentException("view is inconsistent");
        if (!metadataFingerprint.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("metadataFingerprint is invalid");
        }
    }

    /** @return exact authoring scope */
    public AuthoringScope scope() { return scope; }
    /** @return defensive payload-free view */
    public ApiConnectionView view() { return view; }
    /** @return metadata fingerprint */
    public String metadataFingerprint() { return metadataFingerprint; }
    /** @return opaque strong etag */
    public String strongEtag() { return strongEtag; }
    /** @return committing command identifier */
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

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }
    private static String requireEtag(String value) {
        if (!StrongEtag.isValid(value)) throw new IllegalArgumentException("strongEtag is invalid");
        return value;
    }
}
