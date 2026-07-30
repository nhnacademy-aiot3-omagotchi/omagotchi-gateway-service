package site.omagotchi.gatewayservice.global.security;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@TestConfiguration(proxyBeanMethods = false)
public class TestJwtKeyConfig {

    public static final String ISSUER = "https://identity.omagotchi.local";
    public static final String AUDIENCE = "omagotchi-api";
    public static final String USER_ID = "019d2a48-80c0-4d6a-9a15-0b16d2dd74f1";

    private static final KeyPair KEY_PAIR = generateKeyPair(2048);
    private static final JwtEncoder JWT_ENCODER = NimbusJwtEncoder.withKeyPair(
            (RSAPublicKey) KEY_PAIR.getPublic(),
            (RSAPrivateKey) KEY_PAIR.getPrivate()
    ).build();

    @Bean
    RSAPublicKey jwtPublicKey() {
        return publicKey();
    }

    static RSAPublicKey publicKey() {
        return (RSAPublicKey) KEY_PAIR.getPublic();
    }

    public static String issue() {
        Instant now = Instant.now();
        return issue(
                JWT_ENCODER,
                ISSUER,
                AUDIENCE,
                USER_ID,
                "USER",
                now,
                now.plusSeconds(300)
        );
    }

    public static String issue(
            String issuer,
            String audience,
            String subject,
            String role
    ) {
        Instant now = Instant.now();
        return issue(
                JWT_ENCODER,
                issuer,
                audience,
                subject,
                role,
                now,
                now.plusSeconds(300)
        );
    }

    public static String issueExpired() {
        Instant expiresAt = Instant.now().minusSeconds(60);
        return issue(
                JWT_ENCODER,
                ISSUER,
                AUDIENCE,
                USER_ID,
                "USER",
                expiresAt.minusSeconds(60),
                expiresAt
        );
    }

    public static String issueWithDifferentKey() {
        KeyPair keyPair = generateKeyPair(2048);
        JwtEncoder encoder = NimbusJwtEncoder.withKeyPair(
                (RSAPublicKey) keyPair.getPublic(),
                (RSAPrivateKey) keyPair.getPrivate()
        ).build();
        Instant now = Instant.now();
        return issue(
                encoder,
                ISSUER,
                AUDIENCE,
                USER_ID,
                "USER",
                now,
                now.plusSeconds(300)
        );
    }

    public static String tamperSignature(String token) {
        int signatureStart = token.lastIndexOf('.') + 1;
        char replacement = token.charAt(signatureStart) == 'A' ? 'B' : 'A';
        return token.substring(0, signatureStart)
                + replacement
                + token.substring(signatureStart + 1);
    }

    private static String issue(
            JwtEncoder encoder,
            String issuer,
            String audience,
            String subject,
            String role,
            Instant issuedAt,
            Instant expiresAt
    ) {
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).build();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .audience(List.of(audience))
                .subject(subject)
                .claim("role", role)
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .id(UUID.randomUUID().toString())
                .build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    static KeyPair generateKeyPair(int keySize) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(keySize);
            return generator.generateKeyPair();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("테스트 RSA key를 생성할 수 없습니다.", exception);
        }
    }
}
