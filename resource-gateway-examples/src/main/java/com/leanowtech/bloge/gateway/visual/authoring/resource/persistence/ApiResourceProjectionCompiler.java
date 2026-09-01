package com.leanowtech.bloge.gateway.visual.authoring.resource.persistence;

import com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceSpec;

/**
 * Synchronous compiler for the three required API Resource read models.
 *
 * <p>Implementations must resolve both the exact committed Connection snapshot
 * and its non-secret metadata server-side. A compiler must fail closed when
 * only the resource's Connection id is known; it must never invent a revision,
 * URL, credential, or side-effect receipt mapping.</p>
 */
@FunctionalInterface
public interface ApiResourceProjectionCompiler {
    /** Compiles all required projections or throws without producing a stage. */
    ReadyApiResourceProjections compile(AuthoringScope scope, ApiResourceSpec resource);

    /** Compiles against the exact staged Connection owned by a compound save when present. */
    default ReadyApiResourceProjections compile(AuthoringScope scope, ApiResourceSpec resource,
                                                CommandLease lease) {
        return compile(scope, resource);
    }
}
