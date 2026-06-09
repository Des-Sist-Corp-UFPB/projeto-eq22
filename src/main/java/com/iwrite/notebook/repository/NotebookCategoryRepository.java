package com.iwrite.notebook.repository;

import com.iwrite.notebook.entity.NotebookCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface NotebookCategoryRepository extends JpaRepository<NotebookCategory, UUID> {

    List<NotebookCategory> findByBookIdOrderBySortOrderAscNameAscIdAsc(UUID bookId);

    boolean existsByBookIdAndNameIgnoreCase(UUID bookId, String name);

    @Modifying
    @Query(value = """
            insert into notebook_categories (id, book_id, name, sort_order, created_at, updated_at)
            values (:id, :bookId, :name, :sortOrder, current_timestamp, current_timestamp)
            on conflict (book_id, lower(name)) do nothing
            """, nativeQuery = true)
    int insertStarterCategoryIfMissing(
            @Param("id") UUID id,
            @Param("bookId") UUID bookId,
            @Param("name") String name,
            @Param("sortOrder") int sortOrder
    );
}
