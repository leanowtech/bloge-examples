package com.leanowtech.bloge.gateway.visual.authoring.resource.persistence;

/** Authoring command endpoint used in idempotency coordinates. */
public enum AuthoringEndpoint {
    /** Compound API Resource save command. */
    API_RESOURCE_SAVE,
    /** API Connection metadata save command. */
    API_CONNECTION_SAVE
}
