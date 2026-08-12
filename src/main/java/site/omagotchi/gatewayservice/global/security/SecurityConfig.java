package site.omagotchi.gatewayservice.global.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.BearerTokenErrors;
import org.springframework.security.oauth2.server.resource.web.server.authentication.ServerBearerTokenAuthenticationConverter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.ServerAuthenticationConverter;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import reactor.core.publisher.Mono;

@Configuration
public class SecurityConfig {

    @Bean
    SecurityWebFilterChain securityWebFilterChain(
            ServerHttpSecurity http,
            SecurityErrorResponseHandler errorHandler,
            Converter<Jwt, Mono<AbstractAuthenticationToken>> jwtAuthenticationConverter,
            ServerAuthenticationConverter bearerTokenAuthenticationConverter
    ) {
        http
                // Browser Session과 CSRF는 Frontend가 소유하고 Gateway는 Bearer Token만 받음
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .cors(Customizer.withDefaults())
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .authorizeExchange(authorize -> authorize
                        .pathMatchers(HttpMethod.POST,
                                "/api/v1/webhooks/telegram"
                        ).permitAll()
                        .pathMatchers(HttpMethod.GET,
                                "/api/v1/rules/ping"
                        ).permitAll()
                        .pathMatchers(
                                "/actuator/health",
                                "/actuator/health/**",
                                "/actuator/info"
                        ).permitAll()
                        .anyExchange().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2
                        .bearerTokenConverter(bearerTokenAuthenticationConverter)
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))
                        .authenticationEntryPoint(errorHandler)
                        .accessDeniedHandler(errorHandler)
                )
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(errorHandler)
                        .accessDeniedHandler(errorHandler)
                );

        return http.build();
    }

    @Bean
    ServerAuthenticationConverter bearerTokenAuthenticationConverter() {
        ServerAuthenticationConverter delegate = new ServerBearerTokenAuthenticationConverter();

        return exchange -> {
            if (exchange.getRequest().getHeaders().getOrEmpty(HttpHeaders.AUTHORIZATION).size() > 1) {
                return Mono.error(new OAuth2AuthenticationException(
                        BearerTokenErrors.invalidRequest(
                                "Authorization 헤더가 여러 개 전달되었습니다."
                        )
                ));
            }

            return delegate.convert(exchange);
        };
    }
}
