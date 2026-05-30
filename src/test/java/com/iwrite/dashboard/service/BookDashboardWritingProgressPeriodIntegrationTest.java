package com.iwrite.dashboard.service;

import com.iwrite.book.entity.Book;
import com.iwrite.support.PostgresIntegrationTest;
import com.iwrite.writingprogress.entity.DailyWritingProgress;
import com.iwrite.writingprogress.repository.DailyWritingProgressRepository;
import com.iwrite.writingprogress.service.WritingProgressPeriod;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class BookDashboardWritingProgressPeriodIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private BookDashboardService dashboardService;

    @Autowired
    private DailyWritingProgressRepository progressRepository;

    @Test
    void defaultDashboardReturnsSevenDaysOfRecentProgressNewestFirst() {
        var book = createBook("default recent progress");
        LocalDate today = LocalDate.now();
        saveProgress(bookService.getBook(book.id()), today, 0);
        saveProgress(bookService.getBook(book.id()), today.minusDays(1), 1);
        saveProgress(bookService.getBook(book.id()), today.minusDays(6), 6);
        saveProgress(bookService.getBook(book.id()), today.minusDays(7), 7);

        var dashboard = dashboardService.getDashboard(book.id());

        assertThat(dashboard.writingProgress().recentDays())
                .extracting(day -> day.date())
                .containsExactly(today, today.minusDays(1), today.minusDays(6));
    }

    @Test
    void dashboardProgressPeriodThirtyDaysReturnsUpToThirtyDays() {
        var book = createBook("thirty day progress");
        LocalDate today = LocalDate.now();
        Book persistedBook = bookService.getBook(book.id());
        for (int daysAgo = 0; daysAgo <= 30; daysAgo++) {
            saveProgress(persistedBook, today.minusDays(daysAgo), daysAgo);
        }

        var dashboard = dashboardService.getDashboard(book.id(), WritingProgressPeriod.THIRTY_DAYS);

        assertThat(dashboard.writingProgress().recentDays()).hasSize(30);
        assertThat(dashboard.writingProgress().recentDays())
                .extracting(day -> day.date())
                .contains(today, today.minusDays(29))
                .doesNotContain(today.minusDays(30));
    }

    @Test
    void dashboardProgressPeriodThreeMonthsReturnsExpectedRange() {
        var book = createBook("three month progress");
        LocalDate today = LocalDate.now();
        Book persistedBook = bookService.getBook(book.id());
        saveProgress(persistedBook, today, 0);
        saveProgress(persistedBook, today.minusMonths(3), 3);
        saveProgress(persistedBook, today.minusMonths(3).minusDays(1), 4);

        var dashboard = dashboardService.getDashboard(book.id(), WritingProgressPeriod.THREE_MONTHS);

        assertThat(dashboard.writingProgress().recentDays())
                .extracting(day -> day.date())
                .containsExactly(today, today.minusMonths(3));
    }

    @Test
    void dashboardProgressPeriodTwelveMonthsReturnsCurrentMonthAndPreviousElevenMonthsNewestFirst() {
        var book = createBook("twelve month progress");
        LocalDate today = LocalDate.now();
        LocalDate firstDayOfRange = today.minusMonths(11).withDayOfMonth(1);
        Book persistedBook = bookService.getBook(book.id());
        saveProgress(persistedBook, today, 0);
        saveProgress(persistedBook, firstDayOfRange, 11);
        saveProgress(persistedBook, firstDayOfRange.minusDays(1), 12);

        var dashboard = dashboardService.getDashboard(book.id(), WritingProgressPeriod.TWELVE_MONTHS);

        assertThat(dashboard.writingProgress().recentDays())
                .extracting(day -> day.date())
                .containsExactly(today, firstDayOfRange);
    }

    private void saveProgress(Book book, LocalDate progressDate, int wordCount) {
        DailyWritingProgress progress = new DailyWritingProgress();
        progress.setBook(book);
        progress.setProgressDate(progressDate);
        progress.setDailyTargetWordCount(book.getDailyTargetWordCount());
        progress.setStartWordCount(0);
        progress.setEndWordCount(wordCount);
        progress.setNetWordCountChange(wordCount);
        progressRepository.save(progress);
    }
}
