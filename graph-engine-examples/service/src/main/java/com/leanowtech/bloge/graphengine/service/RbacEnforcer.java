package com.leanowtech.bloge.graphengine.service;

import com.leanowtech.bloge.graphengine.model.GraphDefinition;
import com.leanowtech.bloge.graphengine.model.RbacPolicy;

import java.util.Set;

/**
 * Lightweight, stateless RBAC enforcement helper used by
 * {@link DefaultGraphEngineService} to gate operations against the owning
 * definition's {@link RbacPolicy}.
 *
 * <h3>Design rules</h3>
 * <ul>
 *   <li>When no {@link CallerContext} is bound (i.e. {@code null}), the call is
 *       treated as a system/internal invocation and always allowed.  This keeps
 *       embedded, non-HTTP deployments unaffected.</li>
 *   <li>When the relevant role-set on the policy is <em>empty</em>, access is
 *       unrestricted for that permission category.</li>
 *   <li>Otherwise the caller must possess at least one role present in the
 *       required set.</li>
 * </ul>
 *
 * <h3>Permission mapping</h3>
 * <table>
 *   <tr><th>Category</th><th>Operations</th></tr>
 *   <tr><td>{@code viewRoles}</td><td>read / query definitions, versions, deployments,
 *       instances, tasks, dead letters, audit/transitions, operator inventory</td></tr>
 *   <tr><td>{@code startRoles}</td><td>{@code startInstance}, {@code signalInstance}</td></tr>
 *   <tr><td>{@code deployRoles}</td><td>{@code createVersion}, {@code validateVersion},
 *       {@code publishVersion}, {@code deprecateVersion}, deployment mutations</td></tr>
 *   <tr><td>{@code adminRoles}</td><td>definition mutations, cancel/terminate,
 *       dead-letter retry, task mutations</td></tr>
 * </table>
 */
public final class RbacEnforcer {

    private RbacEnforcer() {
    }

    /**
     * Checks that the current caller is allowed to perform a <b>view</b>
     * operation on the given definition.
     *
     * @param definition the owning graph definition
     * @throws GraphEngineServiceException with {@code ACCESS_DENIED} on failure
     */
    public static void requireView(GraphDefinition definition) {
        enforce(definition, definition.rbacPolicy().viewRoles(), "view");
    }

    /**
     * Checks that the current caller is allowed to perform a <b>start</b>
     * operation on the given definition.
     *
     * @param definition the owning graph definition
     * @throws GraphEngineServiceException with {@code ACCESS_DENIED} on failure
     */
    public static void requireStart(GraphDefinition definition) {
        enforce(definition, definition.rbacPolicy().startRoles(), "start");
    }

    /**
     * Checks that the current caller is allowed to perform a <b>deploy</b>
     * operation on the given definition.
     *
     * @param definition the owning graph definition
     * @throws GraphEngineServiceException with {@code ACCESS_DENIED} on failure
     */
    public static void requireDeploy(GraphDefinition definition) {
        enforce(definition, definition.rbacPolicy().deployRoles(), "deploy");
    }

    /**
     * Checks that the current caller is allowed to perform an <b>admin</b>
     * operation on the given definition.
     *
     * @param definition the owning graph definition
     * @throws GraphEngineServiceException with {@code ACCESS_DENIED} on failure
     */
    public static void requireAdmin(GraphDefinition definition) {
        enforce(definition, definition.rbacPolicy().adminRoles(), "admin");
    }

    private static void enforce(GraphDefinition definition, Set<String> requiredRoles, String category) {
        CallerContext caller = CallerContextHolder.current();
        if (caller == null) {
            // No caller context → system/internal call; bypass RBAC.
            return;
        }
        if (caller.hasAnyRole(requiredRoles)) {
            return;
        }
        throw new GraphEngineServiceException(
                GraphEngineServiceErrorCode.ACCESS_DENIED,
                "Access denied: caller does not have '" + category
                        + "' permission on definition '" + definition.definitionKey() + "'"
        );
    }
}
