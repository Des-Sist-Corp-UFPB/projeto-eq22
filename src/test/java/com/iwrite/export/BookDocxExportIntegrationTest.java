package com.iwrite.export;

import com.iwrite.chapter.dto.ChapterRequest;
import com.iwrite.scene.dto.SceneContentRequest;
import com.iwrite.scene.entity.SceneStatus;
import com.iwrite.section.dto.BookSectionRequest;
import com.iwrite.section.entity.SectionType;
import com.iwrite.support.PostgresIntegrationTest;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class BookDocxExportIntegrationTest extends PostgresIntegrationTest {

    private static final String DOCX_CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void docxEndpointReturnsAttachmentWithDocxContentTypeAndFileName() throws Exception {
        var book = createBook("Livro DOCX Agil");
        var section = createSection(book, "Parte");
        var chapter = createChapter(section, "Capitulo");
        createScene(chapter, "Cena", SceneStatus.DRAFT, 0, "texto da cena");

        byte[] responseBody = mockMvc.perform(get("/api/books/{bookId}/export/docx", book.id()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, DOCX_CONTENT_TYPE))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"livro-docx-agil.docx\""))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, HttpHeaders.CONTENT_DISPOSITION))
                .andReturn()
                .getResponse()
                .getContentAsByteArray();

        assertThat(responseBody).isNotEmpty();
        try (XWPFDocument document = openDocument(responseBody)) {
            assertThat(documentText(document)).contains("Livro DOCX Agil");
        }
    }

    @Test
    void docxExportUsesSectionChapterSceneSortOrder() throws Exception {
        var book = createBook("Livro ordenado");
        var firstSection = sectionService.create(book.id(), new BookSectionRequest("Primeira parte", SectionType.PART, 0));
        var secondSection = sectionService.create(book.id(), new BookSectionRequest("Segunda parte", SectionType.PART, 1));
        var firstChapter = chapterService.create(firstSection.id(), new ChapterRequest("Primeiro capitulo", null, 0));
        var secondChapter = chapterService.create(secondSection.id(), new ChapterRequest("Segundo capitulo", null, 0));
        createScene(firstChapter, "Cena dois", SceneStatus.DRAFT, 1, "conteudo dois");
        createScene(firstChapter, "Cena um", SceneStatus.DRAFT, 0, "conteudo um");
        createScene(secondChapter, "Cena tres", SceneStatus.DRAFT, 0, "conteudo tres");

        try (XWPFDocument document = openExport(book.id(), true, false)) {
            List<String> paragraphs = paragraphTexts(document);

            assertComesBefore(paragraphs, "Primeira parte", "Primeiro capitulo");
            assertComesBefore(paragraphs, "Primeiro capitulo", "Cena um");
            assertComesBefore(paragraphs, "Cena um", "conteudo um");
            assertComesBefore(paragraphs, "conteudo um", "Cena dois");
            assertComesBefore(paragraphs, "Cena dois", "conteudo dois");
            assertComesBefore(paragraphs, "conteudo dois", "Segunda parte");
            assertComesBefore(paragraphs, "Segunda parte", "Segundo capitulo");
            assertComesBefore(paragraphs, "Segundo capitulo", "Cena tres");
        }
    }

    @Test
    void docxExportIncludesSceneTitlesOnlyWhenRequested() throws Exception {
        var book = createBook("Livro com cenas");
        var section = createSection(book, "Parte");
        var chapter = createChapter(section, "Capitulo");
        createScene(chapter, "Cena visivel", SceneStatus.DRAFT, 0, "texto da cena");

        try (XWPFDocument document = openExport(book.id(), false, false)) {
            assertThat(documentText(document))
                    .contains("texto da cena")
                    .doesNotContain("Cena visivel");
        }

        try (XWPFDocument document = openExport(book.id(), true, false)) {
            assertThat(documentText(document))
                    .contains("Cena visivel")
                    .contains("texto da cena");
        }
    }

    @Test
    void contentJsonBoldAndItalicArePreservedInDocxRuns() throws Exception {
        var book = createBook("Livro formatado");
        var section = createSection(book, "Parte");
        var chapter = createChapter(section, "Capitulo");
        var scene = createScene(chapter, "Cena", SceneStatus.DRAFT, 0, "fallback");
        sceneService.updateContent(scene.id(), new SceneContentRequest("""
                {"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"Antes "},{"type":"text","text":"negrito","marks":[{"type":"bold"}]},{"type":"text","text":" e "},{"type":"text","text":"italico","marks":[{"type":"italic"}]},{"type":"text","text":" depois"}]}]}""", "fallback"));

        try (XWPFDocument document = openExport(book.id(), false, false)) {
            XWPFParagraph paragraph = paragraphContaining(document, "Antes negrito e italico depois");

            assertThat(runWithText(paragraph, "negrito")).matches(XWPFRun::isBold);
            assertThat(runWithText(paragraph, "italico")).matches(XWPFRun::isItalic);
        }
    }

    @Test
    void invalidContentJsonFallsBackToContentTextInDocxExport() throws Exception {
        var book = createBook("Livro fallback");
        var section = createSection(book, "Parte");
        var chapter = createChapter(section, "Capitulo");
        var scene = createScene(chapter, "Cena", SceneStatus.DRAFT, 0, "original");
        sceneService.updateContent(scene.id(), new SceneContentRequest("{invalid", "texto fallback"));

        try (XWPFDocument document = openExport(book.id(), false, false)) {
            assertThat(documentText(document))
                    .contains("texto fallback")
                    .doesNotContain("original");
        }
    }

    private XWPFDocument openExport(UUID bookId, boolean includeSceneTitles, boolean includeEmptyScenes) throws Exception {
        byte[] responseBody = mockMvc.perform(get("/api/books/{bookId}/export/docx", bookId)
                        .param("includeSceneTitles", String.valueOf(includeSceneTitles))
                        .param("includeEmptyScenes", String.valueOf(includeEmptyScenes)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsByteArray();

        return openDocument(responseBody);
    }

    private XWPFDocument openDocument(byte[] responseBody) throws Exception {
        return new XWPFDocument(new ByteArrayInputStream(responseBody));
    }

    private String documentText(XWPFDocument document) {
        return String.join("\n", paragraphTexts(document));
    }

    private List<String> paragraphTexts(XWPFDocument document) {
        return document.getParagraphs().stream()
                .map(XWPFParagraph::getText)
                .filter(text -> !text.isBlank())
                .toList();
    }

    private void assertComesBefore(List<String> paragraphs, String before, String after) {
        assertThat(paragraphs).contains(before, after);
        assertThat(paragraphs.indexOf(before)).isLessThan(paragraphs.indexOf(after));
    }

    private XWPFParagraph paragraphContaining(XWPFDocument document, String text) {
        return document.getParagraphs().stream()
                .filter(paragraph -> paragraph.getText().equals(text))
                .findFirst()
                .orElseThrow();
    }

    private XWPFRun runWithText(XWPFParagraph paragraph, String text) {
        return paragraph.getRuns().stream()
                .filter(run -> text.equals(run.text()))
                .findFirst()
                .orElseThrow();
    }
}
