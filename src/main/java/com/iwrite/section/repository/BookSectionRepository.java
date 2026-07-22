package com.iwrite.section.repository;

import com.iwrite.section.entity.BookSection;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BookSectionRepository extends JpaRepository<BookSection, UUID> {

    List<BookSection> findByBookIdOrderBySortOrderAsc(UUID bookId);

    Optional<BookSection> findByIdAndBook_Tenant_Id(UUID sectionId, UUID tenantId);

    int countByBookId(UUID bookId);
}
