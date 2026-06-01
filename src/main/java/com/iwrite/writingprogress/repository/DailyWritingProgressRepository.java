package com.iwrite.writingprogress.repository;

import com.iwrite.writingprogress.entity.DailyWritingProgress;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DailyWritingProgressRepository extends JpaRepository<DailyWritingProgress, UUID> {

    Optional<DailyWritingProgress> findByBookIdAndProgressDate(UUID bookId, LocalDate progressDate);

    Optional<DailyWritingProgress> findFirstByBookIdOrderByProgressDateAsc(UUID bookId);

    List<DailyWritingProgress> findByBookIdAndProgressDateBetweenOrderByProgressDateDesc(
            UUID bookId,
            LocalDate startDate,
            LocalDate endDate
    );

    List<DailyWritingProgress> findByBookIdAndProgressDateBetweenOrderByProgressDateAsc(
            UUID bookId,
            LocalDate startDate,
            LocalDate endDate
    );

    List<DailyWritingProgress> findByBookIdAndNetWordCountChangeGreaterThanOrderByProgressDateAsc(
            UUID bookId,
            int netWordCountChange
    );

    long countByBookIdAndProgressDateBetweenAndNetWordCountChangeGreaterThan(
            UUID bookId,
            LocalDate startDate,
            LocalDate endDate,
            int netWordCountChange
    );

    long countByBookIdAndProgressDate(UUID bookId, LocalDate progressDate);
}
