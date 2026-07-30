package site.omagotchi.gatewayservice.global.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;
import org.springframework.validation.annotation.Validated;

// 잘못된 JWT 설정은 바인딩 단계에서 검증해 애플리케이션 시작 차단
@Validated
@ConfigurationProperties(prefix = "auth.jwt")
public record JwtProperties(

        @NotBlank(message = "auth.jwt.issuer는 비어 있을 수 없습니다.")
        @Pattern(
                regexp = "https?://\\S+",
                message = "auth.jwt.issuer는 유효한 HTTP(S) URI여야 합니다."
        )
        String issuer,

        @NotBlank(message = "auth.jwt.audience는 비어 있을 수 없습니다.")
        @Pattern(
                regexp = "[A-Za-z0-9][A-Za-z0-9._:-]*",
                message = "auth.jwt.audience 형식이 올바르지 않습니다."
        )
        String audience,

        @NotNull(message = "auth.jwt.public-key-location은 필수입니다.")
        Resource publicKeyLocation
) {
}
