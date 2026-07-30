package site.omagotchi.gatewayservice.global.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;

import java.security.interfaces.RSAPublicKey;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.BDDAssertions.catchThrowable;
import static org.assertj.core.api.BDDAssertions.then;
import static org.assertj.core.api.BDDSoftAssertions.thenSoftly;

class JwtConfigTest {

    private final JwtConfig jwtConfig = new JwtConfig();
    private final RSAPublicKey publicKey = TestJwtKeyConfig.publicKey();
    private final ReactiveJwtDecoder decoder = jwtConfig.jwtDecoder(publicKey, properties());

    @Test
    @DisplayName("지원하는 전역 역할 허용")
    void acceptsSupportedGlobalRoles() {
        // Given
        List<String> tokens = List.of(
                TestJwtKeyConfig.issue(
                        TestJwtKeyConfig.ISSUER,
                        TestJwtKeyConfig.AUDIENCE,
                        TestJwtKeyConfig.USER_ID,
                        "USER"
                ),
                TestJwtKeyConfig.issue(
                        TestJwtKeyConfig.ISSUER,
                        TestJwtKeyConfig.AUDIENCE,
                        TestJwtKeyConfig.USER_ID,
                        "SYSTEM_ADMIN"
                )
        );

        // When
        List<String> roles = tokens.stream()
                .map(token -> decoder.decode(token).block())
                .filter(Objects::nonNull)
                .map(jwt -> jwt.getClaimAsString("role"))
                .toList();

        // Then
        then(roles).containsExactly("USER", "SYSTEM_ADMIN");
    }

    @Test
    @DisplayName("JWT claim 계약 위반 거부")
    void rejectsInvalidClaims() {
        // Given
        List<String> invalidTokens = List.of(
                TestJwtKeyConfig.issue(
                        "https://other-issuer.example",
                        TestJwtKeyConfig.AUDIENCE,
                        TestJwtKeyConfig.USER_ID,
                        "USER"
                ),
                TestJwtKeyConfig.issue(
                        TestJwtKeyConfig.ISSUER,
                        "other-api",
                        TestJwtKeyConfig.USER_ID,
                        "USER"
                ),
                TestJwtKeyConfig.issue(
                        TestJwtKeyConfig.ISSUER,
                        TestJwtKeyConfig.AUDIENCE,
                        "not-a-uuid",
                        "USER"
                ),
                TestJwtKeyConfig.issue(
                        TestJwtKeyConfig.ISSUER,
                        TestJwtKeyConfig.AUDIENCE,
                        TestJwtKeyConfig.USER_ID,
                        "ADMIN"
                ),
                TestJwtKeyConfig.issue(
                        TestJwtKeyConfig.ISSUER,
                        TestJwtKeyConfig.AUDIENCE,
                        TestJwtKeyConfig.USER_ID,
                        "MANAGER"
                )
        );

        // When
        List<Throwable> failures = invalidTokens.stream()
                .map(token -> catchThrowable(() -> decoder.decode(token).block()))
                .toList();

        // Then
        thenSoftly(softly -> failures.forEach(failure ->
                softly.then(failure).isInstanceOf(JwtValidationException.class)
        ));
    }

    @Test
    @DisplayName("변조, 만료와 다른 RSA key JWT 거부")
    void rejectsInvalidSignaturesAndExpiration() {
        // Given
        List<String> invalidTokens = List.of(
                TestJwtKeyConfig.tamperSignature(TestJwtKeyConfig.issue()),
                TestJwtKeyConfig.issueExpired(),
                TestJwtKeyConfig.issueWithDifferentKey()
        );

        // When
        List<Throwable> failures = invalidTokens.stream()
                .map(token -> catchThrowable(() -> decoder.decode(token).block()))
                .toList();

        // Then
        thenSoftly(softly -> failures.forEach(failure ->
                softly.then(failure).isInstanceOf(JwtException.class)
        ));
    }

    private JwtProperties properties() {
        return new JwtProperties(
                TestJwtKeyConfig.ISSUER,
                TestJwtKeyConfig.AUDIENCE,
                new ByteArrayResource(new byte[0])
        );
    }
}
