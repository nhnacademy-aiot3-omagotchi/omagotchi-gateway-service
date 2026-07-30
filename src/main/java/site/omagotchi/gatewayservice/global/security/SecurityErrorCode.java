package site.omagotchi.gatewayservice.global.security;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;
import site.omagotchi.gatewayservice.global.exception.ErrorCode;
import site.omagotchi.gatewayservice.global.exception.ErrorType;

@Getter
@Accessors(fluent = true)
@RequiredArgsConstructor
public enum SecurityErrorCode implements ErrorCode {

    AUTHENTICATION_REQUIRED(
            ErrorType.AUTHENTICATION,
            "AUTH_AUTHENTICATION_REQUIRED",
            "인증이 필요합니다."
    ),
    ACCESS_DENIED(
            ErrorType.AUTHORIZATION,
            "AUTH_ACCESS_DENIED",
            "접근 권한이 없습니다."
    );

    private final ErrorType type;
    private final String code;
    private final String message;
}
