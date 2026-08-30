package com.leanowtech.bloge.gateway.visual.authoring.connection.persistence;

import com.leanowtech.bloge.gateway.visual.authoring.connection.ApiConnectionSpec;
import com.leanowtech.bloge.gateway.visual.authoring.connection.ApiConnectionView;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ExpectedRevision;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.CommandLease;

import java.util.Objects;

/** An invisible, immutable Connection revision waiting for commit. */
public final class StagedApiConnection {
    private final CommandLease lease;
    private final ApiConnectionSpec spec;
    private final ExpectedRevision connectionExpected;
    private final String strongEtag;

    /** Creates an invisible immutable stage with scope and etag closure checks. */
    public StagedApiConnection(CommandLease lease, ApiConnectionSpec spec,
                               ExpectedRevision connectionExpected, String strongEtag) {
        this.lease = Objects.requireNonNull(lease, "lease");
        this.spec = Objects.requireNonNull(spec, "spec");
        this.connectionExpected = Objects.requireNonNull(connectionExpected, "connectionExpected");
        if (!lease.key().scope().equals(spec.scope()) || !ApiConnectionSpec.SCHEMA_VERSION.equals(spec.schemaVersion())
                || spec.connectionId() == null || spec.connectionId().isBlank() || spec.revision() < 1
                || spec.fingerprint() == null || !spec.fingerprint().matches("sha256:[0-9a-f]{64}")
                || connectionExpectedRevisionMismatch(connectionExpected, spec.revision())) {
            throw new IllegalArgumentException("staged spec is inconsistent");
        }
        this.strongEtag = requireEtag(strongEtag);
    }

    /** @return exact lease captured at stage time */
    public CommandLease lease() { return lease; }
    /** @return defensive payload-free staged view */
    public ApiConnectionView view() { return spec.view(); }
    /** @return metadata fingerprint */
    public String metadataFingerprint() { return spec.fingerprint(); }
    /** @return opaque strong etag */
    public String strongEtag() { return strongEtag; }
    /** @return connection CAS captured at stage time */
    public ExpectedRevision connectionExpected() { return connectionExpected; }
    ApiConnectionSpec spec() { return spec; }

    private static String requireEtag(String value) {
        if (!isStrongHttpEtag(value)) {
            throw new IllegalArgumentException("strongEtag is invalid");
        }
        return value;
    }

    /** Stricter HTTP-safe closure, even if an older database check is wider. */
    private static boolean isStrongHttpEtag(String value) {
        if (value == null || value.length() < 3 || value.length() > 256
                || value.charAt(0) != '"' || value.charAt(value.length() - 1) != '"') return false;
        for (int i = 1; i < value.length() - 1; i++) {
            char c = value.charAt(i);
            if (c != '!' && (c < '#' || c > '~')) return false;
        }
        if (value.startsWith("\"W/")) return false;
        return true;
    }

    private static boolean connectionExpectedRevisionMismatch(ExpectedRevision expected, int revision) {
        return expected instanceof ExpectedRevision.Create ? revision != 1
                : expected instanceof ExpectedRevision.Match match && match.revision() != revision - 1L;
    }

    @Override public String toString() {
        return "StagedApiConnection[connectionId=" + spec.connectionId() + ", revision="
                + spec.revision() + ", metadataFingerprint=" + spec.fingerprint() + "]";
    }
}
