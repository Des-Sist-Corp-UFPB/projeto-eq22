package com.iwrite.export;

import com.iwrite.book.dto.BookRequest;
import com.iwrite.chapter.dto.ChapterRequest;
import com.iwrite.chapter.dto.ChapterResponse;
import com.iwrite.scene.entity.SceneStatus;
import com.iwrite.section.dto.BookSectionRequest;
import com.iwrite.section.dto.BookSectionResponse;
import com.iwrite.section.entity.SectionType;
import com.iwrite.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class BookExportIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void fileNameUsesSanitizedBookTitle() throws Exception {
        var book = createBook("A Garota da Biblioteca");

        mockMvc.perform(get("/api/books/{bookId}/export", book.id()))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/markdown;charset=UTF-8"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"a-garota-da-biblioteca.md\""))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, HttpHeaders.CONTENT_DISPOSITION));
    }

    @Test
    void exportIncludesBookMetadataSectionsAndChapters() throws Exception {
        var book = bookService.create(new BookRequest("Livro", "Subtitulo do livro", "Descricao do livro", null, null));
        BookSectionResponse section = sectionService.create(book.id(), new BookSectionRequest("Parte 1", SectionType.PART, 0));
        ChapterResponse chapter = chapterService.create(section.id(), new ChapterRequest("Capitulo 1", null, 0));
        createScene(chapter, "Cena", SceneStatus.DRAFT, 0, "texto da cena");

        mockMvc.perform(get("/api/books/{bookId}/export", book.id()))
                .andExpect(status().isOk())
                .andExpect(content().string("""
                        # Livro

                        Subtitulo do livro

                        Descricao do livro

                        ## Parte 1

                        ### Capitulo 1

                        texto da cena"""));
    }

    @Test
    void scenesInSameChapterAreSeparatedByDivider() throws Exception {
        var book = createBook("Livro com cenas");
        var section = createSection(book, "Parte");
        var chapter = createChapter(section, "Capitulo");
        createScene(chapter, "Cena 1", SceneStatus.DRAFT, 0, "primeira cena");
        createScene(chapter, "Cena 2", SceneStatus.DRAFT, 1, "segunda cena");

        mockMvc.perform(get("/api/books/{bookId}/export", book.id()))
                .andExpect(status().isOk())
                .andExpect(content().string("""
                        # Livro com cenas

                        ## Parte

                        ### Capitulo

                        primeira cena

                        ---

                        segunda cena"""));
    }

    @Test
    void contentFollowsSectionChapterAndSceneSortOrder() throws Exception {
        var book = createBook("Livro ordenado");
        BookSectionResponse secondSection = sectionService.create(book.id(), new BookSectionRequest("Segunda parte", SectionType.PART, 1));
        BookSectionResponse firstSection = sectionService.create(book.id(), new BookSectionRequest("Primeira parte", SectionType.PART, 0));
        ChapterResponse laterChapter = chapterService.create(firstSection.id(), new ChapterRequest("Capitulo depois", null, 1));
        ChapterResponse earlierChapter = chapterService.create(firstSection.id(), new ChapterRequest("Capitulo antes", null, 0));
        ChapterResponse secondSectionChapter = chapterService.create(secondSection.id(), new ChapterRequest("Capitulo outra parte", null, 0));

        createScene(laterChapter, "Cena depois", SceneStatus.DRAFT, 0, "texto do capitulo depois");
        createScene(earlierChapter, "Cena 2", SceneStatus.DRAFT, 1, "segunda cena do primeiro capitulo");
        createScene(earlierChapter, "Cena 1", SceneStatus.DRAFT, 0, "primeira cena do primeiro capitulo");
        createScene(secondSectionChapter, "Cena outra parte", SceneStatus.DRAFT, 0, "texto da outra parte");

        mockMvc.perform(get("/api/books/{bookId}/export", book.id()))
                .andExpect(status().isOk())
                .andExpect(content().string("""
                        # Livro ordenado

                        ## Primeira parte

                        ### Capitulo antes

                        primeira cena do primeiro capitulo

                        ---

                        segunda cena do primeiro capitulo

                        ### Capitulo depois

                        texto do capitulo depois

                        ## Segunda parte

                        ### Capitulo outra parte

                        texto da outra parte"""));
    }
}
