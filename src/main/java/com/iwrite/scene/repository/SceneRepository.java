package com.iwrite.scene.repository;

import com.iwrite.scene.entity.Scene;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SceneRepository extends JpaRepository<Scene, UUID> {

    List<Scene> findByBookIdOrderBySortOrderAsc(UUID bookId);

    @Query("""
            select scene
            from Scene scene
            join fetch scene.chapter chapter
            join fetch chapter.section section
            left join fetch scene.povCharacter
            where scene.book.id = :bookId
            order by section.sortOrder, chapter.sortOrder, scene.sortOrder
            """)
    List<Scene> findOutlineScenesByBookId(@Param("bookId") UUID bookId);

    List<Scene> findByChapterIdOrderBySortOrderAsc(UUID chapterId);

    @Query("""
            select scene
            from Scene scene
            join scene.chapter chapter
            join chapter.section section
            join section.book book
            where scene.id = :sceneId
              and book.tenant.id = :tenantId
            """)
    Optional<Scene> findByIdAndTenantId(
            @Param("sceneId") UUID sceneId,
            @Param("tenantId") UUID tenantId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select scene
            from Scene scene
            where scene.chapter.id = :chapterId
            order by scene.sortOrder, scene.id
            """)
    List<Scene> findByChapterIdForUpdate(@Param("chapterId") UUID chapterId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select scene
            from Scene scene
            where scene.chapter.section.id = :sectionId
            order by scene.chapter.sortOrder, scene.sortOrder, scene.id
            """)
    List<Scene> findBySectionIdForUpdate(@Param("sectionId") UUID sectionId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select scene
            from Scene scene
            join scene.chapter chapter
            join chapter.section section
            join section.book book
            where scene.id = :sceneId
              and book.tenant.id = :tenantId
            """)
    Optional<Scene> findByIdAndTenantIdForUpdate(
            @Param("sceneId") UUID sceneId,
            @Param("tenantId") UUID tenantId
    );

    int countByChapterId(UUID chapterId);

    boolean existsByPovCharacter_IdAndBook_Tenant_Id(UUID characterId, UUID tenantId);

    boolean existsByParticipantCharacters_IdAndBook_Tenant_Id(UUID characterId, UUID tenantId);

    boolean existsByMainLocation_IdAndBook_Tenant_Id(UUID locationId, UUID tenantId);

    boolean existsByItems_IdAndBook_Tenant_Id(UUID itemId, UUID tenantId);

    @Query("select coalesce(sum(scene.wordCount), 0) from Scene scene where scene.book.id = :bookId")
    long sumWordCountByBookId(@Param("bookId") UUID bookId);
}
