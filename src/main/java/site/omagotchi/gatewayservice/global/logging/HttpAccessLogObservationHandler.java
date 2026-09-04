package site.omagotchi.gatewayservice.global.logging;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.handler.TracingObservationHandler;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.observation.ServerRequestObservationContext;
import org.springframework.stereotype.Component;
import site.omagotchi.gatewayservice.global.requestid.RequestId;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

/**
 * Spring WebFlux HTTP Observation 종료 시점의 요청별 접근 이벤트 기록.
 *
 * <ul>
 *     <li>제외 대상: 생존 확인 요청</li>
 *     <li>경로 표현: 원본 URI 대신 Gateway 라우트 ID</li>
 *     <li>오류 표현: 예외 메시지·스택 트레이스를 제외한 상태와 예외 유형</li>
 * </ul>
 */
@Slf4j
@Component
public class HttpAccessLogObservationHandler implements ObservationHandler<ServerRequestObservationContext> {

    private static final String DATASET = "gateway-service.http";
    private static final String ACTION = "http.server.request.completed";

    private static final String STARTED_AT_NANOS =
            HttpAccessLogObservationHandler.class.getName() + ".startedAtNanos";

    @Override
    public void onStart(ServerRequestObservationContext context) {
        context.put(STARTED_AT_NANOS, System.nanoTime());
    }

    @Override
    public void onStop(ServerRequestObservationContext context) {
        ServerHttpRequest request = context.getCarrier();
        if (request == null) {
            return;
        }

        String path = request.getPath().value();
        // 주기적인 생존 확인으로 발생하는 접근 이벤트 제외
        if (path.equals("/actuator/health") || path.startsWith("/actuator/health/")) {
            return;
        }

        // 연결 중단 여부를 반영한 응답 상태와 전체 요청 처리 시간 확정
        Integer statusCode = resolveStatusCode(context);
        Long startedAtNanos = context.get(STARTED_AT_NANOS);
        long duration = startedAtNanos == null
                ? 0L
                : Math.max(0L, System.nanoTime() - startedAtNanos);
        LoggingEventBuilder event = statusCode != null && statusCode >= 500
                ? log.atError()
                : log.atInfo();

        event = event.addKeyValue("event.dataset", DATASET)
                .addKeyValue("event.action", ACTION)
                .addKeyValue("event.outcome", resolveOutcome(context, statusCode))
                .addKeyValue("event.duration", duration)
                .addKeyValue("http.request.method", request.getMethod().name());

        RequestId requestId = (RequestId) context.getAttributes().get(RequestId.ATTRIBUTE_NAME);
        if (requestId != null) {
            event = event.addKeyValue("http.request.id", requestId.value());
        }
        if (statusCode != null) {
            event = event.addKeyValue("http.response.status_code", statusCode);
        }

        // 사용자 입력 URI 대신 정해진 Gateway 라우트 식별자 기록
        Route route = (Route) context.getAttributes().get(GATEWAY_ROUTE_ATTR);
        if (route != null) {
            event = event.addKeyValue("gateway.route.id", route.getId());
        }
        if (context.getError() != null) {
            event = event.addKeyValue("error.type", context.getError().getClass().getName());
        }

        // 리액티브 요청의 관측 컨텍스트에 보관된 현재 Span 조회
        TracingObservationHandler.TracingContext tracingContext =
                context.get(TracingObservationHandler.TracingContext.class);
        Span span = tracingContext == null ? null : tracingContext.getSpan();
        if (span != null) {
            event = event
                    .addKeyValue("trace.id", span.context().traceId())
                    .addKeyValue("span.id", span.context().spanId());
        }

        event.log("HTTP request completed");
    }

    @Override
    public boolean supportsContext(Observation.Context context) {
        return context instanceof ServerRequestObservationContext;
    }

    private static Integer resolveStatusCode(ServerRequestObservationContext context) {
        if (context.isConnectionAborted() && !context.getResponse().isCommitted()) {
            return null;
        }
        HttpStatusCode status = context.getResponse().getStatusCode();
        if (status != null) {
            return status.value();
        }
        return context.getError() == null ? 200 : 500;
    }

    private static String resolveOutcome(
            ServerRequestObservationContext context,
            Integer statusCode
    ) {
        if (context.isConnectionAborted() || statusCode == null) {
            return "unknown";
        }
        return context.getError() != null || statusCode >= 400 ? "failure" : "success";
    }
}
