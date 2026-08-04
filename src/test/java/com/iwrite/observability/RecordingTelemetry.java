package com.iwrite.observability;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.HistogramPointData;
import io.opentelemetry.sdk.metrics.data.LongPointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.data.PointData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

import java.util.Collection;
import java.util.List;

/**
 * Test-only OpenTelemetry SDK with in-memory exporters, wrapping a real
 * {@link BusinessTelemetry}. Production never builds an SDK: it uses the
 * global instance installed by the Java Agent.
 */
public final class RecordingTelemetry implements AutoCloseable {

    private final InMemorySpanExporter spanExporter = InMemorySpanExporter.create();
    private final InMemoryMetricReader metricReader = InMemoryMetricReader.create();
    private final OpenTelemetrySdk sdk;
    private final BusinessTelemetry businessTelemetry;

    public RecordingTelemetry() {
        this.sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(SdkTracerProvider.builder()
                        .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
                        .build())
                .setMeterProvider(SdkMeterProvider.builder()
                        .registerMetricReader(metricReader)
                        .build())
                .build();
        this.businessTelemetry = new BusinessTelemetry(sdk);
    }

    public BusinessTelemetry telemetry() {
        return businessTelemetry;
    }

    public OpenTelemetrySdk sdk() {
        return sdk;
    }

    public List<SpanData> spans() {
        return spanExporter.getFinishedSpanItems();
    }

    public SpanData span(String name) {
        List<SpanData> matches = spans().stream().filter(span -> span.getName().equals(name)).toList();
        if (matches.size() != 1) {
            throw new AssertionError("expected exactly one span named " + name + " but found " + matches.size());
        }
        return matches.getFirst();
    }

    public long counterValue(String operation, String result) {
        return points(BusinessTelemetry.METRIC_OPERATION_COUNT).stream()
                .filter(point -> matches(point.getAttributes(), operation, result))
                .mapToLong(point -> ((LongPointData) point).getValue())
                .sum();
    }

    public long durationCount(String operation, String result) {
        return points(BusinessTelemetry.METRIC_OPERATION_DURATION).stream()
                .filter(point -> matches(point.getAttributes(), operation, result))
                .mapToLong(point -> ((HistogramPointData) point).getCount())
                .sum();
    }

    public double durationSum(String operation, String result) {
        return points(BusinessTelemetry.METRIC_OPERATION_DURATION).stream()
                .filter(point -> matches(point.getAttributes(), operation, result))
                .mapToDouble(point -> ((HistogramPointData) point).getSum())
                .sum();
    }

    /** Every label set published for a metric, so tests can assert cardinality. */
    public List<Attributes> labels(String metricName) {
        return points(metricName).stream().map(PointData::getAttributes).toList();
    }

    public String durationUnit() {
        return metric(BusinessTelemetry.METRIC_OPERATION_DURATION).getUnit();
    }

    public void reset() {
        spanExporter.reset();
    }

    @Override
    public void close() {
        sdk.close();
    }

    private Collection<? extends PointData> points(String metricName) {
        return metric(metricName).getData().getPoints();
    }

    private MetricData metric(String metricName) {
        return metricReader.collectAllMetrics().stream()
                .filter(metric -> metric.getName().equals(metricName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("metric not recorded: " + metricName));
    }

    private static boolean matches(Attributes attributes, String operation, String result) {
        return operation.equals(attributes.get(AttributeKey.stringKey("operation")))
                && result.equals(attributes.get(AttributeKey.stringKey("result")));
    }
}
