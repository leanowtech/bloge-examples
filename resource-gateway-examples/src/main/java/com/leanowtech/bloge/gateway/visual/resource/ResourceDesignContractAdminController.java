package com.leanowtech.bloge.gateway.visual.resource;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collection;

/**
 * Admin API for resource design contracts consumed by the visual canvas.
 */
@RestController
@RequestMapping("/admin/resource-design-contracts")
public class ResourceDesignContractAdminController {

    private final ResourceDesignContractRegistry registry;

    /**
     * @param registry contract registry
     */
    public ResourceDesignContractAdminController(ResourceDesignContractRegistry registry) {
        this.registry = registry;
    }

    /**
     * @return all design contracts
     */
    @GetMapping
    public Collection<ResourceDesignContract> list() {
        return registry.all();
    }

    /**
     * @param resourceId descriptor id
     * @return matching contract
     */
    @GetMapping("/{resourceId:.+}")
    public ResponseEntity<ResourceDesignContract> get(@PathVariable String resourceId) {
        return registry.findByResourceId(resourceId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Replaces a design contract for a resource.
     *
     * @param resourceId descriptor id
     * @param contract new contract
     * @return stored contract
     */
    @PutMapping("/{resourceId:.+}")
    public ResourceDesignContract upsert(@PathVariable String resourceId,
                                         @RequestBody ResourceDesignContract contract) {
        if (!resourceId.equals(contract.resourceId())) {
            throw new IllegalArgumentException("Path resourceId '%s' does not match body resourceId '%s'"
                    .formatted(resourceId, contract.resourceId()));
        }
        return registry.upsert(contract);
    }

    /**
     * Deletes a design contract.
     *
     * @param resourceId descriptor id
     * @return empty response
     */
    @DeleteMapping("/{resourceId:.+}")
    public ResponseEntity<Void> delete(@PathVariable String resourceId) {
        registry.deleteByResourceId(resourceId);
        return ResponseEntity.noContent().build();
    }
}
