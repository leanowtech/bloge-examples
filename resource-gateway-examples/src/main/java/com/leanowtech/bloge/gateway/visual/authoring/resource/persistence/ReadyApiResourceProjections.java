package com.leanowtech.bloge.gateway.visual.authoring.resource.persistence;

import com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceSpec;

import java.util.EnumMap;
import java.util.Map;

/** Exactly one READY descriptor, design-contract and operator projection. */
public record ReadyApiResourceProjections(ProjectionDocument descriptor,
                                          ProjectionDocument designContract,
                                          ProjectionDocument operator) {
    /** Validates kind uniqueness, exact subject identity and READY state. */
    public ReadyApiResourceProjections {
        if (descriptor == null || designContract == null || operator == null) throw new IllegalArgumentException("three projections are required");
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
