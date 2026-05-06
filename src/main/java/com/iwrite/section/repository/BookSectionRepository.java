package com.iwrite.section.repository;

import com.iwrite.section.entity.BookSection;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface BookSectionRepository extends JpaRepository<BookSection, UUID> {

    List<BookSection> findByBookIdOrderBySortOrderAsc(UUID bookId);

    int countByBookId(UUID bookId);
}
