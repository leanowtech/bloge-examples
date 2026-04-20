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
 * MyBatis mapper for {@code ge_instance}.
 */
@Mapper
public interface GraphInstanceStoreMapper {
    /**
     * Inserts a new instance projection row.
     */
    @Insert("""
            INSERT INTO ge_instance (
                instance_id, definition_key, version_id, tenant_id, namespace,
                business_key, execution_mode, status, initiator, variables_json,
                revision, created_at, updated_at, completed_at
            ) VALUES (
                #{instanceId}, #{definitionKey}, #{versionId}, #{tenantId}, #{namespace},
                #{businessKey}, #{executionMode}, #{status}, #{initiator}, #{variablesJson},
                #{revision}, #{createdAt}, #{updatedAt}, #{completedAt}
            )
            """)
    void insert(@Param("instanceId") String instanceId,
                @Param("definitionKey") String definitionKey,
                @Param("versionId") String versionId,
                @Param("tenantId") String tenantId,
                @Param("namespace") String namespace,
                @Param("businessKey") String businessKey,
                @Param("executionMode") String executionMode,
                @Param("status") String status,
                @Param("initiator") String initiator,
                @Param("variablesJson") String variablesJson,
                @Param("revision") long revision,
                @Param("createdAt") Instant createdAt,
                @Param("updatedAt") Instant updatedAt,
                @Param("completedAt") Instant completedAt);

    /**
     * Selects one instance by identifier.
     */
    @Select("SELECT * FROM ge_instance WHERE instance_id = #{instanceId}")
    Map<String, Object> selectById(@Param("instanceId") String instanceId);

    /**
     * Queries instances with optional filters.
     */
    List<Map<String, Object>> query(@Param("tenantId") String tenantId,
                                    @Param("namespace") String namespace,
                                    @Param("definitionKey") String definitionKey,
                                    @Param("businessKey") String businessKey,
                                    @Param("statuses") List<String> statuses,
                                    @Param("executionMode") String executionMode,
                                    @Param("size") int size,
                                    @Param("offset") int offset);

    /**
     * Updates a full instance projection guarded by optimistic locking.
     */
    @Update("""
            UPDATE ge_instance
               SET definition_key = #{definitionKey},
                   version_id = #{versionId},
                   tenant_id = #{tenantId},
                   namespace = #{namespace},
                   business_key = #{businessKey},
                   execution_mode = #{executionMode},
                   status = #{status},
                   initiator = #{initiator},
                   variables_json = #{variablesJson},
                   completed_at = #{completedAt},
                   revision = revision + 1,
                   updated_at = #{updatedAt}
             WHERE instance_id = #{instanceId}
               AND revision = #{expectedRevision}
            """)
    int update(@Param("instanceId") String instanceId,
               @Param("definitionKey") String definitionKey,
               @Param("versionId") String versionId,
               @Param("tenantId") String tenantId,
               @Param("namespace") String namespace,
               @Param("businessKey") String businessKey,
               @Param("executionMode") String executionMode,
               @Param("status") String status,
               @Param("initiator") String initiator,
               @Param("variablesJson") String variablesJson,
               @Param("completedAt") Instant completedAt,
               @Param("expectedRevision") long expectedRevision,
               @Param("updatedAt") Instant updatedAt);

    /**
     * Updates only the instance status guarded by optimistic locking.
     */
    @Update("""
            UPDATE ge_instance
               SET status = #{status},
                   completed_at = #{completedAt},
                   revision = revision + 1,
                   updated_at = #{updatedAt}
             WHERE instance_id = #{instanceId}
               AND revision = #{expectedRevision}
            """)
    int updateStatus(@Param("instanceId") String instanceId,
                     @Param("status") String status,
                     @Param("completedAt") Instant completedAt,
                     @Param("expectedRevision") long expectedRevision,
                     @Param("updatedAt") Instant updatedAt);

    /**
     * Selects the most recently updated instance for one business key.
     */
    @Select("""
            SELECT * FROM ge_instance
            WHERE tenant_id = #{tenantId}
              AND namespace = #{namespace}
              AND business_key = #{businessKey}
            ORDER BY updated_at DESC
            LIMIT 1
            """)
    Map<String, Object> selectByBusinessKey(@Param("tenantId") String tenantId,
                                            @Param("namespace") String namespace,
                                            @Param("businessKey") String businessKey);
}
