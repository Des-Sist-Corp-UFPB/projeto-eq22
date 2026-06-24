package com.iwrite.sceneversion;

import com.iwrite.book.dto.BookResponse;
import com.iwrite.chapter.dto.ChapterResponse;
import com.iwrite.scene.dto.SceneContentRequest;
import com.iwrite.scene.dto.SceneResponse;
import com.iwrite.scene.entity.Scene;
import com.iwrite.scene.entity.SceneStatus;
import com.iwrite.scene.repository.SceneRepository;
import com.iwrite.sceneversion.entity.SceneVersion;
import com.iwrite.sceneversion.entity.SceneVersionSource;
import com.iwrite.sceneversion.repository.SceneVersionRepository;
import com.iwrite.section.dto.BookSectionResponse;
import com.iwrite.support.PostgresIntegrationTest;
import com.iwrite.support.SwitchableCurrentUserProvider;
import com.iwrite.tenant.entity.Tenant;
import com.iwrite.tenant.entity.TenantMembership;
import com.iwrite.tenant.entity.TenantMembershipRole;
import com.iwrite.tenant.repository.TenantRepository;
import com.iwrite.user.entity.User;
import com.iwrite.writingprogress.entity.DailyWritingProgress;
import com.iwrite.writingprogress.ledger.repository.BookWordCountEventRepository;
import com.iwrite.writingprogress.repository.DailyWritingProgressRepository;
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
import org.springframework.test.web.servlet.MvcResult;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static java.util.Comparator.reverseOrder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@Import(SceneVersionTenantIsolationIntegrationTest.CurrentUserTestConfiguration.class)
class SceneVersionTenantIsolationIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SwitchableCurrentUserProvider currentUserProvider;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private SceneVersionRepository versionRepository;

    @Autowired
    private SceneRepository sceneRepository;

    @Autowired
    private BookWordCountEventRepository eventRepository;

    @Autowired
    private DailyWritingProgressRepository progressRepository;

    @PersistenceContext
    private EntityManager entityManager;

    private Identity tenantA;
    private Identity tenantB;
    private VersionWorld worldA;
    private VersionWorld worldB;

    @BeforeEach
    void setUpTenantVersions() {
        currentUserProvider.reset();
        tenantA = createIdentity("Tenant A", "tenant-a@iwrite.local");
        tenantB = createIdentity("Tenant B", "tenant-b@iwrite.local");

        switchTo(tenantA);
        worldA = createVersionWorld("Tenant A");
        switchTo(tenantB);
        worldB = createVersionWorld("Tenant B");
        switchTo(tenantA);
    }

    @AfterEach
    void resetIdentity() {
        currentUserProvider.reset();
    }

    @Test
    void ownerCanListAndReadOrderedHistoryWithoutForeignVersions() throws Exception {
        List<SceneVersion> expected = versions(worldA.scene().id());
        assertThat(expected)
                .extracting(SceneVersion::getCreatedAt)
                .isSortedAccordingTo(reverseOrder());

        MvcResult history = mockMvc.perform(get("/api/scenes/{sceneId}/versions", worldA.scene().id())
                        .param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.hasNext").value(false))
                .andExpect(jsonPath("$.items.length()").value(expected.size()))
                .andExpect(jsonPath("$.items[0].id").value(expected.get(0).getId().toString()))
                .andExpect(jsonPath("$.items[0].sceneId").value(worldA.scene().id().toString()))
                .andExpect(jsonPath("$.items[0].source").exists())
                .andExpect(jsonPath("$.items[0].createdAt").exists())
                .andExpect(jsonPath("$.items[0].contentTextPreview").exists())
                .andReturn();

        assertThat(history.getResponse().getContentAsString())
                .doesNotContain(worldB.oldVersion().getId().toString())
                .doesNotContain("Tenant B original");

        mockMvc.perform(get(
                        "/api/scenes/{sceneId}/versions/{versionId}",
                        worldA.scene().id(),
                        worldA.oldVersion().getId()
                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(worldA.oldVersion().getId().toString()))
                .andExpect(jsonPath("$.sceneId").value(worldA.scene().id().toString()))
                .andExpect(jsonPath("$.contentText").value("Tenant A original"))
                .andExpect(jsonPath("$.sceneTitleSnapshot").value("Scene Tenant A"));
    }

    @Test
    void foreignAndMissingSceneHistoryHaveEquivalentNotFoundSemantics() throws Exception {
        switchTo(tenantB);

        MvcResult foreign = assertNotFound(get("/api/scenes/{sceneId}/versions", worldA.scene().id()), "Scene not found");
        MvcResult missing = assertNotFound(get("/api/scenes/{sceneId}/versions", UUID.randomUUID()), "Scene not found");

        assertEquivalentNotFound(foreign, missing);
        assertNoVersionLeak(foreign);
    }

    @Test
    void foreignAndMissingVersionIdsHaveEquivalentNotFoundSemantics() throws Exception {
        switchTo(tenantB);

        MvcResult foreign = assertNotFound(
                get("/api/scenes/{sceneId}/versions/{versionId}", worldB.scene().id(), worldA.oldVersion().getId()),
                "Scene version not found"
        );
        MvcResult missing = assertNotFound(
                get("/api/scenes/{sceneId}/versions/{versionId}", worldB.scene().id(), UUID.randomUUID()),
                "Scene version not found"
        );

        assertEquivalentNotFound(foreign, missing);
        assertNoVersionLeak(foreign);
    }

    @Test
    void ownerCanRestoreVersionWithExistingVersioningAndLedgerBehavior() throws Exception {
        long versionsBefore = versionRepository.countByOriginalSceneId(worldA.scene().id());
        long eventsBefore = eventRepository.countByBookId(worldA.book().id());

        mockMvc.perform(post(
                                "/api/scenes/{sceneId}/versions/{versionId}/restore",
                                worldA.scene().id(),
                                worldA.oldVersion().getId()
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(restoreBody(worldA.scene().contentRevision())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contentText").value("Tenant A original"))
                .andExpect(jsonPath("$.wordCount").value(3))
                .andExpect(jsonPath("$.contentRevision").value(worldA.scene().contentRevision() + 1));

        entityManager.flush();
        entityManager.clear();
        assertThat(versionRepository.countByOriginalSceneId(worldA.scene().id())).isEqualTo(versionsBefore + 1);
        assertThat(versions(worldA.scene().id()))
                .extracting(SceneVersion::getSource)
                .contains(SceneVersionSource.RESTORE_SAFETY);
        assertThat(eventRepository.countByBookId(worldA.book().id())).isEqualTo(eventsBefore + 1);
    }

    @Test
    void rejectedForeignAndWrongSceneRestoresLeaveAllPersistedStateUnchanged() throws Exception {
        PersistedState stateA = persistedState(worldA);
        PersistedState stateB = persistedState(worldB);

        switchTo(tenantB);
        MvcResult foreign = assertNotFound(
                post("/api/scenes/{sceneId}/versions/{versionId}/restore", worldB.scene().id(), worldA.oldVersion().getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(restoreBody(worldB.scene().contentRevision())),
                "Scene version not found"
        );
        assertNoVersionLeak(foreign);
        assertStateUnchanged(worldA, stateA);
        assertStateUnchanged(worldB, stateB);

        switchTo(tenantA);
        assertNotFound(
                post(
                                "/api/scenes/{sceneId}/versions/{versionId}/restore",
                                worldA.otherScene().id(),
                                worldA.oldVersion().getId()
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(restoreBody(worldA.otherScene().contentRevision())),
                "Scene version not found"
        );
        assertStateUnchanged(worldA, stateA);
        assertStateUnchanged(worldB, stateB);
    }

    @Test
    void rejectedForeignContentUpdateCreatesNoVersionOrSideEffect() throws Exception {
        PersistedState stateA = persistedState(worldA);
        switchTo(tenantB);

        assertNotFound(
                patch("/api/scenes/{sceneId}/content", worldA.scene().id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"contentJson":"{}","contentText":"foreign overwrite","source":"MANUAL_SAVE",
                                 "expectedContentRevision":1,"operationId":"10000000-0000-0000-0000-000000000099"}
                                """),
                "Scene not found"
        );

        assertStateUnchanged(worldA, stateA);
    }

    private VersionWorld createVersionWorld(String label) {
        BookResponse book = createBook("Book " + label);
        BookSectionResponse section = createSection(book, "Section " + label);
        ChapterResponse chapter = createChapter(section, "Chapter " + label);
        SceneResponse original = createScene(
                chapter,
                "Scene " + label,
                SceneStatus.DRAFT,
                0,
                label + " original"
        );
        SceneResponse otherScene = createScene(
                chapter,
                "Other scene " + label,
                SceneStatus.DRAFT,
                1,
                label + " other"
        );
        SceneResponse current = sceneService.updateContent(
                original.id(),
                new SceneContentRequest(
                        "{}",
                        label + " current content",
                        SceneVersionSource.MANUAL_SAVE,
                        original.contentRevision(),
                        UUID.randomUUID()
                )
        );
        SceneVersion oldVersion = versions(original.id()).stream()
                .filter(version -> (label + " original").equals(version.getContentText()))
                .findFirst()
                .orElseThrow();

        return new VersionWorld(currentUserProvider.userId(), book, current, otherScene, oldVersion);
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

    private List<SceneVersion> versions(UUID sceneId) {
        return versionRepository.findByOriginalSceneIdOrderByCreatedAtDesc(sceneId);
    }

    private PersistedState persistedState(VersionWorld world) {
        entityManager.flush();
        entityManager.clear();
        Scene scene = sceneRepository.findById(world.scene().id()).orElseThrow();
        return new PersistedState(
                scene.getTitle(),
                scene.getContentJson(),
                scene.getContentText(),
                scene.getWordCount(),
                scene.getContentRevision(),
                versionRepository.countByOriginalSceneId(world.scene().id()),
                eventRepository.countByBookId(world.book().id()),
                progressRepository.findFirstByUser_IdAndBookIdOrderByProgressDateAsc(world.ownerUserId(), world.book().id())
                        .map(ProgressState::from)
                        .orElse(null)
        );
    }

    private void assertStateUnchanged(VersionWorld world, PersistedState expected) {
        assertThat(persistedState(world)).isEqualTo(expected);
    }

    private MvcResult assertNotFound(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
            String message
    ) throws Exception {
        return mockMvc.perform(request)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.messages", hasItem(org.hamcrest.Matchers.containsString(message))))
                .andReturn();
    }

    private void assertEquivalentNotFound(MvcResult first, MvcResult second) throws Exception {
        assertThat(first.getResponse().getStatus()).isEqualTo(second.getResponse().getStatus());
        assertThat(first.getResponse().getContentType()).isEqualTo(second.getResponse().getContentType());
    }

    private void assertNoVersionLeak(MvcResult result) throws Exception {
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("Tenant A original")
                .doesNotContain("Scene Tenant A");
    }

    private String restoreBody(long expectedContentRevision) {
        return """
                {"expectedContentRevision":%d,"operationId":"%s"}
                """.formatted(expectedContentRevision, UUID.randomUUID());
    }

    private void switchTo(Identity identity) {
        currentUserProvider.switchTo(identity.userId(), identity.tenantId(), ZoneId.of("UTC"));
    }

    private record Identity(UUID userId, UUID tenantId) {
    }

    private record VersionWorld(
            UUID ownerUserId,
            BookResponse book,
            SceneResponse scene,
            SceneResponse otherScene,
            SceneVersion oldVersion
    ) {
    }

    private record PersistedState(
            String title,
            String contentJson,
            String contentText,
            Integer wordCount,
            Long contentRevision,
            long versionCount,
            long eventCount,
            ProgressState progress
    ) {
    }

    private record ProgressState(
            Integer startWordCount,
            Integer endWordCount,
            Integer productiveWordCountChange,
            Integer manuscriptAdjustmentWordCount,
            OffsetDateTime updatedAt
    ) {

        static ProgressState from(DailyWritingProgress progress) {
            return new ProgressState(
                    progress.getStartingManuscriptWordCount(),
                    progress.getEndingManuscriptWordCount(),
                    progress.getProductiveWordCountChange(),
                    progress.getManuscriptAdjustmentWordCount(),
                    progress.getUpdatedAt()
            );
        }
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
