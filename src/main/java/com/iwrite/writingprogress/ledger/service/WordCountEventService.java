package com.iwrite.writingprogress.ledger.service;

import com.iwrite.book.entity.Book;
import com.iwrite.book.service.BookService;
import com.iwrite.common.exception.BadRequestException;
import com.iwrite.scene.entity.Scene;
import com.iwrite.user.context.CurrentUserMembershipService;
import com.iwrite.writingprogress.ledger.entity.BookWordCountEvent;
import com.iwrite.writingprogress.ledger.repository.BookWordCountEventRepository;
import com.iwrite.writingprogress.repository.DailyWritingProgressRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

@Service
public class WordCountEventService {

    private final BookService bookService;
    private final BookWordCountEventRepository eventRepository;
    private final DailyWritingProgressRepository progressRepository;
    private final CurrentUserMembershipService currentUserMembershipService;
    private final Clock clock;

    public WordCountEventService(
            BookService bookService,
            BookWordCountEventRepository eventRepository,
            DailyWritingProgressRepository progressRepository,
            CurrentUserMembershipService currentUserMembershipService,
            Clock clock
    ) {
        this.bookService = bookService;
        this.eventRepository = eventRepository;
        this.progressRepository = progressRepository;
        this.currentUserMembershipService = currentUserMembershipService;
        this.clock = clock;
    }

    @Transactional
    public WordCountEventRecordResult record(WordCountEventCommand command) {
        validate(command);

        Book book = bookService.getBook(command.bookId());
        UUID actorUserId = currentUserMembershipService.requireCurrentUserMemberId();
        OffsetDateTime eventTime = OffsetDateTime.now(clock);
        int inserted = eventRepository.insertIfAbsent(
                UUID.randomUUID(),
                command.bookId(),
                command.sceneId(),
                actorUserId,
                command.originalSceneId(),
                command.sceneTitleSnapshot(),
                command.eventType().name(),
                command.productiveWordDelta(),
                command.manuscriptWordDelta(),
                command.operationId(),
                command.idempotencyKey(),
                command.contentRevisionBefore(),
                command.contentRevisionAfter(),
                eventTime
        );

        if (inserted == 0) {
            BookWordCountEvent existing = eventRepository
                    .findByBookIdAndIdempotencyKey(command.bookId(), command.idempotencyKey())
                    .orElseThrow(() -> new WordCountEventConflictException("Word-count event idempotency state is inconsistent."));
            if (matches(existing, command, actorUserId)) {
                return WordCountEventRecordResult.ALREADY_RECORDED;
            }
            throw new WordCountEventConflictException("Idempotency key was already used for a different word-count event.");
        }

        if (shouldUpdateDailyRollup(command)) {
            progressRepository.upsertWordCountEventRollup(
                    UUID.randomUUID(),
                    actorUserId,
                    command.bookId(),
                    LocalDate.now(clock),
                    book.getDailyTargetWordCount(),
                    command.knownManuscriptTotalAfterOperation(),
                    command.productiveWordDelta(),
                    command.manuscriptWordDelta(),
                    eventTime
            );
        }
        return WordCountEventRecordResult.RECORDED;
    }

    private void validate(WordCountEventCommand command) {
        if (command == null) {
            throw new BadRequestException("word-count event command is required");
        }
        if (command.bookId() == null) {
            throw new BadRequestException("bookId is required");
        }
        if (command.eventType() == null) {
            throw new BadRequestException("eventType is required");
        }
        if (command.idempotencyKey() == null) {
            throw new BadRequestException("idempotencyKey is required");
        }
    }

    private boolean matches(BookWordCountEvent existing, WordCountEventCommand command, UUID actorUserId) {
        return Objects.equals(existing.getBook().getId(), command.bookId())
                && Objects.equals(existing.getActorUser().getId(), actorUserId)
                && Objects.equals(sceneId(existing), command.sceneId())
                && Objects.equals(existing.getOriginalSceneId(), command.originalSceneId())
                && Objects.equals(existing.getSceneTitleSnapshot(), command.sceneTitleSnapshot())
                && existing.getEventType() == command.eventType()
                && Objects.equals(existing.getProductiveWordDelta(), command.productiveWordDelta())
                && Objects.equals(existing.getManuscriptWordDelta(), command.manuscriptWordDelta())
                && Objects.equals(existing.getOperationId(), command.operationId())
                && Objects.equals(existing.getContentRevisionBefore(), command.contentRevisionBefore())
                && Objects.equals(existing.getContentRevisionAfter(), command.contentRevisionAfter());
    }

    private boolean shouldUpdateDailyRollup(WordCountEventCommand command) {
        return command.productiveWordDelta() != 0 || command.manuscriptWordDelta() != 0;
    }

    private UUID sceneId(BookWordCountEvent event) {
        Scene scene = event.getScene();
        return scene == null ? null : scene.getId();
    }
}
