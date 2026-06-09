package com.iwrite.writingprogress.ledger;

import com.iwrite.book.dto.BookRequest;
import com.iwrite.scene.dto.SceneResponse;
import com.iwrite.scene.entity.SceneStatus;
import com.iwrite.support.PostgresIntegrationTest;
import com.iwrite.writingprogress.entity.DailyWritingProgress;
import com.iwrite.writingprogress.ledger.entity.BookWordCountEventType;
import com.iwrite.writingprogress.ledger.repository.BookWordCountEventRepository;
import com.iwrite.writingprogress.ledger.service.WordCountEventCommand;
import com.iwrite.writingprogress.ledger.service.WordCountEventConflictException;
import com.iwrite.writingprogress.ledger.service.WordCountEventRecordResult;
import com.iwrite.writingprogress.ledger.service.WordCountEventService;
import com.iwrite.writingprogress.repository.DailyWritingProgressRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WordCountEventServiceIntegrationTest extends PostgresIntegrationTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 6, 2);

    @Autowired
    private WordCountEventService eventService;

    @Autowired
    private BookWordCountEventRepository eventRepository;

    @Autowired
    private DailyWritingProgressRepository progressRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    void firstContentSaveEventCreatesLedgerRowAndDailyRollup() {
        var book = bookService.create(new BookRequest("ledger first event", null, null, null, null, 400));
        var scene = createEmptyScene(book.id(), "First scene");
        entityManager.flush();
        UUID idempotencyKey = UUID.randomUUID();

        WordCountEventRecordResult result = eventService.record(command(
                book.id(),
                scene,
                BookWordCountEventType.CONTENT_SAVE,
                5,
                5,
                idempotencyKey,
                15
        ));

        DailyWritingProgress progress = progressRepository.findByBookIdAndProgressDate(book.id(), TODAY).orElseThrow();
        assertThat(result).isEqualTo(WordCountEventRecordResult.RECORDED);
        assertThat(eventRepository.countByBookId(book.id())).isEqualTo(1);
        assertThat(progress.getDailyTargetWordCount()).isEqualTo(400);
        assertThat(progress.getStartingManuscriptWordCount()).isEqualTo(10);
        assertThat(progress.getEndingManuscriptWordCount()).isEqualTo(15);
        assertThat(progress.getProductiveWordCountChange()).isEqualTo(5);
        assertThat(progress.getManuscriptAdjustmentWordCount()).isZero();
    }

    @Test
    void zeroDeltaEventRecordsLedgerRowWithoutCreatingDailyRollup() {
        var book = createBook("ledger zero delta");
        var scene = createEmptyScene(book.id(), "Zero delta scene");
        entityManager.flush();
        UUID idempotencyKey = UUID.randomUUID();

        WordCountEventRecordResult result = eventService.record(command(
                book.id(),
                scene,
                BookWordCountEventType.CONTENT_SAVE,
                0,
                0,
                idempotencyKey,
                10
        ));

        assertThat(result).isEqualTo(WordCountEventRecordResult.RECORDED);
        assertThat(eventRepository.findByBookIdAndIdempotencyKey(book.id(), idempotencyKey)).isPresent();
        assertThat(progressRepository.findByBookIdAndProgressDate(book.id(), TODAY)).isEmpty();
    }

    @Test
    void laterContentSaveEventIncrementsProductiveAndEndingValues() {
        var book = createBook("ledger later event");
        var scene = createEmptyScene(book.id(), "Later scene");
        entityManager.flush();

        eventService.record(command(book.id(), scene, BookWordCountEventType.CONTENT_SAVE, 5, 5, UUID.randomUUID(), 15));
        eventService.record(command(book.id(), scene, BookWordCountEventType.CONTENT_SAVE, 3, 3, UUID.randomUUID(), 18));

        DailyWritingProgress progress = progressRepository.findByBookIdAndProgressDate(book.id(), TODAY).orElseThrow();
        assertThat(eventRepository.countByBookId(book.id())).isEqualTo(2);
        assertThat(progress.getStartingManuscriptWordCount()).isEqualTo(10);
        assertThat(progress.getEndingManuscriptWordCount()).isEqualTo(18);
        assertThat(progress.getProductiveWordCountChange()).isEqualTo(8);
        assertThat(progress.getManuscriptAdjustmentWordCount()).isZero();
    }

    @Test
    void versionRestoreEventUpdatesAdjustmentAndEndingWithoutProductiveGrowth() {
        var book = createBook("ledger restore event");
        var scene = createEmptyScene(book.id(), "Restore scene");
        entityManager.flush();

        eventService.record(command(book.id(), scene, BookWordCountEventType.CONTENT_SAVE, 2, 2, UUID.randomUUID(), 12));
        eventService.record(command(book.id(), scene, BookWordCountEventType.VERSION_RESTORE, 0, 8, UUID.randomUUID(), 20));

        DailyWritingProgress progress = progressRepository.findByBookIdAndProgressDate(book.id(), TODAY).orElseThrow();
        assertThat(eventRepository.countByBookId(book.id())).isEqualTo(2);
        assertThat(progress.getStartingManuscriptWordCount()).isEqualTo(10);
        assertThat(progress.getEndingManuscriptWordCount()).isEqualTo(20);
        assertThat(progress.getProductiveWordCountChange()).isEqualTo(2);
        assertThat(progress.getManuscriptAdjustmentWordCount()).isEqualTo(8);
    }

    @Test
    void duplicateMatchingIdempotencyKeyReturnsAlreadyRecordedWithoutUpdatingRollupAgain() {
        var book = createBook("ledger duplicate same");
        var scene = createEmptyScene(book.id(), "Duplicate scene");
        entityManager.flush();
        UUID idempotencyKey = UUID.randomUUID();
        WordCountEventCommand command = command(
                book.id(),
                scene,
                BookWordCountEventType.CONTENT_SAVE,
                4,
                4,
                idempotencyKey,
                14
        );

        WordCountEventRecordResult firstResult = eventService.record(command);
        WordCountEventRecordResult secondResult = eventService.record(command);

        DailyWritingProgress progress = progressRepository.findByBookIdAndProgressDate(book.id(), TODAY).orElseThrow();
        assertThat(firstResult).isEqualTo(WordCountEventRecordResult.RECORDED);
        assertThat(secondResult).isEqualTo(WordCountEventRecordResult.ALREADY_RECORDED);
        assertThat(eventRepository.countByBookId(book.id())).isEqualTo(1);
        assertThat(progress.getEndingManuscriptWordCount()).isEqualTo(14);
        assertThat(progress.getProductiveWordCountChange()).isEqualTo(4);
        assertThat(progress.getManuscriptAdjustmentWordCount()).isZero();
    }

    @Test
    void reusedIdempotencyKeyWithConflictingIdentityIsRejected() {
        var book = createBook("ledger duplicate conflict");
        var scene = createEmptyScene(book.id(), "Conflict scene");
        entityManager.flush();
        UUID idempotencyKey = UUID.randomUUID();

        eventService.record(command(book.id(), scene, BookWordCountEventType.CONTENT_SAVE, 4, 4, idempotencyKey, 14));

        assertThatThrownBy(() -> eventService.record(command(
                book.id(),
                scene,
                BookWordCountEventType.CONTENT_SAVE,
                5,
                5,
                idempotencyKey,
                15
        ))).isInstanceOf(WordCountEventConflictException.class);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentEventsForDifferentScenesDoNotLoseRollupIncrements() {
        var book = createBook("ledger concurrent");
        ExecutorService executor = Executors.newFixedThreadPool(6);
        try {
            var sceneA = createEmptyScene(book.id(), "Concurrent A");
            var sceneB = createEmptyScene(book.id(), "Concurrent B");

            CountDownLatch start = new CountDownLatch(1);
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            int eventCount = 12;

            for (int index = 0; index < eventCount; index++) {
                SceneResponse scene = index % 2 == 0 ? sceneA : sceneB;
                futures.add(CompletableFuture.runAsync(() -> {
                    await(start);
                    eventService.record(command(
                            book.id(),
                            scene,
                            BookWordCountEventType.CONTENT_SAVE,
                            1,
                            1,
                            UUID.randomUUID(),
                            1
                    ));
                }, executor));
            }

            start.countDown();
            futures.forEach(CompletableFuture::join);

            DailyWritingProgress progress = progressRepository.findByBookIdAndProgressDate(book.id(), TODAY).orElseThrow();
            assertThat(eventRepository.countByBookId(book.id())).isEqualTo(eventCount);
            assertThat(progress.getEndingManuscriptWordCount()).isEqualTo(eventCount);
            assertThat(progress.getProductiveWordCountChange()).isEqualTo(eventCount);
            assertThat(progress.getManuscriptAdjustmentWordCount()).isZero();
        } finally {
            executor.shutdownNow();
            bookService.delete(book.id());
        }
    }

    private SceneResponse createEmptyScene(UUID bookId, String title) {
        var section = createSection(bookService.findById(bookId), "Part " + title);
        var chapter = createChapter(section, "Chapter " + title);
        return createScene(chapter, title, SceneStatus.DRAFT, 0, "");
    }

    private WordCountEventCommand command(
            UUID bookId,
            SceneResponse scene,
            BookWordCountEventType eventType,
            int productiveWordDelta,
            int manuscriptWordDelta,
            UUID idempotencyKey,
            int knownManuscriptTotalAfterOperation
    ) {
        return new WordCountEventCommand(
                bookId,
                scene.id(),
                scene.id(),
                scene.title(),
                eventType,
                productiveWordDelta,
                manuscriptWordDelta,
                UUID.randomUUID(),
                idempotencyKey,
                0L,
                1L,
                knownManuscriptTotalAfterOperation
        );
    }

    private void await(CountDownLatch start) {
        try {
            start.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    @TestConfiguration
    static class FixedWritingProgressClockConfig {

        @Bean
        @Primary
        Clock fixedWritingProgressClock() {
            return Clock.fixed(Instant.parse("2026-06-02T12:00:00Z"), ZoneOffset.UTC);
        }
    }
}
