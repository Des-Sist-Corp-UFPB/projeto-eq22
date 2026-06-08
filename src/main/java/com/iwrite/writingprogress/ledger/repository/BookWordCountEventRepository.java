package com.iwrite.writingprogress.ledger.repository;

import com.iwrite.writingprogress.ledger.entity.BookWordCountEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface BookWordCountEventRepository extends JpaRepository<BookWordCountEvent, UUID> {

    boolean existsByBookIdAndIdempotencyKey(UUID bookId, UUID idempotencyKey);

    Optional<BookWordCountEvent> findByBookIdAndIdempotencyKey(UUID bookId, UUID idempotencyKey);
}
