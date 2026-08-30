package com.leanowtech.bloge.gateway.visual.authoring.application.connection;

import com.leanowtech.bloge.gateway.visual.authoring.connection.ApiConnectionView;

/** Payload-free result returned by a successful Connection authoring command. */
public record ApiConnectionAuthoringResult(ApiConnectionView view, String strongEtag, boolean replayed) {
    /** Returns the exact committed revision and whether it came from a receipt replay. */
    public ApiConnectionAuthoringResult {
        if (view == null || strongEtag == null || strongEtag.isBlank()) {
            throw new IllegalArgumentException("result fields are required");
        }
    }
}
