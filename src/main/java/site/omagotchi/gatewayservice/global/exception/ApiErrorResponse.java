package site.omagotchi.gatewayservice.global.exception;

public record ApiErrorResponse(
        String code,
        String message,
        String path,
        String requestId
) {
}
