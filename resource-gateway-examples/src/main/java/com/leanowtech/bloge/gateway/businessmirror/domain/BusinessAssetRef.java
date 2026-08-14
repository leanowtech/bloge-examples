package com.leanowtech.bloge.gateway.businessmirror.domain;

import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;

/**
 * Exact, authority-qualified reference to one business asset across the L0-L3 service stack.
 *
 * @param layer semantic service layer
 * @param kind business asset kind
 * @param id stable authority-local identity
 * @param revision positive immutable revision
 * @param fingerprint canonical content fingerprint
 * @param authority authoritative registry or source system
 * @param scope complete enterprise namespace
 */
public record BusinessAssetRef(
        Layer layer,
        Kind kind,
        String id,
        long revision,
        String fingerprint,
        String authority,
        CapabilitySnapshot.Scope scope
) {
    /** Customer-service asset layers. */
    public enum Layer {
        L0_RESOURCE,
        L1_SERVICE_DESIGN,
        L2_SERVICE_CARRIER,
        L3_APPLICATION
    }

    /** Typed assets admitted to the first business-mirror protocol. */
    public enum Kind {
        RESOURCE,
        OPERATOR,
        BUILT_IN_FUNCTION,
        FEATURE,
        SCENARIO,
        SOLUTION,
        SOP,
        AGENT,
        WORKFLOW,
        CHANNEL_APPLICATION
    }

    /** Enforces an exact immutable coordinate and a kind/layer combination that cannot drift. */
    public BusinessAssetRef {
        layer = java.util.Objects.requireNonNull(layer, "layer");
        kind = java.util.Objects.requireNonNull(kind, "kind");
        id = BusinessMirrorProtocolSupport.identifier(id, "business asset id");
        if (revision < 1) {
            throw new IllegalArgumentException("business asset revision must be positive");
        }
        fingerprint = BusinessMirrorProtocolSupport.fingerprint(fingerprint, "business asset fingerprint");
        authority = BusinessMirrorProtocolSupport.identifier(authority, "business asset authority");
        scope = java.util.Objects.requireNonNull(scope, "scope");
        if (!compatible(layer, kind)) {
            throw new IllegalArgumentException("business asset kind is incompatible with its L0-L3 layer");
        }
    }

    private static boolean compatible(Layer layer, Kind kind) {
        return switch (layer) {
            case L0_RESOURCE -> kind == Kind.RESOURCE
                    || kind == Kind.OPERATOR
                    || kind == Kind.BUILT_IN_FUNCTION;
            case L1_SERVICE_DESIGN -> kind == Kind.FEATURE
                    || kind == Kind.SCENARIO
                    || kind == Kind.SOLUTION;
            case L2_SERVICE_CARRIER -> kind == Kind.SOP
                    || kind == Kind.AGENT
                    || kind == Kind.WORKFLOW;
            case L3_APPLICATION -> kind == Kind.CHANNEL_APPLICATION;
        };
    }
}
