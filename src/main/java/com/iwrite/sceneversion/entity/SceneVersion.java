package com.iwrite.sceneversion.entity;

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

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "scene_versions")
public class SceneVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "book_id", nullable = false)
    private Book book;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "scene_id")
    private Scene scene;

    @Column(nullable = false)
    private UUID originalSceneId;

    @Column(nullable = false)
    private String sceneTitleSnapshot;

    @Column(columnDefinition = "text")
    private String contentJson;

    @Column(columnDefinition = "text")
    private String contentText;

    @Column(nullable = false)
    private Integer wordCount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SceneVersionSource source;

    @Column(nullable = false)
    private String contentHash;

    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
        if (wordCount == null) {
            wordCount = 0;
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

    public String getContentJson() {
        return contentJson;
    }

    public void setContentJson(String contentJson) {
        this.contentJson = contentJson;
    }

    public String getContentText() {
        return contentText;
    }

    public void setContentText(String contentText) {
        this.contentText = contentText;
    }

    public Integer getWordCount() {
        return wordCount;
    }

    public void setWordCount(Integer wordCount) {
        this.wordCount = wordCount;
    }

    public SceneVersionSource getSource() {
        return source;
    }

    public void setSource(SceneVersionSource source) {
        this.source = source;
    }

    public String getContentHash() {
        return contentHash;
    }

    public void setContentHash(String contentHash) {
        this.contentHash = contentHash;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
