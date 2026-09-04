package site.omagotchi.gatewayservice.global.exception;

import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.webflux.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.ErrorResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import site.omagotchi.gatewayservice.global.requestid.RequestId;
import site.omagotchi.gatewayservice.global.security.SecurityErrorCode;

/**
 * Gateway 라우팅·필터 오류의 공통 API 응답 변환.
 *
 * <ul>
 *     <li>처리: 상태가 확정된 HTTP 4xx·5xx 오류</li>
 *     <li>위임: JVM {@link Error}, 이미 전송된 응답, HTTP 오류가 아닌 상태</li>
 * </ul>
 */
@Component
// Spring Boot 기본 오류 처리보다 앞선 Gateway 오류 응답 변환 순서
@Order(-2)
@RequiredArgsConstructor
public class GatewayErrorWebExceptionHandler implements ErrorWebExceptionHandler {

    private final ApiErrorResponseWriter responseWriter;
    private final GatewayErrorEventLogger errorEventLogger;

    @Override
    public Mono<Void> handle(
            @NonNull ServerWebExchange exchange,
            @NonNull Throwable failure
    ) {
        // 안전한 새 응답 작성이 불가능한 실패의 다음 오류 처리기로 위임
        if (failure instanceof Error || exchange.getResponse().isCommitted()) {
            return Mono.error(failure);
        }

        // Spring 예외의 HTTP 상태 보존과 일반 예외의 안전한 500 변환
        HttpStatusCode failureStatus = failure instanceof ErrorResponse errorResponse
                ? errorResponse.getStatusCode()
                : HttpStatus.INTERNAL_SERVER_ERROR;
        ErrorCode errorCode = resolveErrorCode(failureStatus);
        if (errorCode == null) {
            return Mono.error(failure);
        }

        HttpStatusCode responseStatus = errorCode == CommonErrorCode.INTERNAL_SERVER_ERROR
                ? HttpStatus.INTERNAL_SERVER_ERROR
                : failureStatus;
        // 원래 상태를 유지하는 Spring 오류의 응답 헤더 보존
        if (failure instanceof ErrorResponse errorResponse && responseStatus.equals(failureStatus)) {
            exchange.getResponse().getHeaders().putAll(errorResponse.getHeaders());
        }
        exchange.getResponse().setStatusCode(responseStatus);

        RequestId requestId = exchange.getRequiredAttribute(RequestId.ATTRIBUTE_NAME);
        // 운영 확인이 필요한 5xx만 오류 이벤트 기록
        if (responseStatus.is5xxServerError()) {
            errorEventLogger.logOnce(exchange, failure, errorCode, responseStatus.value());
        }
        return responseWriter.write(
                exchange.getResponse(),
                errorCode,
                exchange.getRequest().getPath().value(),
                requestId.value()
        );
    }

    private static ErrorCode resolveErrorCode(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> CommonErrorCode.INVALID_REQUEST;
            case 401 -> SecurityErrorCode.AUTHENTICATION_REQUIRED;
            case 403 -> SecurityErrorCode.ACCESS_DENIED;
            case 404 -> CommonErrorCode.NOT_FOUND;
            case 405 -> CommonErrorCode.METHOD_NOT_ALLOWED;
            case 406 -> CommonErrorCode.NOT_ACCEPTABLE;
            case 415 -> CommonErrorCode.UNSUPPORTED_MEDIA_TYPE;
            case 500 -> CommonErrorCode.INTERNAL_SERVER_ERROR;
            case 502 -> CommonErrorCode.BAD_GATEWAY;
            case 503 -> CommonErrorCode.SERVICE_UNAVAILABLE;
            case 504 -> CommonErrorCode.GATEWAY_TIMEOUT;
            default -> {
                if (status.is4xxClientError()) {
                    yield CommonErrorCode.INVALID_REQUEST;
                }
                yield status.is5xxServerError()
                        ? CommonErrorCode.INTERNAL_SERVER_ERROR
                        : null;
            }
        };
    }
}
