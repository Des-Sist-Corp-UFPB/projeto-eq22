package com.iwrite.item.repository;

import com.iwrite.item.entity.Item;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ItemRepository extends JpaRepository<Item, UUID> {

    List<Item> findByBook_IdAndBook_Tenant_IdOrderByNameAscIdAsc(UUID bookId, UUID tenantId);

    Optional<Item> findByIdAndBook_Tenant_Id(UUID itemId, UUID tenantId);

    boolean existsByCurrentOwnerCharacter_Id(UUID characterId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select item
            from Item item
            where item.id = :itemId
              and item.book.tenant.id = :tenantId
            """)
    Optional<Item> findByIdAndBookTenantIdForUpdate(
            @Param("itemId") UUID itemId,
            @Param("tenantId") UUID tenantId
    );
}
