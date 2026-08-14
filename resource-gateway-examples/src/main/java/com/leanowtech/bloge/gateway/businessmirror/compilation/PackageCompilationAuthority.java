package com.leanowtech.bloge.gateway.businessmirror.compilation;

import com.leanowtech.bloge.gateway.businessmirror.authoring.StoredDomainCapabilityPackageDraft;

/** Authoritative adapter that freezes and fences every Package compile dependency. */
public interface PackageCompilationAuthority {
    /** Resolve one exact source draft against one coherent authority generation. */
    FrozenPackageDependencies freeze(StoredDomainCapabilityPackageDraft source);

    /**
     * Recheck all mutable heads and authority generation immediately before result publication.
     *
     * @throws PackageDependencyDriftException when any observed fact changed after {@link #freeze}
     */
    void assertUnchanged(FrozenPackageDependencies frozen);
}
