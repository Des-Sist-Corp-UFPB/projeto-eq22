package com.iwrite.book.dto;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.iwrite.book.entity.BookStatus;
import jakarta.validation.constraints.Positive;

public class BookUpdateRequest {

    private String title;
    private String subtitle;
    private String description;
    private BookStatus status;

    @Positive
    private Integer targetWordCount;
    private boolean targetWordCountPresent;

    public String title() {
        return title;
    }

    public String subtitle() {
        return subtitle;
    }

    public String description() {
        return description;
    }

    public BookStatus status() {
        return status;
    }

    public Integer targetWordCount() {
        return targetWordCount;
    }

    public boolean isTargetWordCountPresent() {
        return targetWordCountPresent;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public void setSubtitle(String subtitle) {
        this.subtitle = subtitle;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public void setStatus(BookStatus status) {
        this.status = status;
    }

    @JsonSetter("targetWordCount")
    public void setTargetWordCount(Integer targetWordCount) {
        this.targetWordCount = targetWordCount;
        this.targetWordCountPresent = true;
    }
}
