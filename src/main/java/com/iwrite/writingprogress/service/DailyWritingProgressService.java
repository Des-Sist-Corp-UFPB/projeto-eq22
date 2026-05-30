package com.iwrite.writingprogress.service;

import com.iwrite.book.entity.Book;
import com.iwrite.book.service.BookService;
import com.iwrite.dashboard.dto.WritingConsistencyResponse;
import com.iwrite.writingprogress.entity.DailyWritingProgress;
import com.iwrite.writingprogress.repository.DailyWritingProgressRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class DailyWritingProgressService {

    private static final int WRITING_DAY_THRESHOLD = 0;
    private static final int RECENT_CONSISTENCY_WINDOW_DAYS = 7;

    private final BookService bookService;
    private final DailyWritingProgressRepository progressRepository;
    private final Clock clock;

    public DailyWritingProgressService(
            BookService bookService,
            DailyWritingProgressRepository progressRepository,
            Clock clock
    ) {
        this.bookService = bookService;
        this.progressRepository = progressRepository;
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

        progress.setEndWordCount(totalAfter);
        progress.setNetWordCountChange(totalAfter - progress.getStartWordCount());
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
        LocalDate today = today();
        List<DailyWritingProgress> positiveProgress = progressRepository
                .findByBookIdAndNetWordCountChangeGreaterThanOrderByProgressDateAsc(bookId, WRITING_DAY_THRESHOLD);
        Set<LocalDate> positiveDates = positiveDates(positiveProgress);

        int recentWritingDays = countPositiveProgressBetween(
                bookId,
                today.minusDays(RECENT_CONSISTENCY_WINDOW_DAYS - 1),
                today
        );

        return new WritingConsistencyResponse(
                currentStreakDays(today, positiveDates),
                bestStreakDays(positiveProgress),
                countPositiveProgressBetween(bookId, today.withDayOfMonth(1), today),
                RECENT_CONSISTENCY_WINDOW_DAYS,
                recentWritingDays,
                (recentWritingDays * 100.0) / RECENT_CONSISTENCY_WINDOW_DAYS
        );
    }

    private Set<LocalDate> positiveDates(List<DailyWritingProgress> positiveProgress) {
        Set<LocalDate> dates = new HashSet<>();
        for (DailyWritingProgress progress : positiveProgress) {
            dates.add(progress.getProgressDate());
        }
        return dates;
    }

    private int currentStreakDays(LocalDate today, Set<LocalDate> positiveDates) {
        LocalDate streakEndDate;
        if (positiveDates.contains(today)) {
            streakEndDate = today;
        } else if (positiveDates.contains(today.minusDays(1))) {
            streakEndDate = today.minusDays(1);
        } else {
            return 0;
        }

        int streakDays = 0;
        LocalDate progressDate = streakEndDate;
        while (positiveDates.contains(progressDate)) {
            streakDays++;
            progressDate = progressDate.minusDays(1);
        }
        return streakDays;
    }

    private int bestStreakDays(List<DailyWritingProgress> positiveProgress) {
        int bestStreakDays = 0;
        int currentStreakDays = 0;
        LocalDate previousProgressDate = null;

        for (DailyWritingProgress progress : positiveProgress) {
            LocalDate progressDate = progress.getProgressDate();
            if (previousProgressDate != null && progressDate.equals(previousProgressDate.plusDays(1))) {
                currentStreakDays++;
            } else {
                currentStreakDays = 1;
            }

            bestStreakDays = Math.max(bestStreakDays, currentStreakDays);
            previousProgressDate = progressDate;
        }

        return bestStreakDays;
    }

    private int countPositiveProgressBetween(UUID bookId, LocalDate startDate, LocalDate endDate) {
        return Math.toIntExact(progressRepository.countByBookIdAndProgressDateBetweenAndNetWordCountChangeGreaterThan(
                bookId,
                startDate,
                endDate,
                WRITING_DAY_THRESHOLD
        ));
    }

    private DailyWritingProgress createProgress(Book book, LocalDate progressDate, int totalBefore) {
        DailyWritingProgress progress = new DailyWritingProgress();
        progress.setBook(book);
        progress.setProgressDate(progressDate);
        progress.setDailyTargetWordCount(book.getDailyTargetWordCount());
        progress.setStartWordCount(totalBefore);
        progress.setEndWordCount(totalBefore);
        progress.setNetWordCountChange(0);
        return progress;
    }

    private DailyWritingProgress emptyTodayProgress(UUID bookId, LocalDate progressDate, int currentTotalWordCount) {
        Book book = bookService.getBook(bookId);

        DailyWritingProgress progress = new DailyWritingProgress();
        progress.setBook(book);
        progress.setProgressDate(progressDate);
        progress.setDailyTargetWordCount(book.getDailyTargetWordCount());
        progress.setStartWordCount(currentTotalWordCount);
        progress.setEndWordCount(currentTotalWordCount);
        progress.setNetWordCountChange(0);
        return progress;
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }
}
