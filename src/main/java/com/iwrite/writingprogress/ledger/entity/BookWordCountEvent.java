package com.iwrite.writingprogress.ledger.entity;

import com.iwrite.book.entity.Book;
import com.iwrite.scene.entity.Scene;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(
        name = "book_word_count_events",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_book_word_count_events_book_idempotency",
                columnNames = {"book_id", "idempotency_key"}
        )
)
public class BookWordCountEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "book_id", nullable = false)
    private Book book;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "scene_id")
    private Scene scene;

    private UUID originalSceneId;

    @Column(length = 255)
    private String sceneTitleSnapshot;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private BookWordCountEventType eventType;

    @Column(nullable = false)
    private Integer productiveWordDelta;

    @Column(nullable = false)
    private Integer manuscriptWordDelta;

    private UUID operationId;

    @Column(nullable = false)
    private UUID idempotencyKey;

    private Long contentRevisionBefore;

    private Long contentRevisionAfter;

    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }

    public UUID getId() {
        return id;
    }

    public Book getBook() {
        return book;
    }

    public void setBook(Book book) {
        this.book = book;
    }

    public Scene getScene() {
        return scene;
    }

    public void setScene(Scene scene) {
        this.scene = scene;
    }

    public UUID getOriginalSceneId() {
        return originalSceneId;
    }

    public void setOriginalSceneId(UUID originalSceneId) {
        this.originalSceneId = originalSceneId;
    }

    public String getSceneTitleSnapshot() {
        return sceneTitleSnapshot;
    }

    public void setSceneTitleSnapshot(String sceneTitleSnapshot) {
        this.sceneTitleSnapshot = sceneTitleSnapshot;
    }

    public BookWordCountEventType getEventType() {
        return eventType;
    }

    public void setEventType(BookWordCountEventType eventType) {
        this.eventType = eventType;
    }

    public Integer getProductiveWordDelta() {
        return productiveWordDelta;
    }

    public void setProductiveWordDelta(Integer productiveWordDelta) {
        this.productiveWordDelta = productiveWordDelta;
    }

    public Integer getManuscriptWordDelta() {
        return manuscriptWordDelta;
    }

    public void setManuscriptWordDelta(Integer manuscriptWordDelta) {
        this.manuscriptWordDelta = manuscriptWordDelta;
    }

    public UUID getOperationId() {
        return operationId;
    }

    public void setOperationId(UUID operationId) {
        this.operationId = operationId;
    }

    public UUID getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(UUID idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public Long getContentRevisionBefore() {
        return contentRevisionBefore;
    }

    public void setContentRevisionBefore(Long contentRevisionBefore) {
        this.contentRevisionBefore = contentRevisionBefore;
    }

    public Long getContentRevisionAfter() {
        return contentRevisionAfter;
    }

    public void setContentRevisionAfter(Long contentRevisionAfter) {
        this.contentRevisionAfter = contentRevisionAfter;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
