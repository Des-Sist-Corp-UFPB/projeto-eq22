package com.iwrite.export.service;

import com.iwrite.book.entity.Book;
import com.iwrite.book.service.BookService;
import com.iwrite.chapter.entity.Chapter;
import com.iwrite.chapter.repository.ChapterRepository;
import com.iwrite.scene.entity.Scene;
import com.iwrite.scene.repository.SceneRepository;
import com.iwrite.section.entity.BookSection;
import com.iwrite.section.repository.BookSectionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class BookExportService {

    private final BookService bookService;
    private final BookSectionRepository sectionRepository;
    private final ChapterRepository chapterRepository;
    private final SceneRepository sceneRepository;

    public BookExportService(
            BookService bookService,
            BookSectionRepository sectionRepository,
            ChapterRepository chapterRepository,
            SceneRepository sceneRepository
    ) {
        this.bookService = bookService;
        this.sectionRepository = sectionRepository;
        this.chapterRepository = chapterRepository;
        this.sceneRepository = sceneRepository;
    }

    @Transactional(readOnly = true)
    public String exportMarkdown(UUID bookId, boolean includeSceneTitles, boolean includeEmptyScenes) {
        Book book = bookService.getBook(bookId);
        List<BookSection> sections = sectionRepository.findByBookIdOrderBySortOrderAsc(bookId);
        List<Chapter> chapters = chapterRepository.findByBookIdOrderBySortOrderAsc(bookId);
        List<Scene> scenes = sceneRepository.findByBookIdOrderBySortOrderAsc(bookId);

        Map<UUID, List<Chapter>> chaptersBySection = chapters.stream()
                .collect(Collectors.groupingBy(chapter -> chapter.getSection().getId()));
        Map<UUID, List<Scene>> scenesByChapter = scenes.stream()
                .collect(Collectors.groupingBy(scene -> scene.getChapter().getId()));

        StringBuilder markdown = new StringBuilder();
        appendHeading(markdown, "#", book.getTitle());
        appendOptionalBlock(markdown, book.getSubtitle());
        appendOptionalBlock(markdown, book.getDescription());

        for (BookSection section : sections) {
            appendHeading(markdown, "##", section.getTitle());

            for (Chapter chapter : chaptersBySection.getOrDefault(section.getId(), List.of())) {
                appendHeading(markdown, "###", chapter.getTitle());
                appendChapterScenes(markdown, scenesByChapter.getOrDefault(chapter.getId(), List.of()), includeSceneTitles, includeEmptyScenes);
            }
        }

        return markdown.toString();
    }

    public String getMarkdownFileName(UUID bookId) {
        Book book = bookService.getBook(bookId);
        String normalizedTitle = Normalizer.normalize(book.getTitle(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");

        if (normalizedTitle.isBlank()) {
            normalizedTitle = "manuscrito";
        }

        return normalizedTitle + ".md";
    }

    private void appendHeading(StringBuilder markdown, String marker, String title) {
        appendBlock(markdown, marker + " " + title);
    }

    private void appendOptionalBlock(StringBuilder markdown, String text) {
        if (text == null || text.isBlank()) {
            return;
        }

        appendBlock(markdown, text);
    }

    private void appendChapterScenes(
            StringBuilder markdown,
            List<Scene> scenes,
            boolean includeSceneTitles,
            boolean includeEmptyScenes
    ) {
        boolean hasPreviousSceneContent = false;

        for (Scene scene : scenes) {
            String sceneBlock = buildSceneBlock(scene, includeSceneTitles, includeEmptyScenes);
            if (sceneBlock == null) {
                continue;
            }

            if (hasPreviousSceneContent) {
                appendBlock(markdown, "---");
            }

            appendBlock(markdown, sceneBlock);
            hasPreviousSceneContent = true;
        }
    }

    private String buildSceneBlock(Scene scene, boolean includeSceneTitles, boolean includeEmptyScenes) {
        String contentText = scene.getContentText();
        boolean hasContent = contentText != null && !contentText.isBlank();

        if (!hasContent && !includeEmptyScenes) {
            return null;
        }

        StringBuilder sceneBlock = new StringBuilder();
        if (includeSceneTitles) {
            sceneBlock.append("#### ").append(scene.getTitle());
        }
        if (hasContent) {
            if (!sceneBlock.isEmpty()) {
                sceneBlock.append("\n\n");
            }
            sceneBlock.append(contentText);
        }

        return sceneBlock.isEmpty() ? null : sceneBlock.toString();
    }

    private void appendBlock(StringBuilder markdown, String block) {
        if (!markdown.isEmpty()) {
            markdown.append("\n\n");
        }

        markdown.append(block);
    }
}
