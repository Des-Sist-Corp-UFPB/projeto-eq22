package com.iwrite.export;

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
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class BookExportIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void exportOmitsSceneTitlesByDefault() throws Exception {
        var book = createBook("Livro sem titulos");
        var section = createSection(book, "Parte");
        var chapter = createChapter(section, "Capitulo");
        createScene(chapter, "Cena secreta", SceneStatus.DRAFT, 0, "texto da cena");

        mockMvc.perform(get("/api/books/{bookId}/export", book.id()))
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

        mockMvc.perform(get("/api/books/{bookId}/export", book.id())
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

        mockMvc.perform(get("/api/books/{bookId}/export", book.id()))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Cena vazia"))));

        mockMvc.perform(get("/api/books/{bookId}/export", book.id())
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
                {"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"marcado","marks":[{"type":"bold"},{"type":"italic"}]}]}]}""", "fallback"));

        mockMvc.perform(get("/api/books/{bookId}/export", book.id()))
                .andExpect(status().isOk())
                .andExpect(content().string("""
                        # Livro formatado

                        ## Parte

                        ### Capitulo

                        **_marcado_**"""));
    }

    @Test
    void contentJsonTextStartingWithTripleAsteriskIsEscaped() throws Exception {
        var book = createBook("Livro com asteriscos");
        var section = createSection(book, "Parte");
        var chapter = createChapter(section, "Capitulo");
        var scene = createScene(chapter, "Cena", SceneStatus.DRAFT, 0, "fallback");
        sceneService.updateContent(scene.id(), new SceneContentRequest("""
                {"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"*** texto comum"}]}]}""", "fallback"));

        mockMvc.perform(get("/api/books/{bookId}/export", book.id()))
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

        mockMvc.perform(get("/api/books/{bookId}/export", book.id()))
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

        mockMvc.perform(get("/api/books/{bookId}/export", book.id()))
                .andExpect(status().isOk())
                .andExpect(content().string("""
                        # Livro com heading

                        ## Parte

                        ### Capitulo

                        #### Titulo interno

                        ###### Subtitulo interno"""));
    }

    @Test
    void invalidContentJsonFallsBackToContentText() throws Exception {
        var book = createBook("Livro fallback invalido");
        var section = createSection(book, "Parte");
        var chapter = createChapter(section, "Capitulo");
        var scene = createScene(chapter, "Cena", SceneStatus.DRAFT, 0, "original");
        sceneService.updateContent(scene.id(), new SceneContentRequest("{invalid", "texto fallback"));

        mockMvc.perform(get("/api/books/{bookId}/export", book.id()))
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

        mockMvc.perform(get("/api/books/{bookId}/export", book.id()))
                .andExpect(status().isOk())
                .andExpect(content().string("""
                        # Livro fallback vazio

                        ## Parte

                        ### Capitulo

                        texto fallback"""));
    }
}
