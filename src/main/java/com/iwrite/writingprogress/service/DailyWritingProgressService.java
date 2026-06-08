package com.iwrite.writingprogress.service;

import com.iwrite.book.entity.Book;
import com.iwrite.book.service.BookService;
import com.iwrite.dashboard.dto.WritingConsistencyResponse;
import com.iwrite.writingprogress.entity.BookWritingSchedule;
import com.iwrite.writingprogress.entity.DailyWritingProgress;
import com.iwrite.writingprogress.repository.DailyWritingProgressRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.UUID;

@Service
public class DailyWritingProgressService {

    private static final int WRITING_DAY_THRESHOLD = 0;
    private final BookService bookService;
    private final DailyWritingProgressRepository progressRepository;
    private final WritingScheduleService writingScheduleService;
    private final Clock clock;

    public DailyWritingProgressService(
            BookService bookService,
            DailyWritingProgressRepository progressRepository,
            WritingScheduleService writingScheduleService,
            Clock clock
    ) {
        this.bookService = bookService;
        this.progressRepository = progressRepository;
        this.writingScheduleService = writingScheduleService;
        this.clock = clock;
    }

    @Transactional
    public void recordWordCountChange(UUID bookId, int totalBefore, int totalAfter) {
        if (totalBefore == totalAfter) {
            return;
        }

        Book book = bookService.getBook(bookId);
        LocalDate progressDate = today();

        DailyWritingProgress progress = progressRepository.findByBookIdAndProgressDate(bookId, progressDate)
                .orElseGet(() -> createProgress(book, progressDate, totalBefore));

        progress.setEndingManuscriptWordCount(totalAfter);
        progress.setProductiveWordCountChange(totalAfter - progress.getStartingManuscriptWordCount());
        progressRepository.save(progress);
    }

    @Transactional(readOnly = true)
    public DailyWritingProgress getTodayProgressOrEmpty(UUID bookId, int currentTotalWordCount) {
        LocalDate progressDate = today();
        return progressRepository.findByBookIdAndProgressDate(bookId, progressDate)
                .orElseGet(() -> emptyTodayProgress(bookId, progressDate, currentTotalWordCount));
    }

    @Transactional(readOnly = true)
    public List<DailyWritingProgress> getRecentProgress(UUID bookId) {
        return getRecentProgress(bookId, WritingProgressPeriod.DEFAULT);
    }

    @Transactional(readOnly = true)
    public List<DailyWritingProgress> getRecentProgress(UUID bookId, WritingProgressPeriod period) {
        LocalDate endDate = today();
        LocalDate startDate = period.startDateInclusive(endDate);
        return progressRepository.findByBookIdAndProgressDateBetweenOrderByProgressDateDesc(bookId, startDate, endDate);
    }

    @Transactional(readOnly = true)
    public WritingConsistencyResponse getWritingConsistency(UUID bookId) {
        return getWritingConsistency(bookId, WritingProgressPeriod.DEFAULT);
    }

    @Transactional(readOnly = true)
    public WritingConsistencyResponse getWritingConsistency(UUID bookId, WritingProgressPeriod period) {
        LocalDate today = today();
        LocalDate earliestScheduleDate = writingScheduleService.getEarliestEffectiveFrom(bookId);
        LocalDate calculationStartDate = progressRepository.findFirstByBookIdOrderByProgressDateAsc(bookId)
                .map(DailyWritingProgress::getProgressDate)
                .filter(progressDate -> progressDate.isBefore(earliestScheduleDate))
                .orElse(earliestScheduleDate);
        LocalDate recentStartDate = period.startDateInclusive(today);
        List<DailyWritingProgress> progressHistory = progressRepository
                .findByBookIdAndProgressDateBetweenOrderByProgressDateAsc(bookId, calculationStartDate, today);
        Map<LocalDate, DailyWritingProgress> progressByDate = progressHistory.stream()
                .collect(Collectors.toMap(DailyWritingProgress::getProgressDate, Function.identity()));
        List<BookWritingSchedule> fullScheduleHistory =
                writingScheduleService.getSchedulesForRange(bookId, calculationStartDate, today);
        List<BookWritingSchedule> recentScheduleHistory =
                writingScheduleService.getSchedulesForRange(bookId, recentStartDate, today);

        int recentWritingDays = countPositiveProgressBetween(bookId, recentStartDate, today);
        int recentWindowDays = Math.toIntExact(java.time.temporal.ChronoUnit.DAYS.between(recentStartDate, today) + 1);
        int recentPlannedWritingDays = countPlannedDays(recentStartDate, today, recentScheduleHistory);
        int recentSuccessfulPlannedWritingDays = countSuccessfulPlannedDays(recentStartDate, today, progressByDate, recentScheduleHistory);

        return new WritingConsistencyResponse(
                currentStreakDays(calculationStartDate, today, progressByDate, fullScheduleHistory),
                bestStreakDays(calculationStartDate, today, progressByDate, fullScheduleHistory),
                countPositiveProgressBetween(bookId, today.withDayOfMonth(1), today),
                recentWindowDays,
                recentWritingDays,
                recentWindowDays == 0 ? 0.0 : (recentWritingDays * 100.0) / recentWindowDays,
                recentPlannedWritingDays,
                recentSuccessfulPlannedWritingDays,
                recentPlannedWritingDays == 0 ? 0.0 : (recentSuccessfulPlannedWritingDays * 100.0) / recentPlannedWritingDays
        );
    }

    private int currentStreakDays(
            LocalDate earliestScheduleDate,
            LocalDate today,
            Map<LocalDate, DailyWritingProgress> progressByDate,
            List<BookWritingSchedule> schedules
    ) {
        int streakDays = 0;
        LocalDate progressDate = today;

        while (!progressDate.isBefore(earliestScheduleDate)) {
            if (!writingScheduleService.isPlannedWritingDay(progressDate, schedules)) {
                progressDate = progressDate.minusDays(1);
                continue;
            }

            DailyWritingProgress progress = progressByDate.get(progressDate);
            if (progressDate.equals(today) && (progress == null || isManuscriptAdjustmentOnly(progress))) {
                progressDate = progressDate.minusDays(1);
                continue;
            }
            if (isProductiveWritingDay(progress)) {
                streakDays++;
                progressDate = progressDate.minusDays(1);
                continue;
            }

            break;
        }

        return streakDays;
    }

    private int bestStreakDays(
            LocalDate earliestScheduleDate,
            LocalDate today,
            Map<LocalDate, DailyWritingProgress> progressByDate,
            List<BookWritingSchedule> schedules
    ) {
        int bestStreakDays = 0;
        int currentStreakDays = 0;
        LocalDate progressDate = earliestScheduleDate;

        while (!progressDate.isAfter(today)) {
            if (!writingScheduleService.isPlannedWritingDay(progressDate, schedules)) {
                progressDate = progressDate.plusDays(1);
                continue;
            }

            DailyWritingProgress progress = progressByDate.get(progressDate);
            if (isProductiveWritingDay(progress)) {
                currentStreakDays++;
                bestStreakDays = Math.max(bestStreakDays, currentStreakDays);
            } else {
                currentStreakDays = 0;
            }

            progressDate = progressDate.plusDays(1);
        }

        return bestStreakDays;
    }

    private int countPlannedDays(
            LocalDate startDate,
            LocalDate endDate,
            List<BookWritingSchedule> schedules
    ) {
        int plannedDays = 0;
        LocalDate progressDate = startDate;
        while (!progressDate.isAfter(endDate)) {
            if (writingScheduleService.isPlannedWritingDay(progressDate, schedules)) {
                plannedDays++;
            }
            progressDate = progressDate.plusDays(1);
        }
        return plannedDays;
    }

    private int countSuccessfulPlannedDays(
            LocalDate startDate,
            LocalDate endDate,
            Map<LocalDate, DailyWritingProgress> progressByDate,
            List<BookWritingSchedule> schedules
    ) {
        int successfulPlannedDays = 0;
        LocalDate progressDate = startDate;
        while (!progressDate.isAfter(endDate)) {
            DailyWritingProgress progress = progressByDate.get(progressDate);
            if (writingScheduleService.isPlannedWritingDay(progressDate, schedules)
                    && isProductiveWritingDay(progress)) {
                successfulPlannedDays++;
            }
            progressDate = progressDate.plusDays(1);
        }
        return successfulPlannedDays;
    }

    private int countPositiveProgressBetween(UUID bookId, LocalDate startDate, LocalDate endDate) {
        return Math.toIntExact(progressRepository.countByBookIdAndProgressDateBetweenAndProductiveWordCountChangeGreaterThan(
                bookId,
                startDate,
                endDate,
                WRITING_DAY_THRESHOLD
        ));
    }

    private boolean isProductiveWritingDay(DailyWritingProgress progress) {
        return progress != null && progress.getProductiveWordCountChange() > WRITING_DAY_THRESHOLD;
    }

    private boolean isManuscriptAdjustmentOnly(DailyWritingProgress progress) {
        return progress != null
                && progress.getProductiveWordCountChange() == 0
                && progress.getManuscriptAdjustmentWordCount() != 0;
    }

    private DailyWritingProgress createProgress(Book book, LocalDate progressDate, int totalBefore) {
        DailyWritingProgress progress = new DailyWritingProgress();
        progress.setBook(book);
        progress.setProgressDate(progressDate);
        progress.setDailyTargetWordCount(book.getDailyTargetWordCount());
        progress.setStartingManuscriptWordCount(totalBefore);
        progress.setEndingManuscriptWordCount(totalBefore);
        progress.setProductiveWordCountChange(0);
        progress.setManuscriptAdjustmentWordCount(0);
        return progress;
    }

    private DailyWritingProgress emptyTodayProgress(UUID bookId, LocalDate progressDate, int currentTotalWordCount) {
        Book book = bookService.getBook(bookId);

        DailyWritingProgress progress = new DailyWritingProgress();
        progress.setBook(book);
        progress.setProgressDate(progressDate);
        progress.setDailyTargetWordCount(book.getDailyTargetWordCount());
        progress.setStartingManuscriptWordCount(currentTotalWordCount);
        progress.setEndingManuscriptWordCount(currentTotalWordCount);
        progress.setProductiveWordCountChange(0);
        progress.setManuscriptAdjustmentWordCount(0);
        return progress;
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }
}
