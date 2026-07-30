package site.omagotchi.gatewayservice.global.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;

import java.nio.charset.StandardCharsets;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;

import static org.assertj.core.api.BDDAssertions.catchThrowable;
import static org.assertj.core.api.BDDAssertions.then;

class JwtKeyConfigTest {

    private final JwtKeyConfig jwtKeyConfig = new JwtKeyConfig();

    @Test
    @DisplayName("2048 bit RSA 공개키 로딩")
    void loadsSupportedRsaPublicKey() {
        // Given
        RSAPublicKey expected = (RSAPublicKey) TestJwtKeyConfig
                .generateKeyPair(2048)
                .getPublic();
        JwtProperties properties = properties(pemResource(expected));

        // When
        RSAPublicKey actual = jwtKeyConfig.jwtPublicKey(properties);

        // Then
        then(actual.getModulus())
                .isEqualTo(expected.getModulus());
        then(actual.getPublicExponent())
                .isEqualTo(expected.getPublicExponent());
    }

    @Test
    @DisplayName("2048 bit 미만 RSA 공개키 거부")
    void rejectsWeakRsaPublicKey() {
        // Given
        RSAPublicKey weakKey = (RSAPublicKey) TestJwtKeyConfig
                .generateKeyPair(1024)
                .getPublic();
        JwtProperties properties = properties(pemResource(weakKey));

        // When
        Throwable throwable = catchThrowable(() -> jwtKeyConfig.jwtPublicKey(properties));

        // Then
        then(throwable)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("2048 bit 이상");
    }

    @Test
    @DisplayName("잘못된 PEM 공개키 거부")
    void rejectsMalformedPublicKey() {
        // Given
        JwtProperties properties = properties(
                new ByteArrayResource("not-a-public-key".getBytes(StandardCharsets.UTF_8))
        );

        // When
        Throwable throwable = catchThrowable(() -> jwtKeyConfig.jwtPublicKey(properties));

        // Then
        then(throwable)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("읽을 수 없습니다");
    }

    private JwtProperties properties(ByteArrayResource publicKeyResource) {
        return new JwtProperties(
                TestJwtKeyConfig.ISSUER,
                TestJwtKeyConfig.AUDIENCE,
                publicKeyResource
        );
    }

    private ByteArrayResource pemResource(RSAPublicKey publicKey) {
        String encodedKey = Base64.getMimeEncoder(
                64,
                "\n".getBytes(StandardCharsets.UTF_8)
        ).encodeToString(publicKey.getEncoded());
        String pem = """
                -----BEGIN PUBLIC KEY-----
                %s
                -----END PUBLIC KEY-----
                """.formatted(encodedKey);
        return new ByteArrayResource(pem.getBytes(StandardCharsets.UTF_8));
    }

}
