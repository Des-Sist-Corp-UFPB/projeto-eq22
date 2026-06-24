package com.iwrite.notebook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iwrite.book.dto.BookResponse;
import com.iwrite.notebook.repository.NotebookCategoryRepository;
import com.iwrite.notebook.repository.NotebookNoteRepository;
import com.iwrite.support.PostgresIntegrationTest;
import com.iwrite.support.SwitchableCurrentUserProvider;
import com.iwrite.tenant.entity.Tenant;
import com.iwrite.tenant.entity.TenantMembership;
import com.iwrite.tenant.entity.TenantMembershipRole;
import com.iwrite.tenant.repository.TenantRepository;
import com.iwrite.user.entity.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
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
@Import(NotebookTenantIsolationIntegrationTest.CurrentUserTestConfiguration.class)
class NotebookTenantIsolationIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SwitchableCurrentUserProvider currentUserProvider;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private NotebookCategoryRepository categoryRepository;

    @Autowired
    private NotebookNoteRepository noteRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @PersistenceContext
    private EntityManager entityManager;

    private Identity tenantA;
    private Identity tenantB;
    private BookResponse bookA1;
    private BookResponse bookA2;
    private BookResponse bookB;
    private UUID categoryA1;
    private UUID categoryA2;
    private UUID categoryB;
    private UUID noteA1;
    private UUID noteB;

    @BeforeEach
    void setUpNotebookTenants() throws Exception {
        currentUserProvider.reset();
        tenantA = createIdentity("Notebook Tenant A", "notebook-tenant-a@iwrite.local");
        tenantB = createIdentity("Notebook Tenant B", "notebook-tenant-b@iwrite.local");

        switchTo(tenantA);
        bookA1 = createBook("Notebook Book A1");
        bookA2 = createBook("Notebook Book A2");
        categoryA1 = createCategory(bookA1.id(), "Notebook Category A1");
        categoryA2 = createCategory(bookA2.id(), "Notebook Category A2");
        noteA1 = createNote(bookA1.id(), "Notebook Note A1", "Tenant A content", categoryA1);

        switchTo(tenantB);
        bookB = createBook("Notebook Book B");
        categoryB = createCategory(bookB.id(), "Notebook Category B");
        noteB = createNote(bookB.id(), "Notebook Note B", "Tenant B content", categoryB);

        switchTo(tenantA);
    }

    @AfterEach
    void resetIdentity() {
        currentUserProvider.reset();
    }

    @Test
    void categoryCrudAndListsAreTenantScoped() throws Exception {
        UUID created = createCategory(bookA1.id(), "Owner category");

        mockMvc.perform(get("/api/books/{bookId}/notebook/categories", bookA1.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + categoryA1 + "')].name", hasItem("Notebook Category A1")))
                .andExpect(jsonPath("$[?(@.id == '" + categoryB + "')]").isEmpty());

        MvcResult foreignBook = assertNotFound(
                get("/api/books/{bookId}/notebook/categories", bookB.id()),
                "Book not found"
        );
        MvcResult missingBook = assertNotFound(
                get("/api/books/{bookId}/notebook/categories", UUID.randomUUID()),
                "Book not found"
        );
        assertEquivalentNotFound(foreignBook, missingBook);
        assertThat(foreignBook.getResponse().getContentAsString()).doesNotContain("Notebook Category B");

        mockMvc.perform(patch("/api/notebook/categories/{categoryId}", created)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Owner category updated", "sortOrder", 7))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Owner category updated"))
                .andExpect(jsonPath("$.sortOrder").value(7));

        String foreignName = categoryRepository.findById(categoryB).orElseThrow().getName();
        MvcResult foreignCategory = assertNotFound(
                patch("/api/notebook/categories/{categoryId}", categoryB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Compromised"))),
                "Notebook category not found"
        );
        MvcResult missingCategory = assertNotFound(
                patch("/api/notebook/categories/{categoryId}", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Missing"))),
                "Notebook category not found"
        );
        assertEquivalentNotFound(foreignCategory, missingCategory);
        assertNotFound(delete("/api/notebook/categories/{categoryId}", categoryB), "Notebook category not found");
        assertThat(categoryRepository.findById(categoryB)).hasValueSatisfying(category ->
                assertThat(category.getName()).isEqualTo(foreignName));

        mockMvc.perform(delete("/api/notebook/categories/{categoryId}", created))
                .andExpect(status().isNoContent());
        assertThat(categoryRepository.findById(created)).isEmpty();
    }

    @Test
    void noteCrudFiltersMovesAndDeletesAreTenantScoped() throws Exception {
        UUID created = createNote(bookA1.id(), "Owner note", "Owner note content", null);

        mockMvc.perform(get("/api/books/{bookId}/notebook/notes", bookA1.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + noteA1 + "')].title", hasItem("Notebook Note A1")))
                .andExpect(jsonPath("$[?(@.id == '" + noteB + "')]").isEmpty());
        mockMvc.perform(get("/api/books/{bookId}/notebook/notes", bookA1.id())
                        .param("categoryId", categoryA1.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(noteA1.toString()));

        assertEquivalentNotFound(
                assertNotFound(get("/api/books/{bookId}/notebook/notes", bookB.id()), "Book not found"),
                assertNotFound(get("/api/books/{bookId}/notebook/notes", UUID.randomUUID()), "Book not found")
        );

        mockMvc.perform(get("/api/notebook/notes/{noteId}", created))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Owner note"));
        mockMvc.perform(patch("/api/notebook/notes/{noteId}", created)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "title", "Owner note moved",
                                "content", "Moved content",
                                "categoryId", categoryA1,
                                "status", "RESOLVED"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Owner note moved"))
                .andExpect(jsonPath("$.categoryId").value(categoryA1.toString()))
                .andExpect(jsonPath("$.category.name").value("Notebook Category A1"))
                .andExpect(jsonPath("$.status").value("RESOLVED"));
        mockMvc.perform(patch("/api/notebook/notes/{noteId}", created)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryId\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categoryId").value(nullValue()));

        String foreignTitle = noteRepository.findById(noteB).orElseThrow().getTitle();
        assertEquivalentNotFound(
                assertNotFound(get("/api/notebook/notes/{noteId}", noteB), "Notebook note not found"),
                assertNotFound(get("/api/notebook/notes/{noteId}", UUID.randomUUID()), "Notebook note not found")
        );
        assertNotFound(patch("/api/notebook/notes/{noteId}", noteB)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("title", "Compromised"))), "Notebook note not found");
        assertNotFound(delete("/api/notebook/notes/{noteId}", noteB), "Notebook note not found");
        assertThat(noteRepository.findById(noteB)).hasValueSatisfying(note ->
                assertThat(note.getTitle()).isEqualTo(foreignTitle));

        assertNotFound(post("/api/books/{bookId}/notebook/notes", bookB.id())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("title", "Foreign creation"))), "Book not found");
        assertNotFound(post("/api/books/{bookId}/notebook/notes", bookA1.id())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("title", "Foreign category", "categoryId", categoryB))), "Notebook category not found");
        mockMvc.perform(post("/api/books/{bookId}/notebook/notes", bookA1.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("title", "Wrong book category", "categoryId", categoryA2))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messages", hasItem(containsString("categoryId must belong to the same book"))));

        mockMvc.perform(delete("/api/notebook/notes/{noteId}", created))
                .andExpect(status().isNoContent());
        assertThat(noteRepository.findById(created)).isEmpty();
    }

    @Test
    void legacyMismatchedNoteResponsesAreUncategorizedWithoutMutatingCategory() throws Exception {
        UUID crossTenantNote = createNote(
                bookA1.id(),
                "Owned note with cross-tenant category",
                "cross-tenant legacy content",
                null
        );
        assignCategoryDirectly(crossTenantNote, categoryB);
        assertLegacyMismatchedGetIsUncategorized(
                crossTenantNote,
                categoryB,
                "Owned note with cross-tenant category",
                "Notebook Category B",
                bookB.id()
        );

        UUID sameTenantWrongBookNote = createNote(
                bookA1.id(),
                "Owned note with same-tenant wrong-book category",
                "same-tenant legacy content",
                null
        );
        assignCategoryDirectly(sameTenantWrongBookNote, categoryA2);
        assertLegacyMismatchedGetIsUncategorized(
                sameTenantWrongBookNote,
                categoryA2,
                "Owned note with same-tenant wrong-book category",
                "Notebook Category A2",
                bookA2.id()
        );

        String updateResponse = mockMvc.perform(patch("/api/notebook/notes/{noteId}", crossTenantNote)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "title", "Updated legacy cross-tenant note",
                                "content", "Updated legacy content"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Updated legacy cross-tenant note"))
                .andExpect(jsonPath("$.content").value("Updated legacy content"))
                .andExpect(jsonPath("$.categoryId").value(nullValue()))
                .andExpect(jsonPath("$.category").value(nullValue()))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(updateResponse)
                .doesNotContain(categoryB.toString())
                .doesNotContain("Notebook Category B")
                .doesNotContain(bookB.id().toString());
        flushAndClear();
        assertThat(persistedCategoryId(crossTenantNote)).isEqualTo(categoryB);
        assertThat(noteRepository.findById(crossTenantNote)).hasValueSatisfying(note -> {
            assertThat(note.getTitle()).isEqualTo("Updated legacy cross-tenant note");
            assertThat(note.getContent()).isEqualTo("Updated legacy content");
        });

        String listResponse = mockMvc.perform(get("/api/books/{bookId}/notebook/notes", bookA1.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + crossTenantNote + "')].categoryId", hasItem(nullValue())))
                .andExpect(jsonPath("$[?(@.id == '" + sameTenantWrongBookNote + "')].categoryId", hasItem(nullValue())))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(listResponse)
                .contains("Updated legacy cross-tenant note")
                .contains("Owned note with same-tenant wrong-book category")
                .doesNotContain(categoryB.toString())
                .doesNotContain("Notebook Category B")
                .doesNotContain(bookB.id().toString())
                .doesNotContain(categoryA2.toString())
                .doesNotContain("Notebook Category A2")
                .doesNotContain(bookA2.id().toString());

        mockMvc.perform(patch("/api/notebook/notes/{noteId}", sameTenantWrongBookNote)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("categoryId", categoryA1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categoryId").value(categoryA1.toString()))
                .andExpect(jsonPath("$.category.name").value("Notebook Category A1"));
        flushAndClear();
        assertThat(persistedCategoryId(sameTenantWrongBookNote)).isEqualTo(categoryA1);

        mockMvc.perform(patch("/api/notebook/notes/{noteId}", crossTenantNote)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryId\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categoryId").value(nullValue()))
                .andExpect(jsonPath("$.category").value(nullValue()));
        flushAndClear();
        assertThat(persistedCategoryId(crossTenantNote)).isNull();
    }

    @Test
    void categoryDeleteUncategorizesSameBookNotesAndBlocksLegacyCrossBookReferences() throws Exception {
        UUID deletableCategory = createCategory(bookA1.id(), "Delete moves same-book notes");
        UUID sameBookNote = createNote(bookA1.id(), "Same-book category note", "same book", deletableCategory);

        mockMvc.perform(delete("/api/notebook/categories/{categoryId}", deletableCategory))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/notebook/notes/{noteId}", sameBookNote))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categoryId").value(nullValue()))
                .andExpect(jsonPath("$.category").value(nullValue()));

        UUID crossTenantCategory = createCategory(bookA1.id(), "Cross-tenant legacy category");
        switchTo(tenantB);
        UUID crossTenantNote = createNote(bookB.id(), "Cross-tenant legacy note", "legacy", null);
        switchTo(tenantA);
        assignCategoryDirectly(crossTenantNote, crossTenantCategory);

        assertLegacyReferenceBlocksDelete(crossTenantCategory, crossTenantNote);

        UUID sameTenantCategory = createCategory(bookA1.id(), "Same-tenant legacy category");
        UUID sameTenantWrongBookNote = createNote(bookA2.id(), "Same-tenant wrong-book note", "legacy", null);
        assignCategoryDirectly(sameTenantWrongBookNote, sameTenantCategory);

        assertLegacyReferenceBlocksDelete(sameTenantCategory, sameTenantWrongBookNote);
    }

    @Test
    void notebookExportIsTenantScopedAndDoesNotExposeLegacyForeignCategoryMetadata() throws Exception {
        UUID exportCategory = createCategory(bookA1.id(), "Owner export category");
        UUID foreignCategory = createCategory(bookA2.id(), "Foreign category metadata");
        createNote(bookA1.id(), "Owner categorized export note", "categorized", exportCategory);
        UUID mismatchedNote = createNote(bookA1.id(), "Owner mismatched export note", "mismatched", null);
        assignCategoryDirectly(mismatchedNote, foreignCategory);

        String content = mockMvc.perform(get("/api/books/{bookId}/exports/notebook", bookA1.id())
                        .param("format", "md"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(content)
                .contains("Owner export category")
                .contains("Owner categorized export note")
                .contains("Sem categoria")
                .contains("Owner mismatched export note")
                .doesNotContain("Foreign category metadata")
                .doesNotContain("Notebook Note B")
                .doesNotContain(categoryB.toString());

        switchTo(tenantB);
        assertEquivalentNotFound(
                assertNotFound(get("/api/books/{bookId}/exports/notebook", bookA1.id())
                        .param("format", "md"), "Book not found"),
                assertNotFound(get("/api/books/{bookId}/exports/notebook", UUID.randomUUID())
                        .param("format", "md"), "Book not found")
        );
    }

    private Identity createIdentity(String name, String email) {
        Tenant tenant = new Tenant();
        tenant.setName(name);
        tenant.setDefaultTimeZoneId("UTC");
        UUID tenantId = tenantRepository.save(tenant).getId();

        User user = new User();
        user.setDisplayName(name + " User");
        user.setEmail(email);
        user.setTimeZoneId("UTC");
        entityManager.persist(user);

        TenantMembership membership = new TenantMembership();
        membership.setTenant(tenant);
        membership.setUser(user);
        membership.setRole(TenantMembershipRole.OWNER);
        entityManager.persist(membership);
        return new Identity(user.getId(), tenantId);
    }

    private UUID createCategory(UUID bookId, String name) throws Exception {
        String response = mockMvc.perform(post("/api/books/{bookId}/notebook/categories", bookId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", name))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return UUID.fromString(objectMapper.readTree(response).get("id").asText());
    }

    private UUID createNote(UUID bookId, String title, String content, UUID categoryId) throws Exception {
        String body = categoryId == null
                ? json(Map.of("title", title, "content", content))
                : json(Map.of("title", title, "content", content, "categoryId", categoryId));
        String response = mockMvc.perform(post("/api/books/{bookId}/notebook/notes", bookId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode note = objectMapper.readTree(response);
        return UUID.fromString(note.get("id").asText());
    }

    private void assignCategoryDirectly(UUID noteId, UUID categoryId) {
        entityManager.flush();
        jdbcTemplate.update("update notebook_notes set category_id = ? where id = ?", categoryId, noteId);
        entityManager.clear();
    }

    private void assertLegacyMismatchedGetIsUncategorized(
            UUID noteId,
            UUID categoryId,
            String noteTitle,
            String foreignCategoryName,
            UUID foreignBookId
    ) throws Exception {
        String response = mockMvc.perform(get("/api/notebook/notes/{noteId}", noteId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(noteId.toString()))
                .andExpect(jsonPath("$.title").value(noteTitle))
                .andExpect(jsonPath("$.categoryId").value(nullValue()))
                .andExpect(jsonPath("$.category").value(nullValue()))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(response)
                .doesNotContain(categoryId.toString())
                .doesNotContain(foreignCategoryName)
                .doesNotContain(foreignBookId.toString());
        flushAndClear();
        assertThat(persistedCategoryId(noteId)).isEqualTo(categoryId);
    }

    private UUID persistedCategoryId(UUID noteId) {
        String categoryId = jdbcTemplate.queryForObject(
                "select category_id::text from notebook_notes where id = ?",
                String.class,
                noteId
        );
        return categoryId == null ? null : UUID.fromString(categoryId);
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    private void assertLegacyReferenceBlocksDelete(UUID categoryId, UUID noteId) throws Exception {
        MvcResult conflict = mockMvc.perform(delete("/api/notebook/categories/{categoryId}", categoryId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.messages", hasItem(containsString("Notebook category cannot be deleted"))))
                .andReturn();

        assertThat(conflict.getResponse().getContentAsString())
                .doesNotContain(noteId.toString())
                .doesNotContain(bookA2.id().toString())
                .doesNotContain(bookB.id().toString())
                .doesNotContain(tenantB.tenantId().toString());
        assertThat(categoryRepository.findById(categoryId)).isPresent();
        assertThat(noteRepository.findById(noteId)).hasValueSatisfying(note ->
                assertThat(note.getCategory().getId()).isEqualTo(categoryId));
    }

    private MvcResult assertNotFound(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
            String message
    ) throws Exception {
        return mockMvc.perform(request)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.messages", hasItem(containsString(message))))
                .andReturn();
    }

    private void assertEquivalentNotFound(MvcResult first, MvcResult second) throws Exception {
        assertThat(first.getResponse().getStatus()).isEqualTo(second.getResponse().getStatus());
        assertThat(first.getResponse().getContentType()).isEqualTo(second.getResponse().getContentType());
    }

    private void switchTo(Identity identity) {
        currentUserProvider.switchTo(identity.userId(), identity.tenantId(), ZoneId.of("UTC"));
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private record Identity(UUID userId, UUID tenantId) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class CurrentUserTestConfiguration {

        @Bean
        @Primary
        SwitchableCurrentUserProvider switchableCurrentUserProvider() {
            return new SwitchableCurrentUserProvider();
        }
    }
}
