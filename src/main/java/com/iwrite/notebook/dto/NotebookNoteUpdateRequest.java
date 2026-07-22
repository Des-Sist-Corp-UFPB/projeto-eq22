package com.iwrite.notebook.dto;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.iwrite.notebook.entity.NotebookNoteStatus;

import java.util.UUID;

public class NotebookNoteUpdateRequest {

    private String title;
    private String content;
    private UUID categoryId;
    private NotebookNoteStatus status;
    private boolean contentPresent;
    private boolean categoryIdPresent;

    public String title() {
        return title;
    }

    public String content() {
        return content;
    }

    public UUID categoryId() {
        return categoryId;
    }

    public NotebookNoteStatus status() {
        return status;
    }

    public boolean isContentPresent() {
        return contentPresent;
    }

    public boolean isCategoryIdPresent() {
        return categoryIdPresent;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    @JsonSetter("content")
    public void setContent(String content) {
        this.content = content;
        this.contentPresent = true;
    }

    public void setStatus(NotebookNoteStatus status) {
        this.status = status;
    }

    @JsonSetter("categoryId")
    public void setCategoryId(UUID categoryId) {
        this.categoryId = categoryId;
        this.categoryIdPresent = true;
    }
}
