package site.omagotchi.gatewayservice.global.exception;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import site.omagotchi.gatewayservice.global.requestid.RequestId;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.BDDAssertions.then;

class GatewayErrorEventLoggerTest {

    private static final String REQUEST_ID = "0123456789abcdef0123456789abcdef";

    private final GatewayErrorEventLogger errorEventLogger = new GatewayErrorEventLogger();
    private final Logger logger =
            (Logger) LoggerFactory.getLogger(GatewayErrorEventLogger.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private boolean additive;

    @BeforeEach
    void captureEvents() {
        this.additive = this.logger.isAdditive();
        this.appender.start();
        this.logger.addAppender(this.appender);
        this.logger.setAdditive(false);
    }

    @AfterEach
    void stopCapturingEvents() {
        this.logger.setAdditive(this.additive);
        this.logger.detachAppender(this.appender);
        this.appender.stop();
    }

    @Test
    @DisplayName("같은 Gateway 오류의 안전한 오류 이벤트와 진단 이벤트를 1회 기록")
    void recordsSafeErrorAndLinkedDiagnosticOnce() {
        // Given
        MockServerWebExchange exchange = exchange();
        IllegalStateException failure = new IllegalStateException(
                "private-detail\r\nsecond-line\t\"quoted\""
        );

        // When
        this.errorEventLogger.logOnce(
                exchange,
                failure,
                CommonErrorCode.INTERNAL_SERVER_ERROR,
                500
        );
        this.errorEventLogger.logOnce(
                exchange,
                failure,
                CommonErrorCode.INTERNAL_SERVER_ERROR,
                500
        );

        // Then
        ILoggingEvent errorEvent = onlyEvent("gateway-service.error");
        then(errorEvent.getLevel()).isEqualTo(Level.ERROR);
        then(errorEvent.getFormattedMessage()).doesNotContain("private-detail");
        then(errorEvent.getThrowableProxy()).isNull();
        then(fields(errorEvent))
                .containsEntry("event.dataset", "gateway-service.error")
                .containsEntry("event.action", "http.server.request.failed")
                .containsEntry("event.outcome", "failure")
                .containsEntry("error.code", "COMMON_INTERNAL_SERVER_ERROR")
                .containsEntry("http.request.id", REQUEST_ID)
                .containsEntry("http.request.method", "GET")
                .containsEntry("http.response.status_code", 500)
                .doesNotContainKey("event.duration")
                .doesNotContainKey("gateway.route.id")
                .doesNotContainKey("http.route");

        ILoggingEvent diagnosticEvent = onlyEvent("gateway-service.diagnostic");
        then(fields(diagnosticEvent).get("event.id"))
                .isEqualTo(fields(errorEvent).get("event.id"));
        then(diagnosticEvent.getThrowableProxy().getClassName())
                .isEqualTo(IllegalStateException.class.getName());
        then(diagnosticEvent.getThrowableProxy().getMessage()).contains("private-detail");
    }

    @Test
    @DisplayName("서로 다른 Gateway 오류에 별도 Event ID 발급")
    void issuesDistinctEventIdsForDifferentFailures() {
        // Given
        MockServerWebExchange firstExchange = exchange();
        MockServerWebExchange secondExchange = exchange();
        IllegalStateException firstFailure = new IllegalStateException("first");
        IllegalStateException secondFailure = new IllegalStateException("second");

        // When
        this.errorEventLogger.logOnce(
                firstExchange,
                firstFailure,
                CommonErrorCode.INTERNAL_SERVER_ERROR,
                500
        );
        this.errorEventLogger.logOnce(
                secondExchange,
                secondFailure,
                CommonErrorCode.INTERNAL_SERVER_ERROR,
                500
        );

        // Then
        List<String> eventIds = events("gateway-service.error").stream()
                .map(event -> (String) fields(event).get("event.id"))
                .toList();
        then(eventIds).hasSize(2).doesNotHaveDuplicates();
        then(eventIds).allSatisfy(eventId -> then(eventId).isNotBlank());
    }

    private static MockServerWebExchange exchange() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/rules")
        );
        exchange.getAttributes().put(RequestId.ATTRIBUTE_NAME, new RequestId(REQUEST_ID));
        return exchange;
    }

    private static Map<String, Object> fields(ILoggingEvent event) {
        return event.getKeyValuePairs().stream()
                .collect(Collectors.toMap(pair -> pair.key, pair -> pair.value));
    }

    private ILoggingEvent onlyEvent(String dataset) {
        List<ILoggingEvent> matchingEvents = events(dataset);
        then(matchingEvents).singleElement();
        return matchingEvents.getFirst();
    }

    private List<ILoggingEvent> events(String dataset) {
        return this.appender.list.stream()
                .filter(event -> dataset.equals(fields(event).get("event.dataset")))
                .toList();
    }
}
