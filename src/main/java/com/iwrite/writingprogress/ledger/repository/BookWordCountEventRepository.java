package com.iwrite.writingprogress.ledger.repository;

import com.iwrite.writingprogress.ledger.entity.BookWordCountEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

public interface BookWordCountEventRepository extends JpaRepository<BookWordCountEvent, UUID> {

    boolean existsByBookIdAndIdempotencyKey(UUID bookId, UUID idempotencyKey);

    Optional<BookWordCountEvent> findByBookIdAndIdempotencyKey(UUID bookId, UUID idempotencyKey);

    long countByBookId(UUID bookId);

    @Modifying
    @Query(value = """
            insert into book_word_count_events (
                id,
                book_id,
                scene_id,
                actor_user_id,
                original_scene_id,
                scene_title_snapshot,
                event_type,
                productive_word_delta,
                manuscript_word_delta,
                operation_id,
                idempotency_key,
                content_revision_before,
                content_revision_after,
                created_at
            )
            values (
                :id,
                :bookId,
                :sceneId,
                :actorUserId,
                :originalSceneId,
                :sceneTitleSnapshot,
                :eventType,
                :productiveWordDelta,
                :manuscriptWordDelta,
                :operationId,
                :idempotencyKey,
                :contentRevisionBefore,
                :contentRevisionAfter,
                :createdAt
            )
            on conflict (book_id, idempotency_key) do nothing
            """, nativeQuery = true)
    int insertIfAbsent(
            @Param("id") UUID id,
            @Param("bookId") UUID bookId,
            @Param("sceneId") UUID sceneId,
            @Param("actorUserId") UUID actorUserId,
            @Param("originalSceneId") UUID originalSceneId,
            @Param("sceneTitleSnapshot") String sceneTitleSnapshot,
            @Param("eventType") String eventType,
            @Param("productiveWordDelta") int productiveWordDelta,
            @Param("manuscriptWordDelta") int manuscriptWordDelta,
            @Param("operationId") UUID operationId,
            @Param("idempotencyKey") UUID idempotencyKey,
            @Param("contentRevisionBefore") Long contentRevisionBefore,
            @Param("contentRevisionAfter") Long contentRevisionAfter,
            @Param("createdAt") OffsetDateTime createdAt
    );
}
