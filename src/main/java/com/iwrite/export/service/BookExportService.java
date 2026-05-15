package com.iwrite.export.service;

import com.iwrite.book.entity.Book;
import com.iwrite.book.service.BookService;
import com.iwrite.chapter.entity.Chapter;
import com.iwrite.chapter.repository.ChapterRepository;
import com.iwrite.scene.entity.Scene;
import com.iwrite.scene.repository.SceneRepository;
import com.iwrite.section.entity.BookSection;
import com.iwrite.section.repository.BookSectionRepository;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
    private final TipTapMarkdownRenderer tipTapMarkdownRenderer;
    private final TipTapDocxRenderer tipTapDocxRenderer;

    public BookExportService(
            BookService bookService,
            BookSectionRepository sectionRepository,
            ChapterRepository chapterRepository,
            SceneRepository sceneRepository,
            TipTapMarkdownRenderer tipTapMarkdownRenderer,
            TipTapDocxRenderer tipTapDocxRenderer
    ) {
        this.bookService = bookService;
        this.sectionRepository = sectionRepository;
        this.chapterRepository = chapterRepository;
        this.sceneRepository = sceneRepository;
        this.tipTapMarkdownRenderer = tipTapMarkdownRenderer;
        this.tipTapDocxRenderer = tipTapDocxRenderer;
    }

    @Transactional(readOnly = true)
    public String exportMarkdown(UUID bookId, boolean includeSceneTitles, boolean includeEmptyScenes) {
        ManuscriptExport manuscript = getManuscriptExport(bookId);

        StringBuilder markdown = new StringBuilder();
        appendHeading(markdown, "#", manuscript.book().getTitle());
        appendOptionalBlock(markdown, manuscript.book().getSubtitle());
        appendOptionalBlock(markdown, manuscript.book().getDescription());

        for (BookSection section : manuscript.sections()) {
            appendHeading(markdown, "##", section.getTitle());

            for (Chapter chapter : manuscript.chaptersBySection().getOrDefault(section.getId(), List.of())) {
                appendHeading(markdown, "###", chapter.getTitle());
                appendChapterScenes(markdown, manuscript.scenesByChapter().getOrDefault(chapter.getId(), List.of()), includeSceneTitles, includeEmptyScenes);
            }
        }

        return markdown.toString();
    }

    @Transactional(readOnly = true)
    public byte[] exportDocx(UUID bookId, boolean includeSceneTitles, boolean includeEmptyScenes) {
        ManuscriptExport manuscript = getManuscriptExport(bookId);

        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            appendDocxHeading(document, "Title", manuscript.book().getTitle());
            appendDocxOptionalParagraph(document, manuscript.book().getSubtitle());
            appendDocxOptionalParagraph(document, manuscript.book().getDescription());

            for (BookSection section : manuscript.sections()) {
                appendDocxHeading(document, "Heading1", section.getTitle());

                for (Chapter chapter : manuscript.chaptersBySection().getOrDefault(section.getId(), List.of())) {
                    appendDocxHeading(document, "Heading2", chapter.getTitle());
                    appendDocxChapterScenes(document, manuscript.scenesByChapter().getOrDefault(chapter.getId(), List.of()), includeSceneTitles, includeEmptyScenes);
                }
            }

            document.write(outputStream);
            return outputStream.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to export DOCX manuscript", exception);
        }
    }

    public String getMarkdownFileName(UUID bookId) {
        Book book = bookService.getBook(bookId);
        return getExportFileName(book, "md");
    }

    public String getDocxFileName(UUID bookId) {
        Book book = bookService.getBook(bookId);
        return getExportFileName(book, "docx");
    }

    private ManuscriptExport getManuscriptExport(UUID bookId) {
        Book book = bookService.getBook(bookId);
        List<BookSection> sections = sectionRepository.findByBookIdOrderBySortOrderAsc(bookId);
        List<Chapter> chapters = chapterRepository.findByBookIdOrderBySortOrderAsc(bookId);
        List<Scene> scenes = sceneRepository.findByBookIdOrderBySortOrderAsc(bookId);

        Map<UUID, List<Chapter>> chaptersBySection = chapters.stream()
                .collect(Collectors.groupingBy(chapter -> chapter.getSection().getId()));
        Map<UUID, List<Scene>> scenesByChapter = scenes.stream()
                .collect(Collectors.groupingBy(scene -> scene.getChapter().getId()));

        return new ManuscriptExport(book, sections, chaptersBySection, scenesByChapter);
    }

    private String getExportFileName(Book book, String extension) {
        String normalizedTitle = Normalizer.normalize(book.getTitle(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");

        if (normalizedTitle.isBlank()) {
            normalizedTitle = "manuscrito";
        }

        return normalizedTitle + "." + extension;
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
        String sceneContent = getSceneContent(scene);
        boolean hasContent = sceneContent != null && !sceneContent.isBlank();

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
            sceneBlock.append(sceneContent);
        }

        return sceneBlock.isEmpty() ? null : sceneBlock.toString();
    }

    private String getSceneContent(Scene scene) {
        return tipTapMarkdownRenderer.render(scene.getContentJson())
                .orElseGet(() -> scene.getContentText() == null || scene.getContentText().isBlank() ? null : scene.getContentText());
    }

    private void appendBlock(StringBuilder markdown, String block) {
        if (!markdown.isEmpty()) {
            markdown.append("\n\n");
        }

        markdown.append(block);
    }

    private void appendDocxChapterScenes(
            XWPFDocument document,
            List<Scene> scenes,
            boolean includeSceneTitles,
            boolean includeEmptyScenes
    ) {
        for (Scene scene : scenes) {
            appendDocxScene(document, scene, includeSceneTitles, includeEmptyScenes);
        }
    }

    private void appendDocxScene(
            XWPFDocument document,
            Scene scene,
            boolean includeSceneTitles,
            boolean includeEmptyScenes
    ) {
        boolean hasJsonContent = tipTapDocxRenderer.canRender(scene.getContentJson());
        boolean hasTextContent = scene.getContentText() != null && !scene.getContentText().isBlank();

        if (!hasJsonContent && !hasTextContent && !includeEmptyScenes) {
            return;
        }
        if (!hasJsonContent && !hasTextContent && !includeSceneTitles) {
            return;
        }

        if (includeSceneTitles) {
            appendDocxHeading(document, "Heading3", scene.getTitle());
        }

        if (hasJsonContent) {
            tipTapDocxRenderer.renderInto(document, scene.getContentJson());
            return;
        }

        if (hasTextContent) {
            appendDocxParagraph(document, scene.getContentText());
        }
    }

    private void appendDocxHeading(XWPFDocument document, String style, String text) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setStyle(style);
        paragraph.createRun().setText(text);
    }

    private void appendDocxOptionalParagraph(XWPFDocument document, String text) {
        if (text == null || text.isBlank()) {
            return;
        }

        appendDocxParagraph(document, text);
    }

    private void appendDocxParagraph(XWPFDocument document, String text) {
        XWPFParagraph paragraph = document.createParagraph();
        String[] lines = text.split("\\R", -1);

        for (int index = 0; index < lines.length; index++) {
            XWPFRun run = paragraph.createRun();
            if (index > 0) {
                run.addBreak();
            }
            run.setText(lines[index]);
        }
    }

    private record ManuscriptExport(
            Book book,
            List<BookSection> sections,
            Map<UUID, List<Chapter>> chaptersBySection,
            Map<UUID, List<Scene>> scenesByChapter
    ) {
    }
}
