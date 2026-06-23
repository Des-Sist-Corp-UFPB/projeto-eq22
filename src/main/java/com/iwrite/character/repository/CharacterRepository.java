package com.iwrite.character.repository;

import com.iwrite.character.entity.Character;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CharacterRepository extends JpaRepository<Character, UUID> {

    List<Character> findByBook_IdAndBook_Tenant_IdOrderByNameAscIdAsc(UUID bookId, UUID tenantId);

    Optional<Character> findByIdAndBook_Tenant_Id(UUID characterId, UUID tenantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select character
            from Character character
            where character.id = :characterId
              and character.book.tenant.id = :tenantId
            """)
    Optional<Character> findByIdAndBookTenantIdForUpdate(
            @Param("characterId") UUID characterId,
            @Param("tenantId") UUID tenantId
    );
}
