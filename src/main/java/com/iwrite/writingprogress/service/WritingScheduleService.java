package com.iwrite.writingprogress.service;

import com.iwrite.book.entity.Book;
import com.iwrite.common.exception.BadRequestException;
import com.iwrite.common.exception.ResourceNotFoundException;
import com.iwrite.user.context.CurrentUserMembershipService;
import com.iwrite.user.repository.UserRepository;
import com.iwrite.writingprogress.entity.BookWritingSchedule;
import com.iwrite.writingprogress.repository.BookWritingScheduleRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class WritingScheduleService {

    private static final List<DayOfWeek> ORDERED_DAYS = List.of(
            DayOfWeek.MONDAY,
            DayOfWeek.TUESDAY,
            DayOfWeek.WEDNESDAY,
            DayOfWeek.THURSDAY,
            DayOfWeek.FRIDAY,
            DayOfWeek.SATURDAY,
            DayOfWeek.SUNDAY
    );

    private final BookWritingScheduleRepository scheduleRepository;
    private final CurrentUserMembershipService currentUserMembershipService;
    private final UserRepository userRepository;
    private final Clock clock;
    private final WritingDayResolver writingDayResolver;

    @PersistenceContext
    private EntityManager entityManager;

    public WritingScheduleService(
            BookWritingScheduleRepository scheduleRepository,
            CurrentUserMembershipService currentUserMembershipService,
            UserRepository userRepository,
            Clock clock,
            WritingDayResolver writingDayResolver
    ) {
        this.scheduleRepository = scheduleRepository;
        this.currentUserMembershipService = currentUserMembershipService;
        this.userRepository = userRepository;
        this.clock = clock;
        this.writingDayResolver = writingDayResolver;
    }

    @Transactional
    public List<DayOfWeek> createInitialSchedule(Book book, List<DayOfWeek> plannedWritingDays) {
        UUID userId = currentUserMembershipService.requireCurrentUserMemberId();
        Set<DayOfWeek> plannedDays = normalizePlannedDaysOrDefault(plannedWritingDays);
        ScheduleOperationTime operationTime = currentOperationTime();
        BookWritingSchedule schedule = newSchedule(book, userId, plannedDays, operationTime.date(), operationTime.timestamp());
        scheduleRepository.save(schedule);
        return orderedDays(plannedDays);
    }

    @Transactional
    public List<DayOfWeek> changeSchedule(Book book, List<DayOfWeek> plannedWritingDays) {
        Set<DayOfWeek> plannedDays = normalizePlannedDays(plannedWritingDays);
        ScheduleOperationTime operationTime = currentOperationTime();
        LocalDate tomorrow = operationTime.date().plusDays(1);
        BookWritingSchedule activeSchedule = getOrCreateActiveScheduleForCurrentUser(book, operationTime);

        if (sameDays(activeSchedule.getPlannedDays(), plannedDays)) {
            return orderedDays(activeSchedule.getPlannedDays());
        }

        if (!activeSchedule.getEffectiveFrom().isBefore(tomorrow)) {
            activeSchedule.setPlannedDays(plannedDays);
            activeSchedule.setUpdatedAt(operationTime.timestamp());
            return orderedDays(plannedDays);
        }

        activeSchedule.setEffectiveTo(tomorrow);
        activeSchedule.setUpdatedAt(operationTime.timestamp());
        scheduleRepository.saveAndFlush(activeSchedule);

        BookWritingSchedule newSchedule = newSchedule(
                book,
                activeSchedule.getUser().getId(),
                plannedDays,
                tomorrow,
                operationTime.timestamp()
        );
        scheduleRepository.save(newSchedule);

        return orderedDays(plannedDays);
    }

    @Transactional
    public List<DayOfWeek> getActivePlannedWritingDays(Book book) {
        return orderedDays(getOrCreateActiveScheduleForCurrentUser(book, currentOperationTime()).getPlannedDays());
    }

    @Transactional
    public BookWritingSchedule getOrCreateActiveScheduleForCurrentUser(Book book) {
        return getOrCreateActiveScheduleForCurrentUser(book, currentOperationTime());
    }

    private BookWritingSchedule getOrCreateActiveScheduleForCurrentUser(Book book, ScheduleOperationTime operationTime) {
        UUID userId = currentUserMembershipService.requireCurrentUserMemberId();
        return scheduleRepository.findFirstByUser_IdAndBookIdAndEffectiveToIsNull(userId, book.getId())
                .orElseGet(() -> createDefaultActiveScheduleWithConflictReload(book.getId(), userId, operationTime));
    }

    @Transactional(readOnly = true)
    public BookWritingSchedule getActiveSchedule(UUID bookId) {
        UUID userId = currentUserMembershipService.requireCurrentUserMemberId();
        return scheduleRepository.findFirstByUser_IdAndBookIdAndEffectiveToIsNull(userId, bookId)
                .orElseThrow(() -> new ResourceNotFoundException("Active writing schedule not found for book: " + bookId));
    }

    @Transactional(readOnly = true)
    public BookWritingSchedule getScheduleForDate(UUID bookId, LocalDate date) {
        UUID userId = currentUserMembershipService.requireCurrentUserMemberId();
        return scheduleRepository.findByUserIdAndBookIdAndDate(userId, bookId, date)
                .orElseThrow(() -> new ResourceNotFoundException("Writing schedule not found for book date: " + bookId + " " + date));
    }

    @Transactional
    public BookWritingSchedule getScheduleForDate(Book book, LocalDate date) {
        BookWritingSchedule activeSchedule = getOrCreateActiveScheduleForCurrentUser(book, currentOperationTime());
        UUID userId = activeSchedule.getUser().getId();
        return scheduleRepository.findByUserIdAndBookIdAndDate(userId, book.getId(), date)
                .orElseThrow(() -> new ResourceNotFoundException("Writing schedule not found for book date: " + book.getId() + " " + date));
    }

    @Transactional(readOnly = true)
    public List<BookWritingSchedule> getSchedulesForRange(UUID bookId, LocalDate startInclusive, LocalDate endInclusive) {
        UUID userId = currentUserMembershipService.requireCurrentUserMemberId();
        return scheduleRepository.findByUserIdAndBookIdOverlappingPeriod(userId, bookId, startInclusive, endInclusive.plusDays(1));
    }

    @Transactional
    public List<BookWritingSchedule> getSchedulesForRange(Book book, LocalDate startInclusive, LocalDate endInclusive) {
        BookWritingSchedule activeSchedule = getOrCreateActiveScheduleForCurrentUser(book, currentOperationTime());
        return scheduleRepository.findByUserIdAndBookIdOverlappingPeriod(
                activeSchedule.getUser().getId(),
                book.getId(),
                startInclusive,
                endInclusive.plusDays(1)
        );
    }

    @Transactional(readOnly = true)
    public LocalDate getEarliestEffectiveFrom(UUID bookId) {
        UUID userId = currentUserMembershipService.requireCurrentUserMemberId();
        return scheduleRepository.findFirstByUser_IdAndBookIdOrderByEffectiveFromAsc(userId, bookId)
                .map(BookWritingSchedule::getEffectiveFrom)
                .orElseThrow(() -> new ResourceNotFoundException("Writing schedule not found for book: " + bookId));
    }

    @Transactional
    public LocalDate getEarliestEffectiveFrom(Book book) {
        BookWritingSchedule activeSchedule = getOrCreateActiveScheduleForCurrentUser(book, currentOperationTime());
        return scheduleRepository.findFirstByUser_IdAndBookIdOrderByEffectiveFromAsc(activeSchedule.getUser().getId(), book.getId())
                .map(BookWritingSchedule::getEffectiveFrom)
                .orElse(activeSchedule.getEffectiveFrom());
    }

    public boolean isPlannedWritingDay(LocalDate date, List<BookWritingSchedule> schedules) {
        return scheduleForDate(date, schedules)
                .getPlannedDays()
                .contains(date.getDayOfWeek());
    }

    public List<DayOfWeek> restDays(List<DayOfWeek> plannedWritingDays) {
        Set<DayOfWeek> plannedDays = EnumSet.copyOf(plannedWritingDays);
        return ORDERED_DAYS.stream()
                .filter(day -> !plannedDays.contains(day))
                .toList();
    }

    public List<DayOfWeek> orderedDays(Set<DayOfWeek> plannedDays) {
        return ORDERED_DAYS.stream()
                .filter(plannedDays::contains)
                .toList();
    }

    private BookWritingSchedule scheduleForDate(LocalDate date, List<BookWritingSchedule> schedules) {
        BookWritingSchedule firstSchedule = schedules.isEmpty() ? null : schedules.get(0);
        return schedules.stream()
                .filter(schedule -> isEffectiveOn(schedule, date))
                .findFirst()
                .orElseGet(() -> {
                    if (firstSchedule != null && date.isBefore(firstSchedule.getEffectiveFrom())) {
                        return firstSchedule;
                    }
                    throw new ResourceNotFoundException("Writing schedule not found for date: " + date);
                });
    }

    private boolean isEffectiveOn(BookWritingSchedule schedule, LocalDate date) {
        return !schedule.getEffectiveFrom().isAfter(date)
                && (schedule.getEffectiveTo() == null || schedule.getEffectiveTo().isAfter(date));
    }

    private BookWritingSchedule createDefaultActiveScheduleWithConflictReload(
            UUID bookId,
            UUID userId,
            ScheduleOperationTime operationTime
    ) {
        UUID scheduleId = UUID.randomUUID();
        int inserted = entityManager.createNativeQuery("""
                        insert into book_writing_schedules (
                            id,
                            book_id,
                            user_id,
                            effective_from,
                            effective_to,
                            created_at,
                            updated_at
                        )
                        values (:id, :bookId, :userId, :effectiveFrom, null, :now, :now)
                        on conflict (user_id, book_id)
                        where effective_to is null
                        do nothing
                        """)
                .setParameter("id", scheduleId)
                .setParameter("bookId", bookId)
                .setParameter("userId", userId)
                .setParameter("effectiveFrom", operationTime.date())
                .setParameter("now", operationTime.timestamp())
                .executeUpdate();

        if (inserted == 1) {
            for (DayOfWeek day : ORDERED_DAYS) {
                entityManager.createNativeQuery("""
                                insert into book_writing_schedule_days (schedule_id, day_of_week)
                                values (:scheduleId, :day)
                                """)
                        .setParameter("scheduleId", scheduleId)
                        .setParameter("day", day.name())
                        .executeUpdate();
            }
        }

        return scheduleRepository.findFirstByUser_IdAndBookIdAndEffectiveToIsNull(userId, bookId)
                .orElseThrow(() -> new ResourceNotFoundException("Active writing schedule not found for book: " + bookId));
    }

    private BookWritingSchedule newSchedule(
            Book book,
            UUID userId,
            Set<DayOfWeek> plannedDays,
            LocalDate effectiveFrom,
            OffsetDateTime timestamp
    ) {
        BookWritingSchedule schedule = new BookWritingSchedule();
        schedule.setBook(book);
        schedule.setUser(userRepository.getReferenceById(userId));
        schedule.setEffectiveFrom(effectiveFrom);
        schedule.setPlannedDays(plannedDays);
        schedule.setCreatedAt(timestamp);
        schedule.setUpdatedAt(timestamp);
        return schedule;
    }

    private Set<DayOfWeek> normalizePlannedDaysOrDefault(List<DayOfWeek> plannedWritingDays) {
        if (plannedWritingDays == null) {
            return EnumSet.copyOf(ORDERED_DAYS);
        }

        return normalizePlannedDays(plannedWritingDays);
    }

    private Set<DayOfWeek> normalizePlannedDays(List<DayOfWeek> plannedWritingDays) {
        if (plannedWritingDays == null || plannedWritingDays.isEmpty()) {
            throw new BadRequestException("plannedWritingDays must contain at least one day");
        }
        if (plannedWritingDays.stream().anyMatch(Objects::isNull)) {
            throw new BadRequestException("plannedWritingDays must contain only valid weekdays");
        }

        return EnumSet.copyOf(plannedWritingDays);
    }

    private boolean sameDays(Set<DayOfWeek> first, Set<DayOfWeek> second) {
        return first.size() == second.size() && first.containsAll(second);
    }

    private ScheduleOperationTime currentOperationTime() {
        Instant operationInstant = clock.instant();
        OffsetDateTime operationTimestamp = operationInstant.atOffset(ZoneOffset.UTC);
        LocalDate operationDate = writingDayResolver.writingDateFor(operationInstant);
        return new ScheduleOperationTime(operationTimestamp, operationDate);
    }

    private record ScheduleOperationTime(OffsetDateTime timestamp, LocalDate date) {
    }
}
