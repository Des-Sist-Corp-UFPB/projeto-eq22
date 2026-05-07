package com.iwrite.chapter.controller;

import com.iwrite.chapter.dto.ChapterRequest;
import com.iwrite.chapter.dto.ChapterResponse;
import com.iwrite.chapter.dto.ChapterUpdateRequest;
import com.iwrite.chapter.service.ChapterService;
import com.iwrite.common.dto.ReorderRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api")
public class ChapterController {

    private final ChapterService chapterService;

    public ChapterController(ChapterService chapterService) {
        this.chapterService = chapterService;
    }

    @PostMapping("/sections/{sectionId}/chapters")
    @ResponseStatus(HttpStatus.CREATED)
    public ChapterResponse create(@PathVariable UUID sectionId, @Valid @RequestBody ChapterRequest request) {
        return chapterService.create(sectionId, request);
    }

    @PatchMapping("/chapters/{chapterId}")
    public ChapterResponse update(@PathVariable UUID chapterId, @Valid @RequestBody ChapterUpdateRequest request) {
        return chapterService.update(chapterId, request);
    }

    @PatchMapping("/sections/{sectionId}/chapters/reorder")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reorder(@PathVariable UUID sectionId, @Valid @RequestBody ReorderRequest request) {
        chapterService.reorder(sectionId, request);
    }

    @DeleteMapping("/chapters/{chapterId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID chapterId) {
        chapterService.delete(chapterId);
    }
}
