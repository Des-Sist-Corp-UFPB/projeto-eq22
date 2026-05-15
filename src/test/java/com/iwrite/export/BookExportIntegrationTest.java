package com.iwrite.export;

import com.iwrite.book.dto.BookRequest;
import com.iwrite.chapter.dto.ChapterRequest;
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
}
