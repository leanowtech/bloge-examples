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
 * MyBatis mapper for {@code ge_definition}.
 */
@Mapper
public interface GraphDefinitionStoreMapper {
    /**
     * Inserts a new definition row.
     */
    @Insert("""
            INSERT INTO ge_definition (
                definition_id, definition_key, tenant_id, namespace, display_name,
                description, category, labels_json, owner_team, rbac_policy_json,
                status, revision, created_at, updated_at
            ) VALUES (
                #{definitionId}, #{definitionKey}, #{tenantId}, #{namespace}, #{displayName},
                #{description}, #{category}, #{labelsJson}, #{ownerTeam}, #{rbacPolicyJson},
                #{status}, #{revision}, #{createdAt}, #{updatedAt}
            )
            """)
    void insert(@Param("definitionId") String definitionId,
                @Param("definitionKey") String definitionKey,
                @Param("tenantId") String tenantId,
                @Param("namespace") String namespace,
                @Param("displayName") String displayName,
                @Param("description") String description,
                @Param("category") String category,
                @Param("labelsJson") String labelsJson,
                @Param("ownerTeam") String ownerTeam,
                @Param("rbacPolicyJson") String rbacPolicyJson,
                @Param("status") String status,
                @Param("revision") long revision,
                @Param("createdAt") Instant createdAt,
                @Param("updatedAt") Instant updatedAt);

    /**
     * Selects one definition by identifier.
     */
    @Select("SELECT * FROM ge_definition WHERE definition_id = #{definitionId}")
    Map<String, Object> selectById(@Param("definitionId") String definitionId);

    /**
     * Selects one definition by tenant-qualified key.
     */
    @Select("""
            SELECT * FROM ge_definition
            WHERE tenant_id = #{tenantId}
              AND namespace = #{namespace}
              AND definition_key = #{definitionKey}
            """)
    Map<String, Object> selectByKey(@Param("tenantId") String tenantId,
                                    @Param("namespace") String namespace,
                                    @Param("definitionKey") String definitionKey);

    /**
     * Queries definitions with optional filters.
     */
    List<Map<String, Object>> query(@Param("tenantId") String tenantId,
                                    @Param("namespace") String namespace,
                                    @Param("status") String status,
                                    @Param("definitionKey") String definitionKey,
                                    @Param("ownerTeam") String ownerTeam,
                                    @Param("category") String category,
                                    @Param("size") int size,
                                    @Param("offset") int offset);

    /**
     * Updates a full definition snapshot guarded by optimistic locking.
     */
    @Update("""
            UPDATE ge_definition
               SET definition_key = #{definitionKey},
                   tenant_id = #{tenantId},
                   namespace = #{namespace},
                   display_name = #{displayName},
                   description = #{description},
                   category = #{category},
                   labels_json = #{labelsJson},
                   owner_team = #{ownerTeam},
                   rbac_policy_json = #{rbacPolicyJson},
                   status = #{status},
                   revision = revision + 1,
                   updated_at = #{updatedAt}
             WHERE definition_id = #{definitionId}
               AND revision = #{expectedRevision}
            """)
    int update(@Param("definitionId") String definitionId,
               @Param("definitionKey") String definitionKey,
               @Param("tenantId") String tenantId,
               @Param("namespace") String namespace,
               @Param("displayName") String displayName,
               @Param("description") String description,
               @Param("category") String category,
               @Param("labelsJson") String labelsJson,
               @Param("ownerTeam") String ownerTeam,
               @Param("rbacPolicyJson") String rbacPolicyJson,
               @Param("status") String status,
               @Param("expectedRevision") long expectedRevision,
               @Param("updatedAt") Instant updatedAt);

    /**
     * Updates only the lifecycle status guarded by optimistic locking.
     */
    @Update("""
            UPDATE ge_definition
               SET status = #{status},
                   revision = revision + 1,
                   updated_at = #{updatedAt}
             WHERE definition_id = #{definitionId}
               AND revision = #{expectedRevision}
            """)
    int updateStatus(@Param("definitionId") String definitionId,
                     @Param("status") String status,
                     @Param("expectedRevision") long expectedRevision,
                     @Param("updatedAt") Instant updatedAt);
}
