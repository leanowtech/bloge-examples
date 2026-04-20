package com.leanowtech.bloge.gateway.resource;

import com.leanowtech.bloge.gateway.exception.ResourceNotFoundException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collection;

/**
 * REST controller exposing CRUD operations on {@link ResourceDescriptor} entries
 * in the {@link WritableResourceRegistry}.
 *
 * <p>All endpoints live under {@code /admin/resources} and operate on descriptor
 * JSON bodies directly. This controller is intended for administrative use —
 * registering, updating, inspecting, and removing API resource definitions that
 * the gateway uses at runtime.
 *
 * <h3>Error handling</h3>
 * <ul>
 *   <li>{@code 404} — descriptor not found (GET / PUT / DELETE with unknown ID)</li>
 *   <li>{@code 409} — duplicate descriptor on POST</li>
 *   <li>{@code 400} — invalid descriptor (expression compilation failure)</li>
 * </ul>
 */
@RestController
@RequestMapping("/admin/resources")
public class ResourceRegistryAdminController {

    private static final Logger log = LoggerFactory.getLogger(ResourceRegistryAdminController.class);

    private final WritableResourceRegistry registry;

    /**
     * @param registry the writable registry to manage
     */
    public ResourceRegistryAdminController(WritableResourceRegistry registry) {
        this.registry = registry;
    }

    /**
     * Lists all registered resource descriptors.
     *
     * @return a collection of all descriptors (may be empty)
     */
    @GetMapping
    public Collection<ResourceDescriptor> listAll() {
        return registry.all();
    }

    /**
     * Retrieves a single resource descriptor by its unique identifier.
     *
     * @param resourceId the logical resource identifier
     * @return the matching descriptor
     */
    @GetMapping("/{resourceId:.+}")
    public ResourceDescriptor getOne(@PathVariable String resourceId) {
        return registry.resolve(resourceId);
    }

    /**
     * Registers a new resource descriptor.
     *
     * <p>The descriptor's bloge expressions (parameter mappings, response protocol)
     * are validated at registration time. If any expression fails to compile, the
     * request is rejected with {@code 400 Bad Request}.
     *
     * @param descriptor the descriptor to register
     * @return the registered descriptor with {@code 201 Created}
     */
    @PostMapping
    public ResponseEntity<ResourceDescriptor> create(@RequestBody ResourceDescriptor descriptor) {
        registry.register(descriptor);
        log.info("Created resource descriptor via admin API: {}", descriptor.resourceId());
        return ResponseEntity.status(HttpStatus.CREATED).body(descriptor);
    }

    /**
     * Replaces an existing resource descriptor.
     *
     * <p>The {@code resourceId} in the URL path must match the descriptor body's
     * {@code resourceId}. Expressions are re-validated on update.
     *
     * @param resourceId the logical resource identifier (must match the body)
     * @param descriptor the updated descriptor
     * @return the updated descriptor
     */
    @PutMapping("/{resourceId:.+}")
    public ResourceDescriptor update(@PathVariable String resourceId,
                                     @RequestBody ResourceDescriptor descriptor) {
        if (!resourceId.equals(descriptor.resourceId())) {
            throw new IllegalArgumentException(
                    "Path resourceId '%s' does not match body resourceId '%s'"
                            .formatted(resourceId, descriptor.resourceId()));
        }
        registry.update(descriptor);
        log.info("Updated resource descriptor via admin API: {}", resourceId);
        return descriptor;
    }

    /**
     * Removes a resource descriptor by its identifier.
     *
     * @param resourceId the logical resource identifier to deregister
     * @return {@code 204 No Content} on success
     */
    @DeleteMapping("/{resourceId:.+}")
    public ResponseEntity<Void> delete(@PathVariable String resourceId) {
        registry.deregister(resourceId);
        log.info("Deleted resource descriptor via admin API: {}", resourceId);
        return ResponseEntity.noContent().build();
    }

    // ── Exception handlers ──────────────────────────────────────────────

    /**
     * Handles resource-not-found errors with a 404 response.
     */
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<String> handleNotFound(ResourceNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ex.getMessage());
    }

    /**
     * Handles duplicate-registration and path/body mismatch errors with 409 or 400.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleBadRequest(IllegalArgumentException ex) {
        if (ex.getMessage() != null && ex.getMessage().startsWith("Descriptor already registered")) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(ex.getMessage());
        }
        return ResponseEntity.badRequest().body(ex.getMessage());
    }

    /**
     * Handles expression compilation failures with a 400 response.
     */
    @ExceptionHandler(com.leanowtech.bloge.gateway.exception.ResourceDescriptorException.class)
    public ResponseEntity<String> handleExpressionError(
            com.leanowtech.bloge.gateway.exception.ResourceDescriptorException ex) {
        return ResponseEntity.badRequest().body(ex.getMessage());
    }
}
