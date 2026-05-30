package com.iwrite.scene.repository;

import com.iwrite.scene.entity.Scene;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface SceneRepository extends JpaRepository<Scene, UUID> {

    List<Scene> findByBookIdOrderBySortOrderAsc(UUID bookId);

    List<Scene> findByChapterIdOrderBySortOrderAsc(UUID chapterId);

    int countByChapterId(UUID chapterId);

    @Query("select coalesce(sum(scene.wordCount), 0) from Scene scene where scene.book.id = :bookId")
    long sumWordCountByBookId(@Param("bookId") UUID bookId);
}
