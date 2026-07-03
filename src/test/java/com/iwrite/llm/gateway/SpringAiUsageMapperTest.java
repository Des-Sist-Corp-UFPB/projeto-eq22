package com.iwrite.llm.gateway;

import com.iwrite.llm.LlmTokenUsage;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.EmptyUsage;

import static org.assertj.core.api.Assertions.assertThat;

class SpringAiUsageMapperTest {

    @Test
    void mapsReportedUsage() {
        assertThat(SpringAiUsageMapper.toTokenUsage(new DefaultUsage(120, 45, 165)))
                .isEqualTo(new LlmTokenUsage(120, 45, 165));
    }

    @Test
    void absentUsageMapsToNull() {
        assertThat(SpringAiUsageMapper.toTokenUsage(null)).isNull();
    }

    @Test
    void emptyUsageIsNotInventedAsZeroCounts() {
        assertThat(SpringAiUsageMapper.toTokenUsage(new EmptyUsage())).isNull();
        assertThat(SpringAiUsageMapper.toTokenUsage(new DefaultUsage(0, 0))).isNull();
    }
}
