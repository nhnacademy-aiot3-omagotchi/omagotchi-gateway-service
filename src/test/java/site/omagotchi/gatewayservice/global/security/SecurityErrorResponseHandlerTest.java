package site.omagotchi.gatewayservice.global.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.server.resource.BearerTokenErrors;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.BDDAssertions.then;

class SecurityErrorResponseHandlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SecurityErrorResponseHandler handler = new SecurityErrorResponseHandler(objectMapper);

    @Test
    @DisplayName("미인증 요청의 401 상태와 Header 및 공통 오류 Code 유지")
    void writesAuthenticationRequiredResponse() {
        // Given
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/users/me")
        );

        // When
        handler.commence(
                exchange,
                new AuthenticationCredentialsNotFoundException("인증 없음")
        ).block();

        // Then
        JsonNode body = objectMapper.readTree(exchange.getResponse().getBodyAsString().block());

        then(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        then(exchange.getResponse().getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE))
                .isEqualTo("Bearer");
        then(body.get("code").asString()).isEqualTo("AUTH_AUTHENTICATION_REQUIRED");
        then(body.get("path").asString()).isEqualTo("/api/v1/users/me");
    }

    @Test
    @DisplayName("Bearer 잘못된 요청의 400 상태와 Header 및 공통 오류 Code 유지")
    void preservesBearerInvalidRequestStatus() {
        // Given
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/users/me")
        );
        OAuth2AuthenticationException exception = new OAuth2AuthenticationException(
                BearerTokenErrors.invalidRequest("잘못된 Bearer 요청")
        );

        // When
        handler.commence(exchange, exception).block();

        // Then
        JsonNode body = objectMapper.readTree(exchange.getResponse().getBodyAsString().block());

        then(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        then(exchange.getResponse().getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE))
                .startsWith("Bearer")
                .contains("error=\"invalid_request\"");
        then(body.get("code").asString()).isEqualTo("COMMON_INVALID_REQUEST");
        then(body.get("path").asString()).isEqualTo("/api/v1/users/me");
    }

}
