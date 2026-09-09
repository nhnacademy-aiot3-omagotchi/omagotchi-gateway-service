package site.omagotchi.gatewayservice.global.deployment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.WebFilterChainProxy;

import java.net.InetSocketAddress;

import static org.assertj.core.api.Assertions.assertThat;

class DeploymentSecurityConfigTest {

    @ParameterizedTest
    @CsvSource({"127.0.0.1, 204", "::1, 204", "0:0:0:0:0:0:0:1, 204", "127.0.0.2, 403", "172.24.0.2, 403", ", 403"})
    @DisplayName("전달 Header와 무관한 실제 Loopback 연결의 관리 요청만 허용")
    void restrictsManagementToLoopback(String remote, int expectedStatus) {
        // Given
        SecurityWebFilterChain chain = new DeploymentSecurityConfig().deploymentSecurityFilterChain(ServerHttpSecurity.http());
        WebFilterChainProxy filter = new WebFilterChainProxy(chain);
        MockServerHttpRequest.BodyBuilder request = MockServerHttpRequest.post("/actuator/serviceregistry")
                .header("X-Forwarded-For", "127.0.0.1")
                .header("Forwarded", "for=127.0.0.1");
        if (remote != null) {
            request.remoteAddress(new InetSocketAddress(remote, 12345));
        }
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        // When
        filter.filter(exchange, incoming -> {
            incoming.getResponse().setStatusCode(HttpStatus.NO_CONTENT);
            return incoming.getResponse().setComplete();
        }).block();

        // Then
        assertThat(exchange.getResponse().getStatusCode())
                .isEqualTo(HttpStatus.valueOf(expectedStatus));
    }
}
