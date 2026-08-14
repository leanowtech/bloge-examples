package com.leanowtech.bloge.gateway.businessmirror.compilation;

import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorArtifactRef;

import java.util.Set;

/**
 * One source-of-truth adapter used by the composite Package compilation authority.
 *
 * <p>Each source kind has exactly one owner. Adapters resolve an exact mutable source reference to
 * immutable compile material without inventing fallback precedence or consulting client-provided
 * observations.</p>
 */
public interface PackageDependencyAuthorityAdapter {
    /** Stable implementation identity included in the authority generation. */
    String adapterId();

    /** Source artifact kinds exclusively owned by this adapter. */
    Set<String> sourceKinds();

    /** Resolves one exact source reference inside one complete enterprise scope. */
    PackageDependencyResolution resolve(
            CapabilitySnapshot.Scope scope, MirrorArtifactRef sourceRef);
}
