package com.leanowtech.bloge.gateway.visual.authoring.migration;

import com.leanowtech.bloge.gateway.visual.resource.VisualResourceParameterMapping;
import com.leanowtech.bloge.gateway.visual.resource.VisualResourceResponseProtocol;

import java.util.Optional;
import java.util.Set;

/** Visual-owned, payload-free projection of legacy Resource Descriptor identities. */
@FunctionalInterface
public interface LegacyResourceDescriptorSource {
    /** Returns the current public logical ids without transport, schema, or credential fields. */
    Set<String> resourceIds();

    /** Returns one transport-redacted descriptor projection when it is safe to inspect for re-authoring. */
    default Optional<Descriptor> find(String resourceId) {
        return Optional.empty();
    }

    /**
     * Only the semantics needed to build a new Resource command. Host, headers, auth, timeout,
     * credentials, and managed-write material are deliberately absent.
     */
    record Descriptor(String resourceId, String method, String path,
                      VisualResourceParameterMapping parameterMapping,
                      VisualResourceResponseProtocol responseProtocol,
                      String payloadPath) {
    }
}
