package com.leanowtech.bloge.gateway.visual.authoring.application.connection;

import com.leanowtech.bloge.gateway.visual.authoring.connection.ApiConnectionView;
import com.leanowtech.bloge.gateway.visual.authoring.connection.persistence.StrongEtag;

/** Payload-free current Connection view and its opaque strong validator. */
public record ApiConnectionAuthoringRead(ApiConnectionView view, String strongEtag) {
    /** Rejects incomplete or non-strong read results at the application seam. */
    public ApiConnectionAuthoringRead {
        if (view == null || !StrongEtag.isValid(strongEtag)) {
            throw new IllegalArgumentException("connection read fields are required");
        }
    }
}
