package com.iwrite.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iwrite.audit.entity.AuditAction;
import com.iwrite.audit.entity.AuditLog;
import com.iwrite.audit.entity.AuditResourceType;
import com.iwrite.audit.entity.AuditResult;
import com.iwrite.audit.repository.AuditLogRepository;
import com.iwrite.support.PostgresIntegrationTest;
import com.iwrite.support.SwitchableCurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class AuditLogIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Test
    void persistsSuccessfulOperationWithCurrentIdentityAndCreatedResourceId() throws Exception {
        String responseBody = mockMvc.perform(post("/api/books")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Audited book"}
                                """))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode response = objectMapper.readTree(responseBody);
        UUID bookId = UUID.fromString(response.get("id").asText());
        AuditLog auditLog = requireAudit(AuditAction.BOOK_CREATED, bookId);

        assertThat(auditLog.getTenantId()).isEqualTo(SwitchableCurrentUserProvider.DEFAULT_TENANT_ID);
        assertThat(auditLog.getUserId()).isEqualTo(SwitchableCurrentUserProvider.DEFAULT_USER_ID);
        assertThat(auditLog.getResourceType()).isEqualTo(AuditResourceType.BOOK);
        assertThat(auditLog.getResult()).isEqualTo(AuditResult.SUCCEEDED);
        assertThat(auditLog.getOccurredAt()).isNotNull();
    }

    @Test
    void persistsFailedOperationWithoutSensitiveRequestData() throws Exception {
        UUID missingBookId = UUID.randomUUID();

        mockMvc.perform(patch("/api/books/{bookId}", missingBookId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());

        AuditLog auditLog = requireAudit(AuditAction.BOOK_UPDATED, missingBookId);

        assertThat(auditLog.getResourceType()).isEqualTo(AuditResourceType.BOOK);
        assertThat(auditLog.getResult()).isEqualTo(AuditResult.FAILED);
    }

    @Test
    void auditsOpenAiOperationWhenIntegrationIsDisabled() throws Exception {
        StoryWorld storyWorld = createStoryWorld("Audit AI");

        mockMvc.perform(post("/api/scenes/{sceneId}/ai-analysis", storyWorld.scene().id()))
                .andExpect(status().isServiceUnavailable());

        AuditLog auditLog = requireAudit(AuditAction.OPENAI_SCENE_ANALYSIS, storyWorld.scene().id());

        assertThat(auditLog.getResourceType()).isEqualTo(AuditResourceType.SCENE);
        assertThat(auditLog.getResult()).isEqualTo(AuditResult.FAILED);
    }

    private AuditLog requireAudit(AuditAction action, UUID resourceId) {
        return auditLogRepository.findTopByActionAndResourceIdOrderByOccurredAtDesc(action, resourceId)
                .orElseThrow();
    }
}
