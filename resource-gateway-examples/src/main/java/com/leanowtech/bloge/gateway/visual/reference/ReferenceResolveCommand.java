package com.leanowtech.bloge.gateway.visual.reference;

/** Untrusted transport input; the controller supplies scope from the authenticated identity. */
public record ReferenceResolveCommand(
        String schemaVersion,
        String kind,
        String id,
        long revision,
        String fingerprint,
        String intendedUse
) {
    public static final String SCHEMA_VERSION = "bloge.referenceResolveCommand.v1";

    public ReferenceResolveCommand {
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("Unsupported ReferenceResolveCommand schemaVersion");
        }
    }

    public ResolveRequest toRequest(ReferenceScope trustedScope) {
        return new ResolveRequest(kind, id, revision, fingerprint, trustedScope, intendedUse);
    }
}
