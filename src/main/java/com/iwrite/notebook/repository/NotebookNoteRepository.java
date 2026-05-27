package com.iwrite.notebook.repository;

import com.iwrite.notebook.entity.NotebookNote;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface NotebookNoteRepository extends JpaRepository<NotebookNote, UUID> {

    List<NotebookNote> findByBookIdOrderByUpdatedAtDescIdAsc(UUID bookId);

    List<NotebookNote> findByBookIdAndCategoryIdOrderByUpdatedAtDescIdAsc(UUID bookId, UUID categoryId);

    List<NotebookNote> findByCategoryId(UUID categoryId);
}
