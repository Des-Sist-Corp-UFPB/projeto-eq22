package com.iwrite.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iwrite.book.dto.BookRequest;
import com.iwrite.chapter.dto.ChapterRequest;
import com.iwrite.scene.dto.SceneContentRequest;
import com.iwrite.scene.entity.SceneStatus;
import com.iwrite.section.dto.BookSectionRequest;
import com.iwrite.section.entity.SectionType;
import com.iwrite.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class BookExportIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ExportFileNameService exportFileNameService;

    @Test
    void canonicalTxtEndpointReturnsAttachmentWithPlainTextContentTypeAndFileName() throws Exception {
        var book = createBook("Livro TXT Agil");
        var section = createSection(book, "Parte");
        var chapter = createChapter(section, "Capitulo");
        createScene(chapter, "Cena", SceneStatus.DRAFT, 0, "texto da cena");

        mockMvc.perform(get("/api/books/{bookId}/exports/manuscript", book.id())
                        .param("format", "txt"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/plain;charset=UTF-8"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"livro-txt-agil.txt\""))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, HttpHeaders.CONTENT_DISPOSITION))
                .andExpect(content().string("""
                        Livro TXT Agil

                        Parte

                        Capitulo

                        texto da cena"""));
    }

    @Test
    void txtExportPreservesHierarchyAndOrdering() throws Exception {
        var book = createBook("Livro TXT ordenado");
        var firstSection = sectionService.create(book.id(), new BookSectionRequest("Primeira parte", SectionType.PART, 0));
        var secondSection = sectionService.create(book.id(), new BookSectionRequest("Segunda parte", SectionType.PART, 1));
        var firstChapter = chapterService.create(firstSection.id(), new ChapterRequest("Primeiro capitulo", null, 0));
        var secondChapter = chapterService.create(secondSection.id(), new ChapterRequest("Segundo capitulo", null, 0));
        createScene(firstChapter, "Cena dois", SceneStatus.DRAFT, 1, "conteudo dois");
        createScene(firstChapter, "Cena um", SceneStatus.DRAFT, 0, "conteudo um");
        createScene(secondChapter, "Cena tres", SceneStatus.DRAFT, 0, "conteudo tres");

        mockMvc.perform(get("/api/books/{bookId}/exports/manuscript", book.id())
                        .param("format", "txt"))
                .andExpect(status().isOk())
                .andExpect(content().string("""
                        Livro TXT ordenado

                        Primeira parte

                        Primeiro capitulo

                        conteudo um

                        ----------------------------------------

                        conteudo dois

                        Segunda parte

                        Segundo capitulo

                        conteudo tres"""));
    }

    @Test
    void txtExportIncludesSceneTitlesWhenRequested() throws Exception {
        var book = bookService.create(new BookRequest("Livro TXT com titulos", "Subtitulo", "Descricao", null, null));
        var section = sectionService.create(book.id(), new BookSectionRequest("Parte", SectionType.PART, 0));
        var chapter = chapterService.create(section.id(), new ChapterRequest("Capitulo", null, 0));
        createScene(chapter, "Cena visivel", SceneStatus.DRAFT, 0, "texto da cena");

        mockMvc.perform(get("/api/books/{bookId}/exports/manuscript", book.id())
                        .param("format", "txt")
                        .param("includeSceneTitles", "true"))
                .andExpect(status().isOk())
                .andExpect(content().string("""
                        Livro TXT com titulos

                        Subtitulo

                        Descricao

                        Parte

                        Capitulo

                        Cena visivel

                        texto da cena"""));
    }

    @Test
    void txtExportIncludesEmptyScenesOnlyWhenTitlesAreIncluded() throws Exception {
        var book = createBook("Livro TXT com vazias");
        var section = createSection(book, "Parte");
        var chapter = createChapter(section, "Capitulo");
        createScene(chapter, "Cena preenchida", SceneStatus.DRAFT, 0, "texto");
        createScene(chapter, "Cena vazia", SceneStatus.DRAFT, 1, null);

        mockMvc.perform(get("/api/books/{bookId}/exports/manuscript", book.id())
                        .param("format", "txt")
                        .param("includeEmptyScenes", "true"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Cena vazia"))));

        mockMvc.perform(get("/api/books/{bookId}/exports/manuscript", book.id())
                        .param("format", "txt")
                        .param("includeSceneTitles", "true")
                        .param("includeEmptyScenes", "true"))
                .andExpect(status().isOk())
                .andExpect(content().string("""
                        Livro TXT com vazias

                        Parte

                        Capitulo

                        Cena preenchida

                        texto

                        Cena vazia"""));
    }

    @Test
    void txtExportUsesTipTapPlainTextAndFallsBackToContentText() throws Exception {
        var book = createBook("Livro TXT tiptap");
        var section = createSection(book, "Parte");
        var chapter = createChapter(section, "Capitulo");
        var renderedScene = createScene(chapter, "Cena json", SceneStatus.DRAFT, 0, "fallback ignorado");
        var fallbackScene = createScene(chapter, "Cena fallback", SceneStatus.DRAFT, 1, "fallback usado");
        sceneService.updateContent(renderedScene.id(), new SceneContentRequest("""
                {"type":"doc","content":[{"type":"heading","attrs":{"level":1},"content":[{"type":"text","text":"Titulo interno"}]},{"type":"paragraph","content":[{"type":"text","text":"Primeira linha"},{"type":"hardBreak"},{"type":"text","text":"segunda linha"}]},{"type":"bulletList","content":[{"type":"listItem","content":[{"type":"paragraph","content":[{"type":"text","text":"Item simples","marks":[{"type":"bold"}]}]}]}]},{"type":"orderedList","attrs":{"start":2},"content":[{"type":"listItem","content":[{"type":"paragraph","content":[{"type":"text","text":"Item numerado"}]}]}]},{"type":"blockquote","content":[{"type":"paragraph","content":[{"type":"text","text":"Texto citado"}]}]},{"type":"horizontalRule"}]}""", "fallback ignorado"));
        sceneService.updateContent(fallbackScene.id(), new SceneContentRequest("{invalid", "fallback usado"));

        mockMvc.perform(get("/api/books/{bookId}/exports/manuscript", book.id())
                        .param("format", "txt"))
                .andExpect(status().isOk())
                .andExpect(content().string("""
                        Livro TXT tiptap

                        Parte

                        Capitulo

                        Titulo interno

                        Primeira linha
                        segunda linha

                        • Item simples

                        2. Item numerado

                        CITAÇÃO:
                        Texto citado

                        ----------------------------------------

                        ----------------------------------------

                        fallback usado"""));
    }

    @Test
    void fileNameServiceRemovesAccentsAndPreservesExtension() {
        assertThat(exportFileNameService.fileName("Coração, Razão & Memória", "manuscrito", "txt"))
                .isEqualTo("coracao-razao-memoria.txt");
    }

    @Test
    void fileNameServiceCollapsesRepeatedSeparatorsAndTrimsEdges() {
        assertThat(exportFileNameService.fileName("  -- Livro... com   espaços && sinais --  ", "manuscrito", "md"))
                .isEqualTo("livro-com-espacos-sinais.md");
    }

    @Test
    void fileNameServiceUsesFallbackWhenSlugIsEmptyAndPreservesDocxExtension() {
        assertThat(exportFileNameService.fileName("!!!", "manuscrito", "docx"))
                .isEqualTo("manuscrito.docx");
    }

    @Test
    void canonicalTxtEndpointUsesFallbackFileNameWhenTitleSlugIsEmpty() throws Exception {
        var book = createBook("!!!");
        var section = createSection(book, "Parte");
        var chapter = createChapter(section, "Capitulo");
        createScene(chapter, "Cena", SceneStatus.DRAFT, 0, "texto");

        mockMvc.perform(get("/api/books/{bookId}/exports/manuscript", book.id())
                        .param("format", "txt"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"manuscrito.txt\""));
    }

    @Test
    void notebookTxtEndpointExportsFullNotebookGroupedByCategoryAndStatus() throws Exception {
        String today = LocalDate.now().toString();
        var book = createBook("Livro Caderno Agil");
        var pesquisaId = findNotebookCategoryId(book.id(), "Pesquisa");
        var outroId = findNotebookCategoryId(book.id(), "Outro");
        createNotebookNote(book.id(), "Nota resolvida", "Conteudo resolvido", outroId, "RESOLVED");
        createNotebookNote(book.id(), "Nota solta", "Conteudo solto", null, "OPEN");
        createNotebookNote(book.id(), "Nota pesquisa", "Conteudo pesquisa\ncom linha", pesquisaId, "OPEN");

        mockMvc.perform(get("/api/books/{bookId}/exports/notebook", book.id())
                        .param("format", "txt"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/plain;charset=UTF-8"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"caderno-livro-caderno-agil.txt\""))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, HttpHeaders.CONTENT_DISPOSITION))
                .andExpect(content().string("""
                        Caderno \u2014 Livro Caderno Agil

                        Pesquisa

                        Abertas

                        Nota pesquisa
                        Status: Aberta | Atualizada em: %s

                        Conteudo pesquisa
                        com linha

                        Outro

                        Resolvidas

                        Nota resolvida
                        Status: Resolvida | Atualizada em: %s

                        Conteudo resolvido

                        Sem categoria

                        Abertas

                        Nota solta
                        Status: Aberta | Atualizada em: %s

                        Conteudo solto""".formatted(today, today, today)));
    }

    @Test
    void notebookMarkdownEndpointPreservesHierarchyAndCanExcludeResolvedNotes() throws Exception {
        String today = LocalDate.now().toString();
        var book = createBook("Livro Caderno Markdown");
        var pesquisaId = findNotebookCategoryId(book.id(), "Pesquisa");
        createNotebookNote(book.id(), "Nota aberta", "Conteudo aberto", pesquisaId, "OPEN");
        createNotebookNote(book.id(), "Nota resolvida", "Conteudo resolvido", pesquisaId, "RESOLVED");

        mockMvc.perform(get("/api/books/{bookId}/exports/notebook", book.id())
                        .param("format", "md")
                        .param("includeResolved", "false"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/markdown;charset=UTF-8"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"caderno-livro-caderno-markdown.md\""))
                .andExpect(content().string("""
                        # Caderno \u2014 Livro Caderno Markdown

                        ## Pesquisa

                        ### Abertas

                        #### Nota aberta

                        Status: Aberta | Atualizada em: %s

                        Conteudo aberto""".formatted(today)))
                .andExpect(content().string(not(containsString("Nota resolvida"))))
                .andExpect(content().string(not(containsString("Resolvidas"))));
    }

    @Test
    void notebookExportRejectsEmptyStatusSelection() throws Exception {
        var book = createBook("Livro Caderno status invalido");

        mockMvc.perform(get("/api/books/{bookId}/exports/notebook", book.id())
                        .param("format", "txt")
                        .param("includeOpen", "false")
                        .param("includeResolved", "false"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void notebookExportRejectsInvalidFormat() throws Exception {
        var book = createBook("Livro Caderno formato invalido");

        mockMvc.perform(get("/api/books/{bookId}/exports/notebook", book.id())
                        .param("format", "pdf"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void missingBookOnNotebookExportReturnsNotFound() throws Exception {
        mockMvc.perform(get("/api/books/{bookId}/exports/notebook", java.util.UUID.randomUUID())
                        .param("format", "txt"))
                .andExpect(status().isNotFound());
    }

    @Test
    void notebookExportAndCategoryApiShareNamedCategoryOrdering() throws Exception {
        String today = LocalDate.now().toString();
        var book = createBook("Livro Caderno ordem compartilhada");
        var ideiaId = findNotebookCategoryId(book.id(), "Ideia");
        var pesquisaId = findNotebookCategoryId(book.id(), "Pesquisa");
        var outroId = findNotebookCategoryId(book.id(), "Outro");

        mockMvc.perform(patch("/api/notebook/categories/{categoryId}", outroId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "name", "  outro  ",
                                "sortOrder", -10
                        ))))
                .andExpect(status().isOk());

        createNotebookNote(book.id(), "Nota ideia", "Conteudo ideia", ideiaId, "OPEN");
        createNotebookNote(book.id(), "Nota pesquisa", "Conteudo pesquisa", pesquisaId, "OPEN");
        createNotebookNote(book.id(), "Nota outro", "Conteudo outro", outroId, "OPEN");

        String categoriesResponse = mockMvc.perform(get("/api/books/{bookId}/notebook/categories", book.id()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode categories = objectMapper.readTree(categoriesResponse);
        assertThat(categories.get(0).get("name").asText()).isEqualTo("Ideia");
        assertThat(categories.get(1).get("name").asText()).isEqualTo("Pesquisa");
        assertThat(categories.get(categories.size() - 1).get("name").asText()).isEqualTo("  outro  ");

        String export = mockMvc.perform(get("/api/books/{bookId}/exports/notebook", book.id())
                        .param("format", "txt"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Status: Aberta | Atualizada em: " + today)))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(export).containsSubsequence(
                "Ideia",
                "Nota ideia",
                "Pesquisa",
                "Nota pesquisa",
                "  outro  ",
                "Nota outro"
        );
    }

    @Test
    void canonicalMarkdownEndpointPreservesCurrentMarkdownSemantics() throws Exception {
        var book = bookService.create(new BookRequest("Livro canonico markdown", "Subtitulo", "Descricao", null, null));
        var section = sectionService.create(book.id(), new BookSectionRequest("Parte", SectionType.PART, 0));
        var chapter = chapterService.create(section.id(), new ChapterRequest("Capitulo", null, 0));
        createScene(chapter, "Cena visivel", SceneStatus.DRAFT, 0, "texto da cena");

        mockMvc.perform(get("/api/books/{bookId}/exports/manuscript", book.id())
                        .param("format", "md")
                        .param("includeSceneTitles", "true"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/markdown;charset=UTF-8"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"livro-canonico-markdown.md\""))
                .andExpect(content().string("""
                        # Livro canonico markdown

                        Subtitulo

                        Descricao

                        ## Parte

                        ### Capitulo

                        #### Cena visivel

                        texto da cena"""));
    }

    @Test
    void invalidCanonicalFormatReturnsBadRequest() throws Exception {
        var book = createBook("Livro formato invalido");

        mockMvc.perform(get("/api/books/{bookId}/exports/manuscript", book.id())
                        .param("format", "pdf"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void missingBookOnCanonicalExportReturnsNotFound() throws Exception {
        mockMvc.perform(get("/api/books/{bookId}/exports/manuscript", java.util.UUID.randomUUID())
                        .param("format", "txt"))
                .andExpect(status().isNotFound());
    }

    @Test
    void exportOmitsSceneTitlesByDefault() throws Exception {
        var book = createBook("Livro sem titulos");
        var section = createSection(book, "Parte");
        var chapter = createChapter(section, "Capitulo");
        createScene(chapter, "Cena secreta", SceneStatus.DRAFT, 0, "texto da cena");

        mockMvc.perform(get("/api/books/{bookId}/export/markdown", book.id()))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/markdown;charset=UTF-8"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"livro-sem-titulos.md\""))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, HttpHeaders.CONTENT_DISPOSITION))
                .andExpect(content().string(not(containsString("Cena secreta"))))
                .andExpect(content().string("""
                        # Livro sem titulos

                        ## Parte

                        ### Capitulo

                        texto da cena"""));
    }

    @Test
    void exportIncludesSceneTitlesWhenRequested() throws Exception {
        var book = bookService.create(new BookRequest("Livro com titulos", "Subtitulo", "Descricao", null, null));
        var section = sectionService.create(book.id(), new BookSectionRequest("Parte", SectionType.PART, 0));
        var chapter = chapterService.create(section.id(), new ChapterRequest("Capitulo", null, 0));
        createScene(chapter, "Cena visivel", SceneStatus.DRAFT, 0, "texto da cena");

        mockMvc.perform(get("/api/books/{bookId}/export/markdown", book.id())
                        .param("includeSceneTitles", "true"))
                .andExpect(status().isOk())
                .andExpect(content().string("""
                        # Livro com titulos

                        Subtitulo

                        Descricao

                        ## Parte

                        ### Capitulo

                        #### Cena visivel

                        texto da cena"""));
    }

    @Test
    void emptyScenesAppearOnlyWhenRequested() throws Exception {
        var book = createBook("Livro com vazias");
        var section = createSection(book, "Parte");
        var chapter = createChapter(section, "Capitulo");
        createScene(chapter, "Cena preenchida", SceneStatus.DRAFT, 0, "texto");
        createScene(chapter, "Cena vazia", SceneStatus.DRAFT, 1, null);

        mockMvc.perform(get("/api/books/{bookId}/export/markdown", book.id()))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Cena vazia"))));

        mockMvc.perform(get("/api/books/{bookId}/export/markdown", book.id())
                        .param("includeSceneTitles", "true")
                        .param("includeEmptyScenes", "true"))
                .andExpect(status().isOk())
                .andExpect(content().string("""
                        # Livro com vazias

                        ## Parte

                        ### Capitulo

                        #### Cena preenchida

                        texto

                        ---

                        #### Cena vazia"""));
    }

    @Test
    void contentJsonBoldAndItalicCombinationUsesStableMarkdown() throws Exception {
        var book = createBook("Livro formatado");
        var section = createSection(book, "Parte");
        var chapter = createChapter(section, "Capitulo");
        var scene = createScene(chapter, "Cena", SceneStatus.DRAFT, 0, "fallback");
        sceneService.updateContent(scene.id(), new SceneContentRequest("""
                {"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"Antes"},{"type":"text","text":" marcado ","marks":[{"type":"bold"},{"type":"italic"}]},{"type":"text","text":"depois"}]}]}""", "fallback"));

        mockMvc.perform(get("/api/books/{bookId}/export/markdown", book.id()))
                .andExpect(status().isOk())
                .andExpect(content().string("""
                        # Livro formatado

                        ## Parte

                        ### Capitulo

                        Antes **_marcado_** depois"""));
    }

    @Test
    void contentJsonBoldAndItalicWithEndingPunctuationUsesBalancedMarkdown() throws Exception {
        var book = createBook("Livro com pontuacao");
        var section = createSection(book, "Parte");
        var chapter = createChapter(section, "Capitulo");
        var scene = createScene(chapter, "Cena", SceneStatus.DRAFT, 0, "fallback");
        sceneService.updateContent(scene.id(), new SceneContentRequest("""
                {"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"Disse "},{"type":"text","text":"ola,","marks":[{"type":"bold"}]},{"type":"text","text":" e "},{"type":"text","text":"corra!","marks":[{"type":"italic"}]}]}]}""", "fallback"));

        mockMvc.perform(get("/api/books/{bookId}/export/markdown", book.id()))
                .andExpect(status().isOk())
                .andExpect(content().string("""
                        # Livro com pontuacao

                        ## Parte

                        ### Capitulo

                        Disse **ola,** e *corra!*"""));
    }

    @Test
    void contentJsonMarkedTextKeepsTrailingSpaceBeforeNormalText() throws Exception {
        var book = createBook("Livro sem colar");
        var section = createSection(book, "Parte");
        var chapter = createChapter(section, "Capitulo");
        var scene = createScene(chapter, "Cena", SceneStatus.DRAFT, 0, "fallback");
        sceneService.updateContent(scene.id(), new SceneContentRequest("""
                {"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"Antes "},{"type":"text","text":"palavra ","marks":[{"type":"bold"}]},{"type":"text","text":"seguinte"}]}]}""", "fallback"));

        mockMvc.perform(get("/api/books/{bookId}/export/markdown", book.id()))
                .andExpect(status().isOk())
                .andExpect(content().string("""
                        # Livro sem colar

                        ## Parte

                        ### Capitulo

                        Antes **palavra** seguinte"""));
    }

    @Test
    void contentJsonTextStartingWithTripleAsteriskIsEscaped() throws Exception {
        var book = createBook("Livro com asteriscos");
        var section = createSection(book, "Parte");
        var chapter = createChapter(section, "Capitulo");
        var scene = createScene(chapter, "Cena", SceneStatus.DRAFT, 0, "fallback");
        sceneService.updateContent(scene.id(), new SceneContentRequest("""
                {"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"*** texto comum"}]}]}""", "fallback"));

        mockMvc.perform(get("/api/books/{bookId}/export/markdown", book.id()))
                .andExpect(status().isOk())
                .andExpect(content().string("""
                        # Livro com asteriscos

                        ## Parte

                        ### Capitulo

                        \\*** texto comum"""));
    }

    @Test
    void contentJsonTextStartingWithStructuralMarkdownCharactersIsEscaped() throws Exception {
        var book = createBook("Livro com escapes");
        var section = createSection(book, "Parte");
        var chapter = createChapter(section, "Capitulo");
        var scene = createScene(chapter, "Cena", SceneStatus.DRAFT, 0, "fallback");
        sceneService.updateContent(scene.id(), new SceneContentRequest("""
                {"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"# titulo falso"},{"type":"hardBreak"},{"type":"text","text":"> citacao falsa"},{"type":"hardBreak"},{"type":"text","text":"- item falso"}]}]}""", "fallback"));

        mockMvc.perform(get("/api/books/{bookId}/export/markdown", book.id()))
                .andExpect(status().isOk())
                .andExpect(content().string("""
                        # Livro com escapes

                        ## Parte

                        ### Capitulo

                        \\# titulo falso
                        \\> citacao falsa
                        \\- item falso"""));
    }

    @Test
    void contentJsonHeadingIsShiftedBelowChapterLevel() throws Exception {
        var book = createBook("Livro com heading");
        var section = createSection(book, "Parte");
        var chapter = createChapter(section, "Capitulo");
        var scene = createScene(chapter, "Cena", SceneStatus.DRAFT, 0, "fallback");
        sceneService.updateContent(scene.id(), new SceneContentRequest("""
                {"type":"doc","content":[{"type":"heading","attrs":{"level":1},"content":[{"type":"text","text":"Titulo interno"}]},{"type":"heading","attrs":{"level":3},"content":[{"type":"text","text":"Subtitulo interno"}]}]}""", "fallback"));

        mockMvc.perform(get("/api/books/{bookId}/export/markdown", book.id()))
                .andExpect(status().isOk())
                .andExpect(content().string("""
                        # Livro com heading

                        ## Parte

                        ### Capitulo

                        #### Titulo interno

                        ###### Subtitulo interno"""));
    }

    @Test
    void contentJsonHeadingLevelsFourFiveAndSixArePreservedBelowChapterLevel() throws Exception {
        var book = createBook("Livro com headings profundos");
        var section = createSection(book, "Parte");
        var chapter = createChapter(section, "Capitulo");
        var scene = createScene(chapter, "Cena", SceneStatus.DRAFT, 0, "fallback");
        sceneService.updateContent(scene.id(), new SceneContentRequest("""
                {"type":"doc","content":[{"type":"heading","attrs":{"level":4},"content":[{"type":"text","text":"Titulo nivel quatro"}]},{"type":"heading","attrs":{"level":5},"content":[{"type":"text","text":"Titulo nivel cinco"}]},{"type":"heading","attrs":{"level":6},"content":[{"type":"text","text":"Titulo nivel seis"}]}]}""", "fallback"));

        mockMvc.perform(get("/api/books/{bookId}/export/markdown", book.id()))
                .andExpect(status().isOk())
                .andExpect(content().string("""
                        # Livro com headings profundos

                        ## Parte

                        ### Capitulo

                        ###### Titulo nivel quatro

                        ###### Titulo nivel cinco

                        ###### Titulo nivel seis"""));
    }

    @Test
    void contentJsonBulletListIsExportedWithoutLosingItems() throws Exception {
        var book = createBook("Livro com lista");
        var section = createSection(book, "Parte");
        var chapter = createChapter(section, "Capitulo");
        var scene = createScene(chapter, "Cena", SceneStatus.DRAFT, 0, "fallback");
        sceneService.updateContent(scene.id(), new SceneContentRequest("""
                {"type":"doc","content":[{"type":"bulletList","content":[{"type":"listItem","content":[{"type":"paragraph","content":[{"type":"text","text":"Item um"}]}]},{"type":"listItem","content":[{"type":"paragraph","content":[{"type":"text","text":"Item dois","marks":[{"type":"bold"}]}]}]}]}]}""", "fallback"));

        mockMvc.perform(get("/api/books/{bookId}/export/markdown", book.id()))
                .andExpect(status().isOk())
                .andExpect(content().string("""
                        # Livro com lista

                        ## Parte

                        ### Capitulo

                        - Item um
                        - **Item dois**"""));
    }

    @Test
    void contentJsonBlockquoteIsExportedWithoutLosingText() throws Exception {
        var book = createBook("Livro com citacao");
        var section = createSection(book, "Parte");
        var chapter = createChapter(section, "Capitulo");
        var scene = createScene(chapter, "Cena", SceneStatus.DRAFT, 0, "fallback");
        sceneService.updateContent(scene.id(), new SceneContentRequest("""
                {"type":"doc","content":[{"type":"blockquote","content":[{"type":"paragraph","content":[{"type":"text","text":"Texto citado"}]}]}]}""", "fallback"));

        mockMvc.perform(get("/api/books/{bookId}/export/markdown", book.id()))
                .andExpect(status().isOk())
                .andExpect(content().string("""
                        # Livro com citacao

                        ## Parte

                        ### Capitulo

                        > Texto citado"""));
    }

    @Test
    void contentJsonCodeBlockIsExportedWithoutLosingText() throws Exception {
        var book = createBook("Livro com codigo");
        var section = createSection(book, "Parte");
        var chapter = createChapter(section, "Capitulo");
        var scene = createScene(chapter, "Cena", SceneStatus.DRAFT, 0, "fallback");
        sceneService.updateContent(scene.id(), new SceneContentRequest("""
                {"type":"doc","content":[{"type":"codeBlock","content":[{"type":"text","text":"linha 1\\nlinha 2"}]}]}""", "fallback"));

        mockMvc.perform(get("/api/books/{bookId}/export/markdown", book.id()))
                .andExpect(status().isOk())
                .andExpect(content().string("""
                        # Livro com codigo

                        ## Parte

                        ### Capitulo

                        ```
                        linha 1
                        linha 2
                        ```"""));
    }

    @Test
    void unknownContentJsonBlockWithTextFallsBackToContentText() throws Exception {
        var book = createBook("Livro fallback desconhecido");
        var section = createSection(book, "Parte");
        var chapter = createChapter(section, "Capitulo");
        var scene = createScene(chapter, "Cena", SceneStatus.DRAFT, 0, "original");
        sceneService.updateContent(scene.id(), new SceneContentRequest("""
                {"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"inicio json"}]},{"type":"unknownBlock","content":[{"type":"text","text":"texto importante"}]},{"type":"paragraph","content":[{"type":"text","text":"fim json"}]}]}""", "fallback completo"));

        mockMvc.perform(get("/api/books/{bookId}/export/markdown", book.id()))
                .andExpect(status().isOk())
                .andExpect(content().string("""
                        # Livro fallback desconhecido

                        ## Parte

                        ### Capitulo

                        fallback completo"""));
    }

    @Test
    void invalidContentJsonFallsBackToContentText() throws Exception {
        var book = createBook("Livro fallback invalido");
        var section = createSection(book, "Parte");
        var chapter = createChapter(section, "Capitulo");
        var scene = createScene(chapter, "Cena", SceneStatus.DRAFT, 0, "original");
        sceneService.updateContent(scene.id(), new SceneContentRequest("{invalid", "texto fallback"));

        mockMvc.perform(get("/api/books/{bookId}/export/markdown", book.id()))
                .andExpect(status().isOk())
                .andExpect(content().string("""
                        # Livro fallback invalido

                        ## Parte

                        ### Capitulo

                        texto fallback"""));
    }

    @Test
    void emptyContentJsonFallsBackToContentText() throws Exception {
        var book = createBook("Livro fallback vazio");
        var section = createSection(book, "Parte");
        var chapter = createChapter(section, "Capitulo");
        var scene = createScene(chapter, "Cena", SceneStatus.DRAFT, 0, "original");
        sceneService.updateContent(scene.id(), new SceneContentRequest("{\"type\":\"doc\",\"content\":[]}", "texto fallback"));

        mockMvc.perform(get("/api/books/{bookId}/export/markdown", book.id()))
                .andExpect(status().isOk())
                .andExpect(content().string("""
                        # Livro fallback vazio

                        ## Parte

                        ### Capitulo

                        texto fallback"""));
    }

    @Test
    void legacyGenericExportEndpointNoLongerMaps() throws Exception {
        var book = createBook("Livro legado");

        mockMvc.perform(get("/api/books/{bookId}/export", book.id()))
                .andExpect(status().isNotFound());
    }

    private java.util.UUID findNotebookCategoryId(java.util.UUID bookId, String name) throws Exception {
        String response = mockMvc.perform(get("/api/books/{bookId}/notebook/categories", bookId))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        for (JsonNode category : objectMapper.readTree(response)) {
            if (name.equals(category.get("name").asText())) {
                return java.util.UUID.fromString(category.get("id").asText());
            }
        }
        throw new IllegalStateException("Notebook category not found: " + name);
    }

    private java.util.UUID createNotebookNote(
            java.util.UUID bookId,
            String title,
            String content,
            java.util.UUID categoryId,
            String status
    ) throws Exception {
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("title", title);
        body.put("content", content);
        if (categoryId != null) {
            body.put("categoryId", categoryId);
        }
        body.put("status", status);

        String response = mockMvc.perform(post("/api/books/{bookId}/notebook/notes", bookId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return java.util.UUID.fromString(objectMapper.readTree(response).get("id").asText());
    }
}
