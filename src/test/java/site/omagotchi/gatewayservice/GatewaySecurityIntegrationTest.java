package site.omagotchi.gatewayservice;

import io.netty.handler.codec.http.HttpHeaderNames;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import site.omagotchi.gatewayservice.global.security.TestJwtKeyConfig;

import java.time.Duration;

/*
 * 실제 Gateway 애플리케이션을 임의 포트로 실행해 보안 필터와 Route 함께 검증
 *
 * - 요청 흐름: WebTestClient -> Gateway -> 테스트용 downstream 서버
 * - downstream이 받은 Header를 X-Received-* 응답 Header로 반환
 * - Bearer Token 전달과 외부 식별 Header·요청 Cookie·응답 Set-Cookie 제거 확인
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
// 테스트 JWT 발급과 검증에 같은 RSA key pair가 필요해서 테스트 공개키 Bean 사용
@Import(TestJwtKeyConfig.class)
class GatewaySecurityIntegrationTest {

    // downstream 요청 Header 확인을 위한 테스트 전용 Header
    private static final String RECEIVED_USER_ID_HEADER = "X-Received-User-Id";
    private static final String RECEIVED_GLOBAL_ROLE_HEADER = "X-Received-Global-Role";
    private static final String RECEIVED_AUTHORIZATION_HEADER = "X-Received-Authorization";
    private static final String RECEIVED_COOKIE_HEADER = "X-Received-Cookie";

    // 테스트 클래스 전체에서 서버 하나를 공유하고 종료 시 직접 정리
    private static final DisposableServer DOWNSTREAM = startDownstream();

    // RANDOM_PORT로 실행한 실제 Gateway Netty 서버 포트
    @Value("${local.server.port}")
    private int port;

    private WebTestClient webTestClient;

    /*
     * - Spring Context 시작 전에 application.yaml의 서비스 URI 대체
     * - 서비스별 라우팅 대상이 아니라 보안 경계 검증이므로 같은 downstream 사용
     */
    @DynamicPropertySource
    static void downstreamRoutes(DynamicPropertyRegistry registry) {
        String downstreamUri = "http://127.0.0.1:" + DOWNSTREAM.port();
        registry.add("IDENTITY_SERVICE_URI", () -> downstreamUri);
        registry.add("LEARNING_SERVICE_URI", () -> downstreamUri);
        registry.add("RULE_SERVICE_URI", () -> downstreamUri);
    }

    @BeforeEach
    void setUp() {
        // Mock WebFlux Context가 아니라 실제 Gateway 포트로 HTTP 요청
        webTestClient = WebTestClient.bindToServer()
                .responseTimeout(Duration.ofSeconds(5))
                .baseUrl("http://127.0.0.1:" + port)
                .build();
    }

    @AfterAll
    static void stopDownstream() {
        DOWNSTREAM.disposeNow();
    }

    @ParameterizedTest
    @CsvSource({
            "POST, /api/telegram/webhook",
            "GET, /api/rules/ping"
    })
    @DisplayName("공개 경로는 Access JWT 없이 전달")
    void publicRoutesDoNotRequireBearerToken(String method, String path) {
        // When
        WebTestClient.ResponseSpec response = webTestClient
                .method(HttpMethod.valueOf(method))
                .uri(path)
                .exchange();

        // Then
        response.expectStatus().isOk();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"signup", "login", "refresh", "logout"})
    @DisplayName("Identity 인증 API는 Gateway에서 라우팅하지 않음")
    void doesNotRouteIdentityAuthApi(String operation) {
        // Given
        String token = TestJwtKeyConfig.issue();

        // When
        WebTestClient.ResponseSpec response = webTestClient.post()
                .uri("/api/v1/auth/" + operation)
                .headers(headers -> headers.setBearerAuth(token))
                .exchange();

        // Then
        response.expectStatus().isNotFound();
    }

    @Test
    @DisplayName("보호 경로는 Access JWT가 없으면 401")
    void protectedRouteRequiresBearerToken() {
        // When
        WebTestClient.ResponseSpec response = webTestClient.get()
                .uri("/api/cohorts")
                .exchange();

        // Then
        response
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.code").isEqualTo("AUTH_AUTHENTICATION_REQUIRED");
    }

    @Test
    @DisplayName("유효한 Access JWT를 전달하고 외부 사용자 식별 Header 제거")
    void forwardsValidBearerTokenAndRemovesUntrustedIdentityHeaders() {
        // Given
        String token = TestJwtKeyConfig.issue();

        // When
        // 외부 클라이언트가 사용자 식별 Header를 위조한 요청
        WebTestClient.ResponseSpec response = webTestClient.get()
                .uri("/api/v1/users/me")
                .headers(headers -> {
                    headers.setBearerAuth(token);
                    headers.set("X-User-Id", "spoofed-user");
                    headers.set("X-Global-Role", "SYSTEM_ADMIN");
                    headers.set(HttpHeaders.COOKIE, "OMAGOTCHI_SESSION=session-secret");
                })
                .exchange();

        // Then
        // 가짜 downstream이 실제로 받은 요청 Header를 응답 Header로 반환
        response
                .expectStatus().isOk()
                .expectHeader().valueEquals(
                        RECEIVED_AUTHORIZATION_HEADER,
                        "Bearer " + token
                )
                .expectHeader().valueEquals(RECEIVED_USER_ID_HEADER, "absent")
                .expectHeader().valueEquals(RECEIVED_GLOBAL_ROLE_HEADER, "absent")
                .expectHeader().valueEquals(RECEIVED_COOKIE_HEADER, "absent")
                .expectHeader().doesNotExist(HttpHeaders.SET_COOKIE);
    }

    @Test
    @DisplayName("변조된 Access JWT 거부")
    void rejectsTamperedBearerToken() {
        // Given
        String token = TestJwtKeyConfig.tamperSignature(TestJwtKeyConfig.issue());

        // When
        WebTestClient.ResponseSpec response = webTestClient.get()
                .uri("/api/cohorts")
                .headers(headers -> headers.setBearerAuth(token))
                .exchange();

        // Then
        response
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.code").isEqualTo("AUTH_AUTHENTICATION_REQUIRED");
    }

    @Test
    @DisplayName("중복 Bearer Header는 400")
    void preservesMalformedBearerRequestContract() {
        // When
        // Authorization Header 값 두 개를 하나의 요청으로 전달
        WebTestClient.ResponseSpec response = webTestClient.get()
                .uri("/api/cohorts")
                .header(HttpHeaders.AUTHORIZATION, "Bearer first", "Bearer second")
                .exchange();

        // Then
        response
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo("COMMON_INVALID_REQUEST");
    }

    /*
     * - 실제 서비스 대신 요청을 받는 최소 Reactor Netty HTTP 서버
     * - 요청 Header를 응답 Header로 반환해 Gateway를 통과한 최종 요청 확인
     */
    private static DisposableServer startDownstream() {
        return HttpServer.create()
                // 운영체제에서 사용 가능한 포트 자동 할당
                .port(0)
                .handle((request, response) -> {
                    String userId = request.requestHeaders().get("X-User-Id");
                    String globalRole = request.requestHeaders().get("X-Global-Role");
                    String authorization = request.requestHeaders()
                            .get(HttpHeaders.AUTHORIZATION);
                    String cookie = request.requestHeaders().get(HttpHeaders.COOKIE);
                    return response
                            .header(
                                    RECEIVED_AUTHORIZATION_HEADER,
                                    authorization == null ? "absent" : authorization
                            )
                            .header(
                                    RECEIVED_USER_ID_HEADER,
                                    userId == null ? "absent" : userId
                            )
                            .header(
                                    RECEIVED_GLOBAL_ROLE_HEADER,
                                    globalRole == null ? "absent" : globalRole
                            )
                            .header(
                                    RECEIVED_COOKIE_HEADER,
                                    cookie == null ? "absent" : cookie
                            )
                            .header(
                                    HttpHeaders.SET_COOKIE,
                                    "OMAGOTCHI_SESSION=downstream-value; Path=/; HttpOnly"
                            )
                            .header(HttpHeaderNames.CONTENT_TYPE, "text/plain")
                            .sendString(reactor.core.publisher.Mono.just("ok"));
                })
                // DynamicPropertySource에서 포트를 바로 사용하도록 시작 완료까지 대기
                .bindNow();
    }
}
