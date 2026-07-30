package site.omagotchi.gatewayservice.global.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import site.omagotchi.gatewayservice.global.auth.GlobalRole;

import java.security.interfaces.RSAPublicKey;
import java.util.List;
import java.util.UUID;

@Configuration
public class JwtConfig {

    @Bean
    ReactiveJwtDecoder jwtDecoder(RSAPublicKey publicKey, JwtProperties properties) {
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withPublicKey(publicKey).build();
        OAuth2TokenValidator<Jwt> issuerValidator = JwtValidators.createDefaultWithIssuer(properties.issuer());
        OAuth2TokenValidator<Jwt> audienceValidator = new JwtClaimValidator<List<String>>(
                "aud",
                audience -> audience.contains(properties.audience())
        );
        OAuth2TokenValidator<Jwt> subjectValidator = new JwtClaimValidator<>(
                "sub",
                JwtConfig::isValidSubject
        );
        OAuth2TokenValidator<Jwt> roleValidator = new JwtClaimValidator<>(
                "role",
                GlobalRole::isSupported
        );
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                issuerValidator,
                audienceValidator,
                subjectValidator,
                roleValidator
        ));
        return decoder;
    }

    private static boolean isValidSubject(String subject) {
        if (subject == null) {
            return false;
        }

        try {
            UUID accountId = UUID.fromString(subject);
            return accountId.toString().equals(subject);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
