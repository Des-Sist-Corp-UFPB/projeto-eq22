package com.iwrite.notebook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iwrite.support.PostgresIntegrationTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.transaction.TestTransaction;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class NotebookControllerIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @Test
    void getCategoriesInitializesStarterCategoriesOnceForExistingBook() throws Exception {
        var book = createBook("Notebook starters");

        mockMvc.perform(get("/api/books/{bookId}/notebook/categories", book.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(8)))
                .andExpect(jsonPath("$[0].name").value("Ideia"))
                .andExpect(jsonPath("$[0].sortOrder").value(0))
                .andExpect(jsonPath("$[7].name").value("Outro"));

        assertSettingsCount(book.id(), 1);

        mockMvc.perform(get("/api/books/{bookId}/notebook/categories", book.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(8)))
                .andExpect(jsonPath("$[?(@.name == 'Ideia')]", hasSize(1)))
                .andExpect(jsonPath("$[?(@.name == 'Pesquisa')]", hasSize(1)))
                .andExpect(jsonPath("$[?(@.name == 'Mundo')]", hasSize(1)))
                .andExpect(jsonPath("$[?(@.name == 'Pergunta')]", hasSize(1)))
                .andExpect(jsonPath("$[?(@.name == 'Trecho')]", hasSize(1)))
                .andExpect(jsonPath("$[?(@.name == 'Outro')]", hasSize(1)));
    }

    @Test
    void concurrentFirstCategoryAccessInitializesStartersOnce() throws Exception {
        var book = createBook("Notebook starter race");
        TestTransaction.flagForCommit();
        TestTransaction.end();

        var executor = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> request = () -> mockMvc.perform(get("/api/books/{bookId}/notebook/categories", book.id()))
                    .andReturn()
                    .getResponse()
                    .getStatus();

            var results = executor.invokeAll(java.util.List.of(request, request));
            for (var result : results) {
                org.assertj.core.api.Assertions.assertThat(result.get(5, TimeUnit.SECONDS)).isEqualTo(200);
            }
        } finally {
            executor.shutdownNow();
        }

        TestTransaction.start();
        assertSettingsCount(book.id(), 1);
        mockMvc.perform(get("/api/books/{bookId}/notebook/categories", book.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(8)))
                .andExpect(jsonPath("$[?(@.name == 'Ideia')]", hasSize(1)))
                .andExpect(jsonPath("$[?(@.name == 'Outro')]", hasSize(1)));
    }

    @Test
    void createCategoryBeforeListAccessInitializesStartersFirst() throws Exception {
        var book = createBook("Notebook create initializes starters");

        postJson(
                "/api/books/" + book.id() + "/notebook/categories",
                Map.of("name", "Linha do tempo"),
                201
        );

        assertSettingsCount(book.id(), 1);
        mockMvc.perform(get("/api/books/{bookId}/notebook/categories", book.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(9)))
                .andExpect(jsonPath("$[?(@.name == 'Ideia')]", hasSize(1)))
                .andExpect(jsonPath("$[?(@.name == 'Linha do tempo')]", hasSize(1)));
    }

    @Test
    void existingCategorizedBooksMarkedInitializedByBackfillDoNotReceiveStarters() throws Exception {
        var book = createBook("Notebook backfilled settings");
        entityManager.flush();
        UUID categoryId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                        insert into notebook_categories (id, book_id, name, sort_order, created_at, updated_at)
                        values (?, ?, ?, 7, current_timestamp, current_timestamp)
                        """,
                categoryId,
                book.id(),
                "Categoria existente"
        );
        jdbcTemplate.update(
                """
                        insert into book_notebook_settings (book_id, defaults_initialized_at)
                        values (?, current_timestamp)
                        """,
                book.id()
        );

        mockMvc.perform(get("/api/books/{bookId}/notebook/categories", book.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(categoryId.toString()))
                .andExpect(jsonPath("$[0].name").value("Categoria existente"));
    }

    @Test
    void createUpdateAndDeleteCategory() throws Exception {
        var book = createBook("Notebook category");

        JsonNode category = postJson(
                "/api/books/" + book.id() + "/notebook/categories",
                Map.of("name", "Linha do tempo", "sortOrder", 20),
                201
        );
        UUID categoryId = UUID.fromString(category.get("id").asText());

        mockMvc.perform(patch("/api/notebook/categories/{categoryId}", categoryId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Cronologia", "sortOrder", 3))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Cronologia"))
                .andExpect(jsonPath("$.sortOrder").value(3));

        mockMvc.perform(delete("/api/notebook/categories/{categoryId}", categoryId))
                .andExpect(status().isNoContent());

        mockMvc.perform(patch("/api/notebook/categories/{categoryId}", categoryId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Excluida"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void renamingCustomCategoryCanOnlyChangeLetterCasing() throws Exception {
        var book = createBook("Notebook category case rename");
        UUID categoryId = createCategory(book.id(), "cronologia");

        mockMvc.perform(patch("/api/notebook/categories/{categoryId}", categoryId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Cronologia"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Cronologia"));

        mockMvc.perform(get("/api/books/{bookId}/notebook/categories", book.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + categoryId + "')].name").value(hasItem("Cronologia")));
    }

    @Test
    void renamingCustomCategoryToAnotherCategoryNameWithDifferentCasingIsRejected() throws Exception {
        var book = createBook("Notebook category duplicate case rename");
        createCategory(book.id(), "Personagens extras");
        UUID categoryId = createCategory(book.id(), "Cronologia externa");

        mockMvc.perform(patch("/api/notebook/categories/{categoryId}", categoryId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "personagens extras"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messages", hasItem(containsString("Notebook category name must be unique within the book"))));
    }

    @Test
    void creatingCategoryWithStarterNameBeforeListingIsRejectedAndStarterStillExists() throws Exception {
        var book = createBook("Notebook starter name collision");

        mockMvc.perform(post("/api/books/{bookId}/notebook/categories", book.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Ideia"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messages", hasItem(containsString("Notebook category name must be unique within the book"))));

        mockMvc.perform(get("/api/books/{bookId}/notebook/categories", book.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name == 'Ideia')]", hasSize(1)));
    }

    @Test
    void deletingCategorySetsExistingNoteCategoryToNull() throws Exception {
        var book = createBook("Notebook category delete");
        UUID categoryId = createCategory(book.id(), "Pesquisa sensivel");
        UUID noteId = createNote(book.id(), "Nota com categoria", "Conteudo", categoryId);

        mockMvc.perform(delete("/api/notebook/categories/{categoryId}", categoryId))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/notebook/notes/{noteId}", noteId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categoryId").value(nullValue()))
                .andExpect(jsonPath("$.category").value(nullValue()))
                .andExpect(jsonPath("$.title").value("Nota com categoria"));
    }

    @Test
    void deletingFormerStarterCategoryMovesNotesToUncategorizedAndDoesNotRecreateIt() throws Exception {
        var book = createBook("Notebook starter category delete");
        UUID starterCategoryId = findCategoryId(book.id(), "Pesquisa");
        UUID noteId = createNote(book.id(), "Nota starter", "Conteudo", starterCategoryId);

        mockMvc.perform(delete("/api/notebook/categories/{categoryId}", starterCategoryId))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/notebook/notes/{noteId}", noteId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categoryId").value(nullValue()))
                .andExpect(jsonPath("$.category").value(nullValue()))
                .andExpect(jsonPath("$.title").value("Nota starter"));

        mockMvc.perform(get("/api/books/{bookId}/notebook/categories", book.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name == 'Pesquisa')]", hasSize(0)));
    }

    @Test
    void renamingFormerStarterCategoryIsAllowedAndDoesNotRecreateOldName() throws Exception {
        var book = createBook("Notebook starter category rename");
        UUID starterCategoryId = findCategoryId(book.id(), "Pesquisa");

        mockMvc.perform(patch("/api/notebook/categories/{categoryId}", starterCategoryId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Pesquisa editada"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Pesquisa editada"));

        mockMvc.perform(get("/api/books/{bookId}/notebook/categories", book.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + starterCategoryId + "')].name").value(hasItem("Pesquisa editada")))
                .andExpect(jsonPath("$[?(@.name == 'Pesquisa')]", hasSize(0)));
    }

    @Test
    void outroSortsLastCaseInsensitivelyAfterTrimming() throws Exception {
        var book = createBook("Notebook outro sorting");
        UUID outroId = findCategoryId(book.id(), "Outro");

        mockMvc.perform(patch("/api/notebook/categories/{categoryId}", outroId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "  outro  ", "sortOrder", -10))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/books/{bookId}/notebook/categories", book.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[7].name").value("  outro  "));
    }

    @Test
    void createListUpdateAndDeleteNote() throws Exception {
        var book = createBook("Notebook note");
        UUID noteId = createNote(book.id(), "Ideia inicial", "Primeiro conteudo", null);

        mockMvc.perform(get("/api/books/{bookId}/notebook/notes", book.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(noteId.toString()))
                .andExpect(jsonPath("$[0].title").value("Ideia inicial"))
                .andExpect(jsonPath("$[0].content").value("Primeiro conteudo"))
                .andExpect(jsonPath("$[0].status").value("OPEN"));

        mockMvc.perform(patch("/api/notebook/notes/{noteId}", noteId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "title", "Ideia revisada",
                                "content", "Conteudo revisado",
                                "status", "RESOLVED"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Ideia revisada"))
                .andExpect(jsonPath("$.content").value("Conteudo revisado"))
                .andExpect(jsonPath("$.status").value("RESOLVED"));

        mockMvc.perform(delete("/api/notebook/notes/{noteId}", noteId))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/books/{bookId}/notebook/notes", book.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void createNoteWithResolvedStatus() throws Exception {
        var book = createBook("Notebook note resolved");

        mockMvc.perform(post("/api/books/{bookId}/notebook/notes", book.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "title", "Pergunta respondida",
                                "content", "Resposta anotada",
                                "status", "RESOLVED"
                        ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("Pergunta respondida"))
                .andExpect(jsonPath("$.status").value("RESOLVED"));
    }

    @Test
    void patchContentPresenceControlsPreserveClearAndReplace() throws Exception {
        var book = createBook("Notebook patch content");
        UUID noteId = createNote(book.id(), "Nota editavel", "Conteudo original", null);

        mockMvc.perform(patch("/api/notebook/notes/{noteId}", noteId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("status", "RESOLVED"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("Conteudo original"))
                .andExpect(jsonPath("$.status").value("RESOLVED"));

        mockMvc.perform(patch("/api/notebook/notes/{noteId}", noteId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value(nullValue()));

        mockMvc.perform(patch("/api/notebook/notes/{noteId}", noteId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("content", "Conteudo novo"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("Conteudo novo"));
    }

    @Test
    void filtersNotesByCategory() throws Exception {
        var book = createBook("Notebook filter");
        UUID categoryId = createCategory(book.id(), "Pesquisa externa");
        UUID matchingNoteId = createNote(book.id(), "Nota filtrada", "A", categoryId, "RESOLVED");
        createNote(book.id(), "Nota solta", "B", null);

        mockMvc.perform(get("/api/books/{bookId}/notebook/notes", book.id())
                        .param("categoryId", categoryId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(matchingNoteId.toString()))
                .andExpect(jsonPath("$[0].categoryId").value(categoryId.toString()))
                .andExpect(jsonPath("$[0].status").value("RESOLVED"));
    }

    @Test
    void rejectsCrossBookCategoryUsage() throws Exception {
        var firstBook = createBook("Notebook first");
        var secondBook = createBook("Notebook second");
        UUID foreignCategoryId = createCategory(secondBook.id(), "Outro livro");

        mockMvc.perform(post("/api/books/{bookId}/notebook/notes", firstBook.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "title", "Nota invalida",
                                "categoryId", foreignCategoryId
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messages", hasItem(containsString("categoryId must belong to the same book"))));
    }

    @Test
    void rejectsBlankTitleAndCategoryName() throws Exception {
        var book = createBook("Notebook validation");

        mockMvc.perform(post("/api/books/{bookId}/notebook/categories", book.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "   "))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messages", hasItem(containsString("name"))));

        mockMvc.perform(post("/api/books/{bookId}/notebook/notes", book.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("title", "   "))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messages", hasItem(containsString("title"))));
    }

    @Test
    void missingBookNoteAndCategoryReturnNotFound() throws Exception {
        UUID missingBookId = UUID.randomUUID();
        UUID missingCategoryId = UUID.randomUUID();
        UUID missingNoteId = UUID.randomUUID();

        mockMvc.perform(get("/api/books/{bookId}/notebook/categories", missingBookId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.messages", hasItem(containsString("Book not found"))));

        mockMvc.perform(patch("/api/notebook/categories/{categoryId}", missingCategoryId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Missing"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.messages", hasItem(containsString("Notebook category not found"))));

        mockMvc.perform(get("/api/notebook/notes/{noteId}", missingNoteId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.messages", hasItem(containsString("Notebook note not found"))));
    }

    private UUID createCategory(UUID bookId, String name) throws Exception {
        JsonNode category = postJson(
                "/api/books/" + bookId + "/notebook/categories",
                Map.of("name", name),
                201
        );
        return UUID.fromString(category.get("id").asText());
    }

    private UUID findCategoryId(UUID bookId, String name) throws Exception {
        String response = mockMvc.perform(get("/api/books/{bookId}/notebook/categories", bookId))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        for (JsonNode category : objectMapper.readTree(response)) {
            if (name.equals(category.get("name").asText())) {
                return UUID.fromString(category.get("id").asText());
            }
        }
        throw new IllegalStateException("Category not found: " + name);
    }

    private void assertSettingsCount(UUID bookId, int expectedCount) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from book_notebook_settings where book_id = ?",
                Integer.class,
                bookId
        );
        org.assertj.core.api.Assertions.assertThat(count).isEqualTo(expectedCount);
    }

    private UUID createNote(UUID bookId, String title, String content, UUID categoryId) throws Exception {
        return createNote(bookId, title, content, categoryId, null);
    }

    private UUID createNote(UUID bookId, String title, String content, UUID categoryId, String status) throws Exception {
        String body = categoryId == null
                ? json(Map.of("title", title, "content", content))
                : json(Map.of("title", title, "content", content, "categoryId", categoryId));
        if (status != null) {
            body = categoryId == null
                    ? json(Map.of("title", title, "content", content, "status", status))
                    : json(Map.of("title", title, "content", content, "categoryId", categoryId, "status", status));
        }
        String response = mockMvc.perform(post("/api/books/{bookId}/notebook/notes", bookId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return UUID.fromString(objectMapper.readTree(response).get("id").asText());
    }

    private JsonNode postJson(String path, Object body, int expectedStatus) throws Exception {
        String response = mockMvc.perform(post(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().is(expectedStatus))
                .andReturn()
                .getResponse()
                .getContentAsString();

        return objectMapper.readTree(response);
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }
}
