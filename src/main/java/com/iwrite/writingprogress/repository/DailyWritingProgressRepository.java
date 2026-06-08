package com.iwrite.writingprogress.repository;

import com.iwrite.writingprogress.entity.DailyWritingProgress;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.OffsetDateTime;
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

    List<DailyWritingProgress> findByBookIdAndProductiveWordCountChangeGreaterThanOrderByProgressDateAsc(
            UUID bookId,
            int productiveWordCountChange
    );

    long countByBookIdAndProgressDateBetweenAndProductiveWordCountChangeGreaterThan(
            UUID bookId,
            LocalDate startDate,
            LocalDate endDate,
            int productiveWordCountChange
    );

    long countByBookIdAndProgressDate(UUID bookId, LocalDate progressDate);

    @Modifying
    @Query(value = """
            insert into book_daily_writing_progress (
                id,
                book_id,
                progress_date,
                daily_target_word_count,
                starting_manuscript_word_count,
                ending_manuscript_word_count,
                productive_word_count_change,
                manuscript_adjustment_word_count,
                created_at,
                updated_at
            )
            values (
                :id,
                :bookId,
                :progressDate,
                :dailyTargetWordCount,
                :knownManuscriptTotalAfterOperation - :manuscriptWordDelta,
                :knownManuscriptTotalAfterOperation,
                :productiveWordDelta,
                :manuscriptWordDelta - :productiveWordDelta,
                :updatedAt,
                :updatedAt
            )
            on conflict (book_id, progress_date) do update set
                ending_manuscript_word_count =
                    book_daily_writing_progress.ending_manuscript_word_count + :manuscriptWordDelta,
                productive_word_count_change =
                    book_daily_writing_progress.productive_word_count_change + :productiveWordDelta,
                manuscript_adjustment_word_count =
                    book_daily_writing_progress.manuscript_adjustment_word_count
                        + (:manuscriptWordDelta - :productiveWordDelta),
                updated_at = :updatedAt
            """, nativeQuery = true)
    int upsertWordCountEventRollup(
            @Param("id") UUID id,
            @Param("bookId") UUID bookId,
            @Param("progressDate") LocalDate progressDate,
            @Param("dailyTargetWordCount") Integer dailyTargetWordCount,
            @Param("knownManuscriptTotalAfterOperation") int knownManuscriptTotalAfterOperation,
            @Param("productiveWordDelta") int productiveWordDelta,
            @Param("manuscriptWordDelta") int manuscriptWordDelta,
            @Param("updatedAt") OffsetDateTime updatedAt
    );
}
