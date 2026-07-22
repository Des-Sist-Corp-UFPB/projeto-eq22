package com.iwrite.notebook.repository;

import com.iwrite.notebook.entity.NotebookNote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotebookNoteRepository extends JpaRepository<NotebookNote, UUID> {

    List<NotebookNote> findByBookIdOrderByUpdatedAtDescIdAsc(UUID bookId);

    List<NotebookNote> findByBook_IdAndBook_Tenant_IdOrderByUpdatedAtDescIdAsc(UUID bookId, UUID tenantId);

    List<NotebookNote> findByBookIdAndCategoryIdOrderByUpdatedAtDescIdAsc(UUID bookId, UUID categoryId);

    List<NotebookNote> findByBook_IdAndBook_Tenant_IdAndCategory_IdOrderByUpdatedAtDescIdAsc(
            UUID bookId,
            UUID tenantId,
            UUID categoryId
    );

    Optional<NotebookNote> findByIdAndBook_Tenant_Id(UUID noteId, UUID tenantId);

    List<NotebookNote> findByCategoryId(UUID categoryId);

    List<NotebookNote> findByBook_IdAndCategory_Id(UUID bookId, UUID categoryId);

    @Modifying
    @Query("update NotebookNote note set note.category = null where note.category.id = :categoryId")
    int clearCategory(@Param("categoryId") UUID categoryId);

    @Query("""
            select count(note) > 0
            from NotebookNote note
            where note.category.id = :categoryId
              and note.book.id <> :bookId
            """)
    boolean existsByCategoryIdAndBookIdNot(
            @Param("categoryId") UUID categoryId,
            @Param("bookId") UUID bookId
    );
}
