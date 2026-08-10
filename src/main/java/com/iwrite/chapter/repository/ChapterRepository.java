package com.iwrite.chapter.repository;

import com.iwrite.chapter.entity.Chapter;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ChapterRepository extends JpaRepository<Chapter, UUID> {

    List<Chapter> findByBookIdOrderBySortOrderAsc(UUID bookId);

    List<Chapter> findBySectionIdOrderBySortOrderAsc(UUID sectionId);

    @Query("""
            select chapter
            from Chapter chapter
            join chapter.section section
            join section.book book
            where chapter.id = :chapterId
              and book.tenant.id = :tenantId
            """)
    Optional<Chapter> findByIdAndTenantId(
            @Param("chapterId") UUID chapterId,
            @Param("tenantId") UUID tenantId
    );

    int countByBookId(UUID bookId);

    int countBySectionId(UUID sectionId);
}
