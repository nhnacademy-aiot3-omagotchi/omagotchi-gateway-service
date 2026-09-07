package site.omagotchi.gatewayservice.global.logging;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationFilter;
import org.springframework.stereotype.Component;

import java.util.Set;

/** Trace 전송 전 원본 URL·SQL·대화·도구 입력 등 고카디널리티 원문 제외. */
@Component
public class TraceAttributeFilter implements ObservationFilter {

    // 값이 제거된 쿼리 요약·오류 종류·사용량만 허용, 추가 항목은 Collector 정책과 함께 검토
    private static final Set<String> ALLOWED_KEYS = Set.of(
            "db.query.summary", "error.type",
            "gen_ai.usage.input_tokens", "gen_ai.usage.output_tokens", "gen_ai.usage.total_tokens"
    );

    @Override
    public Observation.Context map(Observation.Context context) {
        for (KeyValue keyValue : context.getHighCardinalityKeyValues()) {
            if (!ALLOWED_KEYS.contains(keyValue.getKey())) {
                context.removeHighCardinalityKeyValue(keyValue.getKey());
            }
        }
        return context;
    }
}
