package com.iwrite.scene.controller;

import com.iwrite.scene.ai.SceneAnalysisPrompt;
import com.iwrite.scene.ai.WritingAssistant;
import com.iwrite.scene.dto.SceneAnalysisResponse;
import com.iwrite.scene.entity.SceneStatus;
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
import org.junit.jupiter.api.Test;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static com.iwrite.support.SwitchableCurrentUserProvider.DEFAULT_TENANT_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@Import(SceneAnalysisAccessIntegrationTest.CurrentUserTestConfiguration.class)
class SceneAnalysisAccessIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SwitchableCurrentUserProvider currentUserProvider;

    @Autowired
    private TenantRepository tenantRepository;

    @MockitoBean
    private WritingAssistant writingAssistant;

    @PersistenceContext
    private EntityManager entityManager;

    @AfterEach
    void resetIdentity() {
        currentUserProvider.reset();
    }

    @Test
    void endpointUsesExistingTenantAwareSceneAccess() throws Exception {
        var book = createBook("AI access");
        var section = createSection(book, "Part");
        var chapter = createChapter(section, "Chapter");
        var scene = createScene(chapter, "Scene", SceneStatus.DRAFT, 0, "accessible scene words");
        when(writingAssistant.analyzeScene(any(SceneAnalysisPrompt.class))).thenReturn(validAnalysis());

        mockMvc.perform(post("/api/scenes/{sceneId}/ai-analysis", scene.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary").value("Summary"));

        currentUserProvider.switchTo(createMember(DEFAULT_TENANT_ID, "Unrelated", "ai-unrelated@iwrite.local"), DEFAULT_TENANT_ID, ZoneId.of("UTC"));
        mockMvc.perform(post("/api/scenes/{sceneId}/ai-analysis", scene.id()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.messages", hasItem(containsString("Scene not found"))));

        ForeignIdentity foreign = createForeignIdentity();
        currentUserProvider.switchTo(foreign.userId(), foreign.tenantId(), ZoneId.of("UTC"));
        mockMvc.perform(post("/api/scenes/{sceneId}/ai-analysis", scene.id()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.messages", hasItem(containsString("Scene not found"))));
    }

    @Test
    void endpointDoesNotCallAssistantForBlankContent() throws Exception {
        var book = createBook("AI blank");
        var section = createSection(book, "Part");
        var chapter = createChapter(section, "Chapter");
        var scene = createScene(chapter, "Blank", SceneStatus.DRAFT, 0, "");

        mockMvc.perform(post("/api/scenes/{sceneId}/ai-analysis", scene.id()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messages", hasItem(containsString("textual content"))));

        verifyNoInteractions(writingAssistant);
    }

    @Test
    void providerFailureReturnsSafeUnavailableError() throws Exception {
        var book = createBook("AI upstream failure");
        var section = createSection(book, "Part");
        var chapter = createChapter(section, "Chapter");
        var scene = createScene(chapter, "Scene", SceneStatus.DRAFT, 0, "scene words");
        when(writingAssistant.analyzeScene(any(SceneAnalysisPrompt.class)))
                .thenThrow(new NonTransientAiException("raw upstream authentication body"));

        mockMvc.perform(post("/api/scenes/{sceneId}/ai-analysis", scene.id()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.messages", hasItem("AI scene analysis could not be completed.")));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void endpointCallsAssistantOutsideDatabaseTransaction() throws Exception {
        var book = createBook("AI transaction boundary");
        var section = createSection(book, "Part");
        var chapter = createChapter(section, "Chapter");
        var scene = createScene(chapter, "Scene", SceneStatus.DRAFT, 0, "scene words");
        when(writingAssistant.analyzeScene(any(SceneAnalysisPrompt.class))).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return validAnalysis();
        });

        mockMvc.perform(post("/api/scenes/{sceneId}/ai-analysis", scene.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary").value("Summary"));
    }

    private UUID createMember(UUID tenantId, String displayName, String email) {
        User user = new User();
        user.setDisplayName(displayName);
        user.setEmail(email);
        user.setTimeZoneId("UTC");
        entityManager.persist(user);

        TenantMembership membership = new TenantMembership();
        membership.setTenant(entityManager.getReference(Tenant.class, tenantId));
        membership.setUser(user);
        membership.setRole(TenantMembershipRole.OWNER);
        entityManager.persist(membership);
        entityManager.flush();
        return user.getId();
    }

    private ForeignIdentity createForeignIdentity() {
        Tenant tenant = new Tenant();
        tenant.setName("Foreign AI");
        tenant.setDefaultTimeZoneId("UTC");
        Tenant savedTenant = tenantRepository.save(tenant);
        UUID userId = createMember(savedTenant.getId(), "Foreign User", "ai-foreign@iwrite.local");
        return new ForeignIdentity(userId, savedTenant.getId());
    }

    private SceneAnalysisResponse validAnalysis() {
        return new SceneAnalysisResponse(
                "Summary",
                "Tone",
                "Pacing",
                List.of("Strength"),
                List.of("Issue"),
                List.of("Suggestion")
        );
    }

    private record ForeignIdentity(UUID userId, UUID tenantId) {
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
