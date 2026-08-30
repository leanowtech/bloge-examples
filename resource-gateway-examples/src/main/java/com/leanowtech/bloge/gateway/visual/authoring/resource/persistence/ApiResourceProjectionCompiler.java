package com.leanowtech.bloge.gateway.visual.authoring.resource.persistence;

import com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceSpec;

/** Synchronous compiler for the three required API Resource read models. */
@FunctionalInterface
public interface ApiResourceProjectionCompiler {
    /** Compiles all required projections or throws without producing a stage. */
    ReadyApiResourceProjections compile(ApiResourceSpec resource);
}
