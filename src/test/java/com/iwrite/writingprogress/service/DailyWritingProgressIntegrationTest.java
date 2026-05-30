package com.iwrite.writingprogress.service;

import com.iwrite.book.dto.BookUpdateRequest;
import com.iwrite.book.entity.Book;
import com.iwrite.dashboard.service.BookDashboardService;
import com.iwrite.scene.dto.SceneContentRequest;
import com.iwrite.scene.entity.SceneStatus;
import com.iwrite.support.PostgresIntegrationTest;
import com.iwrite.writingprogress.entity.DailyWritingProgress;
import com.iwrite.writingprogress.repository.DailyWritingProgressRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class DailyWritingProgressIntegrationTest extends PostgresIntegrationTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 5, 30);

    @Autowired
    private DailyWritingProgressRepository progressRepository;

    @Autowired
    private BookDashboardService dashboardService;

    @Test
    void sceneContentUpdateCreatesTodayProgressFromBeforeAndAfterTotals() {
        var book = createBook("content progress");
        var section = createSection(book, "Part");
        var chapter = createChapter(section, "Chapter");
        var scene = createScene(chapter, "Scene", SceneStatus.DRAFT, 0, "");

        sceneService.updateContent(scene.id(), new SceneContentRequest("{}", wordText(5)));

        DailyWritingProgress progress = progressRepository.findByBookIdAndProgressDate(book.id(), TODAY)
                .orElseThrow();
        assertThat(progress.getStartWordCount()).isZero();
        assertThat(progress.getEndWordCount()).isEqualTo(5);
        assertThat(progress.getNetWordCountChange()).isEqualTo(5);
    }

    @Test
    void multipleSavesOnSameDayUpdateOneProgressRow() {
        var book = createBook("multiple saves");
        var section = createSection(book, "Part");
        var chapter = createChapter(section, "Chapter");
        var scene = createScene(chapter, "Scene", SceneStatus.DRAFT, 0, "");

        sceneService.updateContent(scene.id(), new SceneContentRequest("{}", wordText(2)));
        sceneService.updateContent(scene.id(), new SceneContentRequest("{}", wordText(5)));

        assertThat(progressRepository.countByBookIdAndProgressDate(book.id(), TODAY)).isEqualTo(1);
        DailyWritingProgress progress = progressRepository.findByBookIdAndProgressDate(book.id(), TODAY)
                .orElseThrow();
        assertThat(progress.getStartWordCount()).isZero();
        assertThat(progress.getEndWordCount()).isEqualTo(5);
        assertThat(progress.getNetWordCountChange()).isEqualTo(5);
    }

    @Test
    void sceneDeleteUpdatesNetProgress() {
        var book = createBook("delete progress");
        var section = createSection(book, "Part");
        var chapter = createChapter(section, "Chapter");
        var scene = createScene(chapter, "Scene", SceneStatus.DRAFT, 0, wordText(4));

        sceneService.delete(scene.id());

        DailyWritingProgress progress = progressRepository.findByBookIdAndProgressDate(book.id(), TODAY)
                .orElseThrow();
        assertThat(progress.getStartWordCount()).isZero();
        assertThat(progress.getEndWordCount()).isZero();
        assertThat(progress.getNetWordCountChange()).isZero();
    }

    @Test
    void dashboardReturnsSafeEmptyWritingProgressWithoutProgressRows() {
        var book = createBook("empty writing progress");

        var dashboard = dashboardService.getDashboard(book.id());

        assertThat(dashboard.writingProgress().today().date()).isEqualTo(TODAY);
        assertThat(dashboard.writingProgress().today().dailyTargetWordCount()).isNull();
        assertThat(dashboard.writingProgress().today().startWordCount()).isZero();
        assertThat(dashboard.writingProgress().today().endWordCount()).isZero();
        assertThat(dashboard.writingProgress().today().netWordCountChange()).isZero();
        assertThat(dashboard.writingProgress().today().progressPercent()).isNull();
        assertThat(dashboard.writingProgress().recentDays()).isEmpty();
    }

    @Test
    void dashboardReturnsTodayAndRecentDaysNewestFirst() {
        var book = createBook("recent writing progress");
        BookUpdateRequest targetRequest = new BookUpdateRequest();
        targetRequest.setDailyTargetWordCount(6);
        bookService.update(book.id(), targetRequest);
        var section = createSection(book, "Part");
        var chapter = createChapter(section, "Chapter");
        var scene = createScene(chapter, "Scene", SceneStatus.DRAFT, 0, "");
        LocalDate today = TODAY;

        saveProgress(bookService.getBook(book.id()), today.minusDays(2), 3, 7);
        saveProgress(bookService.getBook(book.id()), today.minusDays(1), 7, 9);
        sceneService.updateContent(scene.id(), new SceneContentRequest("{}", wordText(3)));

        var dashboard = dashboardService.getDashboard(book.id());

        assertThat(dashboard.writingProgress().today().date()).isEqualTo(today);
        assertThat(dashboard.writingProgress().today().dailyTargetWordCount()).isEqualTo(6);
        assertThat(dashboard.writingProgress().today().netWordCountChange()).isEqualTo(3);
        assertThat(dashboard.writingProgress().today().progressPercent()).isEqualTo(50.0);
        assertThat(dashboard.writingProgress().recentDays())
                .extracting(day -> day.date())
                .containsExactly(today, today.minusDays(1), today.minusDays(2));
    }

    @Test
    void dashboardCurrentStreakIncludesTodayWhenTodayIsPositive() {
        var book = createBook("current streak includes today");
        LocalDate today = TODAY;
        Book persistedBook = bookService.getBook(book.id());

        saveProgress(persistedBook, today.minusDays(2), 0, 4);
        saveProgress(persistedBook, today.minusDays(1), 4, 7);
        saveProgress(persistedBook, today, 7, 9);

        var consistency = dashboardService.getDashboard(book.id()).writingProgress().consistency();

        assertThat(consistency.currentStreakDays()).isEqualTo(3);
        assertThat(consistency.bestStreakDays()).isEqualTo(3);
    }

    @Test
    void dashboardCurrentStreakFallsBackToYesterdayWhenTodayIsMissingOrZero() {
        LocalDate today = TODAY;

        var missingTodayBook = createBook("current streak missing today");
        Book persistedMissingTodayBook = bookService.getBook(missingTodayBook.id());
        saveProgress(persistedMissingTodayBook, today.minusDays(2), 0, 1);
        saveProgress(persistedMissingTodayBook, today.minusDays(1), 1, 3);

        var zeroTodayBook = createBook("current streak zero today");
        Book persistedZeroTodayBook = bookService.getBook(zeroTodayBook.id());
        saveProgress(persistedZeroTodayBook, today.minusDays(2), 0, 1);
        saveProgress(persistedZeroTodayBook, today.minusDays(1), 1, 3);
        saveProgress(persistedZeroTodayBook, today, 3, 3);

        assertThat(dashboardService.getDashboard(missingTodayBook.id()).writingProgress().consistency().currentStreakDays())
                .isEqualTo(2);
        assertThat(dashboardService.getDashboard(zeroTodayBook.id()).writingProgress().consistency().currentStreakDays())
                .isEqualTo(2);
    }

    @Test
    void dashboardCurrentStreakIsZeroWhenNeitherTodayNorYesterdayIsPositive() {
        var book = createBook("current streak zero");
        LocalDate today = TODAY;
        Book persistedBook = bookService.getBook(book.id());

        saveProgress(persistedBook, today.minusDays(3), 0, 2);
        saveProgress(persistedBook, today.minusDays(1), 2, 2);
        saveProgress(persistedBook, today, 2, 1);

        var consistency = dashboardService.getDashboard(book.id()).writingProgress().consistency();

        assertThat(consistency.currentStreakDays()).isZero();
        assertThat(consistency.bestStreakDays()).isEqualTo(1);
    }

    @Test
    void dashboardMissingDateBreaksCurrentAndBestStreak() {
        var book = createBook("missing date breaks streak");
        LocalDate today = TODAY;
        Book persistedBook = bookService.getBook(book.id());

        saveProgress(persistedBook, today.minusDays(4), 0, 1);
        saveProgress(persistedBook, today.minusDays(3), 1, 2);
        saveProgress(persistedBook, today.minusDays(1), 2, 3);
        saveProgress(persistedBook, today, 3, 4);

        var consistency = dashboardService.getDashboard(book.id()).writingProgress().consistency();

        assertThat(consistency.currentStreakDays()).isEqualTo(2);
        assertThat(consistency.bestStreakDays()).isEqualTo(2);
    }

    @Test
    void dashboardZeroOrNegativeDayBreaksStreak() {
        var book = createBook("zero negative breaks streak");
        LocalDate today = TODAY;
        Book persistedBook = bookService.getBook(book.id());

        saveProgress(persistedBook, today.minusDays(4), 0, 2);
        saveProgress(persistedBook, today.minusDays(3), 2, 2);
        saveProgress(persistedBook, today.minusDays(2), 2, 4);
        saveProgress(persistedBook, today.minusDays(1), 4, 3);
        saveProgress(persistedBook, today, 3, 5);

        var consistency = dashboardService.getDashboard(book.id()).writingProgress().consistency();

        assertThat(consistency.currentStreakDays()).isEqualTo(1);
        assertThat(consistency.bestStreakDays()).isEqualTo(1);
    }

    @Test
    void dashboardBestStreakUsesFullHistoryNotSelectedChartPeriod() {
        var book = createBook("best streak full history");
        LocalDate today = TODAY;
        Book persistedBook = bookService.getBook(book.id());

        saveProgress(persistedBook, today.minusDays(20), 0, 1);
        saveProgress(persistedBook, today.minusDays(19), 1, 2);
        saveProgress(persistedBook, today.minusDays(18), 2, 3);
        saveProgress(persistedBook, today.minusDays(17), 3, 4);
        saveProgress(persistedBook, today.minusDays(1), 4, 5);
        saveProgress(persistedBook, today, 5, 6);

        var dashboard = dashboardService.getDashboard(book.id(), WritingProgressPeriod.SEVEN_DAYS);
        var consistency = dashboard.writingProgress().consistency();

        assertThat(dashboard.writingProgress().recentDays())
                .extracting(day -> day.date())
                .doesNotContain(today.minusDays(20), today.minusDays(19), today.minusDays(18), today.minusDays(17));
        assertThat(consistency.currentStreakDays()).isEqualTo(2);
        assertThat(consistency.bestStreakDays()).isEqualTo(4);
    }

    @Test
    void dashboardMonthAndRecentConsistencyCountOnlyPositiveRows() {
        var book = createBook("month and recent consistency");
        LocalDate today = TODAY;
        Book persistedBook = bookService.getBook(book.id());
        LocalDate firstDayOfMonth = today.withDayOfMonth(1);

        saveProgress(persistedBook, firstDayOfMonth.minusDays(1), 0, 10);
        saveProgress(persistedBook, firstDayOfMonth, 10, 12);
        saveProgress(persistedBook, today.minusDays(8), 12, 14);
        saveProgress(persistedBook, today.minusDays(6), 14, 17);
        saveProgress(persistedBook, today.minusDays(5), 17, 17);
        saveProgress(persistedBook, today.minusDays(4), 17, 16);
        saveProgress(persistedBook, today, 16, 20);

        var consistency = dashboardService.getDashboard(book.id()).writingProgress().consistency();

        assertThat(consistency.writingDaysThisMonth()).isEqualTo(4);
        assertThat(consistency.recentWindowDays()).isEqualTo(7);
        assertThat(consistency.recentWritingDays()).isEqualTo(2);
        assertThat(consistency.recentWritingDaysPercent()).isEqualTo((2 * 100.0) / 7);
    }

    private void saveProgress(Book book, LocalDate progressDate, int startWordCount, int endWordCount) {
        DailyWritingProgress progress = new DailyWritingProgress();
        progress.setBook(book);
        progress.setProgressDate(progressDate);
        progress.setDailyTargetWordCount(book.getDailyTargetWordCount());
        progress.setStartWordCount(startWordCount);
        progress.setEndWordCount(endWordCount);
        progress.setNetWordCountChange(endWordCount - startWordCount);
        progressRepository.save(progress);
    }

    @TestConfiguration
    static class FixedWritingProgressClockConfig {

        @Bean
        @Primary
        Clock fixedWritingProgressClock() {
            return Clock.fixed(Instant.parse("2026-05-30T12:00:00Z"), ZoneOffset.UTC);
        }
    }
}
