package com.iwrite.notebook.dto;

import com.fasterxml.jackson.annotation.JsonSetter;

import java.util.UUID;

public class NotebookNoteUpdateRequest {

    private String title;
    private String content;
    private UUID categoryId;
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

    public boolean isCategoryIdPresent() {
        return categoryIdPresent;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public void setContent(String content) {
        this.content = content;
    }

    @JsonSetter("categoryId")
    public void setCategoryId(UUID categoryId) {
        this.categoryId = categoryId;
        this.categoryIdPresent = true;
    }
}
