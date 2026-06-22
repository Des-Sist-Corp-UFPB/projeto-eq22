package com.iwrite.manuscript;

import com.iwrite.book.dto.BookResponse;
import com.iwrite.chapter.dto.ChapterRequest;
import com.iwrite.chapter.dto.ChapterResponse;
import com.iwrite.chapter.entity.Chapter;
import com.iwrite.chapter.repository.ChapterRepository;
import com.iwrite.common.exception.ResourceNotFoundException;
import com.iwrite.scene.dto.SceneRequest;
import com.iwrite.scene.dto.SceneResponse;
import com.iwrite.scene.entity.Scene;
import com.iwrite.scene.entity.SceneStatus;
import com.iwrite.scene.repository.SceneRepository;
import com.iwrite.section.dto.BookSectionRequest;
import com.iwrite.section.dto.BookSectionResponse;
import com.iwrite.section.entity.BookSection;
import com.iwrite.section.entity.SectionType;
import com.iwrite.section.repository.BookSectionRepository;
import com.iwrite.support.PostgresIntegrationTest;
import com.iwrite.support.SwitchableCurrentUserProvider;
import com.iwrite.tenant.entity.Tenant;
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
import org.springframework.test.web.servlet.MockMvc;

import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@Import(ManuscriptHierarchyTenantIsolationIntegrationTest.CurrentUserTestConfiguration.class)
class ManuscriptHierarchyTenantIsolationIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SwitchableCurrentUserProvider currentUserProvider;

    @Autowired
    private TenantRepository tenantRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private BookSectionRepository sectionRepository;

    @Autowired
    private ChapterRepository chapterRepository;

    @Autowired
    private SceneRepository sceneRepository;

    private UUID tenantBId;
    private UUID userBId;
    private BookResponse bookA;
    private BookSectionResponse sectionA;
    private ChapterResponse chapterA;
    private SceneResponse sceneA;
    private BookResponse bookB;
    private BookSectionResponse sectionB;
    private BookSectionResponse sectionBSecond;
    private ChapterResponse chapterB;
    private ChapterResponse chapterBSecond;
    private ChapterResponse chapterBWrongParent;
    private SceneResponse sceneB;
    private SceneResponse sceneBSecond;
    private SceneResponse sceneBWrongParent;

    @BeforeEach
    void setUpHierarchy() {
        currentUserProvider.reset();
        bookA = createBook("Book A");
        sectionA = createSection(bookA, "Section A", 0);
        chapterA = createChapter(sectionA, "Chapter A", 0);
        sceneA = createScene(chapterA, "Scene A", 0, "original scene content");

        Tenant tenantB = new Tenant();
        tenantB.setName("Tenant B");
        tenantB.setDefaultTimeZoneId("UTC");
        tenantBId = tenantRepository.save(tenantB).getId();

        User userB = new User();
        userB.setDisplayName("User B");
        userB.setEmail("tenant-b@iwrite.local");
        userB.setTimeZoneId("UTC");
        entityManager.persist(userB);
        userBId = userB.getId();

        switchToTenantB();
        bookB = createBook("Book B");
        sectionB = createSection(bookB, "Section B", 0);
        sectionBSecond = createSection(bookB, "Section B second", 1);
        chapterB = createChapter(sectionB, "Chapter B", 0);
        chapterBSecond = createChapter(sectionB, "Chapter B second", 1);
        chapterBWrongParent = createChapter(sectionBSecond, "Chapter B other section", 0);
        sceneB = createScene(chapterB, "Scene B", 0, "tenant b scene");
        sceneBSecond = createScene(chapterB, "Scene B second", 1, "tenant b second scene");
        sceneBWrongParent = createScene(chapterBWrongParent, "Scene B other chapter", 0, "other chapter scene");
        currentUserProvider.reset();
    }

    @AfterEach
    void resetIdentity() {
        currentUserProvider.reset();
    }

    @Test
    void foreignSectionOperationsAndChapterCreationReturnNotFoundWithoutMutation() throws Exception {
        switchToTenantB();

        assertEquivalentNotFound(
                () -> sectionService.getSection(sectionA.id()),
                () -> sectionService.getSection(UUID.randomUUID()),
                "Section not found"
        );
        assertNotFound(patch("/api/sections/{sectionId}", sectionA.id())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Compromised section\"}"), "Section not found");
        assertNotFound(delete("/api/sections/{sectionId}", sectionA.id()), "Section not found");
        assertNotFound(post("/api/sections/{sectionId}/chapters", sectionA.id())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Foreign chapter\"}"), "Section not found");

        BookSection unchanged = sectionRepository.findById(sectionA.id()).orElseThrow();
        assertThat(unchanged.getTitle()).isEqualTo("Section A");
        assertThat(chapterRepository.findBySectionIdOrderBySortOrderAsc(sectionA.id()))
                .extracting(Chapter::getTitle)
                .containsExactly("Chapter A");
    }

    @Test
    void foreignChapterOperationsAndSceneCreationReturnNotFoundWithoutMutation() throws Exception {
        switchToTenantB();

        assertEquivalentNotFound(
                () -> chapterService.getChapter(chapterA.id()),
                () -> chapterService.getChapter(UUID.randomUUID()),
                "Chapter not found"
        );
        assertNotFound(patch("/api/chapters/{chapterId}", chapterA.id())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Compromised chapter\"}"), "Chapter not found");
        assertNotFound(delete("/api/chapters/{chapterId}", chapterA.id()), "Chapter not found");
        assertNotFound(post("/api/chapters/{chapterId}/scenes", chapterA.id())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Foreign scene\"}"), "Chapter not found");

        Chapter unchanged = chapterRepository.findById(chapterA.id()).orElseThrow();
        assertThat(unchanged.getTitle()).isEqualTo("Chapter A");
        assertThat(sceneRepository.findByChapterIdOrderBySortOrderAsc(chapterA.id()))
                .extracting(Scene::getTitle)
                .containsExactly("Scene A");
    }

    @Test
    void foreignSceneReadMetadataAutosaveAndDeleteReturnNotFoundWithoutMutation() throws Exception {
        switchToTenantB();

        assertNotFound(get("/api/scenes/{sceneId}", sceneA.id()), "Scene not found");
        assertNotFound(get("/api/scenes/{sceneId}", UUID.randomUUID()), "Scene not found");
        assertNotFound(patch("/api/scenes/{sceneId}", sceneA.id())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Compromised scene\"}"), "Scene not found");
        assertNotFound(patch("/api/scenes/{sceneId}/content", sceneA.id())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"contentJson":"{}","contentText":"foreign content","expectedContentRevision":0,
                         "operationId":"10000000-0000-0000-0000-000000000099"}
                        """), "Scene not found");
        assertNotFound(delete("/api/scenes/{sceneId}", sceneA.id()), "Scene not found");

        Scene unchanged = sceneRepository.findById(sceneA.id()).orElseThrow();
        assertThat(unchanged.getTitle()).isEqualTo("Scene A");
        assertThat(unchanged.getContentText()).isEqualTo("original scene content");
        assertThat(unchanged.getWordCount()).isEqualTo(3);
        assertThat(unchanged.getContentRevision()).isZero();
    }

    @Test
    void maliciousReordersAreRejectedBeforeAnyOrderChanges() throws Exception {
        switchToTenantB();

        assertBadReorder("/api/books/{parentId}/sections/reorder", bookB.id(), List.of(sectionB.id(), sectionA.id()));
        assertOrdersUnchanged(
                sectionRepository.findByBookIdOrderBySortOrderAsc(bookB.id()).stream()
                        .collect(java.util.stream.Collectors.toMap(BookSection::getId, BookSection::getSortOrder)),
                sectionB.id(), sectionBSecond.id()
        );

        assertBadReorder("/api/sections/{parentId}/chapters/reorder", sectionB.id(), List.of(chapterB.id(), chapterA.id()));
        assertBadReorder("/api/sections/{parentId}/chapters/reorder", sectionB.id(), List.of(chapterB.id(), chapterBWrongParent.id()));
        assertOrdersUnchanged(
                chapterRepository.findBySectionIdOrderBySortOrderAsc(sectionB.id()).stream()
                        .collect(java.util.stream.Collectors.toMap(Chapter::getId, Chapter::getSortOrder)),
                chapterB.id(), chapterBSecond.id()
        );

        assertBadReorder("/api/chapters/{parentId}/scenes/reorder", chapterB.id(), List.of(sceneB.id(), sceneA.id()));
        assertBadReorder("/api/chapters/{parentId}/scenes/reorder", chapterB.id(), List.of(sceneB.id(), sceneBWrongParent.id()));
        assertOrdersUnchanged(
                sceneRepository.findByChapterIdOrderBySortOrderAsc(chapterB.id()).stream()
                        .collect(java.util.stream.Collectors.toMap(Scene::getId, Scene::getSortOrder)),
                sceneB.id(), sceneBSecond.id()
        );
    }

    @Test
    void ownerCrudAndTenantScopedOutlineRemainAvailableAndOrdered() throws Exception {
        BookSectionResponse ownerSection = createSection(bookA, "Owner section", 1);
        ChapterResponse ownerChapter = createChapter(ownerSection, "Owner chapter", 0);
        SceneResponse ownerScene = createScene(ownerChapter, "Owner scene", 0, "owner content");

        assertThat(sectionService.getSection(ownerSection.id()).getTitle()).isEqualTo("Owner section");
        assertThat(chapterService.getChapter(ownerChapter.id()).getTitle()).isEqualTo("Owner chapter");
        mockMvc.perform(get("/api/scenes/{sceneId}", ownerScene.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Owner scene"));

        mockMvc.perform(patch("/api/sections/{sectionId}", ownerSection.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Updated owner section\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/api/chapters/{chapterId}", ownerChapter.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Updated owner chapter\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/api/scenes/{sceneId}", ownerScene.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Updated owner scene\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/books/{bookId}/outline", bookA.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sections[0].id").value(sectionA.id().toString()))
                .andExpect(jsonPath("$.sections[1].id").value(ownerSection.id().toString()))
                .andExpect(jsonPath("$.sections[1].chapters[0].id").value(ownerChapter.id().toString()))
                .andExpect(jsonPath("$.sections[1].chapters[0].scenes[0].id").value(ownerScene.id().toString()))
                .andExpect(jsonPath("$..id", org.hamcrest.Matchers.not(hasItem(sectionB.id().toString()))));

        switchToTenantB();
        assertNotFound(get("/api/books/{bookId}/outline", bookA.id()), "Book not found");
        currentUserProvider.reset();

        mockMvc.perform(delete("/api/scenes/{sceneId}", ownerScene.id())).andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/chapters/{chapterId}", ownerChapter.id())).andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/sections/{sectionId}", ownerSection.id())).andExpect(status().isNoContent());
        assertThat(sceneRepository.findById(ownerScene.id())).isEmpty();
        assertThat(chapterRepository.findById(ownerChapter.id())).isEmpty();
        assertThat(sectionRepository.findById(ownerSection.id())).isEmpty();
    }

    private BookSectionResponse createSection(BookResponse book, String title, int sortOrder) {
        return sectionService.create(book.id(), new BookSectionRequest(title, SectionType.PART, sortOrder));
    }

    private ChapterResponse createChapter(BookSectionResponse section, String title, int sortOrder) {
        return chapterService.create(section.id(), new ChapterRequest(title, null, sortOrder));
    }

    private SceneResponse createScene(ChapterResponse chapter, String title, int sortOrder, String content) {
        return sceneService.create(chapter.id(), new SceneRequest(title, null, SceneStatus.DRAFT, sortOrder, "{}", content));
    }

    private void switchToTenantB() {
        currentUserProvider.switchTo(userBId, tenantBId, ZoneId.of("UTC"));
    }

    private void assertNotFound(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
            String message
    ) throws Exception {
        mockMvc.perform(request)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.messages", hasItem(containsString(message))));
    }

    private void assertEquivalentNotFound(
            ThrowingLookup foreignLookup,
            ThrowingLookup missingLookup,
            String message
    ) {
        assertThatThrownBy(foreignLookup::run)
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining(message);
        assertThatThrownBy(missingLookup::run)
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining(message);
    }

    private void assertBadReorder(String path, UUID parentId, List<UUID> orderedIds) throws Exception {
        String ids = orderedIds.stream()
                .map(id -> "\"" + id + "\"")
                .collect(java.util.stream.Collectors.joining(","));
        mockMvc.perform(patch(path, parentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderedIds\":[" + ids + "]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messages", hasItem(containsString("belong to the parent"))));
    }

    private void assertOrdersUnchanged(Map<UUID, Integer> orders, UUID firstId, UUID secondId) {
        assertThat(orders).containsEntry(firstId, 0).containsEntry(secondId, 1);
    }

    @FunctionalInterface
    private interface ThrowingLookup {
        void run();
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
