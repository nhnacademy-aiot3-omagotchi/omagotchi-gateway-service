package site.omagotchi.gatewayservice.global.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import static org.assertj.core.api.BDDAssertions.then;

class JwtAuthorityConfigTest {

    @Test
    @DisplayName("JWT 전역 역할을 ROLE_ 권한으로 변환")
    void convertsGlobalRoleToAuthority() {
        // Given
        Jwt jwt = Jwt.withTokenValue("access-token")
                .header("alg", "RS256")
                .subject(TestJwtKeyConfig.USER_ID)
                .claim("role", "SYSTEM_ADMIN")
                .build();

        // When
        AbstractAuthenticationToken authentication = new JwtAuthorityConfig()
                .jwtAuthenticationConverter()
                .convert(jwt)
                .block();

        // Then
        then(authentication).isNotNull();
        then(authentication.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .contains("ROLE_SYSTEM_ADMIN");
    }
}
