package com.leanowtech.bloge.gateway.visual.resource;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Visual-owned authentication descriptor for suggested HTTP resource calls.
 *
 * <p>The visual canvas keeps this model independent from the gateway runtime HTTP
 * operator. A resource-gateway adapter can translate it to the concrete runtime
 * authentication type when a descriptor is saved for execution.</p>
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = VisualResourceAuth.Bearer.class, name = "bearer"),
        @JsonSubTypes.Type(value = VisualResourceAuth.Basic.class, name = "basic"),
        @JsonSubTypes.Type(value = VisualResourceAuth.ApiKey.class, name = "apiKey")
})
public sealed interface VisualResourceAuth permits
        VisualResourceAuth.Bearer,
        VisualResourceAuth.Basic,
        VisualResourceAuth.ApiKey {

    /**
     * Bearer-token authentication.
     *
     * @param token token value or placeholder supplied by the user
     */
    record Bearer(String token) implements VisualResourceAuth {}

    /**
     * HTTP basic authentication.
     *
     * @param username user name or placeholder supplied by the user
     * @param password password or placeholder supplied by the user
     */
    record Basic(String username, String password) implements VisualResourceAuth {}

    /**
     * Header API-key authentication.
     *
     * @param headerName request header name
     * @param key API key value or placeholder supplied by the user
     */
    record ApiKey(String headerName, String key) implements VisualResourceAuth {}
}
