package site.omagotchi.gatewayservice.global.logging;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Trace 원문 제외")
class TraceAttributeFilterTest {

    @Test
    @DisplayName("원본 URL과 임의 속성 제외, 쿼리 요약과 HTTP 집계 Label 유지")
    void removesRawValuesWithoutChangingMetricLabels() {
        // Given
        Observation.Context context = new Observation.Context()
                .addHighCardinalityKeyValue(KeyValue.of("http.url", "https://example.test/?serviceKey=secret"))
                .addHighCardinalityKeyValue(KeyValue.of("unknown.input", "secret"))
                .addHighCardinalityKeyValue(KeyValue.of("db.query.summary", "SELECT accounts"))
                .addLowCardinalityKeyValue(KeyValue.of("uri", "/api/items/{id}"));

        // When
        new TraceAttributeFilter().map(context);

        // Then
        assertThat(context.getHighCardinalityKeyValues())
                .containsExactly(KeyValue.of("db.query.summary", "SELECT accounts"));
        assertThat(context.getLowCardinalityKeyValue("uri"))
                .isEqualTo(KeyValue.of("uri", "/api/items/{id}"));
    }
}
