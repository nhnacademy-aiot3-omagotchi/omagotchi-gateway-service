package site.omagotchi.gatewayservice.global.exception;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;
import reactor.test.StepVerifier;
import site.omagotchi.gatewayservice.global.requestid.RequestId;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.BDDAssertions.then;

class GatewayErrorWebExceptionHandlerTest {

    private static final String REQUEST_ID = "0123456789abcdef0123456789abcdef";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final GatewayErrorWebExceptionHandler handler = new GatewayErrorWebExceptionHandler(
            new ApiErrorResponseWriter(objectMapper),
            new GatewayErrorEventLogger()
    );

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("supportedStatuses")
    @DisplayName("Gateway가 생성한 지원 상태를 공통 오류 응답으로 변환")
    void writesCommonErrorResponse(HttpStatus status, String expectedCode) {
        // Given
        MockServerWebExchange exchange = exchange();
        ResponseStatusException failure = new ResponseStatusException(status);

        // When
        List<ILoggingEvent> errorEvents = captureErrorEvents(
                () -> handler.handle(exchange, failure).block()
        );

        // Then
        JsonNode body = objectMapper.readTree(exchange.getResponse().getBodyAsString().block());
        then(exchange.getResponse().getStatusCode()).isEqualTo(status);
        then(body.get("code").asString()).isEqualTo(expectedCode);
        then(body.get("path").asString()).isEqualTo("/api/v1/rules");
        then(body.get("requestId").asString()).isEqualTo(REQUEST_ID);
        if (status.is5xxServerError()) {
            then(errorEvents).singleElement().satisfies(event -> {
                then(field(event, "error.code")).isEqualTo(expectedCode);
                then(field(event, "http.response.status_code")).isEqualTo(status.value());
            });
        } else {
            then(errorEvents).isEmpty();
        }
    }

    @Test
    @DisplayName("그 밖의 Gateway 4xx도 상태를 유지한 공통 오류 응답으로 변환")
    void writesCommonErrorResponseForOtherClientError() {
        // Given
        MockServerWebExchange exchange = exchange();
        ResponseStatusException failure = new ResponseStatusException(HttpStatusCode.valueOf(418));

        // When
        List<ILoggingEvent> errorEvents = captureErrorEvents(
                () -> handler.handle(exchange, failure).block()
        );

        // Then
        JsonNode body = objectMapper.readTree(exchange.getResponse().getBodyAsString().block());
        then(exchange.getResponse().getStatusCode().value()).isEqualTo(418);
        then(body.get("code").asString()).isEqualTo("COMMON_INVALID_REQUEST");
        then(body.get("path").asString()).isEqualTo("/api/v1/rules");
        then(body.get("requestId").asString()).isEqualTo(REQUEST_ID);
        then(errorEvents).isEmpty();
    }

    @Test
    @DisplayName("HTTP 오류가 아닌 상태는 다음 오류 처리기로 전달")
    void delegatesNonErrorStatus() {
        // Given
        MockServerWebExchange exchange = exchange();
        ResponseStatusException failure = new ResponseStatusException(HttpStatus.TEMPORARY_REDIRECT);

        // When & Then
        StepVerifier.create(handler.handle(exchange, failure))
                .expectErrorSatisfies(actual -> then(actual).isSameAs(failure))
                .verify();
    }

    @Test
    @DisplayName("JVM Error는 HTTP 응답으로 변환하지 않음")
    void delegatesJvmError() {
        // Given
        MockServerWebExchange exchange = exchange();
        AssertionError failure = new AssertionError("fatal");

        // When & Then
        StepVerifier.create(handler.handle(exchange, failure))
                .expectErrorSatisfies(actual -> then(actual).isSameAs(failure))
                .verify();
    }

    @Test
    @DisplayName("알려지지 않은 5xx는 안전한 500 내부 오류로 정규화")
    void normalizesUnknownServerError() {
        // Given
        MockServerWebExchange exchange = exchange();
        ResponseStatusException failure = new ResponseStatusException(
                HttpStatusCode.valueOf(507),
                "private-detail"
        );

        // When
        List<ILoggingEvent> errorEvents = captureErrorEvents(
                () -> handler.handle(exchange, failure).block()
        );

        // Then
        JsonNode body = objectMapper.readTree(exchange.getResponse().getBodyAsString().block());
        then(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        then(body.get("code").asString()).isEqualTo("COMMON_INTERNAL_SERVER_ERROR");
        then(body.get("message").asString()).isEqualTo("서버 내부 오류가 발생했습니다.");
        then(body.get("requestId").asString()).isEqualTo(REQUEST_ID);
        then(errorEvents).singleElement().satisfies(event -> {
            then(field(event, "error.code")).isEqualTo("COMMON_INTERNAL_SERVER_ERROR");
            then(field(event, "http.response.status_code"))
                    .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
        });
    }

    private static Stream<Arguments> supportedStatuses() {
        return Stream.of(
                Arguments.of(HttpStatus.BAD_REQUEST, "COMMON_INVALID_REQUEST"),
                Arguments.of(HttpStatus.UNAUTHORIZED, "AUTH_AUTHENTICATION_REQUIRED"),
                Arguments.of(HttpStatus.FORBIDDEN, "AUTH_ACCESS_DENIED"),
                Arguments.of(HttpStatus.NOT_FOUND, "COMMON_NOT_FOUND"),
                Arguments.of(HttpStatus.METHOD_NOT_ALLOWED, "COMMON_METHOD_NOT_ALLOWED"),
                Arguments.of(HttpStatus.NOT_ACCEPTABLE, "COMMON_NOT_ACCEPTABLE"),
                Arguments.of(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "COMMON_UNSUPPORTED_MEDIA_TYPE"),
                Arguments.of(HttpStatus.INTERNAL_SERVER_ERROR, "COMMON_INTERNAL_SERVER_ERROR"),
                Arguments.of(HttpStatus.BAD_GATEWAY, "COMMON_BAD_GATEWAY"),
                Arguments.of(HttpStatus.SERVICE_UNAVAILABLE, "COMMON_SERVICE_UNAVAILABLE"),
                Arguments.of(HttpStatus.GATEWAY_TIMEOUT, "COMMON_GATEWAY_TIMEOUT")
        );
    }

    private static MockServerWebExchange exchange() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/rules")
        );
        exchange.getAttributes().put(RequestId.ATTRIBUTE_NAME, new RequestId(REQUEST_ID));
        return exchange;
    }

    private static List<ILoggingEvent> captureErrorEvents(Runnable action) {
        Logger logger = (Logger) LoggerFactory.getLogger(GatewayErrorEventLogger.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        boolean additive = logger.isAdditive();
        appender.start();
        logger.addAppender(appender);
        logger.setAdditive(false);

        try {
            action.run();
            return appender.list.stream()
                    .filter(event -> "gateway-service.error".equals(
                            field(event, "event.dataset")
                    ))
                    .toList();
        } finally {
            logger.setAdditive(additive);
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    private static Object field(ILoggingEvent event, String key) {
        return event.getKeyValuePairs().stream()
                .filter(pair -> pair.key.equals(key))
                .findFirst()
                .orElseThrow()
                .value;
    }
}
