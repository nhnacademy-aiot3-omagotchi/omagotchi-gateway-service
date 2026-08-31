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

    private static final String LOOPBACK_HOST = "127.0.0.1";

    // downstream 요청 Header 확인을 위한 테스트 전용 Header
    private static final String RECEIVED_USER_ID_HEADER = "X-Received-User-Id";
    private static final String RECEIVED_GLOBAL_ROLE_HEADER = "X-Received-Global-Role";
    private static final String RECEIVED_AUTHORIZATION_HEADER = "X-Received-Authorization";
    private static final String RECEIVED_COOKIE_HEADER = "X-Received-Cookie";
    private static final String RECEIVED_SERVICE_HEADER = "X-Received-Service";
    private static final String RECEIVED_PATH_HEADER = "X-Received-Path";

    // 서비스별 Route 대상 구분을 위해 downstream 서버를 분리
    private static final DisposableServer IDENTITY_DOWNSTREAM = startDownstream("identity");
    private static final DisposableServer LEARNING_DOWNSTREAM = startDownstream("learning");
    private static final DisposableServer RULE_DOWNSTREAM = startDownstream("rule");

    // RANDOM_PORT로 실행한 실제 Gateway Netty 서버 포트
    @Value("${local.server.port}")
    private int port;

    private WebTestClient webTestClient;

    /*
     * - Spring Context 시작 전에 application.yaml의 서비스 URI 대체
     * - 서비스별 URI를 테스트용 downstream으로 대체해 Route 대상까지 검증
     */
    @DynamicPropertySource
    static void downstreamRoutes(DynamicPropertyRegistry registry) {
        registry.add("server.address", () -> LOOPBACK_HOST);
        registry.add("IDENTITY_SERVICE_URI", () -> downstreamUri(IDENTITY_DOWNSTREAM));
        registry.add("LEARNING_SERVICE_URI", () -> downstreamUri(LEARNING_DOWNSTREAM));
        registry.add("RULE_SERVICE_URI", () -> downstreamUri(RULE_DOWNSTREAM));
    }

    @BeforeEach
    void setUp() {
        // Mock WebFlux Context가 아니라 실제 Gateway 포트로 HTTP 요청
        webTestClient = WebTestClient.bindToServer()
                .responseTimeout(Duration.ofSeconds(5))
                .baseUrl("http://" + LOOPBACK_HOST + ":" + port)
                .build();
    }

    @AfterAll
    static void stopDownstreams() {
        IDENTITY_DOWNSTREAM.disposeNow();
        LEARNING_DOWNSTREAM.disposeNow();
        RULE_DOWNSTREAM.disposeNow();
    }

    @ParameterizedTest
    @CsvSource({
            "POST, /api/v1/webhooks/telegram, learning",
            "GET, /api/v1/rules/ping, rule"
    })
    @DisplayName("공개 경로는 Access JWT 없이 전달")
    void publicRoutesDoNotRequireBearerToken(
            String method,
            String path,
            String expectedService
    ) {
        // When
        WebTestClient.ResponseSpec response = webTestClient
                .method(HttpMethod.valueOf(method))
                .uri(path)
                .exchange();

        // Then
        response
                .expectStatus().isOk()
                .expectHeader().valueEquals(RECEIVED_SERVICE_HEADER, expectedService)
                .expectHeader().valueEquals(RECEIVED_PATH_HEADER, path);
    }

    @Test
    @DisplayName("Telegram Webhook은 POST 요청만 Learning Service로 전달")
    void doesNotRouteTelegramWebhookWithOtherMethod() {
        // Given
        String token = TestJwtKeyConfig.issue();

        // When
        WebTestClient.ResponseSpec response = webTestClient.get()
                .uri("/api/v1/webhooks/telegram")
                .headers(headers -> headers.setBearerAuth(token))
                .exchange();

        // Then
        response.expectStatus().isNotFound();
    }

    @Test
    @DisplayName("Telegram Webhook 비-POST 무인증 요청의 401 응답")
    void requiresBearerTokenForNonPostTelegramWebhook() {
        // When
        WebTestClient.ResponseSpec response = webTestClient.get()
                .uri("/api/v1/webhooks/telegram")
                .exchange();

        // Then
        response.expectStatus().isUnauthorized();
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
                .uri("/api/v1/cohorts")
                .exchange();

        // Then
        response
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.code").isEqualTo("AUTH_AUTHENTICATION_REQUIRED");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"/api/v1/rules", "/api/v1/flows"})
    @DisplayName("Rule API는 Rule Service로 전달")
    void routesRuleApi(String path) {
        // Given
        String token = TestJwtKeyConfig.issue();

        // When
        WebTestClient.ResponseSpec response = webTestClient.get()
                .uri(path)
                .headers(headers -> headers.setBearerAuth(token))
                .exchange();

        // Then
        response
                .expectStatus().isOk()
                .expectHeader().valueEquals(RECEIVED_SERVICE_HEADER, "rule")
                .expectHeader().valueEquals(RECEIVED_PATH_HEADER, path);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "/api/v1/cohorts",
            "/api/v1/cohort-memberships",
            "/api/v1/teams",
            "/api/v1/spaces",
            "/api/v1/admin/spaces",
            "/api/v1/threshold-rules",
            "/api/v1/community/posts",
            "/api/v1/gamification",
            "/api/v1/rankings",
            "/api/v1/user-profiles/me",
            "/api/v1/telegram/link"
    })
    @DisplayName("Learning 공개 API는 원본 v1 경로로 Learning Service에 전달")
    void routesLearningApi(String path) {
        // Given
        String token = TestJwtKeyConfig.issue();

        // When
        WebTestClient.ResponseSpec response = webTestClient.get()
                .uri(path)
                .headers(headers -> headers.setBearerAuth(token))
                .exchange();

        // Then
        response
                .expectStatus().isOk()
                .expectHeader().valueEquals(RECEIVED_SERVICE_HEADER, "learning")
                .expectHeader().valueEquals(RECEIVED_PATH_HEADER, path);
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
            "POST, /api/v1/spaces/1/vacancy-alerts",
            "GET, /api/v1/vacancy-alerts/me",
            "DELETE, /api/v1/vacancy-alerts/1"
    })
    @DisplayName("공실 알림 API는 Learning Service로 전달")
    void routesVacancyAlertApi(String method, String path) {
        // Given
        String token = TestJwtKeyConfig.issue();

        // When
        WebTestClient.ResponseSpec response = webTestClient
                .method(HttpMethod.valueOf(method))
                .uri(path)
                .headers(headers -> headers.setBearerAuth(token))
                .exchange();

        // Then
        response
                .expectStatus().isOk()
                .expectHeader().valueEquals(RECEIVED_SERVICE_HEADER, "learning")
                .expectHeader().valueEquals(RECEIVED_PATH_HEADER, path);
    }

    @ParameterizedTest(name = "{1}")
    @CsvSource({
            "GET, /api/cohorts",
            "GET, /api/1/cohorts",
            "GET, /api/version1/cohorts",
            "GET, /api/v1cohorts",
            "GET, /api/rules",
            "POST, /api/webhooks/telegram"
    })
    @DisplayName("공개 Domain API는 /api/v{major}/ 형식이 아니면 라우팅하지 않음")
    void doesNotRouteDomainApiWithInvalidVersionPath(String method, String path) {
        // Given
        String token = TestJwtKeyConfig.issue();

        // When
        WebTestClient.ResponseSpec response = webTestClient
                .method(HttpMethod.valueOf(method))
                .uri(path)
                .headers(headers -> headers.setBearerAuth(token))
                .exchange();

        // Then
        response.expectStatus().isNotFound();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"/api/v2/cohorts", "/api/v2/rules"})
    @DisplayName("등록되지 않은 API 메이저 버전은 라우팅하지 않음")
    void doesNotRouteUnsupportedMajorVersion(String path) {
        // Given
        String token = TestJwtKeyConfig.issue();

        // When
        WebTestClient.ResponseSpec response = webTestClient.get()
                .uri(path)
                .headers(headers -> headers.setBearerAuth(token))
                .exchange();

        // Then
        response.expectStatus().isNotFound();
    }

    @Test
    @DisplayName("Rule 내부 API는 Gateway에서 라우팅하지 않음")
    void doesNotRouteRuleInternalApi() {
        // Given
        String token = TestJwtKeyConfig.issue();

        // When
        WebTestClient.ResponseSpec response = webTestClient.get()
                .uri("/api/v1/internal/engines/self")
                .headers(headers -> headers.setBearerAuth(token))
                .exchange();

        // Then
        response.expectStatus().isNotFound();
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
                .expectHeader().valueEquals(RECEIVED_SERVICE_HEADER, "identity")
                .expectHeader().valueEquals(RECEIVED_PATH_HEADER, "/api/v1/users/me")
                .expectHeader().doesNotExist(HttpHeaders.SET_COOKIE);
    }

    @Test
    @DisplayName("변조된 Access JWT 거부")
    void rejectsTamperedBearerToken() {
        // Given
        String token = TestJwtKeyConfig.tamperSignature(TestJwtKeyConfig.issue());

        // When
        WebTestClient.ResponseSpec response = webTestClient.get()
                .uri("/api/v1/cohorts")
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
                .uri("/api/v1/cohorts")
                .header(HttpHeaders.AUTHORIZATION, "Bearer first", "Bearer second")
                .exchange();

        // Then
        response
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo("COMMON_INVALID_REQUEST");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"/api/v1/admin/users", "/api/v1/admin/users/"})
    @DisplayName("Identity 관리자 사용자 API는 원본 v1 경로로 Identity Service에 전달")
    void routesIdentityAdminUserApi(String path) {
        // Given
        String token = TestJwtKeyConfig.issue();

        // When
        WebTestClient.ResponseSpec response = webTestClient.get()
                .uri(path)
                .headers(headers -> headers.setBearerAuth(token))
                .exchange();

        // Then
        response
                .expectStatus().isOk()
                .expectHeader().valueEquals(RECEIVED_SERVICE_HEADER, "identity")
                .expectHeader().valueEquals(RECEIVED_PATH_HEADER, path);
    }

    @Test
    @DisplayName("Identity 관리자 사용자 API 무인증 요청의 401 응답")
    void requiresBearerTokenForIdentityAdminUserApi() {
        // When
        WebTestClient.ResponseSpec response = webTestClient.get()
                .uri("/api/v1/admin/users")
                .exchange();

        // Then
        response
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.code").isEqualTo("AUTH_AUTHENTICATION_REQUIRED");
    }

    /*
     * - 실제 서비스 대신 요청을 받는 최소 Reactor Netty HTTP 서버
     * - 요청 Header를 응답 Header로 반환해 Gateway를 통과한 최종 요청 확인
     */
    private static String downstreamUri(DisposableServer downstream) {
        return "http://" + LOOPBACK_HOST + ":" + downstream.port();
    }

    private static DisposableServer startDownstream(String service) {
        return HttpServer.create()
                // localhost의 IPv4·IPv6 해석 차이를 제거하고 테스트 서버를 Loopback에만 Bind
                .host(LOOPBACK_HOST)
                // 운영체제에서 사용 가능한 포트 자동 할당
                .port(0)
                .handle((request, response) -> {
                    String userId = request.requestHeaders().get("X-User-Id");
                    String globalRole = request.requestHeaders().get("X-Global-Role");
                    String authorization = request.requestHeaders()
                            .get(HttpHeaders.AUTHORIZATION);
                    String cookie = request.requestHeaders().get(HttpHeaders.COOKIE);
                    return response
                            .header(RECEIVED_SERVICE_HEADER, service)
                            .header(RECEIVED_PATH_HEADER, request.uri())
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
