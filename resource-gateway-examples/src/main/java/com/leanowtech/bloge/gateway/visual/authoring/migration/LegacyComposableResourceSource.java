package com.leanowtech.bloge.gateway.visual.authoring.migration;

import com.leanowtech.bloge.gateway.visual.authoring.flow.ComposableDefinition;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;

import java.util.Optional;

/** Read-only bridge from a legacy resource operator id to one exact committed API Resource head. */
@FunctionalInterface
public interface LegacyComposableResourceSource {
    /** Returns only the current committed Resource in the trusted scope; never a descriptor or transport secret. */
    Optional<ComposableDefinition> findHead(AuthoringScope scope, String resourceId);
}
