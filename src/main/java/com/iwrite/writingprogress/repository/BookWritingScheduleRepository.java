package com.iwrite.writingprogress.repository;

import com.iwrite.writingprogress.entity.BookWritingSchedule;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BookWritingScheduleRepository extends JpaRepository<BookWritingSchedule, UUID> {

    @EntityGraph(attributePaths = "plannedDays")
    Optional<BookWritingSchedule> findFirstByBookIdAndEffectiveToIsNull(UUID bookId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = "plannedDays")
    @Query("""
            select schedule
            from BookWritingSchedule schedule
            where schedule.book.id = :bookId
              and schedule.effectiveTo is null
            """)
    Optional<BookWritingSchedule> findActiveByBookIdForUpdate(@Param("bookId") UUID bookId);

    @EntityGraph(attributePaths = "plannedDays")
    @Query("""
            select schedule
            from BookWritingSchedule schedule
            where schedule.book.id = :bookId
              and schedule.effectiveFrom <= :date
              and (schedule.effectiveTo is null or schedule.effectiveTo > :date)
            """)
    Optional<BookWritingSchedule> findByBookIdAndDate(@Param("bookId") UUID bookId, @Param("date") LocalDate date);

    @EntityGraph(attributePaths = "plannedDays")
    @Query("""
            select schedule
            from BookWritingSchedule schedule
            where schedule.book.id = :bookId
              and schedule.effectiveFrom < :endExclusive
              and (schedule.effectiveTo is null or schedule.effectiveTo > :startInclusive)
            order by schedule.effectiveFrom asc
            """)
    List<BookWritingSchedule> findByBookIdOverlappingPeriod(
            @Param("bookId") UUID bookId,
            @Param("startInclusive") LocalDate startInclusive,
            @Param("endExclusive") LocalDate endExclusive
    );

    Optional<BookWritingSchedule> findFirstByBookIdOrderByEffectiveFromAsc(UUID bookId);

    long countByBookIdAndEffectiveToIsNull(UUID bookId);
}
