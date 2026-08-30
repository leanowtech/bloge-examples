package com.leanowtech.bloge.gateway.visual.authoring.application.connection;

import com.leanowtech.bloge.gateway.visual.authoring.connection.ApiConnectionView;
import com.leanowtech.bloge.gateway.visual.authoring.connection.persistence.StrongEtag;

/** Payload-free result returned by a successful Connection authoring command. */
public record ApiConnectionAuthoringResult(ApiConnectionView view, String strongEtag, boolean replayed) {
    /** Returns the exact committed revision and whether it came from a receipt replay. */
    public ApiConnectionAuthoringResult {
        if (view == null || !StrongEtag.isValid(strongEtag)) {
            throw new IllegalArgumentException("result fields are required");
        }
    }
}
