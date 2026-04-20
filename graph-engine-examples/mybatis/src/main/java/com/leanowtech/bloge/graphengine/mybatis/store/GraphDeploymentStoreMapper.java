package com.leanowtech.bloge.graphengine.mybatis.store;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * MyBatis mapper for {@code ge_deployment}.
 */
@Mapper
public interface GraphDeploymentStoreMapper {
    /**
     * Inserts a new deployment row.
     */
    @Insert("""
            INSERT INTO ge_deployment (
                deployment_id, definition_key, tenant_id, namespace, environment,
                routing_policy_json, operator_plane_json, is_active, revision,
                created_at, updated_at
            ) VALUES (
                #{deploymentId}, #{definitionKey}, #{tenantId}, #{namespace}, #{environment},
                #{routingPolicyJson}, #{operatorPlaneJson}, #{active}, #{revision},
                #{createdAt}, #{updatedAt}
            )
            """)
    void insert(@Param("deploymentId") String deploymentId,
                @Param("definitionKey") String definitionKey,
                @Param("tenantId") String tenantId,
                @Param("namespace") String namespace,
                @Param("environment") String environment,
                @Param("routingPolicyJson") String routingPolicyJson,
                @Param("operatorPlaneJson") String operatorPlaneJson,
                @Param("active") boolean active,
                @Param("revision") long revision,
                @Param("createdAt") Instant createdAt,
                @Param("updatedAt") Instant updatedAt);

    /**
     * Selects one deployment by identifier.
     */
    @Select("SELECT * FROM ge_deployment WHERE deployment_id = #{deploymentId}")
    Map<String, Object> selectById(@Param("deploymentId") String deploymentId);

    /**
     * Queries deployments with optional filters.
     */
    List<Map<String, Object>> query(@Param("tenantId") String tenantId,
                                    @Param("namespace") String namespace,
                                    @Param("definitionKey") String definitionKey,
                                    @Param("environment") String environment,
                                    @Param("active") Boolean active,
                                    @Param("size") int size,
                                    @Param("offset") int offset);

    /**
     * Selects the currently active deployment for one definition and environment.
     */
    @Select("""
            SELECT * FROM ge_deployment
            WHERE tenant_id = #{tenantId}
              AND namespace = #{namespace}
              AND definition_key = #{definitionKey}
              AND environment = #{environment}
              AND is_active = TRUE
            ORDER BY updated_at DESC
            LIMIT 1
            """)
    Map<String, Object> selectActive(@Param("tenantId") String tenantId,
                                     @Param("namespace") String namespace,
                                     @Param("definitionKey") String definitionKey,
                                     @Param("environment") String environment);

    /**
     * Updates a full deployment snapshot guarded by optimistic locking.
     */
    @Update("""
            UPDATE ge_deployment
               SET definition_key = #{definitionKey},
                   tenant_id = #{tenantId},
                   namespace = #{namespace},
                   environment = #{environment},
                   routing_policy_json = #{routingPolicyJson},
                   operator_plane_json = #{operatorPlaneJson},
                   is_active = #{active},
                   revision = revision + 1,
                   updated_at = #{updatedAt}
             WHERE deployment_id = #{deploymentId}
               AND revision = #{expectedRevision}
            """)
    int update(@Param("deploymentId") String deploymentId,
               @Param("definitionKey") String definitionKey,
               @Param("tenantId") String tenantId,
               @Param("namespace") String namespace,
               @Param("environment") String environment,
               @Param("routingPolicyJson") String routingPolicyJson,
               @Param("operatorPlaneJson") String operatorPlaneJson,
               @Param("active") boolean active,
               @Param("expectedRevision") long expectedRevision,
               @Param("updatedAt") Instant updatedAt);

    /**
     * Updates only the active flag guarded by optimistic locking.
     */
    @Update("""
            UPDATE ge_deployment
               SET is_active = #{active},
                   revision = revision + 1,
                   updated_at = #{updatedAt}
             WHERE deployment_id = #{deploymentId}
               AND revision = #{expectedRevision}
            """)
    int updateActive(@Param("deploymentId") String deploymentId,
                     @Param("active") boolean active,
                     @Param("expectedRevision") long expectedRevision,
                     @Param("updatedAt") Instant updatedAt);

    /**
     * Deactivates peer deployments in the same tenant/environment scope.
     */
    @Update("""
            UPDATE ge_deployment
               SET is_active = FALSE,
                   revision = revision + 1,
                   updated_at = #{updatedAt}
             WHERE tenant_id = #{tenantId}
               AND namespace = #{namespace}
               AND definition_key = #{definitionKey}
               AND environment = #{environment}
               AND deployment_id <> #{exceptDeploymentId}
               AND is_active = TRUE
            """)
    int deactivatePeers(@Param("tenantId") String tenantId,
                        @Param("namespace") String namespace,
                        @Param("definitionKey") String definitionKey,
                        @Param("environment") String environment,
                        @Param("exceptDeploymentId") String exceptDeploymentId,
                        @Param("updatedAt") Instant updatedAt);
}
