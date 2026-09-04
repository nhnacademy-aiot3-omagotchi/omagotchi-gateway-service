package site.omagotchi.gatewayservice.global.security;

import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NullMarked;
import org.springframework.http.HttpStatus;
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
import site.omagotchi.gatewayservice.global.exception.ApiErrorResponseWriter;
import site.omagotchi.gatewayservice.global.exception.CommonErrorCode;
import site.omagotchi.gatewayservice.global.exception.ErrorCode;
import site.omagotchi.gatewayservice.global.requestid.RequestId;

import java.util.function.IntFunction;

/**
 * Spring Security Bearer 처리 결과를 보존한 인증·인가 실패의 공통 JSON 응답.
 *
 * <ul>
 *     <li>Spring Security 책임: 상태와 {@code WWW-Authenticate} 헤더 결정</li>
 *     <li>서비스 책임: 공통 오류 본문과 Request ID 기록</li>
 * </ul>
 */
@Component
@NullMarked
@RequiredArgsConstructor
public class SecurityErrorResponseHandler implements ServerAuthenticationEntryPoint, ServerAccessDeniedHandler {

    private final ApiErrorResponseWriter responseWriter;
    private final BearerTokenServerAuthenticationEntryPoint bearerTokenAuthenticationEntryPoint =
            new BearerTokenServerAuthenticationEntryPoint();
    private final BearerTokenServerAccessDeniedHandler bearerTokenAccessDeniedHandler =
            new BearerTokenServerAccessDeniedHandler();

    @Override
    public Mono<Void> commence(
            ServerWebExchange exchange,
            AuthenticationException exception
    ) {
        // 인증 실패 상태와 WWW-Authenticate 헤더 결정을 기존 Bearer 처리기에 위임
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
        // 인가 실패 상태와 WWW-Authenticate 헤더 결정을 기존 Bearer 처리기에 위임
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
        // Bearer 처리기의 상태·WWW-Authenticate 결정 이후 빈 완료 신호의 JSON 본문 교체
        ServerHttpResponse response = new ServerHttpResponseDecorator(exchange.getResponse()) {
            @Override
            public Mono<Void> setComplete() {
                int status = getStatusCode() == null
                        ? HttpStatus.INTERNAL_SERVER_ERROR.value()
                        : getStatusCode().value();
                return responseWriter.write(
                        this,
                        errorCodeResolver.apply(status),
                        exchange.getRequest().getPath().value(),
                        exchange.<RequestId>getRequiredAttribute(RequestId.ATTRIBUTE_NAME).value()
                );
            }
        };
        return exchange.mutate().response(response).build();
    }
}
