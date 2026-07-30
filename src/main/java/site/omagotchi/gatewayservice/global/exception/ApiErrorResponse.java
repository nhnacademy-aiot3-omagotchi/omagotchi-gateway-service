package site.omagotchi.gatewayservice.global.exception;

public record ApiErrorResponse(
        int status,
        String code,
        String message,
        String path
) {
}
