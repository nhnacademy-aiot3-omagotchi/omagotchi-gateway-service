package site.omagotchi.gatewayservice.global.requestid;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.BDDAssertions.then;

class RequestIdWebFilterTest {

    private static final String REQUEST_ID = "0123456789abcdef0123456789abcdef";
    private static final String ANOTHER_REQUEST_ID = "abcdef0123456789abcdef0123456789";

    private final RequestIdWebFilter filter = new RequestIdWebFilter();

    @Test
    @DisplayName("정규 형식 Request ID를 요청·응답·Reactor Context에 유지")
    void preservesCanonicalRequestIdAcrossBoundaries() {
        // Given
        MockServerWebExchange exchange = exchangeWithRequestId(REQUEST_ID);
        AtomicReference<RequestId> attribute = new AtomicReference<>();
        AtomicReference<RequestId> contextValue = new AtomicReference<>();
        AtomicReference<String> downstreamHeader = new AtomicReference<>();

        // When
        filter.filter(exchange, filteredExchange -> Mono.deferContextual(context -> {
            attribute.set(filteredExchange.getAttribute(RequestId.ATTRIBUTE_NAME));
            contextValue.set(context.get(RequestId.class));
            downstreamHeader.set(filteredExchange.getRequest().getHeaders()
                    .getFirst(RequestId.HEADER_NAME));
            return filteredExchange.getResponse().setComplete();
        })).block();

        // Then
        then(attribute.get()).isEqualTo(new RequestId(REQUEST_ID));
        then(contextValue.get()).isEqualTo(new RequestId(REQUEST_ID));
        then(downstreamHeader.get()).isEqualTo(REQUEST_ID);
        then(exchange.getResponse().getHeaders().getFirst(RequestId.HEADER_NAME))
                .isEqualTo(REQUEST_ID);
    }

    @Test
    @DisplayName("Request ID가 없으면 정규 형식 ID 한 개 생성")
    void generatesRequestIdWhenMissing() {
        // Given
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/rules")
        );
        AtomicReference<String> downstreamHeader = new AtomicReference<>();

        // When
        filter.filter(exchange, filteredExchange -> {
            downstreamHeader.set(filteredExchange.getRequest().getHeaders()
                    .getFirst(RequestId.HEADER_NAME));
            return filteredExchange.getResponse().setComplete();
        }).block();

        // Then
        then(downstreamHeader.get()).matches("^[0-9a-f]{32}$");
        then(exchange.getResponse().getHeaders().getFirst(RequestId.HEADER_NAME))
                .isEqualTo(downstreamHeader.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            " ",
            "0123456789abcdef0123456789abcde",
            "0123456789ABCDEF0123456789ABCDEF",
            "01234567-89ab-cdef-0123-456789abcdef",
            "0123456789abcdef0123456789abcdef,abcdef0123456789abcdef0123456789"
    })
    @DisplayName("정규 형식이 아닌 Request ID를 새 ID로 교체")
    void replacesNonCanonicalRequestId(String inboundRequestId) {
        // Given
        MockServerWebExchange exchange = exchangeWithRequestId(inboundRequestId);
        AtomicReference<String> downstreamHeader = new AtomicReference<>();

        // When
        filter.filter(exchange, filteredExchange -> {
            downstreamHeader.set(filteredExchange.getRequest().getHeaders()
                    .getFirst(RequestId.HEADER_NAME));
            return Mono.empty();
        }).block();

        // Then
        then(downstreamHeader.get()).matches("^[0-9a-f]{32}$");
        then(downstreamHeader.get()).isNotEqualTo(inboundRequestId);
    }

    @Test
    @DisplayName("중복 Request ID를 새 단일 ID로 교체")
    void replacesDuplicateRequestIdsWithOneValue() {
        // Given
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/rules")
                        .header(RequestId.HEADER_NAME, REQUEST_ID, ANOTHER_REQUEST_ID)
        );
        AtomicReference<String> downstreamHeader = new AtomicReference<>();
        AtomicReference<Integer> headerCount = new AtomicReference<>();

        // When
        filter.filter(exchange, filteredExchange -> {
            downstreamHeader.set(filteredExchange.getRequest().getHeaders()
                    .getFirst(RequestId.HEADER_NAME));
            headerCount.set(filteredExchange.getRequest().getHeaders()
                    .getOrEmpty(RequestId.HEADER_NAME).size());
            return Mono.empty();
        }).block();

        // Then
        then(downstreamHeader.get()).matches("^[0-9a-f]{32}$");
        then(downstreamHeader.get()).isNotIn(REQUEST_ID, ANOTHER_REQUEST_ID);
        then(headerCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("동시에 처리한 요청의 Reactor Context가 서로 오염되지 않음")
    void isolatesRequestIdsBetweenConcurrentPublishers() {
        // Given
        AtomicReference<RequestId> firstContext = new AtomicReference<>();
        AtomicReference<RequestId> secondContext = new AtomicReference<>();

        Mono<Void> first = filter.filter(exchangeWithRequestId(REQUEST_ID), ignored ->
                Mono.deferContextual(context -> {
                    firstContext.set(context.get(RequestId.class));
                    return Mono.empty();
                })).subscribeOn(Schedulers.parallel());
        Mono<Void> second = filter.filter(exchangeWithRequestId(ANOTHER_REQUEST_ID), ignored ->
                Mono.deferContextual(context -> {
                    secondContext.set(context.get(RequestId.class));
                    return Mono.empty();
                })).subscribeOn(Schedulers.parallel());

        // When
        Mono.when(first, second).block();

        // Then
        then(firstContext.get()).isEqualTo(new RequestId(REQUEST_ID));
        then(secondContext.get()).isEqualTo(new RequestId(ANOTHER_REQUEST_ID));
    }

    private static MockServerWebExchange exchangeWithRequestId(String requestId) {
        return MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/rules")
                        .header(RequestId.HEADER_NAME, requestId)
        );
    }
}
