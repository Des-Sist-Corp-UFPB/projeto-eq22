package com.iwrite.llm.gateway;

import com.iwrite.audit.entity.AuditResourceType;
import com.iwrite.llm.LlmFeature;
import com.iwrite.llm.LlmTokenUsage;
import com.iwrite.llm.audit.LlmErrorCategory;
import com.iwrite.llm.audit.LlmExecutionAudit;
import com.iwrite.llm.audit.LlmExecutionAuditRecorder;
import com.iwrite.llm.audit.LlmExecutionAuditRepository;
import com.iwrite.llm.audit.LlmExecutionCompletion;
import com.iwrite.llm.audit.LlmExecutionStatus;
import com.iwrite.support.SwitchableCurrentUserProvider;
import com.iwrite.support.TestDatabaseInitializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end gateway coverage against real PostgreSQL. Deliberately not
 * {@code @Transactional}: the gateway rejects executions inside an active
 * transaction by design.
 */
@SpringBootTest
@Import(LlmExecutionGatewayIntegrationTest.CurrentUserTestConfiguration.class)
class LlmExecutionGatewayIntegrationTest {

    private static final String MANUSCRIPT_SAMPLE = "Era uma vez um manuscrito completo da cena do capitulo um";
    private static final String FULL_PROMPT_SAMPLE = "Analyze this fictional scene text. Scene text:";
    private static final String API_KEY_SAMPLE = "sk-live-super-secret-key";

    @DynamicPropertySource
    static void testProperties(DynamicPropertyRegistry registry) {
        TestDatabaseInitializer.prepareDatabase();
        registry.add("spring.datasource.url", TestDatabaseInitializer::testDbUrl);
        registry.add("spring.datasource.username", TestDatabaseInitializer::username);
        registry.add("spring.datasource.password", TestDatabaseInitializer::password);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "4");
        registry.add("iwrite.current-user.development.enabled", () -> "true");
        registry.add("iwrite.ai.audit.pricing.enabled", () -> "true");
        registry.add("iwrite.ai.audit.pricing.currency", () -> "USD");
        registry.add("iwrite.ai.audit.pricing.providers.fake.models.fake-model.input-per-million-tokens", () -> "2.00");
        registry.add("iwrite.ai.audit.pricing.providers.fake.models.fake-model.output-per-million-tokens", () -> "4.00");
    }

    @Autowired
    private LlmExecutionGateway gateway;

    @Autowired
    private LlmExecutionAuditRepository repository;

    @Autowired
    private LlmExecutionAuditRecorder recorder;

    @Autowired
    private SwitchableCurrentUserProvider currentUserProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @AfterEach
    void resetIdentity() {
        currentUserProvider.reset();
    }

    @Test
    void successfulExecutionPersistsCommittedAuditRowWithUsageAndCost() {
        AtomicReference<UUID> traceId = new AtomicReference<>();

        String value = gateway.execute(spec(), context -> {
            traceId.set(context.traceId());
            return LlmCallResult.of("structured analysis")
                    .withTokenUsage(new LlmTokenUsage(100, 50, 150))
                    .withModel("fake-model");
        });

        assertThat(value).isEqualTo("structured analysis");
        LlmExecutionAudit audit = repository.findByTraceId(traceId.get()).orElseThrow();
        assertThat(audit.getStatus()).isEqualTo(LlmExecutionStatus.SUCCEEDED);
        assertThat(audit.getTenantId()).isEqualTo(SwitchableCurrentUserProvider.DEFAULT_TENANT_ID);
        assertThat(audit.getUserId()).isEqualTo(SwitchableCurrentUserProvider.DEFAULT_USER_ID);
        assertThat(audit.getFeature()).isEqualTo(LlmFeature.SCENE_ANALYSIS);
        assertThat(audit.getPromptVersion()).isEqualTo("scene-analysis:v1");
        assertThat(audit.getInputTokens()).isEqualTo(100);
        assertThat(audit.getOutputTokens()).isEqualTo(50);
        assertThat(audit.getTotalTokens()).isEqualTo(150);
        assertThat(audit.getEstimatedCost()).isEqualByComparingTo(new BigDecimal("0.000400"));
        assertThat(audit.getCostCurrency()).isEqualTo("USD");
        assertThat(audit.getLatencyMs()).isNotNegative();
        assertThat(audit.getCompletedAt()).isNotNull();
    }

    @Test
    void failedExecutionPersistsTerminalAuditAndPropagatesSafeError() {
        AtomicReference<UUID> traceId = new AtomicReference<>();

        assertThatThrownBy(() -> gateway.execute(spec(), context -> {
            traceId.set(context.traceId());
            throw new TransientAiException("HTTP 503 from provider; Authorization: Bearer " + API_KEY_SAMPLE);
        }))
                .isInstanceOf(LlmExecutionException.class)
                .hasMessageNotContaining(API_KEY_SAMPLE);

        LlmExecutionAudit audit = repository.findByTraceId(traceId.get()).orElseThrow();
        assertThat(audit.getStatus()).isEqualTo(LlmExecutionStatus.UNAVAILABLE);
        assertThat(audit.getErrorCategory()).isEqualTo(LlmErrorCategory.PROVIDER_UNAVAILABLE);
        assertThat(audit.getCompletedAt()).isNotNull();
        assertThat(audit.getInputTokens()).isNull();
        assertThat(audit.getEstimatedCost()).isNull();
    }

    @Test
    void providerCallRunsWithoutAnActiveDatabaseTransaction() {
        AtomicBoolean transactionActiveDuringCall = new AtomicBoolean(true);

        gateway.execute(spec(), context -> {
            transactionActiveDuringCall.set(TransactionSynchronizationManager.isActualTransactionActive());
            return LlmCallResult.of("analysis");
        });

        assertThat(transactionActiveDuringCall).isFalse();
    }

    @Test
    void executionInsideAnActiveTransactionIsRejectedWithoutPersistingAnything() {
        long rowsBefore = repository.count();
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                gateway.execute(spec(), context -> LlmCallResult.of("analysis"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must not run inside a database transaction");

        assertThat(repository.count()).isEqualTo(rowsBefore);
    }

    @Test
    void recordedSuccessCannotBeReplacedByDelayedFailure() {
        AtomicReference<UUID> traceId = new AtomicReference<>();
        gateway.execute(spec(), context -> {
            traceId.set(context.traceId());
            return LlmCallResult.of("analysis").withTokenUsage(new LlmTokenUsage(10, 20, 30));
        });
        LlmExecutionAudit audit = repository.findByTraceId(traceId.get()).orElseThrow();

        boolean delayedFailureApplied = recorder.complete(audit.getId(), LlmExecutionCompletion.failure(
                LlmExecutionStatus.TIMED_OUT,
                LlmErrorCategory.PROVIDER_TIMEOUT,
                "fake-model",
                audit.getStartedAt().plusMinutes(2),
                120_000
        ));

        assertThat(delayedFailureApplied).isFalse();
        LlmExecutionAudit reloaded = repository.findById(audit.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(LlmExecutionStatus.SUCCEEDED);
        assertThat(reloaded.getInputTokens()).isEqualTo(10);
    }

    @Test
    void executionsAreRecordedUnderTheRequestingTenant() {
        UUID otherTenant = UUID.randomUUID();
        UUID otherUser = UUID.randomUUID();
        currentUserProvider.switchTo(otherUser, otherTenant, ZoneId.of("UTC"));
        AtomicReference<UUID> traceId = new AtomicReference<>();

        gateway.execute(spec(), context -> {
            traceId.set(context.traceId());
            return LlmCallResult.of("analysis");
        });

        LlmExecutionAudit audit = repository.findByTraceId(traceId.get()).orElseThrow();
        assertThat(audit.getTenantId()).isEqualTo(otherTenant);
        assertThat(audit.getUserId()).isEqualTo(otherUser);
        assertThat(repository.findByTenantIdOrderByStartedAtDesc(otherTenant))
                .extracting(LlmExecutionAudit::getId)
                .containsExactly(audit.getId());
    }

    @Test
    void persistedRowNeverContainsManuscriptPromptResponseOrCredentials() {
        AtomicReference<UUID> successTraceId = new AtomicReference<>();
        gateway.execute(spec(), context -> {
            successTraceId.set(context.traceId());
            return LlmCallResult.of("Full model response: the scene named '" + MANUSCRIPT_SAMPLE + "' flows well")
                    .withTokenUsage(new LlmTokenUsage(100, 50, 150));
        });

        AtomicReference<UUID> failureTraceId = new AtomicReference<>();
        assertThatThrownBy(() -> gateway.execute(spec(), context -> {
            failureTraceId.set(context.traceId());
            throw new RuntimeException(
                    FULL_PROMPT_SAMPLE + " " + MANUSCRIPT_SAMPLE + " api-key=" + API_KEY_SAMPLE + " password=hunter2");
        })).isInstanceOf(LlmExecutionException.class);

        for (UUID traceId : new UUID[]{successTraceId.get(), failureTraceId.get()}) {
            String rowAsText = jdbcTemplate.queryForObject(
                    "select t::text from llm_execution_audits t where trace_id = ?",
                    String.class,
                    traceId
            );
            assertThat(rowAsText)
                    .doesNotContain(MANUSCRIPT_SAMPLE)
                    .doesNotContain(FULL_PROMPT_SAMPLE)
                    .doesNotContain("Full model response")
                    .doesNotContain(API_KEY_SAMPLE)
                    .doesNotContain("hunter2");
        }
    }

    private LlmExecutionSpec spec() {
        return LlmExecutionSpec
                .of(LlmFeature.SCENE_ANALYSIS, "fake", "fake-model", "scene-analysis:v1")
                .withResource(AuditResourceType.SCENE, UUID.randomUUID());
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
