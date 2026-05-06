package com.iwrite.chapter.service;

import com.iwrite.chapter.dto.ChapterRequest;
import com.iwrite.chapter.dto.ChapterResponse;
import com.iwrite.chapter.dto.ChapterUpdateRequest;
import com.iwrite.chapter.entity.Chapter;
import com.iwrite.chapter.repository.ChapterRepository;
import com.iwrite.common.exception.ResourceNotFoundException;
import com.iwrite.common.validation.RequestValidation;
import com.iwrite.section.entity.BookSection;
import com.iwrite.section.service.BookSectionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class ChapterService {

    private final ChapterRepository chapterRepository;
    private final BookSectionService sectionService;

    public ChapterService(ChapterRepository chapterRepository, BookSectionService sectionService) {
        this.chapterRepository = chapterRepository;
        this.sectionService = sectionService;
    }

    @Transactional
    public ChapterResponse create(UUID sectionId, ChapterRequest request) {
        BookSection section = sectionService.getSection(sectionId);

        Chapter chapter = new Chapter();
        chapter.setBook(section.getBook());
        chapter.setSection(section);
        chapter.setTitle(request.title());
        chapter.setSummary(request.summary());
        chapter.setSortOrder(request.sortOrder() == null ? chapterRepository.countBySectionId(sectionId) : request.sortOrder());

        return ChapterResponse.fromEntity(chapterRepository.save(chapter));
    }

    @Transactional
    public ChapterResponse update(UUID chapterId, ChapterUpdateRequest request) {
        Chapter chapter = getChapter(chapterId);
        RequestValidation.rejectBlankWhenPresent("title", request.title());

        if (request.title() != null) {
            chapter.setTitle(request.title());
        }
        if (request.summary() != null) {
            chapter.setSummary(request.summary());
        }
        if (request.sortOrder() != null) {
            chapter.setSortOrder(request.sortOrder());
        }

        return ChapterResponse.fromEntity(chapter);
    }

    @Transactional
    public void delete(UUID chapterId) {
        Chapter chapter = getChapter(chapterId);
        chapterRepository.delete(chapter);
    }

    @Transactional(readOnly = true)
    public Chapter getChapter(UUID chapterId) {
        return chapterRepository.findById(chapterId)
                .orElseThrow(() -> new ResourceNotFoundException("Chapter not found: " + chapterId));
    }
}
