package site.omagotchi.gatewayservice.global.logging;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.export.prometheus.PrometheusMetricsExportAutoConfiguration;
import org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration;
import org.springframework.boot.micrometer.tracing.autoconfigure.MicrometerTracingAutoConfiguration;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.OpenTelemetryTracingAutoConfiguration;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.otlp.OtlpTracingAutoConfiguration;
import org.springframework.boot.opentelemetry.autoconfigure.OpenTelemetrySdkAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("애플리케이션 설정의 메트릭·Trace 연결")
class TelemetryConfigurationTest {

    @Test
    @DisplayName("동일 요청의 Histogram·부모 자식 Span 생성과 전송 전 원문 제외")
    void recordsMetricsAndRelatedSpans() {
        // Given: 실제 설정과 자동 구성, Network 전송만 대체한 Exporter
        List<SpanData> spans = new CopyOnWriteArrayList<>();
        SpanExporter exporter = mock(SpanExporter.class);
        when(exporter.export(anyCollection())).thenAnswer(invocation -> {
            spans.addAll(invocation.<Collection<SpanData>>getArgument(0));
            return CompletableResultCode.ofSuccess();
        });
        when(exporter.shutdown()).thenReturn(CompletableResultCode.ofSuccess());
        when(exporter.flush()).thenReturn(CompletableResultCode.ofSuccess());

        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(
                        MetricsAutoConfiguration.class, CompositeMeterRegistryAutoConfiguration.class,
                        PrometheusMetricsExportAutoConfiguration.class, ObservationAutoConfiguration.class,
                        MicrometerTracingAutoConfiguration.class, OpenTelemetrySdkAutoConfiguration.class,
                        OpenTelemetryTracingAutoConfiguration.class, OtlpTracingAutoConfiguration.class))
                .withUserConfiguration(TraceAttributeFilter.class)
                .withBean(SpanExporter.class, () -> exporter)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ObservationRegistry registry = context.getBean(ObservationRegistry.class);

                    // When
                    Observation.createNotStarted("http.server.requests", registry)
                            .lowCardinalityKeyValue("uri", "/probe/{id}")
                            .highCardinalityKeyValue("http.url", "https://example.test/?token=secret")
                            .observe(() -> Observation.createNotStarted("probe.child", registry).observe(() -> {}));
                    context.getBean(SdkTracerProvider.class).forceFlush().join(5, TimeUnit.SECONDS);

                    // Then
                    SpanData parent = spans.stream().filter(span -> span.getName().equals("http.server.requests"))
                            .findFirst().orElseThrow();
                    SpanData child = spans.stream().filter(span -> span.getName().equals("probe.child"))
                            .findFirst().orElseThrow();
                    assertThat(child.getTraceId()).isEqualTo(parent.getTraceId());
                    assertThat(child.getParentSpanId()).isEqualTo(parent.getSpanId());
                    assertThat(parent.getAttributes().asMap().toString()).doesNotContain("secret", "http.url");
                    String scrape = context.getBean(PrometheusMeterRegistry.class).scrape();
                    assertThat(scrape).contains("http_server_requests_seconds_bucket", "/probe/{id}")
                            .doesNotContain("secret");
                });
    }
}
