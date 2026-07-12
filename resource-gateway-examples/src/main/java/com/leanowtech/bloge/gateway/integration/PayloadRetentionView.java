package com.leanowtech.bloge.gateway.integration;

import com.leanowtech.bloge.gateway.visual.runtime.VisualPayloadLifecycleEvent;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunPayloadStatus;

import java.util.List;

/** Governance projection of current payload state and its signed transition chain. */
public record PayloadRetentionView(
        String schemaVersion,
        VisualRunPayloadStatus status,
        List<VisualPayloadLifecycleEvent> events
) {
    public static final String SCHEMA_VERSION = "toolStudio.resourceGateway.payloadRetentionView.v1";

    public PayloadRetentionView {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        events = events == null ? List.of() : List.copyOf(events);
    }
}
