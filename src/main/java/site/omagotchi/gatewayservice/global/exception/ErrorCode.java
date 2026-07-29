package site.omagotchi.gatewayservice.global.exception;

public interface ErrorCode {

    ErrorType type();

    String code();

    String message();
}
