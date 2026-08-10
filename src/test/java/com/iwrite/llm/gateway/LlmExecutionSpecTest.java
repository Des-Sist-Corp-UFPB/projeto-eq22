package com.iwrite.llm.gateway;

import com.iwrite.audit.entity.AuditResourceType;
import com.iwrite.llm.LlmFeature;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmExecutionSpecTest {

    @Test
    void acceptsBoundedIdentifiersAndOptionalResource() {
        LlmExecutionSpec spec = LlmExecutionSpec
                .of(LlmFeature.SCENE_ANALYSIS, "openai", "gpt-4o-mini", "scene-analysis:v1")
                .withResource(AuditResourceType.SCENE, UUID.randomUUID());

        assertThat(spec.provider()).isEqualTo("openai");
        assertThat(spec.model()).isEqualTo("gpt-4o-mini");
        assertThat(spec.promptVersion()).isEqualTo("scene-analysis:v1");
        assertThat(spec.resourceType()).isEqualTo(AuditResourceType.SCENE);
    }

    @Test
    void allowsMissingModelAndMissingResource() {
        LlmExecutionSpec spec = LlmExecutionSpec.of(LlmFeature.SCENE_ANALYSIS, "disabled", null, "scene-analysis:v1");

        assertThat(spec.model()).isNull();
        assertThat(spec.resourceType()).isNull();
        assertThat(spec.resourceId()).isNull();
    }

    @Test
    void rejectsPromptTextAsPromptVersion() {
        assertThatThrownBy(() -> LlmExecutionSpec.of(
                LlmFeature.SCENE_ANALYSIS,
                "openai",
                "gpt-4o-mini",
                "Analyze this fictional scene text with focus on pacing:v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("promptVersion");
    }

    @Test
    void rejectsPromptVersionWithoutReadableVersionSuffix() {
        assertThatThrownBy(() -> LlmExecutionSpec.of(
                LlmFeature.SCENE_ANALYSIS,
                "openai",
                "gpt-4o-mini",
                "scene-analysis"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("promptVersion");
    }

    @Test
    void acceptsPromptVersionAt64CharacterBoundary() {
        String promptVersion = "a".repeat(54) + ":v" + "1".repeat(8);

        LlmExecutionSpec spec = LlmExecutionSpec.of(
                LlmFeature.SCENE_ANALYSIS,
                "openai",
                "gpt-4o-mini",
                promptVersion
        );

        assertThat(spec.promptVersion()).hasSize(64).isEqualTo(promptVersion);
    }

    @Test
    void rejectsPromptVersionAbove64CharacterBoundary() {
        String promptVersion = "a".repeat(55) + ":v" + "1".repeat(8);

        assertThatThrownBy(() -> LlmExecutionSpec.of(
                LlmFeature.SCENE_ANALYSIS,
                "openai",
                "gpt-4o-mini",
                promptVersion
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("promptVersion");
    }

    @Test
    void acceptsExistingSceneAnalysisPromptVersion() {
        LlmExecutionSpec spec = LlmExecutionSpec.of(
                LlmFeature.SCENE_ANALYSIS,
                "openai",
                "gpt-4o-mini",
                "scene-analysis:v1"
        );

        assertThat(spec.promptVersion()).isEqualTo("scene-analysis:v1");
    }

    @Test
    void rejectsFreeTextProviderAndModel() {
        assertThatThrownBy(() -> LlmExecutionSpec.of(
                LlmFeature.SCENE_ANALYSIS,
                "openai with api key sk-secret",
                "gpt-4o-mini",
                "scene-analysis:v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("provider");

        assertThatThrownBy(() -> LlmExecutionSpec.of(
                LlmFeature.SCENE_ANALYSIS,
                "openai",
                "model name containing manuscript content",
                "scene-analysis:v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("model");
    }

    @Test
    void rejectsResourceIdWithoutResourceType() {
        assertThatThrownBy(() -> new LlmExecutionSpec(
                LlmFeature.SCENE_ANALYSIS,
                "openai",
                null,
                "scene-analysis:v1",
                null,
                UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("resourceType");
    }
}
