package com.iwrite.notebook.repository;

import com.iwrite.notebook.entity.NotebookCategory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface NotebookCategoryRepository extends JpaRepository<NotebookCategory, UUID> {

    List<NotebookCategory> findByBookIdOrderBySortOrderAscNameAscIdAsc(UUID bookId);

    boolean existsByBookIdAndNameIgnoreCase(UUID bookId, String name);
}
