package site.omagotchi.gatewayservice.global.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.converter.RsaKeyConverters;

import java.io.IOException;
import java.io.InputStream;
import java.security.interfaces.RSAPublicKey;

// PEM public key를 Bean으로 준비하지 못하면 애플리케이션 시작 중단
@Configuration
@Profile("!test")
public class JwtKeyConfig {

    private static final int MIN_RSA_KEY_SIZE = 2048;

    @Bean
    RSAPublicKey jwtPublicKey(JwtProperties properties) {
        try (InputStream inputStream = properties.publicKeyLocation().getInputStream()) {
            RSAPublicKey publicKey = RsaKeyConverters.x509().convert(inputStream);
            if (publicKey.getModulus().bitLength() < MIN_RSA_KEY_SIZE) {
                throw new IllegalStateException("JWT RSA public key는 2048 bit 이상이어야 합니다.");
            }
            return publicKey;
        } catch (IOException | IllegalArgumentException exception) {
            throw new IllegalStateException("JWT public key를 읽을 수 없습니다.", exception);
        }
    }
}
