package com.leanowtech.bloge.gateway.visual.authoring.flow;

import com.leanowtech.bloge.gateway.visual.authoring.resource.ExpectedRevision;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;

/** Complete non-secret authority required for one atomic draft save. */
public record ReusableFlowSaveIntent(AuthoringScope scope, String actorId, String flowId,
                                     ExpectedRevision expectedRevision, String idempotencyKey,
                                     String requestFingerprint, String contentFingerprint,
                                     ReusableFlowCommand command) {
    public ReusableFlowSaveIntent {
        if (scope == null || expectedRevision == null || command == null
                || invalid(actorId, 256) || invalid(flowId, 128) || invalid(idempotencyKey, 160)
                || !fingerprint(requestFingerprint) || !fingerprint(contentFingerprint)) {
            throw new IllegalArgumentException("reusable Flow save intent is invalid");
        }
    }

    private static boolean invalid(String value, int maximum) {
        return value == null || value.isBlank() || value.length() > maximum;
    }

    private static boolean fingerprint(String value) {
        return value != null && value.matches("sha256:[0-9a-f]{64}");
    }
}
