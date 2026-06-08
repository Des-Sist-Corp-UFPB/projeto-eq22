package com.iwrite.writingprogress.entity;

import com.iwrite.book.entity.Book;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(
        name = "book_daily_writing_progress",
        uniqueConstraints = @UniqueConstraint(name = "uk_book_daily_writing_progress_book_date", columnNames = {"book_id", "progress_date"})
)
public class DailyWritingProgress {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "book_id", nullable = false)
    private Book book;

    @Column(nullable = false)
    private LocalDate progressDate;

    private Integer dailyTargetWordCount;

    @Column(nullable = false, name = "starting_manuscript_word_count")
    private Integer startingManuscriptWordCount;

    @Column(nullable = false, name = "ending_manuscript_word_count")
    private Integer endingManuscriptWordCount;

    @Column(nullable = false, name = "productive_word_count_change")
    private Integer productiveWordCountChange;

    @Column(nullable = false, name = "manuscript_adjustment_word_count")
    private Integer manuscriptAdjustmentWordCount;

    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        createdAt = now;
        updatedAt = now;
        if (manuscriptAdjustmentWordCount == null) {
            manuscriptAdjustmentWordCount = 0;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
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

    public LocalDate getProgressDate() {
        return progressDate;
    }

    public void setProgressDate(LocalDate progressDate) {
        this.progressDate = progressDate;
    }

    public Integer getDailyTargetWordCount() {
        return dailyTargetWordCount;
    }

    public void setDailyTargetWordCount(Integer dailyTargetWordCount) {
        this.dailyTargetWordCount = dailyTargetWordCount;
    }

    public Integer getStartWordCount() {
        return startingManuscriptWordCount;
    }

    public void setStartWordCount(Integer startWordCount) {
        this.startingManuscriptWordCount = startWordCount;
    }

    public Integer getEndWordCount() {
        return endingManuscriptWordCount;
    }

    public void setEndWordCount(Integer endWordCount) {
        this.endingManuscriptWordCount = endWordCount;
    }

    public Integer getNetWordCountChange() {
        return productiveWordCountChange;
    }

    public void setNetWordCountChange(Integer netWordCountChange) {
        this.productiveWordCountChange = netWordCountChange;
    }

    public Integer getStartingManuscriptWordCount() {
        return startingManuscriptWordCount;
    }

    public void setStartingManuscriptWordCount(Integer startingManuscriptWordCount) {
        this.startingManuscriptWordCount = startingManuscriptWordCount;
    }

    public Integer getEndingManuscriptWordCount() {
        return endingManuscriptWordCount;
    }

    public void setEndingManuscriptWordCount(Integer endingManuscriptWordCount) {
        this.endingManuscriptWordCount = endingManuscriptWordCount;
    }

    public Integer getProductiveWordCountChange() {
        return productiveWordCountChange;
    }

    public void setProductiveWordCountChange(Integer productiveWordCountChange) {
        this.productiveWordCountChange = productiveWordCountChange;
    }

    public Integer getManuscriptAdjustmentWordCount() {
        return manuscriptAdjustmentWordCount;
    }

    public void setManuscriptAdjustmentWordCount(Integer manuscriptAdjustmentWordCount) {
        this.manuscriptAdjustmentWordCount = manuscriptAdjustmentWordCount;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}
