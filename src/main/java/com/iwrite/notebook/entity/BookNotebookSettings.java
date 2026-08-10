package com.iwrite.notebook.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "book_notebook_settings")
public class BookNotebookSettings {

    @Id
    @Column(name = "book_id")
    private UUID bookId;

    @Column(nullable = false)
    private OffsetDateTime defaultsInitializedAt;

    @PrePersist
    void onCreate() {
        if (defaultsInitializedAt == null) {
            defaultsInitializedAt = OffsetDateTime.now();
        }
    }

    public UUID getBookId() {
        return bookId;
    }

    public void setBookId(UUID bookId) {
        this.bookId = bookId;
    }

    public OffsetDateTime getDefaultsInitializedAt() {
        return defaultsInitializedAt;
    }
}
