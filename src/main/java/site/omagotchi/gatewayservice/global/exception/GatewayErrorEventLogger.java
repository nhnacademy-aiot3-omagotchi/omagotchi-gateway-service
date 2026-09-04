package site.omagotchi.gatewayservice.global.exception;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.handler.TracingObservationHandler;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.http.server.reactive.observation.ServerRequestObservationContext;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import site.omagotchi.gatewayservice.global.requestid.RequestId;

import java.util.UUID;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

/**
 * Gateway 내부 오류의 중앙 검색용 이벤트와 로컬 진단 이벤트 기록.
 *
 * <ul>
 *     <li>오류 이벤트: 중앙 검색과 Telegram 알림에 필요한 안전한 필드</li>
 *     <li>진단 이벤트: 같은 {@code event.id}로 연결된 원본 예외와 스택 트레이스</li>
 * </ul>
 */
@Slf4j
@Component
public class GatewayErrorEventLogger {

    /** 같은 요청에서 여러 오류 처리기가 실행될 때의 중복 기록 방지 표식. */
    private static final String RECORDED_ATTRIBUTE =
            GatewayErrorEventLogger.class.getName() + ".recorded";

    /** 같은 요청의 첫 번째 Gateway 내부 처리 실패 기록. */
    void logOnce(
            ServerWebExchange exchange,
            Throwable failure,
            ErrorCode errorCode,
            int responseStatusCode
    ) {
        if (exchange.getAttributes().putIfAbsent(RECORDED_ATTRIBUTE, Boolean.TRUE) != null) {
            return;
        }

        // 중앙 오류 이벤트와 로컬 진단 이벤트의 연결 식별자
        String eventId = UUID.randomUUID().toString();
        RequestId requestId = exchange.getRequiredAttribute(RequestId.ATTRIBUTE_NAME);
        LoggingEventBuilder event = log.atError()
                .addKeyValue("event.id", eventId)
                .addKeyValue("event.dataset", "gateway-service.error")
                .addKeyValue("event.action", "http.server.request.failed")
                .addKeyValue("event.outcome", "failure")
                .addKeyValue("error.code", errorCode.code())
                .addKeyValue("error.type", failure.getClass().getName())
                .addKeyValue("http.request.id", requestId.value())
                .addKeyValue("http.request.method", exchange.getRequest().getMethod().name())
                .addKeyValue("http.response.status_code", responseStatusCode);

        Route route = exchange.getAttribute(GATEWAY_ROUTE_ATTR);
        if (route != null) {
            event = event.addKeyValue("gateway.route.id", route.getId());
        }

        // ThreadLocal 대신 WebFlux 요청의 관측 컨텍스트에 보관된 Span 사용
        Span span = ServerRequestObservationContext.findCurrent(exchange.getAttributes())
                .map(context -> context.get(TracingObservationHandler.TracingContext.class))
                .filter(TracingObservationHandler.TracingContext.class::isInstance)
                .map(TracingObservationHandler.TracingContext.class::cast)
                .map(TracingObservationHandler.TracingContext::getSpan)
                .orElse(null);
        if (span != null) {
            event = addTraceFields(event, span);
        }
        event.log("Gateway request failed");

        // 원본 예외가 필요한 예상 밖의 내부 오류만 별도 진단 이벤트로 기록
        if (errorCode == CommonErrorCode.INTERNAL_SERVER_ERROR) {
            LoggingEventBuilder diagnostic = log.atError()
                    .addKeyValue("event.id", eventId)
                    .addKeyValue("event.dataset", "gateway-service.diagnostic")
                    .addKeyValue("event.action", "http.server.request.failed")
                    .addKeyValue("event.outcome", "failure")
                    .addKeyValue("http.request.id", requestId.value());
            if (span != null) {
                diagnostic = addTraceFields(diagnostic, span);
            }
            diagnostic.setCause(failure).log("Unexpected gateway failure diagnostic");
        }
    }

    private static LoggingEventBuilder addTraceFields(LoggingEventBuilder event, Span span) {
        return event
                .addKeyValue("trace.id", span.context().traceId())
                .addKeyValue("span.id", span.context().spanId());
    }
}
