package site.omagotchi.gatewayservice;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import site.omagotchi.gatewayservice.global.requestid.RequestId;
import site.omagotchi.gatewayservice.global.security.TestJwtKeyConfig;

import java.time.Duration;

import static org.assertj.core.api.BDDAssertions.then;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestJwtKeyConfig.class)
class GatewayRequestIdIT {

    private static final String LOOPBACK_HOST = "127.0.0.1";
    private static final String RECEIVED_REQUEST_ID = "X-Received-Request-Id";
    private static final String RECEIVED_TRACEPARENT = "X-Received-Traceparent";
    private static final String RECEIVED_TRACESTATE = "X-Received-Tracestate";
    private static final String RECEIVED_BAGGAGE = "X-Received-Baggage";
    private static final String REQUEST_ID = "0123456789abcdef0123456789abcdef";
    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String TRACEPARENT = "00-" + TRACE_ID + "-00f067aa0ba902b7-01";

    private static final DisposableServer DOWNSTREAM = HttpServer.create()
            .host(LOOPBACK_HOST)
            .port(0)
            .handle((request, response) -> {
                if (request.uri().endsWith("/downstream-bad-gateway")) {
                    response.status(HttpStatus.BAD_GATEWAY.value());
                } else if (request.uri().endsWith("/downstream-gateway-timeout")) {
                    response.status(HttpStatus.GATEWAY_TIMEOUT.value());
                } else if (request.uri().endsWith("/downstream-teapot")) {
                    response.status(418);
                }
                String traceparent = request.requestHeaders().get("traceparent");
                if (traceparent != null) {
                    response.header(RECEIVED_TRACEPARENT, traceparent);
                }
                String tracestate = request.requestHeaders().get("tracestate");
                if (tracestate != null) {
                    response.header(RECEIVED_TRACESTATE, tracestate);
                }
                String baggage = request.requestHeaders().get("baggage");
                if (baggage != null) {
                    response.header(RECEIVED_BAGGAGE, baggage);
                }
                return request.receive()
                        .asString()
                        .then(Mono.from(response
                                .header(
                                        RECEIVED_REQUEST_ID,
                                        request.requestHeaders().get(RequestId.HEADER_NAME)
                                )
                                .sendString(Mono.just("downstream-response"))));
            })
            .bindNow();

    @Value("${local.server.port}")
    private int port;

    private WebTestClient webTestClient;

    @DynamicPropertySource
    static void downstreamRoutes(DynamicPropertyRegistry registry) {
        registry.add("server.address", () -> LOOPBACK_HOST);
        registry.add("IDENTITY_SERVICE_URI", GatewayRequestIdIT::downstreamUri);
        registry.add("LEARNING_SERVICE_URI", GatewayRequestIdIT::downstreamUri);
        registry.add("RULE_SERVICE_URI", GatewayRequestIdIT::downstreamUri);
    }

    @BeforeEach
    void setUp() {
        webTestClient = WebTestClient.bindToServer()
                .responseTimeout(Duration.ofSeconds(5))
                .baseUrl("http://" + LOOPBACK_HOST + ":" + port)
                .build();
    }

    @AfterAll
    static void stopDownstream() {
        DOWNSTREAM.disposeNow();
    }

    @Test
    @DisplayName("같은 Request ID를 하위 서비스 요청과 Gateway 응답에 전달")
    void propagatesRequestIdAcrossGatewayBoundary() {
        // Given
        String jwt = TestJwtKeyConfig.issue();

        // When & Then
        webTestClient.get()
                .uri("/api/v1/rules")
                .headers(headers -> {
                    headers.setBearerAuth(jwt);
                    headers.set(RequestId.HEADER_NAME, REQUEST_ID);
                })
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(RECEIVED_REQUEST_ID, REQUEST_ID)
                .expectHeader().valueEquals(RequestId.HEADER_NAME, REQUEST_ID)
                .expectBody(String.class).isEqualTo("downstream-response");
    }

    @Test
    @DisplayName("Request ID가 없는 요청에도 새 ID를 생성해 양쪽 경계에 전달")
    void generatesRequestIdAtGatewayBoundary() {
        // Given
        String jwt = TestJwtKeyConfig.issue();

        // When
        EntityExchangeResult<byte[]> result = webTestClient.get()
                .uri("/api/v1/rules")
                .headers(headers -> headers.setBearerAuth(jwt))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueMatches(RequestId.HEADER_NAME, "^[0-9a-f]{32}$")
                .expectHeader().valueMatches(RECEIVED_REQUEST_ID, "^[0-9a-f]{32}$")
                .expectBody()
                .returnResult();

        // Then
        then(result.getResponseHeaders().getFirst(RequestId.HEADER_NAME))
                .isEqualTo(result.getResponseHeaders().getFirst(RECEIVED_REQUEST_ID));
    }

    @Test
    @DisplayName("W3C Trace Context는 하위 서비스로 이어가고 Baggage는 제거")
    void propagatesW3cTraceContextWithoutBaggage() {
        // Given
        String jwt = TestJwtKeyConfig.issue();

        // When & Then
        webTestClient.get()
                .uri("/api/v1/rules")
                .headers(headers -> {
                    headers.setBearerAuth(jwt);
                    headers.set("traceparent", TRACEPARENT);
                    headers.set("tracestate", "vendor=value");
                    headers.set("baggage", "user-id=must-not-propagate");
                })
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueMatches(
                        RECEIVED_TRACEPARENT,
                        "^00-" + TRACE_ID + "-[0-9a-f]{16}-01$"
                )
                .expectHeader().valueEquals(RECEIVED_TRACESTATE, "vendor=value")
                .expectHeader().doesNotExist(RECEIVED_BAGGAGE);
    }

    @Test
    @DisplayName("인증 오류 응답 헤더와 본문에 같은 Request ID 포함")
    void includesRequestIdInSecurityError() {
        // Given
        String path = "/api/v1/cohorts";

        // When & Then
        webTestClient.get()
                .uri(path)
                .header(RequestId.HEADER_NAME, REQUEST_ID)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueEquals(RequestId.HEADER_NAME, REQUEST_ID)
                .expectBody()
                .jsonPath("$.code").isEqualTo("AUTH_AUTHENTICATION_REQUIRED")
                .jsonPath("$.requestId").isEqualTo(REQUEST_ID);
    }

    @Test
    @DisplayName("Gateway가 생성한 no-route 404에도 같은 Request ID 포함")
    void includesRequestIdInGatewayNotFoundError() {
        // Given
        String jwt = TestJwtKeyConfig.issue();

        // When & Then
        webTestClient.get()
                .uri("/api/v2/rules")
                .headers(headers -> {
                    headers.setBearerAuth(jwt);
                    headers.set(RequestId.HEADER_NAME, REQUEST_ID);
                })
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().valueEquals(RequestId.HEADER_NAME, REQUEST_ID)
                .expectHeader().contentType("application/json")
                .expectBody()
                .jsonPath("$.code").isEqualTo("COMMON_NOT_FOUND")
                .jsonPath("$.message").isEqualTo("요청한 리소스를 찾을 수 없습니다.")
                .jsonPath("$.path").isEqualTo("/api/v2/rules")
                .jsonPath("$.requestId").isEqualTo(REQUEST_ID);
    }

    @ParameterizedTest
    @CsvSource({
            "downstream-teapot, 418",
            "downstream-bad-gateway, 502",
            "downstream-gateway-timeout, 504"
    })
    @DisplayName("하위 서비스가 직접 반환한 4xx·5xx 응답은 Gateway 오류 변환 없이 전달")
    void passesThroughDownstreamGatewayError(String suffix, int status) {
        // Given
        String jwt = TestJwtKeyConfig.issue();

        // When & Then
        webTestClient.get()
                .uri("/api/v1/rules/" + suffix)
                .headers(headers -> {
                    headers.setBearerAuth(jwt);
                    headers.set(RequestId.HEADER_NAME, REQUEST_ID);
                })
                .exchange()
                .expectStatus().isEqualTo(status)
                .expectHeader().valueEquals(RequestId.HEADER_NAME, REQUEST_ID)
                .expectHeader().valueEquals(RECEIVED_REQUEST_ID, REQUEST_ID)
                .expectBody(String.class).isEqualTo("downstream-response");
    }

    private static String downstreamUri() {
        return "http://" + LOOPBACK_HOST + ":" + DOWNSTREAM.port();
    }
}
