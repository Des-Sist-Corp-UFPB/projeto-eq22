package com.iwrite.notebook.repository;

import com.iwrite.notebook.entity.NotebookNote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface NotebookNoteRepository extends JpaRepository<NotebookNote, UUID> {

    List<NotebookNote> findByBookIdOrderByUpdatedAtDescIdAsc(UUID bookId);

    List<NotebookNote> findByBookIdAndCategoryIdOrderByUpdatedAtDescIdAsc(UUID bookId, UUID categoryId);

    List<NotebookNote> findByCategoryId(UUID categoryId);

    @Modifying
    @Query("update NotebookNote note set note.category = null where note.category.id = :categoryId")
    int clearCategory(@Param("categoryId") UUID categoryId);
}
