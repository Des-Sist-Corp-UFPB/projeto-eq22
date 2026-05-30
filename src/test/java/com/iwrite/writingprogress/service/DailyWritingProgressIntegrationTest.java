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

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class DailyWritingProgressIntegrationTest extends PostgresIntegrationTest {

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

        DailyWritingProgress progress = progressRepository.findByBookIdAndProgressDate(book.id(), LocalDate.now())
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

        assertThat(progressRepository.countByBookIdAndProgressDate(book.id(), LocalDate.now())).isEqualTo(1);
        DailyWritingProgress progress = progressRepository.findByBookIdAndProgressDate(book.id(), LocalDate.now())
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

        DailyWritingProgress progress = progressRepository.findByBookIdAndProgressDate(book.id(), LocalDate.now())
                .orElseThrow();
        assertThat(progress.getStartWordCount()).isZero();
        assertThat(progress.getEndWordCount()).isZero();
        assertThat(progress.getNetWordCountChange()).isZero();
    }

    @Test
    void dashboardReturnsSafeEmptyWritingProgressWithoutProgressRows() {
        var book = createBook("empty writing progress");

        var dashboard = dashboardService.getDashboard(book.id());

        assertThat(dashboard.writingProgress().today().date()).isEqualTo(LocalDate.now());
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
        LocalDate today = LocalDate.now();

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
}
