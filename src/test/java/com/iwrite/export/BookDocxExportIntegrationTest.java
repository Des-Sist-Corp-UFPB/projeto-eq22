package com.iwrite.export;

import com.iwrite.chapter.dto.ChapterRequest;
import com.iwrite.scene.dto.SceneContentRequest;
import com.iwrite.scene.entity.SceneStatus;
import com.iwrite.section.dto.BookSectionRequest;
import com.iwrite.section.entity.SectionType;
import com.iwrite.support.PostgresIntegrationTest;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
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
    void docxExportUsesDistinctHeadingStylesForBookSectionAndChapterTitles() throws Exception {
        var book = createBook("Livro com estilos");
        var section = createSection(book, "Parte principal");
        createChapter(section, "Capitulo inicial");

        try (XWPFDocument document = openExport(book.id(), false, false)) {
            XWPFParagraph bookTitle = paragraphContaining(document, "Livro com estilos");
            XWPFParagraph sectionTitle = paragraphContaining(document, "Parte principal");
            XWPFParagraph chapterTitle = paragraphContaining(document, "Capitulo inicial");

            assertThat(bookTitle.getStyle()).isEqualTo("Title");
            assertThat(bookTitle.getAlignment()).isEqualTo(ParagraphAlignment.CENTER);
            assertHeadingRun(bookTitle, "Livro com estilos", 24);

            assertThat(sectionTitle.getStyle()).isEqualTo("Heading1");
            assertHeadingRun(sectionTitle, "Parte principal", 18);

            assertThat(chapterTitle.getStyle()).isEqualTo("Heading2");
            assertHeadingRun(chapterTitle, "Capitulo inicial", 16);
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
        createScene(chapter, "Cena seguinte", SceneStatus.DRAFT, 1, "texto seguinte");

        try (XWPFDocument document = openExport(book.id(), false, false)) {
            assertThat(documentText(document))
                    .contains("texto da cena")
                    .contains("texto seguinte")
                    .doesNotContain("Cena visivel")
                    .doesNotContain("Cena seguinte");
        }

        try (XWPFDocument document = openExport(book.id(), true, false)) {
            assertThat(documentText(document))
                    .contains("Cena visivel")
                    .contains("texto da cena")
                    .contains("Cena seguinte")
                    .contains("texto seguinte");
            assertThat(paragraphContaining(document, "Cena visivel").getStyle()).isEqualTo("Heading3");
            assertThat(paragraphContaining(document, "Cena seguinte").getStyle()).isEqualTo("Heading3");
            assertHeadingRun(paragraphContaining(document, "Cena visivel"), "Cena visivel", 13);
            assertHeadingRun(paragraphContaining(document, "Cena seguinte"), "Cena seguinte", 13);
            assertThat(paragraphTexts(document)).doesNotContain("***");
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
            assertThat(documentText(document)).doesNotContain("fallback");
        }
    }

    @Test
    void contentJsonHeadingInsideSceneUsesLowerDocxHeadingStyle() throws Exception {
        var book = createBook("Livro com heading interno");
        var section = createSection(book, "Parte");
        var chapter = createChapter(section, "Capitulo");
        var scene = createScene(chapter, "Cena", SceneStatus.DRAFT, 0, "fallback");
        sceneService.updateContent(scene.id(), new SceneContentRequest("""
                {"type":"doc","content":[{"type":"heading","attrs":{"level":1},"content":[{"type":"text","text":"Heading de cena"}]},{"type":"paragraph","content":[{"type":"text","text":"Paragrafo normal"}]},{"type":"heading","attrs":{"level":2},"content":[{"type":"text","text":"Subheading de cena"}]},{"type":"heading","attrs":{"level":3},"content":[{"type":"text","text":"Detalhe de cena"}]}]}""", "fallback"));

        try (XWPFDocument document = openExport(book.id(), false, false)) {
            XWPFParagraph heading = paragraphContaining(document, "Heading de cena");
            XWPFParagraph body = paragraphContaining(document, "Paragrafo normal");
            XWPFParagraph subheading = paragraphContaining(document, "Subheading de cena");
            XWPFParagraph detailHeading = paragraphContaining(document, "Detalhe de cena");

            assertThat(heading.getStyle()).isEqualTo("Heading3");
            assertHeadingRun(heading, "Heading de cena", 13);

            assertThat(subheading.getStyle()).isEqualTo("Heading4");
            assertHeadingRun(subheading, "Subheading de cena", 12);

            assertThat(detailHeading.getStyle()).isEqualTo("Heading4");
            assertHeadingRun(detailHeading, "Detalhe de cena", 12);

            assertThat(body.getStyle()).isNull();
            XWPFRun bodyRun = runWithText(body, "Paragrafo normal");
            assertThat(bodyRun.isBold()).isFalse();
            assertThat(bodyRun.getFontSize()).isEqualTo(-1);
        }
    }

    @Test
    void docxExportSeparatesScenesWhenSceneTitlesAreOmitted() throws Exception {
        var book = createBook("Livro com separador");
        var section = createSection(book, "Parte");
        var chapter = createChapter(section, "Capitulo");
        createScene(chapter, "Cena um", SceneStatus.DRAFT, 0, "conteudo um");
        createScene(chapter, "Cena dois", SceneStatus.DRAFT, 1, "conteudo dois");

        try (XWPFDocument document = openExport(book.id(), false, false)) {
            List<String> paragraphs = paragraphTexts(document);

            assertThat(paragraphs).contains("***");
            assertComesBefore(paragraphs, "conteudo um", "***");
            assertComesBefore(paragraphs, "***", "conteudo dois");
            assertThat(paragraphContaining(document, "***").getAlignment()).isEqualTo(ParagraphAlignment.CENTER);
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

    @Test
    void renderableContentJsonThatWritesNoDocxContentFallsBackToContentText() throws Exception {
        var book = createBook("Livro fallback render");
        var section = createSection(book, "Parte");
        var chapter = createChapter(section, "Capitulo");
        var scene = createScene(chapter, "Cena", SceneStatus.DRAFT, 0, "original");
        sceneService.updateContent(scene.id(), new SceneContentRequest("""
                {"type":"doc","content":[{"type":"bulletList","content":[{"type":"paragraph","content":[{"type":"text","text":"json ignorado"}]}]}]}""", "fallback apos render vazio"));

        try (XWPFDocument document = openExport(book.id(), false, false)) {
            assertThat(documentText(document))
                    .contains("fallback apos render vazio")
                    .doesNotContain("json ignorado")
                    .doesNotContain("***");
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

    private void assertHeadingRun(XWPFParagraph paragraph, String text, int fontSize) {
        XWPFRun run = runWithText(paragraph, text);
        assertThat(run.isBold()).isTrue();
        assertThat(run.getFontSize()).isEqualTo(fontSize);
    }
}
