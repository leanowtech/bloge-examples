package com.leanowtech.bloge.gateway.visual.authoring.resource.persistence;

import com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceSpec;

import java.util.EnumMap;
import java.util.Map;

/** Three READY projections bound to one exact committed Connection snapshot. */
public record ReadyApiResourceProjections(ProjectionDocument descriptor,
                                          ProjectionDocument designContract,
                                          ProjectionDocument operator,
                                          ApiResourceConnectionSnapshot connectionSnapshot) {
    /** Validates kind uniqueness, exact subject identity and READY state. */
    public ReadyApiResourceProjections {
        if (descriptor == null || designContract == null || operator == null || connectionSnapshot == null) {
            throw new IllegalArgumentException("three projections and a connection snapshot are required");
        }
        Map<ProjectionDocument.Kind, ProjectionDocument> all = new EnumMap<>(ProjectionDocument.Kind.class);
        for (ProjectionDocument projection : new ProjectionDocument[]{descriptor, designContract, operator}) {
            if (projection.state() != ProjectionDocument.State.READY || all.put(projection.kind(), projection) != null) {
                throw new IllegalArgumentException("projections must be unique and READY");
            }
        }
        if (descriptor.kind() != ProjectionDocument.Kind.DESCRIPTOR
                || designContract.kind() != ProjectionDocument.Kind.DESIGN_CONTRACT
                || operator.kind() != ProjectionDocument.Kind.OPERATOR
                || !descriptor.subject().equals(designContract.subject())
                || !descriptor.subject().equals(operator.subject())) {
            throw new IllegalArgumentException("projection kinds or subjects do not match");
        }
    }

    /** @return exact subject represented by all three documents */
    public ApiResourceSpec.ResourceRef subject() { return descriptor.subject(); }
}
