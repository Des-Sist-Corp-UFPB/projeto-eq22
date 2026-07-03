package com.iwrite.scene.controller;

import com.iwrite.scene.ai.SceneAnalysisPrompt;
import com.iwrite.scene.ai.WritingAssistant;
import com.iwrite.audit.entity.AuditResourceType;
import com.iwrite.llm.LlmFeature;
import com.iwrite.llm.LlmTokenUsage;
import com.iwrite.llm.audit.LlmErrorCategory;
import com.iwrite.llm.audit.LlmExecutionAudit;
import com.iwrite.llm.audit.LlmExecutionAuditRepository;
import com.iwrite.llm.audit.LlmExecutionStatus;
import com.iwrite.llm.gateway.LlmCallResult;
import com.iwrite.llm.gateway.LlmFeatureDisabledException;
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
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static com.iwrite.support.SwitchableCurrentUserProvider.DEFAULT_TENANT_ID;
import static com.iwrite.support.SwitchableCurrentUserProvider.DEFAULT_USER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@Import(SceneAnalysisAccessIntegrationTest.CurrentUserTestConfiguration.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SceneAnalysisAccessIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SwitchableCurrentUserProvider currentUserProvider;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private LlmExecutionAuditRepository llmExecutionAuditRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoBean
    private WritingAssistant writingAssistant;

    @PersistenceContext
    private EntityManager entityManager;

    @BeforeEach
    void configureAssistantMetadata() {
        when(writingAssistant.provider()).thenReturn("openai");
        when(writingAssistant.model()).thenReturn("gemini-2.5-flash");
    }

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
        verify(writingAssistant, times(1)).analyzeScene(any(SceneAnalysisPrompt.class));

        currentUserProvider.switchTo(createMember(DEFAULT_TENANT_ID, "Unrelated", "ai-unrelated@iwrite.local"), DEFAULT_TENANT_ID, ZoneId.of("UTC"));
        mockMvc.perform(post("/api/scenes/{sceneId}/ai-analysis", scene.id()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.messages", hasItem(containsString("Scene not found"))));
        verify(writingAssistant, times(1)).analyzeScene(any(SceneAnalysisPrompt.class));

        ForeignIdentity foreign = createForeignIdentity();
        currentUserProvider.switchTo(foreign.userId(), foreign.tenantId(), ZoneId.of("UTC"));
        mockMvc.perform(post("/api/scenes/{sceneId}/ai-analysis", scene.id()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.messages", hasItem(containsString("Scene not found"))));
        verify(writingAssistant, times(1)).analyzeScene(any(SceneAnalysisPrompt.class));
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
    void successfulAnalysisPersistsSceneAuditWithUsageAndEffectiveModel() throws Exception {
        var book = createBook("AI audited success");
        var section = createSection(book, "Part");
        var chapter = createChapter(section, "Chapter");
        var scene = createScene(chapter, "Scene", SceneStatus.DRAFT, 0, "audited scene words");
        when(writingAssistant.analyzeScene(any(SceneAnalysisPrompt.class))).thenReturn(
                LlmCallResult.of(validResponse())
                        .withTokenUsage(new LlmTokenUsage(120, 30, 150))
                        .withModel("gemini-2.5-flash-002")
        );

        mockMvc.perform(post("/api/scenes/{sceneId}/ai-analysis", scene.id()))
                .andExpect(status().isOk());

        LlmExecutionAudit audit = auditFor(scene.id());
        assertThat(audit.getTenantId()).isEqualTo(DEFAULT_TENANT_ID);
        assertThat(audit.getUserId()).isEqualTo(DEFAULT_USER_ID);
        assertThat(audit.getFeature()).isEqualTo(LlmFeature.SCENE_ANALYSIS);
        assertThat(audit.getProvider()).isEqualTo("openai");
        assertThat(audit.getModel()).isEqualTo("gemini-2.5-flash-002");
        assertThat(audit.getPromptVersion()).isEqualTo("scene-analysis:v1");
        assertThat(audit.getTraceId()).isNotNull();
        assertThat(audit.getResourceType()).isEqualTo(AuditResourceType.SCENE);
        assertThat(audit.getResourceId()).isEqualTo(scene.id());
        assertThat(audit.getLatencyMs()).isNotNegative();
        assertThat(audit.getStatus()).isEqualTo(LlmExecutionStatus.SUCCEEDED);
        assertThat(audit.getErrorCategory()).isNull();
        assertThat(audit.getInputTokens()).isEqualTo(120);
        assertThat(audit.getOutputTokens()).isEqualTo(30);
        assertThat(audit.getTotalTokens()).isEqualTo(150);
    }

    @Test
    void successfulAnalysisWithoutUsagePersistsNullTokenFields() throws Exception {
        var book = createBook("AI audited without usage");
        var section = createSection(book, "Part");
        var chapter = createChapter(section, "Chapter");
        var scene = createScene(chapter, "Scene", SceneStatus.DRAFT, 0, "scene words without usage");
        when(writingAssistant.analyzeScene(any(SceneAnalysisPrompt.class))).thenReturn(validAnalysis());

        mockMvc.perform(post("/api/scenes/{sceneId}/ai-analysis", scene.id()))
                .andExpect(status().isOk());

        LlmExecutionAudit audit = auditFor(scene.id());
        assertThat(audit.getStatus()).isEqualTo(LlmExecutionStatus.SUCCEEDED);
        assertThat(audit.getInputTokens()).isNull();
        assertThat(audit.getOutputTokens()).isNull();
        assertThat(audit.getTotalTokens()).isNull();
    }

    @Test
    void providerTimeoutPersistsTimedOutAudit() throws Exception {
        var book = createBook("AI timeout");
        var section = createSection(book, "Part");
        var chapter = createChapter(section, "Chapter");
        var scene = createScene(chapter, "Scene", SceneStatus.DRAFT, 0, "scene words");
        when(writingAssistant.analyzeScene(any(SceneAnalysisPrompt.class))).thenThrow(
                new ResourceAccessException("I/O", new IOException(new SocketTimeoutException("sensitive timeout")))
        );

        mockMvc.perform(post("/api/scenes/{sceneId}/ai-analysis", scene.id()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.messages", hasItem("AI scene analysis could not be completed.")));

        LlmExecutionAudit audit = auditFor(scene.id());
        assertThat(audit.getStatus()).isEqualTo(LlmExecutionStatus.TIMED_OUT);
        assertThat(audit.getErrorCategory()).isEqualTo(LlmErrorCategory.PROVIDER_TIMEOUT);
        assertThat(audit.getCompletedAt()).isNotNull();
    }

    @Test
    void disabledProviderPersistsDisabledAudit() throws Exception {
        var book = createBook("AI disabled");
        var section = createSection(book, "Part");
        var chapter = createChapter(section, "Chapter");
        var scene = createScene(chapter, "Scene", SceneStatus.DRAFT, 0, "scene words");
        when(writingAssistant.analyzeScene(any(SceneAnalysisPrompt.class)))
                .thenThrow(new LlmFeatureDisabledException());

        mockMvc.perform(post("/api/scenes/{sceneId}/ai-analysis", scene.id()))
                .andExpect(status().isServiceUnavailable());

        LlmExecutionAudit audit = auditFor(scene.id());
        assertThat(audit.getStatus()).isEqualTo(LlmExecutionStatus.DISABLED);
        assertThat(audit.getErrorCategory()).isEqualTo(LlmErrorCategory.FEATURE_DISABLED);
        assertThat(audit.getCompletedAt()).isNotNull();
    }

    @Test
    void invalidStructuredResponsePersistsInvalidResponseAudit() throws Exception {
        var book = createBook("AI invalid response");
        var section = createSection(book, "Part");
        var chapter = createChapter(section, "Chapter");
        var scene = createScene(chapter, "Scene", SceneStatus.DRAFT, 0, "scene words");
        when(writingAssistant.analyzeScene(any(SceneAnalysisPrompt.class)))
                .thenReturn(LlmCallResult.of(new SceneAnalysisResponse(null, null, null, null, null, null)));

        mockMvc.perform(post("/api/scenes/{sceneId}/ai-analysis", scene.id()))
                .andExpect(status().isServiceUnavailable());

        LlmExecutionAudit audit = auditFor(scene.id());
        assertThat(audit.getStatus()).isEqualTo(LlmExecutionStatus.INVALID_RESPONSE);
        assertThat(audit.getErrorCategory()).isEqualTo(LlmErrorCategory.INVALID_STRUCTURED_RESPONSE);
        assertThat(audit.getCompletedAt()).isNotNull();
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

        LlmExecutionAudit audit = auditFor(scene.id());
        assertThat(audit.getStatus()).isEqualTo(LlmExecutionStatus.FAILED);
        assertThat(audit.getErrorCategory()).isEqualTo(LlmErrorCategory.PROVIDER_REQUEST_REJECTED);
        assertThat(audit.getCompletedAt()).isNotNull();
    }

    @Test
    void auditPersistenceExcludesScenePromptAndProviderResponseContent() throws Exception {
        String manuscript = "MANUSCRIPT_SECRET_MARKER";
        String focus = "PROMPT_SECRET_MARKER";
        String providerResponse = "PROVIDER_RESPONSE_SECRET_MARKER";
        var book = createBook("AI sensitive audit");
        var section = createSection(book, "Part");
        var chapter = createChapter(section, "Chapter");
        var scene = createScene(chapter, "Scene", SceneStatus.DRAFT, 0, manuscript);
        when(writingAssistant.analyzeScene(any(SceneAnalysisPrompt.class))).thenReturn(LlmCallResult.of(
                new SceneAnalysisResponse(
                        providerResponse,
                        providerResponse,
                        providerResponse,
                        List.of(providerResponse),
                        List.of(providerResponse),
                        List.of(providerResponse)
                )
        ));

        mockMvc.perform(post("/api/scenes/{sceneId}/ai-analysis", scene.id())
                        .contentType("application/json")
                        .content("{\"focus\":\"" + focus + "\"}"))
                .andExpect(status().isOk());

        LlmExecutionAudit audit = auditFor(scene.id());
        String rowAsText = jdbcTemplate.queryForObject(
                "select audit::text from llm_execution_audits audit where id = ?",
                String.class,
                audit.getId()
        );
        assertThat(rowAsText)
                .doesNotContain(manuscript)
                .doesNotContain(focus)
                .doesNotContain(providerResponse);
    }

    @Test
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
        return new TransactionTemplate(transactionManager).execute(status -> {
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
        });
    }

    private ForeignIdentity createForeignIdentity() {
        Tenant tenant = new Tenant();
        tenant.setName("Foreign AI");
        tenant.setDefaultTimeZoneId("UTC");
        Tenant savedTenant = tenantRepository.save(tenant);
        UUID userId = createMember(savedTenant.getId(), "Foreign User", "ai-foreign@iwrite.local");
        return new ForeignIdentity(userId, savedTenant.getId());
    }

    private LlmCallResult<SceneAnalysisResponse> validAnalysis() {
        return LlmCallResult.of(validResponse());
    }

    private SceneAnalysisResponse validResponse() {
        return new SceneAnalysisResponse(
                "Summary",
                "Tone",
                "Pacing",
                List.of("Strength"),
                List.of("Issue"),
                List.of("Suggestion")
        );
    }

    private LlmExecutionAudit auditFor(UUID sceneId) {
        return llmExecutionAuditRepository.findAll().stream()
                .filter(audit -> sceneId.equals(audit.getResourceId()))
                .max((left, right) -> left.getStartedAt().compareTo(right.getStartedAt()))
                .orElseThrow();
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
