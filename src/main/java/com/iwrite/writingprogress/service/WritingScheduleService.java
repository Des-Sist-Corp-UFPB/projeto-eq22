package com.iwrite.writingprogress.service;

import com.iwrite.book.entity.Book;
import com.iwrite.common.exception.BadRequestException;
import com.iwrite.common.exception.ResourceNotFoundException;
import com.iwrite.writingprogress.entity.BookWritingSchedule;
import com.iwrite.writingprogress.repository.BookWritingScheduleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
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
    private final Clock clock;

    public WritingScheduleService(BookWritingScheduleRepository scheduleRepository, Clock clock) {
        this.scheduleRepository = scheduleRepository;
        this.clock = clock;
    }

    @Transactional
    public List<DayOfWeek> createInitialSchedule(Book book, List<DayOfWeek> plannedWritingDays) {
        Set<DayOfWeek> plannedDays = normalizePlannedDaysOrDefault(plannedWritingDays);
        BookWritingSchedule schedule = new BookWritingSchedule();
        schedule.setBook(book);
        schedule.setEffectiveFrom(today());
        schedule.setPlannedDays(plannedDays);
        scheduleRepository.save(schedule);
        return orderedDays(plannedDays);
    }

    @Transactional
    public List<DayOfWeek> changeSchedule(Book book, List<DayOfWeek> plannedWritingDays) {
        Set<DayOfWeek> plannedDays = normalizePlannedDays(plannedWritingDays);
        LocalDate tomorrow = today().plusDays(1);
        BookWritingSchedule activeSchedule = scheduleRepository.findActiveByBookIdForUpdate(book.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Active writing schedule not found for book: " + book.getId()));

        if (sameDays(activeSchedule.getPlannedDays(), plannedDays)) {
            return orderedDays(activeSchedule.getPlannedDays());
        }

        if (!activeSchedule.getEffectiveFrom().isBefore(tomorrow)) {
            activeSchedule.setPlannedDays(plannedDays);
            return orderedDays(plannedDays);
        }

        activeSchedule.setEffectiveTo(tomorrow);
        scheduleRepository.saveAndFlush(activeSchedule);

        BookWritingSchedule newSchedule = new BookWritingSchedule();
        newSchedule.setBook(book);
        newSchedule.setEffectiveFrom(tomorrow);
        newSchedule.setPlannedDays(plannedDays);
        scheduleRepository.save(newSchedule);

        return orderedDays(plannedDays);
    }

    @Transactional(readOnly = true)
    public List<DayOfWeek> getActivePlannedWritingDays(UUID bookId) {
        return orderedDays(getActiveSchedule(bookId).getPlannedDays());
    }

    @Transactional(readOnly = true)
    public BookWritingSchedule getActiveSchedule(UUID bookId) {
        return scheduleRepository.findFirstByBookIdAndEffectiveToIsNull(bookId)
                .orElseThrow(() -> new ResourceNotFoundException("Active writing schedule not found for book: " + bookId));
    }

    @Transactional(readOnly = true)
    public BookWritingSchedule getScheduleForDate(UUID bookId, LocalDate date) {
        return scheduleRepository.findByBookIdAndDate(bookId, date)
                .orElseThrow(() -> new ResourceNotFoundException("Writing schedule not found for book date: " + bookId + " " + date));
    }

    @Transactional(readOnly = true)
    public List<BookWritingSchedule> getSchedulesForRange(UUID bookId, LocalDate startInclusive, LocalDate endInclusive) {
        return scheduleRepository.findByBookIdOverlappingPeriod(bookId, startInclusive, endInclusive.plusDays(1));
    }

    @Transactional(readOnly = true)
    public LocalDate getEarliestEffectiveFrom(UUID bookId) {
        return scheduleRepository.findFirstByBookIdOrderByEffectiveFromAsc(bookId)
                .map(BookWritingSchedule::getEffectiveFrom)
                .orElseThrow(() -> new ResourceNotFoundException("Writing schedule not found for book: " + bookId));
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
