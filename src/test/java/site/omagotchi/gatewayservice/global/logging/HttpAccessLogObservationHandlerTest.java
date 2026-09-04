package site.omagotchi.gatewayservice.global.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.observation.ServerRequestObservationContext;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpResponse;
import site.omagotchi.gatewayservice.global.requestid.RequestId;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.BDDAssertions.then;

class HttpAccessLogObservationHandlerTest {

    private static final String REQUEST_ID = "0123456789abcdef0123456789abcdef";

    private final HttpAccessLogObservationHandler handler = new HttpAccessLogObservationHandler();

    @Test
    @DisplayName("연결 중단 전 확정된 응답 상태 유지")
    void preservesCommittedStatusWhenConnectionIsAborted() {
        // Given
        ServerRequestObservationContext context = new ServerRequestObservationContext(
                MockServerHttpRequest.get("/api/v1/rules").build(),
                new MockServerHttpResponse(),
                new HashMap<>()
        );
        context.getResponse().setStatusCode(HttpStatus.NO_CONTENT);
        context.getResponse().setComplete().block();
        context.setConnectionAborted(true);

        // When
        ILoggingEvent event = recordEvent(context);

        // Then
        then(event.getLevel()).isEqualTo(Level.INFO);
        then(fields(event))
                .containsEntry("http.response.status_code", 204)
                .containsEntry("event.outcome", "unknown");
    }

    @Test
    @DisplayName("전송 전에 연결이 중단된 응답 상태는 미확정으로 처리")
    void leavesUncommittedStatusUnknownWhenConnectionIsAborted() {
        // Given
        ServerRequestObservationContext context = new ServerRequestObservationContext(
                MockServerHttpRequest.get("/api/v1/rules").build(),
                new MockServerHttpResponse(),
                new HashMap<>()
        );
        context.getResponse().setStatusCode(HttpStatus.NO_CONTENT);
        context.setConnectionAborted(true);

        // When
        ILoggingEvent event = recordEvent(context);

        // Then
        then(event.getLevel()).isEqualTo(Level.INFO);
        then(fields(event))
                .doesNotContainKey("http.response.status_code")
                .containsEntry("event.outcome", "unknown");
    }

    @Test
    @DisplayName("처리되지 않은 예외는 메시지 없이 유형만 ERROR 이벤트에 기록")
    void recordsOnlySafeExceptionTypeAtErrorLevel() {
        // Given
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(RequestId.ATTRIBUTE_NAME, new RequestId(REQUEST_ID));
        ServerRequestObservationContext context = new ServerRequestObservationContext(
                MockServerHttpRequest.get("/api/v1/rules").build(),
                new MockServerHttpResponse(),
                attributes
        );
        context.setError(new IllegalStateException("must-not-appear"));

        // When
        ILoggingEvent event = recordEvent(context);

        // Then
        then(event.getLevel()).isEqualTo(Level.ERROR);
        then(event.getFormattedMessage()).doesNotContain("must-not-appear");
        then(event.getThrowableProxy()).isNull();
        then(fields(event))
                .containsEntry("http.request.id", REQUEST_ID)
                .containsEntry("http.response.status_code", 500)
                .containsEntry("event.outcome", "failure")
                .containsEntry("error.type", IllegalStateException.class.getName());
    }

    @Test
    @DisplayName("예외로 표현된 4xx 응답은 알림 대상 ERROR로 올리지 않음")
    void keepsClientErrorsAtInfoLevel() {
        // Given
        ServerRequestObservationContext context = new ServerRequestObservationContext(
                MockServerHttpRequest.get("/api/v1/rules").build(),
                new MockServerHttpResponse(),
                new HashMap<>()
        );
        context.getResponse().setStatusCode(HttpStatus.NOT_FOUND);
        context.setError(new IllegalStateException("framework-control-flow"));

        // When
        ILoggingEvent event = recordEvent(context);

        // Then
        then(event.getLevel()).isEqualTo(Level.INFO);
        then(fields(event))
                .containsEntry("http.response.status_code", 404)
                .containsEntry("event.outcome", "failure");
    }

    private ILoggingEvent recordEvent(ServerRequestObservationContext context) {
        Logger logger = (Logger) LoggerFactory.getLogger(HttpAccessLogObservationHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        boolean additive = logger.isAdditive();
        appender.start();
        logger.addAppender(appender);
        logger.setAdditive(false);

        try {
            handler.onStart(context);
            handler.onStop(context);
            then(appender.list).singleElement();
            return appender.list.getFirst();
        } finally {
            logger.setAdditive(additive);
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    private static Map<String, Object> fields(ILoggingEvent event) {
        return event.getKeyValuePairs().stream()
                .collect(Collectors.toMap(pair -> pair.key, pair -> pair.value));
    }
}
