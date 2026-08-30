package com.leanowtech.bloge.gateway.visual.authoring.resource.persistence;

import java.util.regex.Pattern;

/** Tenant, project and environment boundary for authoring state. */
public record AuthoringScope(String tenantId, String projectId, String environmentId) {
    private static final Pattern IDENTIFIER = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]*$");

    /** Validates that every scope component is a bounded stable identifier. */
    public AuthoringScope {
        require(tenantId, "tenantId");
        require(projectId, "projectId");
        require(environmentId, "environmentId");
    }

    private static void require(String value, String name) {
        if (value == null || value.length() > 128 || !IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " is invalid");
        }
    }
}
