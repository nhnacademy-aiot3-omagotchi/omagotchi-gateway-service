package site.omagotchi.gatewayservice.global.logging;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.BDDAssertions.then;

class ErrorStackTraceTest {

    @Test
    @DisplayName("예외 메시지 없이 오류 종류와 원인별 호출 위치 보존")
    void keepsLocationsWithoutMessages() {
        // Given
        IllegalArgumentException cause = new IllegalArgumentException("email=private@example.test");
        cause.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("site.omagotchi.ExampleService", "find", "ExampleService.java", 42)
        });
        IllegalStateException failure = new IllegalStateException("token=private-token", cause);
        failure.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("site.omagotchi.ExampleController", "get", "ExampleController.java", 21)
        });
        failure.addSuppressed(new IllegalStateException("SELECT private_value"));

        // When
        String summary = ErrorStackTrace.format(failure);

        // Then
        then(summary)
                .contains("java.lang.IllegalStateException",
                        "ExampleController.get(ExampleController.java:21)",
                        "Caused by: java.lang.IllegalArgumentException",
                        "ExampleService.find(ExampleService.java:42)")
                .doesNotContain("private@example.test", "private-token", "SELECT", "Suppressed");
    }

    @Test
    @DisplayName("순환 원인과 긴 호출 경로의 출력 크기 제한")
    void boundsCyclicAndLargeStacks() {
        // Given
        IllegalStateException first = new IllegalStateException("first-secret");
        IllegalStateException second = new IllegalStateException("second-secret", first);
        first.initCause(second);
        StackTraceElement[] frames = new StackTraceElement[100];
        Arrays.fill(frames, new StackTraceElement(
                "site.omagotchi." + "LongClassName".repeat(20), "invoke", "Example.java", 42
        ));
        first.setStackTrace(frames);
        second.setStackTrace(frames);

        // When
        String summary = ErrorStackTrace.format(first);

        // Then
        then(summary).hasSizeLessThanOrEqualTo(4096)
                .endsWith("[truncated]")
                .doesNotContain("first-secret", "second-secret");
    }

    @Test
    @DisplayName("호출 위치가 없는 예외의 종류 보존")
    void keepsTypeWithoutFrames() {
        // Given
        IllegalStateException failure = new IllegalStateException("private-detail");
        failure.setStackTrace(new StackTraceElement[0]);

        // When
        String summary = ErrorStackTrace.format(failure);

        // Then
        then(summary).isEqualTo("java.lang.IllegalStateException");
    }
}
