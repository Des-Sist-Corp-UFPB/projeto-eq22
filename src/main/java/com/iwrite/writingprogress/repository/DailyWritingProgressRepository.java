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

    Optional<DailyWritingProgress> findByUser_IdAndBookIdAndProgressDate(UUID userId, UUID bookId, LocalDate progressDate);

    Optional<DailyWritingProgress> findFirstByUser_IdAndBookIdOrderByProgressDateAsc(UUID userId, UUID bookId);

    List<DailyWritingProgress> findByUser_IdAndBookIdAndProgressDateBetweenOrderByProgressDateDesc(
            UUID userId,
            UUID bookId,
            LocalDate startDate,
            LocalDate endDate
    );

    List<DailyWritingProgress> findByUser_IdAndBookIdAndProgressDateBetweenOrderByProgressDateAsc(
            UUID userId,
            UUID bookId,
            LocalDate startDate,
            LocalDate endDate
    );

    List<DailyWritingProgress> findByUser_IdAndBookIdAndProductiveWordCountChangeGreaterThanOrderByProgressDateAsc(
            UUID userId,
            UUID bookId,
            int productiveWordCountChange
    );

    long countByUser_IdAndBookIdAndProgressDateBetweenAndProductiveWordCountChangeGreaterThan(
            UUID userId,
            UUID bookId,
            LocalDate startDate,
            LocalDate endDate,
            int productiveWordCountChange
    );

    long countByUser_IdAndBookIdAndProgressDate(UUID userId, UUID bookId, LocalDate progressDate);

    @Modifying
    @Query(value = """
            insert into book_daily_writing_progress (
                id,
                user_id,
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
                :userId,
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
            on conflict (user_id, book_id, progress_date) do update set
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
            @Param("userId") UUID userId,
            @Param("bookId") UUID bookId,
            @Param("progressDate") LocalDate progressDate,
            @Param("dailyTargetWordCount") Integer dailyTargetWordCount,
            @Param("knownManuscriptTotalAfterOperation") int knownManuscriptTotalAfterOperation,
            @Param("productiveWordDelta") int productiveWordDelta,
            @Param("manuscriptWordDelta") int manuscriptWordDelta,
            @Param("updatedAt") OffsetDateTime updatedAt
    );
}
