package site.omagotchi.gatewayservice.global.security;

import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NullMarked;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.access.server.BearerTokenServerAccessDeniedHandler;
import org.springframework.security.oauth2.server.resource.web.server.BearerTokenServerAuthenticationEntryPoint;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import site.omagotchi.gatewayservice.global.exception.ApiErrorResponse;
import site.omagotchi.gatewayservice.global.exception.CommonErrorCode;
import site.omagotchi.gatewayservice.global.exception.ErrorCode;
import tools.jackson.databind.ObjectMapper;

import java.util.function.IntFunction;

// Controller 이전에 발생한 Security 예외를 공통 JSON 응답으로 변환
@Component
@NullMarked
@RequiredArgsConstructor
public class SecurityErrorResponseHandler implements ServerAuthenticationEntryPoint, ServerAccessDeniedHandler {

    private final ObjectMapper objectMapper;
    private final BearerTokenServerAuthenticationEntryPoint bearerTokenAuthenticationEntryPoint =
            new BearerTokenServerAuthenticationEntryPoint();
    private final BearerTokenServerAccessDeniedHandler bearerTokenAccessDeniedHandler =
            new BearerTokenServerAccessDeniedHandler();

    @Override
    public Mono<Void> commence(
            ServerWebExchange exchange,
            AuthenticationException exception
    ) {
        ServerWebExchange responseExchange = withJsonBody(
                exchange,
                status -> status == HttpStatus.BAD_REQUEST.value()
                        ? CommonErrorCode.INVALID_REQUEST
                        : SecurityErrorCode.AUTHENTICATION_REQUIRED
        );
        return bearerTokenAuthenticationEntryPoint.commence(responseExchange, exception);
    }

    @Override
    public Mono<Void> handle(
            ServerWebExchange exchange,
            AccessDeniedException exception
    ) {
        ServerWebExchange responseExchange = withJsonBody(
                exchange,
                status -> SecurityErrorCode.ACCESS_DENIED
        );
        return bearerTokenAccessDeniedHandler.handle(responseExchange, exception);
    }

    private ServerWebExchange withJsonBody(
            ServerWebExchange exchange,
            IntFunction<ErrorCode> errorCodeResolver
    ) {
        ServerHttpResponse response = new ServerHttpResponseDecorator(exchange.getResponse()) {
            @Override
            public Mono<Void> setComplete() {
                int status = getStatusCode() == null
                        ? HttpStatus.INTERNAL_SERVER_ERROR.value()
                        : getStatusCode().value();
                return write(
                        this,
                        status,
                        errorCodeResolver.apply(status),
                        exchange.getRequest().getPath().value()
                );
            }
        };
        return exchange.mutate().response(response).build();
    }

    private Mono<Void> write(
            ServerHttpResponse response,
            int status,
            ErrorCode errorCode,
            String path
    ) {
        ApiErrorResponse body = new ApiErrorResponse(
                status,
                errorCode.code(),
                errorCode.message(),
                path
        );

        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        return Mono.fromCallable(() -> objectMapper.writeValueAsBytes(body))
                .flatMap(bytes -> response.writeWith(Mono.just(
                        response.bufferFactory().wrap(bytes)
                )));
    }
}
