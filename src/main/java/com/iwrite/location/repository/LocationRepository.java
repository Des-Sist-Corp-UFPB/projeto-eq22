package com.iwrite.location.repository;

import com.iwrite.location.entity.Location;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LocationRepository extends JpaRepository<Location, UUID> {

    List<Location> findByBook_IdAndBook_Tenant_IdOrderByNameAscIdAsc(UUID bookId, UUID tenantId);

    Optional<Location> findByIdAndBook_Tenant_Id(UUID locationId, UUID tenantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select location
            from Location location
            where location.id = :locationId
              and location.book.tenant.id = :tenantId
            """)
    Optional<Location> findByIdAndBookTenantIdForUpdate(
            @Param("locationId") UUID locationId,
            @Param("tenantId") UUID tenantId
    );
}
