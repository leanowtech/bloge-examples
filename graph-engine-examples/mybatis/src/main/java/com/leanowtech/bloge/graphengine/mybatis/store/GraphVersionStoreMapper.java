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
 * MyBatis mapper for {@code ge_version}.
 */
@Mapper
public interface GraphVersionStoreMapper {
    /**
     * Inserts a new version row.
     */
    @Insert("""
            INSERT INTO ge_version (
                version_id, definition_id, version, content_hash, dsl_source,
                visual_layout, metadata_json, compiled_artifact_ref, migration_policy,
                status, revision, published_at, created_at, updated_at
            ) VALUES (
                #{versionId}, #{definitionId}, #{version}, #{contentHash}, #{dslSource},
                #{visualLayout}, #{metadataJson}, #{compiledArtifactRef}, #{migrationPolicy},
                #{status}, #{revision}, #{publishedAt}, #{createdAt}, #{updatedAt}
            )
            """)
    void insert(@Param("versionId") String versionId,
                @Param("definitionId") String definitionId,
                @Param("version") String version,
                @Param("contentHash") String contentHash,
                @Param("dslSource") String dslSource,
                @Param("visualLayout") String visualLayout,
                @Param("metadataJson") String metadataJson,
                @Param("compiledArtifactRef") String compiledArtifactRef,
                @Param("migrationPolicy") String migrationPolicy,
                @Param("status") String status,
                @Param("revision") long revision,
                @Param("publishedAt") Instant publishedAt,
                @Param("createdAt") Instant createdAt,
                @Param("updatedAt") Instant updatedAt);

    /**
     * Selects one version by identifier.
     */
    @Select("SELECT * FROM ge_version WHERE version_id = #{versionId}")
    Map<String, Object> selectById(@Param("versionId") String versionId);

    /**
     * Selects one version by definition + semantic version.
     */
    @Select("""
            SELECT * FROM ge_version
            WHERE definition_id = #{definitionId}
              AND version = #{version}
            """)
    Map<String, Object> selectByDefinitionAndVersion(@Param("definitionId") String definitionId,
                                                     @Param("version") String version);

    /**
     * Lists versions for one definition with optional status filtering.
     */
    List<Map<String, Object>> query(@Param("definitionId") String definitionId,
                                    @Param("statuses") List<String> statuses,
                                    @Param("size") int size,
                                    @Param("offset") int offset);

    /**
     * Updates a full version snapshot guarded by optimistic locking.
     */
    @Update("""
            UPDATE ge_version
               SET definition_id = #{definitionId},
                   version = #{version},
                   content_hash = #{contentHash},
                   dsl_source = #{dslSource},
                   visual_layout = #{visualLayout},
                   metadata_json = #{metadataJson},
                   compiled_artifact_ref = #{compiledArtifactRef},
                   migration_policy = #{migrationPolicy},
                   status = #{status},
                   published_at = #{publishedAt},
                   revision = revision + 1,
                   updated_at = #{updatedAt}
             WHERE version_id = #{versionId}
               AND revision = #{expectedRevision}
            """)
    int update(@Param("versionId") String versionId,
               @Param("definitionId") String definitionId,
               @Param("version") String version,
               @Param("contentHash") String contentHash,
               @Param("dslSource") String dslSource,
               @Param("visualLayout") String visualLayout,
               @Param("metadataJson") String metadataJson,
               @Param("compiledArtifactRef") String compiledArtifactRef,
               @Param("migrationPolicy") String migrationPolicy,
               @Param("status") String status,
               @Param("publishedAt") Instant publishedAt,
               @Param("expectedRevision") long expectedRevision,
               @Param("updatedAt") Instant updatedAt);

    /**
     * Updates the lifecycle status guarded by optimistic locking.
     */
    @Update("""
            UPDATE ge_version
               SET status = #{status},
                   published_at = #{publishedAt},
                   revision = revision + 1,
                   updated_at = #{updatedAt}
             WHERE version_id = #{versionId}
               AND revision = #{expectedRevision}
            """)
    int updateStatus(@Param("versionId") String versionId,
                     @Param("status") String status,
                     @Param("publishedAt") Instant publishedAt,
                     @Param("expectedRevision") long expectedRevision,
                     @Param("updatedAt") Instant updatedAt);

    /**
     * Selects the most recently published version for one definition.
     */
    @Select("""
            SELECT * FROM ge_version
            WHERE definition_id = #{definitionId}
              AND status = 'PUBLISHED'
            ORDER BY published_at DESC, updated_at DESC
            LIMIT 1
            """)
    Map<String, Object> selectLatestPublished(@Param("definitionId") String definitionId);
}
