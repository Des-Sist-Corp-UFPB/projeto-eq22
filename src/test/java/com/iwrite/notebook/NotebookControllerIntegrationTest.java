package com.iwrite.notebook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iwrite.notebook.repository.NotebookCategoryRepository;
import com.iwrite.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.UUID;

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
    private NotebookCategoryRepository categoryRepository;

    @Test
    void getCategoriesLazilyCreatesDefaultsForExistingBook() throws Exception {
        var book = createBook("Notebook defaults");

        mockMvc.perform(get("/api/books/{bookId}/notebook/categories", book.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(8)))
                .andExpect(jsonPath("$[0].name").value("Ideia"))
                .andExpect(jsonPath("$[0].sortOrder").value(0))
                .andExpect(jsonPath("$[0].isDefault").value(true))
                .andExpect(jsonPath("$[7].name").value("Outro"));

        mockMvc.perform(get("/api/books/{bookId}/notebook/categories", book.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(8)))
                .andExpect(jsonPath("$[?(@.name == 'Ideia' && @.isDefault == true)]", hasSize(1)))
                .andExpect(jsonPath("$[?(@.name == 'Pesquisa' && @.isDefault == true)]", hasSize(1)))
                .andExpect(jsonPath("$[?(@.name == 'Mundo' && @.isDefault == true)]", hasSize(1)))
                .andExpect(jsonPath("$[?(@.name == 'Pergunta' && @.isDefault == true)]", hasSize(1)))
                .andExpect(jsonPath("$[?(@.name == 'Trecho' && @.isDefault == true)]", hasSize(1)))
                .andExpect(jsonPath("$[?(@.name == 'Outro' && @.isDefault == true)]", hasSize(1)));
    }

    @Test
    void defaultCategorySeedingIgnoresExistingDefaultNameAndReturnsCategories() throws Exception {
        var book = createBook("Notebook default race");

        categoryRepository.insertDefaultCategoryIfMissing(UUID.randomUUID(), book.id(), "Ideia", 0);

        mockMvc.perform(get("/api/books/{bookId}/notebook/categories", book.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(8)))
                .andExpect(jsonPath("$[?(@.name == 'Ideia' && @.isDefault == true)]", hasSize(1)));
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
                .andExpect(jsonPath("$.sortOrder").value(3))
                .andExpect(jsonPath("$.isDefault").value(false));

        mockMvc.perform(delete("/api/notebook/categories/{categoryId}", categoryId))
                .andExpect(status().isNoContent());

        mockMvc.perform(patch("/api/notebook/categories/{categoryId}", categoryId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Excluida"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void creatingCategoryWithDefaultNameBeforeListingIsRejectedAndDefaultStillExists() throws Exception {
        var book = createBook("Notebook default name collision");

        mockMvc.perform(post("/api/books/{bookId}/notebook/categories", book.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Ideia"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messages", hasItem(containsString("Notebook category name must be unique within the book"))));

        mockMvc.perform(get("/api/books/{bookId}/notebook/categories", book.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name == 'Ideia' && @.isDefault == true)]", hasSize(1)))
                .andExpect(jsonPath("$[?(@.name == 'Ideia' && @.isDefault == false)]", hasSize(0)));
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
    void deletingDefaultCategoryIsRejectedAndNotesRemainLinked() throws Exception {
        var book = createBook("Notebook default category delete");
        UUID defaultCategoryId = findDefaultCategoryId(book.id(), "Pesquisa");
        UUID noteId = createNote(book.id(), "Nota padrao", "Conteudo", defaultCategoryId);

        mockMvc.perform(delete("/api/notebook/categories/{categoryId}", defaultCategoryId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messages", hasItem(containsString("Default notebook categories cannot be deleted"))));

        mockMvc.perform(get("/api/notebook/notes/{noteId}", noteId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categoryId").value(defaultCategoryId.toString()))
                .andExpect(jsonPath("$.category.name").value("Pesquisa"));
    }

    @Test
    void renamingDefaultCategoryIsRejected() throws Exception {
        var book = createBook("Notebook default category rename");
        UUID defaultCategoryId = findDefaultCategoryId(book.id(), "Pesquisa");

        mockMvc.perform(patch("/api/notebook/categories/{categoryId}", defaultCategoryId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Pesquisa editada"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messages", hasItem(containsString("Default notebook categories cannot be renamed"))));

        mockMvc.perform(get("/api/books/{bookId}/notebook/categories", book.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + defaultCategoryId + "')].name").value(hasItem("Pesquisa")));
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

    private UUID findDefaultCategoryId(UUID bookId, String name) throws Exception {
        String response = mockMvc.perform(get("/api/books/{bookId}/notebook/categories", bookId))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        for (JsonNode category : objectMapper.readTree(response)) {
            if (name.equals(category.get("name").asText()) && category.get("isDefault").asBoolean()) {
                return UUID.fromString(category.get("id").asText());
            }
        }
        throw new IllegalStateException("Default category not found: " + name);
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
