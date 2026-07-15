package com.leanowtech.bloge.gateway.testing.evidence;

import com.leanowtech.bloge.gateway.exception.ResourceNotFoundException;
import com.leanowtech.bloge.gateway.resource.ResourceDescriptor;
import com.leanowtech.bloge.gateway.resource.ResourceRegistry;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable per-run snapshot of resource descriptors used by protocol-derived fixtures. */
public final class FrozenResourceRegistry implements ResourceRegistry {

    private final Map<String, ResourceDescriptor> descriptors;

    public FrozenResourceRegistry(Collection<ResourceDescriptor> source) {
        Map<String, ResourceDescriptor> snapshot = new LinkedHashMap<>();
        if (source != null) {
            source.forEach(descriptor -> snapshot.put(descriptor.resourceId(), descriptor));
        }
        this.descriptors = Map.copyOf(snapshot);
    }

    @Override
    public ResourceDescriptor resolve(String resourceId) {
        ResourceDescriptor descriptor = descriptors.get(resourceId);
        if (descriptor == null) {
            throw new ResourceNotFoundException(resourceId);
        }
        return descriptor;
    }

    @Override
    public boolean contains(String resourceId) {
        return descriptors.containsKey(resourceId);
    }

    @Override
    public Collection<ResourceDescriptor> all() {
        return descriptors.values();
    }
}
