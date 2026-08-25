package com.leanowtech.bloge.gateway.gatewayverifier.archive;

import java.util.*;

/**
 * Root exception for all DevelopmentPredecessorBinding rejection events.
 * Carries a structured reason code and immutable reason args.
 *
 * <p>NEVER expose raw binding/provider bytes in message or reasonArgs.
 *
 * <p>Frozen consumer codes (R03-*):
 * <ul>
 *   <li>R03-BINDING-MISSING           - binding JSON file missing/unreadable</li>
 *   <li>R03-BINDING-INVALID            - JSON parse failure, duplicate keys, non-canonical, CRLF</li>
 *   <li>R03-BINDING-FP-MISMATCH        - recomputed bindingFingerprint != stored</li>
 *   <li>R03-BINDING-STRUCTURE-MISMATCH - messageVersion/sourceSliceId/targetSliceId wrong</li>
 *   <li>R03-AUTHORITY-FP-MISMATCH      - recomputed authorityRawFingerprint != stored</li>
 *   <li>R03-COORDINATE-MISMATCH        - binding provider coordinate != Authority derivation</li>
 *   <li>R03-PATH-MISMATCH              - binding provider path != Authority derivation</li>
 *   <li>R03-PROVIDER-FP-MISMATCH       - recomputed provider rawFingerprint != stored</li>
 *   <li>R03-SIZE-MISMATCH              - file size != providerArtifact.byteLength</li>
 *   <li>R03-READ-UNREADABLE            - provider file cannot be opened/read</li>
 *   <li>R03-READ-STALE                 - provider file changed during read (pre/post mismatch)</li>
 *   <li>R03-READ-OVERSIZE              - provider file > 16MiB</li>
 * </ul>
 */
public class DevelopmentPredecessorBindingException extends Exception {

    private final String reasonCode;
    /** Immutable - Map.copyOf at construction. */
    private final Map<String, Object> reasonArgs;

    public DevelopmentPredecessorBindingException(String reasonCode, Map<String, Object> reasonArgs) {
        super(reasonCode);
        this.reasonCode   = Objects.requireNonNull(reasonCode);
        this.reasonArgs   = reasonArgs != null ? Map.copyOf(reasonArgs) : null;
    }

    public DevelopmentPredecessorBindingException(String reasonCode, Map<String, Object> reasonArgs, Throwable cause) {
        super(reasonCode, cause);
        this.reasonCode   = Objects.requireNonNull(reasonCode);
        this.reasonArgs   = reasonArgs != null ? Map.copyOf(reasonArgs) : null;
    }

    /** Machine-readable reason code, e.g. {@code "R03-BINDING-MISSING"}. */
    public String reasonCode()         { return reasonCode; }

    /** Defensive-copy map - never expose raw bytes. */
    public Map<String, Object> reasonArgs() {
        return reasonArgs != null ? Map.copyOf(reasonArgs) : null;
    }
}
