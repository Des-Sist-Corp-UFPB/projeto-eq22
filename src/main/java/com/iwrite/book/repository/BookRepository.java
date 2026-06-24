package com.iwrite.book.repository;

import com.iwrite.book.entity.Book;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BookRepository extends JpaRepository<Book, UUID> {

    List<Book> findAllByTenant_Id(UUID tenantId);

    Optional<Book> findByIdAndTenant_Id(UUID bookId, UUID tenantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select book
            from Book book
            where book.id = :bookId
              and book.tenant.id = :tenantId
            """)
    Optional<Book> findByIdAndTenant_IdForUpdate(
            @Param("bookId") UUID bookId,
            @Param("tenantId") UUID tenantId
    );
}
