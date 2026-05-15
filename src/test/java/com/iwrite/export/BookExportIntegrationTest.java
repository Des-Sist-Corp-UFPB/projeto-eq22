package com.iwrite.export;

import com.iwrite.chapter.dto.ChapterRequest;
import com.iwrite.chapter.dto.ChapterResponse;
import com.iwrite.scene.dto.SceneContentRequest;
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

import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class BookExportIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void exportsBookInSectionChapterSceneOrder() throws Exception {
        var book = createBook("Ordered Book");
        BookSectionResponse secondSection = sectionService.create(book.id(), new BookSectionRequest("Second Section", SectionType.PART, 1));
        BookSectionResponse firstSection = sectionService.create(book.id(), new BookSectionRequest("First Section", SectionType.PART, 0));
        ChapterResponse laterChapter = chapterService.create(firstSection.id(), new ChapterRequest("Later Chapter", null, 1));
        ChapterResponse earlierChapter = chapterService.create(firstSection.id(), new ChapterRequest("Earlier Chapter", null, 0));
        ChapterResponse secondSectionChapter = chapterService.create(secondSection.id(), new ChapterRequest("Second Section Chapter", null, 0));

        createScene(laterChapter, "Later Scene", SceneStatus.DRAFT, 0, "later scene text");
        createScene(earlierChapter, "Second Scene", SceneStatus.DRAFT, 1, "second scene text");
        createScene(earlierChapter, "First Scene", SceneStatus.DRAFT, 0, "first scene text");
        createScene(secondSectionChapter, "Other Section Scene", SceneStatus.DRAFT, 0, "other section text");

        mockMvc.perform(get("/api/books/{bookId}/export", book.id()))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/markdown;charset=UTF-8"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"ordered-book.md\""))
                .andExpect(content().string("""
                        # Ordered Book

                        ## First Section

                        ### Earlier Chapter

                        first scene text

                        second scene text

                        ### Later Chapter

                        later scene text

                        ## Second Section

                        ### Second Section Chapter

                        other section text"""));
    }

    @Test
    void usesContentTextWhenContentJsonExists() throws Exception {
        var book = createBook("Text Source");
        var section = createSection(book, "Section");
        var chapter = createChapter(section, "Chapter");
        var scene = createScene(chapter, "Scene", SceneStatus.DRAFT, 0, "original text");
        sceneService.updateContent(scene.id(), new SceneContentRequest("{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"json text\"}]}]}", "plain text wins"));

        mockMvc.perform(get("/api/books/{bookId}/export", book.id()))
                .andExpect(status().isOk())
                .andExpect(content().string("""
                        # Text Source

                        ## Section

                        ### Chapter

                        plain text wins"""));
    }

    @Test
    void sceneWithoutContentDoesNotBreakExport() throws Exception {
        var book = createBook("Empty Scene");
        var section = createSection(book, "Section");
        var chapter = createChapter(section, "Chapter");
        createScene(chapter, "Empty", SceneStatus.DRAFT, 0, null);
        createScene(chapter, "Filled", SceneStatus.DRAFT, 1, "filled text");

        mockMvc.perform(get("/api/books/{bookId}/export", book.id()))
                .andExpect(status().isOk())
                .andExpect(content().string("""
                        # Empty Scene

                        ## Section

                        ### Chapter

                        filled text"""));
    }

    @Test
    void missingBookReturnsNotFound() throws Exception {
        mockMvc.perform(get("/api/books/{bookId}/export", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.messages", hasItem(containsString("Book not found"))));
    }
}
