package site.omagotchi.gatewayservice.global.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

@Getter
@Accessors(fluent = true)
@RequiredArgsConstructor
public enum CommonErrorCode implements ErrorCode {

    INVALID_REQUEST(
            ErrorType.INVALID_INPUT,
            "COMMON_INVALID_REQUEST",
            "요청값이 올바르지 않습니다."
    ),
    NOT_FOUND(
            ErrorType.NOT_FOUND,
            "COMMON_NOT_FOUND",
            "요청한 리소스를 찾을 수 없습니다."
    ),
    METHOD_NOT_ALLOWED(
            ErrorType.METHOD_NOT_ALLOWED,
            "COMMON_METHOD_NOT_ALLOWED",
            "지원하지 않는 요청 방식입니다."
    ),
    NOT_ACCEPTABLE(
            ErrorType.NOT_ACCEPTABLE,
            "COMMON_NOT_ACCEPTABLE",
            "요청한 응답 형식을 제공할 수 없습니다."
    ),
    UNSUPPORTED_MEDIA_TYPE(
            ErrorType.UNSUPPORTED_MEDIA_TYPE,
            "COMMON_UNSUPPORTED_MEDIA_TYPE",
            "지원하지 않는 요청 본문 형식입니다."
    ),
    BAD_GATEWAY(
            ErrorType.BAD_GATEWAY,
            "COMMON_BAD_GATEWAY",
            "연결된 서비스가 올바르게 응답하지 않았습니다."
    ),
    SERVICE_UNAVAILABLE(
            ErrorType.SERVICE_UNAVAILABLE,
            "COMMON_SERVICE_UNAVAILABLE",
            "서비스를 일시적으로 사용할 수 없습니다."
    ),
    GATEWAY_TIMEOUT(
            ErrorType.GATEWAY_TIMEOUT,
            "COMMON_GATEWAY_TIMEOUT",
            "연결된 서비스의 응답 시간이 초과되었습니다."
    ),
    INTERNAL_SERVER_ERROR(
            ErrorType.INTERNAL,
            "COMMON_INTERNAL_SERVER_ERROR",
            "서버 내부 오류가 발생했습니다."
    );

    private final ErrorType type;
    private final String code;
    private final String message;
}
