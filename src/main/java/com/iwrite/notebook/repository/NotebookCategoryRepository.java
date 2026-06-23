package com.iwrite.notebook.repository;

import com.iwrite.notebook.entity.NotebookCategory;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotebookCategoryRepository extends JpaRepository<NotebookCategory, UUID> {

    List<NotebookCategory> findByBookIdOrderBySortOrderAscNameAscIdAsc(UUID bookId);

    List<NotebookCategory> findByBook_IdAndBook_Tenant_IdOrderBySortOrderAscNameAscIdAsc(UUID bookId, UUID tenantId);

    Optional<NotebookCategory> findByIdAndBook_Tenant_Id(UUID categoryId, UUID tenantId);

    boolean existsByBookIdAndNameIgnoreCase(UUID bookId, String name);

    boolean existsByBook_IdAndBook_Tenant_IdAndNameIgnoreCase(UUID bookId, UUID tenantId, String name);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select category
            from NotebookCategory category
            where category.id = :categoryId
              and category.book.tenant.id = :tenantId
            """)
    Optional<NotebookCategory> findByIdAndBookTenantIdForUpdate(
            @Param("categoryId") UUID categoryId,
            @Param("tenantId") UUID tenantId
    );

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
