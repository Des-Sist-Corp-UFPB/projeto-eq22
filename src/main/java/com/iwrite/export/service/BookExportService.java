package com.iwrite.export.service;

import com.iwrite.book.entity.Book;
import com.iwrite.book.service.BookService;
import com.iwrite.chapter.entity.Chapter;
import com.iwrite.chapter.repository.ChapterRepository;
import com.iwrite.export.ExportFile;
import com.iwrite.export.ExportFileNameService;
import com.iwrite.export.ExportFormat;
import com.iwrite.common.exception.BadRequestException;
import com.iwrite.notebook.NotebookCategoryOrdering;
import com.iwrite.notebook.entity.NotebookCategory;
import com.iwrite.notebook.entity.NotebookNote;
import com.iwrite.notebook.entity.NotebookNoteStatus;
import com.iwrite.notebook.repository.NotebookCategoryRepository;
import com.iwrite.notebook.repository.NotebookNoteRepository;
import com.iwrite.scene.entity.Scene;
import com.iwrite.scene.repository.SceneRepository;
import com.iwrite.section.entity.BookSection;
import com.iwrite.section.repository.BookSectionRepository;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class BookExportService {

    private static final String NOTEBOOK_TITLE_PREFIX = "Caderno \u2014 ";
    private static final String UNCATEGORIZED_CATEGORY_NAME = "Sem categoria";

    private final BookService bookService;
    private final BookSectionRepository sectionRepository;
    private final ChapterRepository chapterRepository;
    private final SceneRepository sceneRepository;
    private final NotebookCategoryRepository notebookCategoryRepository;
    private final NotebookNoteRepository notebookNoteRepository;
    private final TipTapMarkdownRenderer tipTapMarkdownRenderer;
    private final TipTapDocxRenderer tipTapDocxRenderer;
    private final TipTapPlainTextRenderer tipTapPlainTextRenderer;
    private final ExportFileNameService exportFileNameService;

    public BookExportService(
            BookService bookService,
            BookSectionRepository sectionRepository,
            ChapterRepository chapterRepository,
            SceneRepository sceneRepository,
            NotebookCategoryRepository notebookCategoryRepository,
            NotebookNoteRepository notebookNoteRepository,
            TipTapMarkdownRenderer tipTapMarkdownRenderer,
            TipTapDocxRenderer tipTapDocxRenderer,
            TipTapPlainTextRenderer tipTapPlainTextRenderer,
            ExportFileNameService exportFileNameService
    ) {
        this.bookService = bookService;
        this.sectionRepository = sectionRepository;
        this.chapterRepository = chapterRepository;
        this.sceneRepository = sceneRepository;
        this.notebookCategoryRepository = notebookCategoryRepository;
        this.notebookNoteRepository = notebookNoteRepository;
        this.tipTapMarkdownRenderer = tipTapMarkdownRenderer;
        this.tipTapDocxRenderer = tipTapDocxRenderer;
        this.tipTapPlainTextRenderer = tipTapPlainTextRenderer;
        this.exportFileNameService = exportFileNameService;
    }

    @Transactional(readOnly = true)
    public ExportFile exportManuscript(
            UUID bookId,
            ExportFormat format,
            boolean includeSceneTitles,
            boolean includeEmptyScenes
    ) {
        ManuscriptExport manuscript = getManuscriptExport(bookId);
        String fileName = manuscriptFileName(manuscript.book(), format);

        return switch (format) {
            case TXT -> new ExportFile(
                    exportTxt(manuscript, includeSceneTitles, includeEmptyScenes).getBytes(StandardCharsets.UTF_8),
                    format.contentType(),
                    fileName
            );
            case MD -> new ExportFile(
                    exportMarkdown(manuscript, includeSceneTitles, includeEmptyScenes).getBytes(StandardCharsets.UTF_8),
                    format.contentType(),
                    fileName
            );
            case DOCX -> new ExportFile(
                    exportDocx(manuscript, includeSceneTitles, includeEmptyScenes),
                    format.contentType(),
                    fileName
            );
        };
    }

    @Transactional(readOnly = true)
    public ExportFile exportNotebook(
            UUID bookId,
            ExportFormat format,
            boolean includeOpen,
            boolean includeResolved
    ) {
        NotebookExport notebook = getNotebookExport(bookId, includeOpen, includeResolved);
        String fileName = exportFileNameService.fileName("caderno " + notebook.book().getTitle(), "caderno", format.extension());

        return switch (format) {
            case TXT -> new ExportFile(
                    exportNotebookTxt(notebook).getBytes(StandardCharsets.UTF_8),
                    format.contentType(),
                    fileName
            );
            case MD -> new ExportFile(
                    exportNotebookMarkdown(notebook).getBytes(StandardCharsets.UTF_8),
                    format.contentType(),
                    fileName
            );
            case DOCX -> new ExportFile(
                    exportNotebookDocx(notebook),
                    format.contentType(),
                    fileName
            );
        };
    }

    @Transactional(readOnly = true)
    public String exportMarkdown(UUID bookId, boolean includeSceneTitles, boolean includeEmptyScenes) {
        return exportMarkdown(getManuscriptExport(bookId), includeSceneTitles, includeEmptyScenes);
    }

    @Transactional(readOnly = true)
    public byte[] exportDocx(UUID bookId, boolean includeSceneTitles, boolean includeEmptyScenes) {
        return exportDocx(getManuscriptExport(bookId), includeSceneTitles, includeEmptyScenes);
    }

    public String getMarkdownFileName(UUID bookId) {
        Book book = bookService.getBook(bookId);
        return manuscriptFileName(book, ExportFormat.MD);
    }

    public String getDocxFileName(UUID bookId) {
        Book book = bookService.getBook(bookId);
        return manuscriptFileName(book, ExportFormat.DOCX);
    }

    private String exportMarkdown(ManuscriptExport manuscript, boolean includeSceneTitles, boolean includeEmptyScenes) {
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

    private byte[] exportDocx(ManuscriptExport manuscript, boolean includeSceneTitles, boolean includeEmptyScenes) {
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

    private String exportTxt(ManuscriptExport manuscript, boolean includeSceneTitles, boolean includeEmptyScenes) {
        StringBuilder text = new StringBuilder();
        appendTextBlock(text, manuscript.book().getTitle());
        appendOptionalTextBlock(text, manuscript.book().getSubtitle());
        appendOptionalTextBlock(text, manuscript.book().getDescription());

        for (BookSection section : manuscript.sections()) {
            appendTextBlock(text, section.getTitle());

            for (Chapter chapter : manuscript.chaptersBySection().getOrDefault(section.getId(), List.of())) {
                appendTextBlock(text, chapter.getTitle());
                appendTxtChapterScenes(text, manuscript.scenesByChapter().getOrDefault(chapter.getId(), List.of()), includeSceneTitles, includeEmptyScenes);
            }
        }

        return normalizeLineEndings(text.toString());
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

    private NotebookExport getNotebookExport(UUID bookId, boolean includeOpen, boolean includeResolved) {
        if (!includeOpen && !includeResolved) {
            throw new BadRequestException("At least one notebook note status must be included");
        }

        Book book = bookService.getBook(bookId);
        List<NotebookNote> notes = notebookNoteRepository.findByBookIdOrderByUpdatedAtDescIdAsc(bookId)
                .stream()
                .filter(note -> shouldIncludeNotebookStatus(note.getStatus(), includeOpen, includeResolved))
                .toList();
        List<NotebookCategory> categories = NotebookCategoryOrdering.ordered(
                notebookCategoryRepository.findByBookIdOrderBySortOrderAscNameAscIdAsc(bookId));
        Set<UUID> categoryIds = categories.stream()
                .map(NotebookCategory::getId)
                .collect(Collectors.toSet());
        List<NotebookCategoryExport> categoryExports = new ArrayList<>();

        for (NotebookCategory category : categories) {
            List<NotebookNote> categoryNotes = notes.stream()
                    .filter(note -> note.getCategory() != null && note.getCategory().getId().equals(category.getId()))
                    .toList();
            if (!categoryNotes.isEmpty()) {
                categoryExports.add(new NotebookCategoryExport(category.getName(), categoryNotes));
            }
        }

        List<NotebookNote> uncategorizedNotes = notes.stream()
                .filter(note -> note.getCategory() == null || !categoryIds.contains(note.getCategory().getId()))
                .toList();
        if (!uncategorizedNotes.isEmpty()) {
            categoryExports.add(new NotebookCategoryExport(UNCATEGORIZED_CATEGORY_NAME, uncategorizedNotes));
        }

        return new NotebookExport(book, categoryExports);
    }

    private boolean shouldIncludeNotebookStatus(NotebookNoteStatus status, boolean includeOpen, boolean includeResolved) {
        return (includeOpen && status == NotebookNoteStatus.OPEN)
                || (includeResolved && status == NotebookNoteStatus.RESOLVED);
    }

    private String manuscriptFileName(Book book, ExportFormat format) {
        return exportFileNameService.fileName(book.getTitle(), "manuscrito", format.extension());
    }

    private String exportNotebookTxt(NotebookExport notebook) {
        StringBuilder text = new StringBuilder();
        appendTextBlock(text, notebookTitle(notebook));

        for (NotebookCategoryExport category : notebook.categories()) {
            appendTextBlock(text, category.name());
            appendNotebookTxtStatusSection(text, "Abertas", category.openNotes());
            appendNotebookTxtStatusSection(text, "Resolvidas", category.resolvedNotes());
        }

        return normalizeLineEndings(text.toString());
    }

    private void appendNotebookTxtStatusSection(StringBuilder text, String statusTitle, List<NotebookNote> notes) {
        if (notes.isEmpty()) {
            return;
        }

        appendTextBlock(text, statusTitle);
        for (NotebookNote note : notes) {
            appendTextBlock(text, notebookNoteTxtBlock(note));
        }
    }

    private String notebookNoteTxtBlock(NotebookNote note) {
        StringBuilder block = new StringBuilder(note.getTitle());
        block.append("\n").append(notebookMetadata(note));
        appendNotebookContent(block, note.getContent());
        return block.toString();
    }

    private String exportNotebookMarkdown(NotebookExport notebook) {
        StringBuilder markdown = new StringBuilder();
        appendHeading(markdown, "#", notebookTitle(notebook));

        for (NotebookCategoryExport category : notebook.categories()) {
            appendHeading(markdown, "##", category.name());
            appendNotebookMarkdownStatusSection(markdown, "Abertas", category.openNotes());
            appendNotebookMarkdownStatusSection(markdown, "Resolvidas", category.resolvedNotes());
        }

        return markdown.toString();
    }

    private void appendNotebookMarkdownStatusSection(StringBuilder markdown, String statusTitle, List<NotebookNote> notes) {
        if (notes.isEmpty()) {
            return;
        }

        appendHeading(markdown, "###", statusTitle);
        for (NotebookNote note : notes) {
            appendHeading(markdown, "####", note.getTitle());
            appendBlock(markdown, notebookMetadata(note));
            appendOptionalBlock(markdown, normalizeNotebookContent(note.getContent()));
        }
    }

    private byte[] exportNotebookDocx(NotebookExport notebook) {
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            appendDocxHeading(document, "Title", notebookTitle(notebook));

            for (NotebookCategoryExport category : notebook.categories()) {
                appendDocxHeading(document, "Heading1", category.name());
                appendNotebookDocxStatusSection(document, "Abertas", category.openNotes());
                appendNotebookDocxStatusSection(document, "Resolvidas", category.resolvedNotes());
            }

            document.write(outputStream);
            return outputStream.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to export DOCX notebook", exception);
        }
    }

    private void appendNotebookDocxStatusSection(XWPFDocument document, String statusTitle, List<NotebookNote> notes) {
        if (notes.isEmpty()) {
            return;
        }

        appendDocxHeading(document, "Heading2", statusTitle);
        for (NotebookNote note : notes) {
            appendDocxHeading(document, "Heading3", note.getTitle());
            appendDocxParagraph(document, notebookMetadata(note));
            appendDocxNotebookContent(document, note.getContent());
        }
    }

    private String notebookTitle(NotebookExport notebook) {
        return NOTEBOOK_TITLE_PREFIX + notebook.book().getTitle();
    }

    private String notebookMetadata(NotebookNote note) {
        return "Status: " + notebookStatusLabel(note.getStatus())
                + " | Atualizada em: " + note.getUpdatedAt().toLocalDate();
    }

    private String notebookStatusLabel(NotebookNoteStatus status) {
        return status == NotebookNoteStatus.RESOLVED ? "Resolvida" : "Aberta";
    }

    private void appendNotebookContent(StringBuilder block, String content) {
        String normalizedContent = normalizeNotebookContent(content);
        if (normalizedContent == null) {
            return;
        }

        block.append("\n\n").append(normalizedContent);
    }

    private String normalizeNotebookContent(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }

        return normalizeLineEndings(content).strip();
    }

    private void appendDocxNotebookContent(XWPFDocument document, String content) {
        String normalizedContent = normalizeNotebookContent(content);
        if (normalizedContent == null) {
            return;
        }

        for (String paragraph : normalizedContent.split("\\n{2,}", -1)) {
            appendDocxParagraph(document, paragraph);
        }
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

    private void appendTxtChapterScenes(
            StringBuilder text,
            List<Scene> scenes,
            boolean includeSceneTitles,
            boolean includeEmptyScenes
    ) {
        boolean hasPreviousSceneContent = false;

        for (Scene scene : scenes) {
            String sceneBlock = buildTxtSceneBlock(scene, includeSceneTitles, includeEmptyScenes);
            if (sceneBlock == null) {
                continue;
            }

            if (hasPreviousSceneContent && !includeSceneTitles) {
                appendTextBlock(text, TipTapPlainTextRenderer.TEXT_SEPARATOR);
            }

            appendTextBlock(text, sceneBlock);
            hasPreviousSceneContent = true;
        }
    }

    private String buildTxtSceneBlock(Scene scene, boolean includeSceneTitles, boolean includeEmptyScenes) {
        String sceneContent = getPlainSceneContent(scene);
        boolean hasContent = sceneContent != null && !sceneContent.isBlank();

        if (!hasContent && !includeEmptyScenes) {
            return null;
        }

        StringBuilder sceneBlock = new StringBuilder();
        if (includeSceneTitles) {
            sceneBlock.append(scene.getTitle());
        }
        if (hasContent) {
            if (!sceneBlock.isEmpty()) {
                sceneBlock.append("\n\n");
            }
            sceneBlock.append(sceneContent);
        }

        return sceneBlock.isEmpty() ? null : sceneBlock.toString();
    }

    private String getPlainSceneContent(Scene scene) {
        return tipTapPlainTextRenderer.render(scene.getContentJson())
                .orElseGet(() -> scene.getContentText() == null || scene.getContentText().isBlank() ? null : normalizeLineEndings(scene.getContentText()));
    }

    private void appendOptionalTextBlock(StringBuilder text, String block) {
        if (block == null || block.isBlank()) {
            return;
        }

        appendTextBlock(text, block);
    }

    private void appendTextBlock(StringBuilder text, String block) {
        if (!text.isEmpty()) {
            text.append("\n\n");
        }

        text.append(normalizeLineEndings(block));
    }

    private String normalizeLineEndings(String text) {
        return text.replaceAll("\\R", "\n");
    }

    private void appendDocxChapterScenes(
            XWPFDocument document,
            List<Scene> scenes,
            boolean includeSceneTitles,
            boolean includeEmptyScenes
    ) {
        boolean hasPreviousScene = false;

        for (Scene scene : scenes) {
            boolean appendedScene = appendDocxScene(document, scene, includeSceneTitles, includeEmptyScenes, hasPreviousScene);
            hasPreviousScene = appendedScene || hasPreviousScene;
        }
    }

    private boolean appendDocxScene(
            XWPFDocument document,
            Scene scene,
            boolean includeSceneTitles,
            boolean includeEmptyScenes,
            boolean hasPreviousScene
    ) {
        int initialBodyElementCount = document.getBodyElements().size();
        boolean hasJsonContent = tipTapDocxRenderer.canRender(scene.getContentJson());
        boolean hasTextContent = scene.getContentText() != null && !scene.getContentText().isBlank();

        if (!hasJsonContent && !hasTextContent && !includeEmptyScenes) {
            return false;
        }
        if (!hasJsonContent && !hasTextContent && !includeSceneTitles) {
            return false;
        }

        if (!includeSceneTitles && hasPreviousScene) {
            appendDocxSceneSeparator(document);
        }

        if (includeSceneTitles) {
            appendDocxHeading(document, "Heading3", scene.getTitle());
        }

        if (hasJsonContent) {
            boolean renderedJsonContent = tipTapDocxRenderer.renderInto(document, scene.getContentJson());
            if (renderedJsonContent) {
                return true;
            }
        }

        if (hasTextContent) {
            appendDocxParagraph(document, scene.getContentText());
            return true;
        }

        if (includeSceneTitles) {
            return true;
        }

        removeDocxBodyElementsFrom(document, initialBodyElementCount);
        return false;
    }

    private void removeDocxBodyElementsFrom(XWPFDocument document, int firstIndexToRemove) {
        for (int index = document.getBodyElements().size() - 1; index >= firstIndexToRemove; index--) {
            document.removeBodyElement(index);
        }
    }

    private void appendDocxSceneSeparator(XWPFDocument document) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setAlignment(ParagraphAlignment.CENTER);
        paragraph.createRun().setText("***");
    }

    private void appendDocxHeading(XWPFDocument document, String style, String text) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setStyle(style);
        applyDocxHeadingParagraphFormat(paragraph, style);

        XWPFRun run = paragraph.createRun();
        run.setText(text);
        applyDocxHeadingRunFormat(run, style);
    }

    private void applyDocxHeadingParagraphFormat(XWPFParagraph paragraph, String style) {
        if ("Title".equals(style)) {
            paragraph.setAlignment(ParagraphAlignment.CENTER);
            paragraph.setSpacingAfter(360);
            return;
        }

        if ("Heading1".equals(style)) {
            paragraph.setSpacingBefore(360);
            paragraph.setSpacingAfter(180);
            return;
        }

        if ("Heading2".equals(style)) {
            paragraph.setSpacingBefore(280);
            paragraph.setSpacingAfter(140);
            return;
        }

        if ("Heading3".equals(style)) {
            paragraph.setSpacingBefore(180);
            paragraph.setSpacingAfter(100);
        }
    }

    private void applyDocxHeadingRunFormat(XWPFRun run, String style) {
        run.setBold(true);

        if ("Title".equals(style)) {
            run.setFontSize(24);
            return;
        }

        if ("Heading1".equals(style)) {
            run.setFontSize(18);
            return;
        }

        if ("Heading2".equals(style)) {
            run.setFontSize(16);
            return;
        }

        if ("Heading3".equals(style)) {
            run.setFontSize(13);
        }
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

    private record NotebookExport(
            Book book,
            List<NotebookCategoryExport> categories
    ) {
    }

    private record NotebookCategoryExport(
            String name,
            List<NotebookNote> notes
    ) {

        List<NotebookNote> openNotes() {
            return notes.stream()
                    .filter(note -> note.getStatus() == NotebookNoteStatus.OPEN)
                    .toList();
        }

        List<NotebookNote> resolvedNotes() {
            return notes.stream()
                    .filter(note -> note.getStatus() == NotebookNoteStatus.RESOLVED)
                    .toList();
        }
    }
}
