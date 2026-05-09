package com.iwrite.scene.entity;

import com.iwrite.book.entity.Book;
import com.iwrite.chapter.entity.Chapter;
import com.iwrite.character.entity.Character;
import com.iwrite.item.entity.Item;
import com.iwrite.location.entity.Location;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "scenes")
public class Scene {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "book_id", nullable = false)
    private Book book;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "chapter_id", nullable = false)
    private Chapter chapter;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "text")
    private String summary;

    @Column(columnDefinition = "text")
    private String contentJson;

    @Column(columnDefinition = "text")
    private String contentText;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SceneStatus status;

    @Column(nullable = false)
    private Integer sortOrder;

    @Column(nullable = false)
    private Integer wordCount;

    @Column(columnDefinition = "text")
    private String goal;

    @Column(columnDefinition = "text")
    private String conflict;

    @Column(columnDefinition = "text")
    private String outcome;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pov_character_id")
    private Character povCharacter;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "main_location_id")
    private Location mainLocation;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "scene_participants",
            joinColumns = @JoinColumn(name = "scene_id"),
            inverseJoinColumns = @JoinColumn(name = "character_id")
    )
    private Set<Character> participantCharacters = new LinkedHashSet<>();

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "scene_items",
            joinColumns = @JoinColumn(name = "scene_id"),
            inverseJoinColumns = @JoinColumn(name = "item_id")
    )
    private Set<Item> items = new LinkedHashSet<>();

    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        createdAt = now;
        updatedAt = now;
        if (status == null) {
            status = SceneStatus.IDEA;
        }
        if (wordCount == null) {
            wordCount = 0;
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

    public Chapter getChapter() {
        return chapter;
    }

    public void setChapter(Chapter chapter) {
        this.chapter = chapter;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
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

    public SceneStatus getStatus() {
        return status;
    }

    public void setStatus(SceneStatus status) {
        this.status = status;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }

    public Integer getWordCount() {
        return wordCount;
    }

    public void setWordCount(Integer wordCount) {
        this.wordCount = wordCount;
    }

    public String getGoal() {
        return goal;
    }

    public void setGoal(String goal) {
        this.goal = goal;
    }

    public String getConflict() {
        return conflict;
    }

    public void setConflict(String conflict) {
        this.conflict = conflict;
    }

    public String getOutcome() {
        return outcome;
    }

    public void setOutcome(String outcome) {
        this.outcome = outcome;
    }

    public Character getPovCharacter() {
        return povCharacter;
    }

    public void setPovCharacter(Character povCharacter) {
        this.povCharacter = povCharacter;
    }

    public Location getMainLocation() {
        return mainLocation;
    }

    public void setMainLocation(Location mainLocation) {
        this.mainLocation = mainLocation;
    }

    public Set<Character> getParticipantCharacters() {
        return participantCharacters;
    }

    public void setParticipantCharacters(Set<Character> participantCharacters) {
        this.participantCharacters = participantCharacters;
    }

    public Set<Item> getItems() {
        return items;
    }

    public void setItems(Set<Item> items) {
        this.items = items;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}
