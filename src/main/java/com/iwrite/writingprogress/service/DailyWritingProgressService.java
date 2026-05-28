package com.iwrite.writingprogress.service;

import com.iwrite.book.entity.Book;
import com.iwrite.book.service.BookService;
import com.iwrite.writingprogress.entity.DailyWritingProgress;
import com.iwrite.writingprogress.repository.DailyWritingProgressRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
public class DailyWritingProgressService {

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
