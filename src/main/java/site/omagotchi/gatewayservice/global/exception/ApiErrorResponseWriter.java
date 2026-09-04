package site.omagotchi.gatewayservice.global.exception;

import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

/** 공통 API 오류 본문의 JSON 직렬화와 WebFlux 응답 기록. */
@Component
@RequiredArgsConstructor
public class ApiErrorResponseWriter {

    private final ObjectMapper objectMapper;

    public Mono<Void> write(
            ServerHttpResponse response,
            ErrorCode errorCode,
            String path,
            String requestId
    ) {
        ApiErrorResponse body = new ApiErrorResponse(
                errorCode.code(),
                errorCode.message(),
                path,
                requestId
        );

        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        return Mono.fromCallable(() -> objectMapper.writeValueAsBytes(body))
                .flatMap(bytes -> response.writeWith(Mono.just(
                        response.bufferFactory().wrap(bytes)
                )));
    }
}
