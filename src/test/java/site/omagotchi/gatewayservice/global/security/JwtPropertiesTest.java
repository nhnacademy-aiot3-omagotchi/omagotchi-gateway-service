package site.omagotchi.gatewayservice.global.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class JwtPropertiesTest {

    private static final String[] VALID_PROPERTIES = {
            "auth.jwt.issuer=https://identity.omagotchi.local",
            "auth.jwt.audience=omagotchi-api",
            "auth.jwt.public-key-location=classpath:jwt-public.pem"
    };

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class);

    @Test
    @DisplayName("유효한 JWT 설정 바인딩")
    void bindsValidProperties() {
        // When
        contextRunner
                .withPropertyValues(VALID_PROPERTIES)
                .run(context -> {
                    // Then
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(JwtProperties.class);
                    assertThat(context.getBean(JwtProperties.class).issuer())
                            .isEqualTo("https://identity.omagotchi.local");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "auth.jwt.issuer",
            "auth.jwt.audience",
            "auth.jwt.public-key-location"
    })
    @DisplayName("필수 JWT 설정이 없으면 애플리케이션 시작 실패")
    void rejectsMissingRequiredProperty(String missingProperty) {
        // Given
        String[] properties = Arrays.stream(VALID_PROPERTIES)
                .filter(property -> !property.startsWith(missingProperty + "="))
                .toArray(String[]::new);

        // When
        contextRunner
                .withPropertyValues(properties)
                .run(context -> {
                    // Then
                    assertThat(context).hasFailed();
                });
    }

    @Test
    @DisplayName("HTTP(S) URI 형식이 아닌 issuer면 애플리케이션 시작 실패")
    void rejectsInvalidIssuer() {
        // Given
        String[] properties = {
                "auth.jwt.issuer=identity.omagotchi.local",
                VALID_PROPERTIES[1],
                VALID_PROPERTIES[2]
        };

        // When
        contextRunner
                .withPropertyValues(properties)
                .run(context -> {
                    // Then
                    assertThat(context).hasFailed();
                });
    }

    @Test
    @DisplayName("허용 형식이 아닌 audience면 애플리케이션 시작 실패")
    void rejectsInvalidAudience() {
        // Given
        String[] properties = {
                VALID_PROPERTIES[0],
                "auth.jwt.audience=omagotchi api",
                VALID_PROPERTIES[2]
        };

        // When
        contextRunner
                .withPropertyValues(properties)
                .run(context -> {
                    // Then
                    assertThat(context).hasFailed();
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(JwtProperties.class)
    static class PropertiesConfiguration {
    }
}
