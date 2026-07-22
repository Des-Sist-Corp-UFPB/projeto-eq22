package com.iwrite.sceneversion.repository;

import com.iwrite.sceneversion.entity.SceneVersion;
import com.iwrite.sceneversion.entity.SceneVersionSource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SceneVersionRepository extends JpaRepository<SceneVersion, UUID> {

    Page<SceneVersion> findBySceneIdOrderByCreatedAtDesc(UUID sceneId, Pageable pageable);

    @Query("""
            select version
            from SceneVersion version
            join version.scene scene
            join scene.chapter chapter
            join chapter.section section
            join section.book book
            where version.id = :versionId
              and scene.id = :sceneId
              and book.tenant.id = :tenantId
            """)
    Optional<SceneVersion> findByIdAndSceneIdAndTenantId(
            @Param("versionId") UUID versionId,
            @Param("sceneId") UUID sceneId,
            @Param("tenantId") UUID tenantId
    );

    Optional<SceneVersion> findTopBySceneIdOrderByCreatedAtDesc(UUID sceneId);

    Optional<SceneVersion> findTopBySceneIdAndSourceOrderByCreatedAtDesc(UUID sceneId, SceneVersionSource source);

    @Query("""
            select version.contentHash
            from SceneVersion version
            where version.scene.id = :sceneId
            order by version.createdAt desc
            """)
    List<String> findContentHashesBySceneId(@Param("sceneId") UUID sceneId, Pageable pageable);

    @Query("""
            select version.contentHash
            from SceneVersion version
            where version.originalSceneId = :originalSceneId
            order by version.createdAt desc
            """)
    List<String> findContentHashesByOriginalSceneId(@Param("originalSceneId") UUID originalSceneId, Pageable pageable);

    List<SceneVersion> findByOriginalSceneIdOrderByCreatedAtDesc(UUID originalSceneId);

    long countByOriginalSceneId(UUID originalSceneId);
}
