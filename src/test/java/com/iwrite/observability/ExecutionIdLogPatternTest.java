package com.iwrite.observability;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.LoggingEvent;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The LLM audit UUID is deliberately kept out of the OTLP attributes, so the
 * console line is the only place it can still be read — which makes the
 * {@code logging.pattern.level} in {@code application.yml} load-bearing for the
 * documented "log line -> audit row" correlation, not cosmetic.
 *
 * <p>The pattern is read from {@code application.yml} rather than duplicated
 * here, so this test fails if the configuration is dropped or its syntax breaks.
 */
class ExecutionIdLogPatternTest {

    private static final String EXECUTION_ID = "9f1c2e44-5a6b-4c7d-8e9f-0a1b2c3d4e5f";

    @Test
    void rendersTheExecutionIdOnTheConsoleLineWhenPresent() {
        assertThat(render(Map.of("llmExecutionId", EXECUTION_ID)))
                .contains(EXECUTION_ID)
                .contains("[" + EXECUTION_ID + "]");
    }

    /** Every other log line in the application must not gain an empty {@code []}. */
    @Test
    void leavesNoEmptyBracketsOnLinesWithoutTheExecutionId() {
        assertThat(render(Map.of())).doesNotContain("[]");
    }

    private static String render(Map<String, String> mdc) {
        PatternLayout layout = new PatternLayout();
        layout.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
        layout.setPattern(configuredLevelPattern());
        layout.start();
        try {
            LoggingEvent event = new LoggingEvent();
            event.setLevel(Level.INFO);
            event.setMessage("LLM execution completed");
            event.setMDCPropertyMap(mdc);
            return layout.doLayout(event);
        } finally {
            layout.stop();
        }
    }

    private static String configuredLevelPattern() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        Properties properties = yaml.getObject();
        assertThat(properties).isNotNull();
        String pattern = properties.getProperty("logging.pattern.level");
        assertThat(pattern)
                .as("application.yml must keep logging.pattern.level rendering llmExecutionId")
                .isNotNull()
                .contains("llmExecutionId");
        return pattern;
    }
}
