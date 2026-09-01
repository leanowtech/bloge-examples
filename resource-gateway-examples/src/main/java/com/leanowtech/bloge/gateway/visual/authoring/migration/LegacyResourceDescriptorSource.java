package com.leanowtech.bloge.gateway.visual.authoring.migration;

import java.util.Set;

/** Visual-owned, payload-free projection of legacy Resource Descriptor identities. */
@FunctionalInterface
public interface LegacyResourceDescriptorSource {
    /** Returns the current public logical ids without transport, schema, or credential fields. */
    Set<String> resourceIds();
}
