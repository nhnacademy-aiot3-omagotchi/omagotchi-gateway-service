package site.omagotchi.gatewayservice.global.deployment;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.HttpStatusServerEntryPoint;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;

/** 업무 인증과 분리한 컨테이너 내부 배포 관리 경계. */
@Configuration
public class DeploymentSecurityConfig {

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    SecurityWebFilterChain deploymentSecurityFilterChain(ServerHttpSecurity http) {
        return http
                .securityMatcher(ServerWebExchangeMatchers.pathMatchers(
                        "/actuator/registry", "/actuator/serviceregistry"))
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(
                        new HttpStatusServerEntryPoint(HttpStatus.FORBIDDEN)))
                .authorizeExchange(authorize -> authorize.anyExchange().access((authentication, context) -> {
                    // 전달 Header가 아닌 실제 연결 주소 확인. 컨테이너 내부의 127.0.0.1·::1만 허용.
                    InetSocketAddress remoteAddress = context.getExchange().getRequest().getRemoteAddress();
                    String remote = remoteAddress != null && remoteAddress.getAddress() != null
                            ? remoteAddress.getAddress().getHostAddress() : null;
                    boolean loopback = "127.0.0.1".equals(remote)
                            || "::1".equals(remote) || "0:0:0:0:0:0:0:1".equals(remote);
                    return Mono.just(new AuthorizationDecision(loopback));
                }))
                .build();
    }
}
