package com.iwrite.book.dto;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.iwrite.book.entity.BookStatus;
import jakarta.validation.constraints.Positive;

import java.time.DayOfWeek;
import java.util.List;

public class BookUpdateRequest {

    private String title;
    private String subtitle;
    private String description;
    private BookStatus status;

    @Positive
    private Integer targetWordCount;
    private boolean targetWordCountPresent;

    @Positive
    private Integer dailyTargetWordCount;
    private boolean dailyTargetWordCountPresent;

    private List<DayOfWeek> plannedWritingDays;
    private boolean plannedWritingDaysPresent;

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

    public Integer dailyTargetWordCount() {
        return dailyTargetWordCount;
    }

    public boolean isDailyTargetWordCountPresent() {
        return dailyTargetWordCountPresent;
    }

    public List<DayOfWeek> plannedWritingDays() {
        return plannedWritingDays;
    }

    public boolean isPlannedWritingDaysPresent() {
        return plannedWritingDaysPresent;
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

    @JsonSetter("dailyTargetWordCount")
    public void setDailyTargetWordCount(Integer dailyTargetWordCount) {
        this.dailyTargetWordCount = dailyTargetWordCount;
        this.dailyTargetWordCountPresent = true;
    }

    @JsonSetter("plannedWritingDays")
    public void setPlannedWritingDays(List<DayOfWeek> plannedWritingDays) {
        this.plannedWritingDays = plannedWritingDays;
        this.plannedWritingDaysPresent = true;
    }
}
