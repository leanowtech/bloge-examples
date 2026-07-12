package com.leanowtech.bloge.gateway.visual.runtime;

import java.time.Instant;

/** Visual-runtime projection of a controlled run lifecycle and termination proof. */
public record VisualRunControlView(
        String schemaVersion,
        String requestId,
        String engineExecutionId,
        String status,
        String reasonCode,
        long revision,
        Instant deadlineAt,
        Instant startedAt,
        Instant cancelRequestedAt,
        Instant terminalAt,
        boolean terminationConfirmed,
        boolean sideEffectsMayBeInFlight
) {
    public static final String SCHEMA_VERSION = "bloge.visualRunControl.v1";

    public VisualRunControlView {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        requestId = requestId == null ? "" : requestId;
        engineExecutionId = engineExecutionId == null ? "" : engineExecutionId;
        status = status == null || status.isBlank() ? "UNMANAGED" : status.trim().toUpperCase();
        reasonCode = reasonCode == null || reasonCode.isBlank() ? "NONE" : reasonCode.trim().toUpperCase();
        revision = Math.max(0, revision);
    }

    public static VisualRunControlView unmanaged() {
        return new VisualRunControlView("", "", "", "UNMANAGED", "NONE", 0,
                null, null, null, null, true, false);
    }
}
