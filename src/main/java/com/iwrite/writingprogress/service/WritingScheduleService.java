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
import java.time.LocalDate;
import java.time.OffsetDateTime;
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

    @PersistenceContext
    private EntityManager entityManager;

    public WritingScheduleService(
            BookWritingScheduleRepository scheduleRepository,
            CurrentUserMembershipService currentUserMembershipService,
            UserRepository userRepository,
            Clock clock
    ) {
        this.scheduleRepository = scheduleRepository;
        this.currentUserMembershipService = currentUserMembershipService;
        this.userRepository = userRepository;
        this.clock = clock;
    }

    @Transactional
    public List<DayOfWeek> createInitialSchedule(Book book, List<DayOfWeek> plannedWritingDays) {
        UUID userId = currentUserMembershipService.requireCurrentUserMemberId();
        Set<DayOfWeek> plannedDays = normalizePlannedDaysOrDefault(plannedWritingDays);
        BookWritingSchedule schedule = newSchedule(book, userId, plannedDays, today());
        scheduleRepository.save(schedule);
        return orderedDays(plannedDays);
    }

    @Transactional
    public List<DayOfWeek> changeSchedule(Book book, List<DayOfWeek> plannedWritingDays) {
        Set<DayOfWeek> plannedDays = normalizePlannedDays(plannedWritingDays);
        LocalDate tomorrow = today().plusDays(1);
        BookWritingSchedule activeSchedule = getOrCreateActiveScheduleForCurrentUser(book);

        if (sameDays(activeSchedule.getPlannedDays(), plannedDays)) {
            return orderedDays(activeSchedule.getPlannedDays());
        }

        if (!activeSchedule.getEffectiveFrom().isBefore(tomorrow)) {
            activeSchedule.setPlannedDays(plannedDays);
            return orderedDays(plannedDays);
        }

        activeSchedule.setEffectiveTo(tomorrow);
        scheduleRepository.saveAndFlush(activeSchedule);

        BookWritingSchedule newSchedule = newSchedule(book, activeSchedule.getUser().getId(), plannedDays, tomorrow);
        scheduleRepository.save(newSchedule);

        return orderedDays(plannedDays);
    }

    @Transactional
    public List<DayOfWeek> getActivePlannedWritingDays(Book book) {
        return orderedDays(getOrCreateActiveScheduleForCurrentUser(book).getPlannedDays());
    }

    @Transactional
    public BookWritingSchedule getOrCreateActiveScheduleForCurrentUser(Book book) {
        UUID userId = currentUserMembershipService.requireCurrentUserMemberId();
        return scheduleRepository.findFirstByUser_IdAndBookIdAndEffectiveToIsNull(userId, book.getId())
                .orElseGet(() -> createDefaultActiveScheduleWithConflictReload(book.getId(), userId));
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
        BookWritingSchedule activeSchedule = getOrCreateActiveScheduleForCurrentUser(book);
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
        BookWritingSchedule activeSchedule = getOrCreateActiveScheduleForCurrentUser(book);
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
        BookWritingSchedule activeSchedule = getOrCreateActiveScheduleForCurrentUser(book);
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

    private BookWritingSchedule createDefaultActiveScheduleWithConflictReload(UUID bookId, UUID userId) {
        UUID scheduleId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(clock);
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
                .setParameter("effectiveFrom", today())
                .setParameter("now", now)
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

    private BookWritingSchedule newSchedule(Book book, UUID userId, Set<DayOfWeek> plannedDays, LocalDate effectiveFrom) {
        BookWritingSchedule schedule = new BookWritingSchedule();
        schedule.setBook(book);
        schedule.setUser(userRepository.getReferenceById(userId));
        schedule.setEffectiveFrom(effectiveFrom);
        schedule.setPlannedDays(plannedDays);
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

    private LocalDate today() {
        return LocalDate.now(clock);
    }
}
