package site.omagotchi.gatewayservice;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.WebFilter;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import site.omagotchi.gatewayservice.global.requestid.RequestId;
import site.omagotchi.gatewayservice.global.security.TestJwtKeyConfig;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.function.Predicate;

import static org.assertj.core.api.BDDAssertions.then;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "logging.level.site.omagotchi.gatewayservice.global.logging.HttpAccessLogObservationHandler=INFO",
                "logging.structured.format.console=ecs",
                "logging.structured.ecs.service.environment=test",
                "logging.structured.json.stacktrace.max-length=4096"
        }
)
@ActiveProfiles("test")
@Import({TestJwtKeyConfig.class, GatewayStructuredLoggingIT.FailureProbeConfig.class})
@ExtendWith(OutputCaptureExtension.class)
class GatewayStructuredLoggingIT {

    private static final String LOOPBACK_HOST = "127.0.0.1";
    private static final String REQUEST_ID = "0123456789abcdef0123456789abcdef";
    private static final String ERROR_REQUEST_ID = "abcdef0123456789abcdef0123456789";
    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String TRACEPARENT = "00-" + TRACE_ID + "-00f067aa0ba902b7-01";
    private static final String REQUEST_SECRET = "must-not-appear-in-gateway-log";
    private static final String FAILURE_DETAIL = "local-diagnostic-detail";

    private static final ObjectMapper STRICT_JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();

    private static final DisposableServer DOWNSTREAM = HttpServer.create()
            .host(LOOPBACK_HOST)
            .port(0)
            .handle((request, response) -> {
                if (request.uri().startsWith("/api/v1/rules/server-error")) {
                    response.status(HttpStatus.SERVICE_UNAVAILABLE.value());
                }
                return request.receive()
                        .asString()
                        .then(Mono.from(response.sendString(Mono.just("ok"))));
            })
            .bindNow();

    @Value("${local.server.port}")
    private int port;

    private WebTestClient webTestClient;

    @DynamicPropertySource
    static void downstreamRoutes(DynamicPropertyRegistry registry) {
        registry.add("server.address", () -> LOOPBACK_HOST);
        registry.add("IDENTITY_SERVICE_URI", GatewayStructuredLoggingIT::downstreamUri);
        registry.add("LEARNING_SERVICE_URI", GatewayStructuredLoggingIT::downstreamUri);
        registry.add("RULE_SERVICE_URI", GatewayStructuredLoggingIT::downstreamUri);
    }

    @BeforeEach
    void setUp() {
        this.webTestClient = WebTestClient.bindToServer()
                .responseTimeout(Duration.ofSeconds(5))
                .baseUrl("http://" + LOOPBACK_HOST + ":" + this.port)
                .build();
    }

    @AfterAll
    static void stopDownstream() {
        DOWNSTREAM.disposeNow();
    }

    @Test
    @DisplayName("접근 이벤트에 안전한 HTTP·Request ID·Trace 필드 기록")
    void recordsSafeStructuredAccessEvent(CapturedOutput output) throws Exception {
        // Given
        String jwt = TestJwtKeyConfig.issue();

        // When
        this.webTestClient.post()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/v1/rules/" + REQUEST_SECRET)
                        .queryParam("secret", REQUEST_SECRET)
                        .build())
                .headers(headers -> {
                    headers.setBearerAuth(jwt);
                    headers.set(RequestId.HEADER_NAME, REQUEST_ID);
                    headers.set("traceparent", TRACEPARENT);
                    headers.set("Cookie", "session=" + REQUEST_SECRET);
                })
                .bodyValue(REQUEST_SECRET)
                .exchange()
                .expectStatus().isOk();

        // Then
        JsonNode event = onlyDatasetLog(output, "gateway-service.http", REQUEST_ID).json();
        then(event.at("/log/level").asString()).isEqualTo("INFO");
        then(event.at("/service/name").asString()).isEqualTo("gateway-service");
        then(event.at("/service/environment").asString()).isEqualTo("test");
        then(event.at("/event/outcome").asString()).isEqualTo("success");
        then(event.at("/event/duration").asLong()).isPositive();
        then(event.at("/trace/id").asString()).isEqualTo(TRACE_ID);
        then(event.at("/span/id").asString()).matches("^[0-9a-f]{16}$");
        then(event.at("/http/request/method").asString()).isEqualTo("POST");
        then(event.at("/http/response/status_code").asInt()).isEqualTo(200);
        then(event.at("/gateway/route/id").asString()).isEqualTo("rule-service-route");
        then(event.at("/url/path").isMissingNode()).isTrue();
        then(output.getAll()).doesNotContain(REQUEST_SECRET, jwt);
    }

    @Test
    @DisplayName("하위 서비스 5xx는 Gateway 오류 이벤트 없이 접근 이벤트만 기록")
    void recordsOnlyAccessEventForDownstreamServerError(CapturedOutput output) throws Exception {
        // Given
        String jwt = TestJwtKeyConfig.issue();

        // When
        this.webTestClient.get()
                .uri("/api/v1/rules/server-error")
                .headers(headers -> {
                    headers.setBearerAuth(jwt);
                    headers.set(RequestId.HEADER_NAME, ERROR_REQUEST_ID);
                })
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

        // Then
        JsonNode accessEvent = onlyDatasetLog(
                output,
                "gateway-service.http",
                ERROR_REQUEST_ID
        ).json();
        then(accessEvent.at("/log/level").asString()).isEqualTo("ERROR");
        then(accessEvent.at("/event/outcome").asString()).isEqualTo("failure");
        then(accessEvent.at("/http/response/status_code").asInt()).isEqualTo(503);
        then(jsonLogs(output))
                .filteredOn(event -> "gateway-service.error".equals(
                        event.json().at("/event/dataset").asString()
                ))
                .noneMatch(event -> ERROR_REQUEST_ID.equals(
                        event.json().at("/http/request/id").asString()
                ));
    }

    @Test
    @DisplayName("예상하지 못한 500의 검색 식별자와 로컬 예외 원인 기록")
    void recordsUnexpectedFailureWithLocalDiagnostic(CapturedOutput output) throws Exception {
        // Given
        String jwt = TestJwtKeyConfig.issue();

        // When
        this.webTestClient.get()
                .uri("/api/v1/rules")
                .headers(headers -> {
                    headers.setBearerAuth(jwt);
                    headers.set(RequestId.HEADER_NAME, ERROR_REQUEST_ID);
                    headers.set("traceparent", TRACEPARENT);
                    headers.set(FailureProbeConfig.HEADER_NAME, "enabled");
                })
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR)
                .expectHeader().valueEquals(RequestId.HEADER_NAME, ERROR_REQUEST_ID)
                .expectBody()
                .jsonPath("$.code").isEqualTo("COMMON_INTERNAL_SERVER_ERROR")
                .jsonPath("$.requestId").isEqualTo(ERROR_REQUEST_ID);

        // Then
        JsonNode accessEvent = onlyDatasetLog(
                output,
                "gateway-service.http",
                ERROR_REQUEST_ID
        ).json();
        JsonNode errorEvent = onlyDatasetLog(
                output,
                "gateway-service.error",
                ERROR_REQUEST_ID
        ).json();
        JsonNode diagnosticEvent = onlyDatasetLog(
                output,
                "gateway-service.diagnostic",
                ERROR_REQUEST_ID
        ).json();

        then(accessEvent.at("/http/response/status_code").asInt()).isEqualTo(500);
        then(errorEvent.at("/event/id").asString()).isNotBlank();
        then(errorEvent.at("/error/code").asString())
                .isEqualTo("COMMON_INTERNAL_SERVER_ERROR");
        then(errorEvent.at("/error/type").asString())
                .isEqualTo(IllegalStateException.class.getName());
        then(errorEvent.at("/trace/id").asString()).isEqualTo(TRACE_ID);
        then(errorEvent.at("/span/id").asString()).matches("^[0-9a-f]{16}$");
        then(diagnosticEvent.at("/event/id").asString())
                .isEqualTo(errorEvent.at("/event/id").asString());
        then(diagnosticEvent.at("/error/message").asString()).isEqualTo(FAILURE_DETAIL);
        then(diagnosticEvent.at("/error/stack_trace").asString()).contains(FAILURE_DETAIL);
        then(diagnosticEvent.at("/trace/id").asString()).isEqualTo(TRACE_ID);
    }

    private static CapturedLog onlyDatasetLog(
            CapturedOutput output,
            String dataset,
            String requestId
    ) throws Exception {
        List<CapturedLog> matching = waitForLogs(output, event ->
                dataset.equals(event.json().at("/event/dataset").asString())
                        && requestId.equals(event.json().at("/http/request/id").asString()));
        then(matching).singleElement();
        return matching.getFirst();
    }

    private static List<CapturedLog> waitForLogs(
            CapturedOutput output,
            Predicate<CapturedLog> predicate
    ) throws Exception {
        List<CapturedLog> matching = List.of();
        for (int attempt = 0; attempt < 100 && matching.isEmpty(); attempt++) {
            matching = jsonLogs(output).stream().filter(predicate).toList();
            if (matching.isEmpty()) {
                Thread.sleep(10);
            }
        }
        return matching;
    }

    private static List<CapturedLog> jsonLogs(CapturedOutput output) {
        return output.getAll().lines()
                .map(String::trim)
                .filter(line -> line.startsWith("{"))
                .map(GatewayStructuredLoggingIT::parseStrictJson)
                .toList();
    }

    private static CapturedLog parseStrictJson(String line) {
        try {
            return new CapturedLog(STRICT_JSON.readTree(line));
        } catch (Exception exception) {
            throw new AssertionError("구조화 로그 JSON 해석 실패", exception);
        }
    }

    private static String downstreamUri() {
        return "http://" + LOOPBACK_HOST + ":" + DOWNSTREAM.port();
    }

    private record CapturedLog(JsonNode json) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FailureProbeConfig {

        static final String HEADER_NAME = "X-Test-Failure-Probe";

        @Bean
        @Order(Ordered.HIGHEST_PRECEDENCE + 1)
        WebFilter failureProbe() {
            return (exchange, chain) -> "enabled".equals(
                    exchange.getRequest().getHeaders().getFirst(HEADER_NAME)
            ) ? Mono.error(new IllegalStateException(FAILURE_DETAIL)) : chain.filter(exchange);
        }
    }
}
