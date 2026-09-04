package site.omagotchi.gatewayservice.global.requestid;

import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * 인증 처리 이전의 Request ID 확정과 요청·응답·Reactor Context 전파.
 * 정규 형식이 아닌 외부 값의 신규 Request ID 교체.
 */
@Component
public class RequestIdWebFilter implements WebFilter, Ordered {

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        RequestId requestId = RequestId.fromHeaderValues(
                exchange.getRequest().getHeaders().getOrEmpty(RequestId.HEADER_NAME)
        );
        ServerWebExchange requestIdExchange = exchange.mutate()
                .request(request -> request.headers(headers ->
                        headers.set(RequestId.HEADER_NAME, requestId.value())))
                .build();

        requestIdExchange.getAttributes().put(RequestId.ATTRIBUTE_NAME, requestId);
        requestIdExchange.getResponse().getHeaders().set(RequestId.HEADER_NAME, requestId.value());

        // 하위 서비스가 같은 응답 헤더를 덮어써도 Gateway에서 확정한 값 유지
        requestIdExchange.getResponse().beforeCommit(() -> {
            requestIdExchange.getResponse().getHeaders().set(RequestId.HEADER_NAME, requestId.value());
            return Mono.empty();
        });

        return chain.filter(requestIdExchange)
                .contextWrite(context -> context.put(RequestId.class, requestId));
    }
}
