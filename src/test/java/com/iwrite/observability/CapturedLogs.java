package com.iwrite.observability;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import org.slf4j.event.KeyValuePair;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Attaches a {@link ListAppender} to the Logback root logger so tests can
 * assert on the real logging events an operation produces — level, message,
 * key-value pairs, MDC, arguments and throwable — instead of on the source
 * code that produces them.
 *
 * <p>Everything the production code logs reaches the root logger by
 * additivity, so one appender covers the business, MCP and LLM loggers at
 * once. {@link #close()} restores the previous root level and detaches the
 * appender.
 */
public final class CapturedLogs implements AutoCloseable {

    private final ch.qos.logback.classic.Logger root;
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final Level previousLevel;

    public CapturedLogs() {
        this.root = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        this.previousLevel = root.getLevel();
        root.setLevel(Level.TRACE);
        appender.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
        appender.start();
        root.addAppender(appender);
    }

    public List<ILoggingEvent> events() {
        return List.copyOf(appender.list);
    }

    /** The single event carrying {@code otel.event.name=name}; fails loudly if there is not exactly one. */
    public ILoggingEvent single(String eventName) {
        List<ILoggingEvent> matching = all(eventName);
        if (matching.size() != 1) {
            throw new AssertionError("expected exactly 1 '" + eventName + "' event but found " + matching.size());
        }
        return matching.get(0);
    }

    public List<ILoggingEvent> all(String eventName) {
        return appender.list.stream()
                .filter(event -> eventName.equals(keyValues(event).get(BusinessTelemetry.EVENT_NAME_KEY)))
                .toList();
    }

    public static Map<String, Object> keyValues(ILoggingEvent event) {
        List<KeyValuePair> pairs = event.getKeyValuePairs();
        if (pairs == null) {
            return Map.of();
        }
        Map<String, Object> byKey = new LinkedHashMap<>();
        pairs.forEach(pair -> byKey.put(pair.key, pair.value));
        return byKey;
    }

    /**
     * Every string an exporter could carry for this event: message pattern,
     * formatted message, logger name, key-value keys and values, MDC entries,
     * argument array and the whole throwable chain. Canary assertions run over
     * this, never over the formatted message alone.
     */
    public static String allSurfaces(ILoggingEvent event) {
        StringBuilder text = new StringBuilder()
                .append(event.getLoggerName()).append('\n')
                .append(event.getMessage()).append('\n')
                .append(event.getFormattedMessage()).append('\n')
                .append(keyValues(event).entrySet().stream()
                        .map(entry -> entry.getKey() + "=" + entry.getValue())
                        .collect(Collectors.joining("\n")))
                .append('\n')
                .append(event.getMDCPropertyMap())
                .append('\n');
        Object[] arguments = event.getArgumentArray();
        if (arguments != null) {
            for (Object argument : arguments) {
                text.append(argument).append('\n');
            }
        }
        for (var proxy = event.getThrowableProxy(); proxy != null; proxy = proxy.getCause()) {
            text.append(proxy.getClassName()).append('\n').append(proxy.getMessage()).append('\n');
            for (var frame : proxy.getStackTraceElementProxyArray()) {
                text.append(frame.getSTEAsString()).append('\n');
            }
        }
        return text.toString();
    }

    @Override
    public void close() {
        root.detachAppender(appender);
        appender.stop();
        root.setLevel(previousLevel);
    }
}
