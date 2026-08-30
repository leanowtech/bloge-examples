package com.leanowtech.bloge.gateway.visual.authoring.resource.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceSpec;

/** One immutable, ready derived document bound to an exact API Resource subject. */
public record ProjectionDocument(Kind kind, ApiResourceSpec.ResourceRef subject,
                                 JsonNode body, String fingerprint, State state) {
    /** Required projection kinds. */
    public enum Kind { DESCRIPTOR, DESIGN_CONTRACT, OPERATOR }
    /** Visibility state of a projection. */
    public enum State { READY }

    /** Defensive immutable projection value. */
    public ProjectionDocument {
        if (kind == null || subject == null || body == null || fingerprint == null || state == null) {
            throw new IllegalArgumentException("projection fields are required");
        }
        body = body.deepCopy();
    }

    @Override public JsonNode body() { return body.deepCopy(); }
}
