package com.iwrite.book.repository;

import com.iwrite.book.entity.Book;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BookRepository extends JpaRepository<Book, UUID> {

    List<Book> findAllByTenant_Id(UUID tenantId);

    Optional<Book> findByIdAndTenant_Id(UUID bookId, UUID tenantId);
}
