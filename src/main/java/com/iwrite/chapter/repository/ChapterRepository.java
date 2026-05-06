package com.iwrite.chapter.repository;

import com.iwrite.chapter.entity.Chapter;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ChapterRepository extends JpaRepository<Chapter, UUID> {

    List<Chapter> findByBookIdOrderBySortOrderAsc(UUID bookId);

    List<Chapter> findBySectionIdOrderBySortOrderAsc(UUID sectionId);

    int countBySectionId(UUID sectionId);
}
