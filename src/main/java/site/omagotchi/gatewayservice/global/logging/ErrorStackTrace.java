package site.omagotchi.gatewayservice.global.logging;

import lombok.NonNull;

/**
 * 예외 메시지·입력값을 제외한 오류 호출 위치.
 * 중앙 로그용 예외 종류·클래스·메서드·파일명·줄 번호만 기록.
 */
public class ErrorStackTrace {

    private static final int MAX_CAUSES = 4;
    private static final int MAX_FRAMES = 12;
    private static final int MAX_CHARACTERS = 4096;
    private static final String TRUNCATED = "\n[truncated]";

    private ErrorStackTrace() {
    }

    /** 원인 예외를 포함한 호출 위치 요약. Suppressed 예외와 원본 메시지 제외. */
    public static String format(@NonNull Throwable failure) {
        StringBuilder result = new StringBuilder();
        Throwable current = failure;
        for (int depth = 0; current != null && depth < MAX_CAUSES; depth++) {
            if (depth > 0) {
                result.append("\nCaused by: ");
            }
            result.append(current.getClass().getName());
            StackTraceElement[] frames = current.getStackTrace();
            for (int index = 0; index < Math.min(frames.length, MAX_FRAMES); index++) {
                StackTraceElement frame = frames[index];
                result.append("\n  at ").append(frame.getClassName())
                        .append(".").append(frame.getMethodName())
                        .append("(").append(frame.getFileName())
                        .append(":").append(frame.getLineNumber()).append(")");
            }
            if (frames.length > MAX_FRAMES) {
                result.append("\n  [frames truncated]");
            }
            current = current.getCause();
        }
        // 순환 원인·긴 호출 경로에 따른 중앙 로그 크기 증가 제한
        if (current != null) {
            result.append("\n[causes truncated]");
        }
        if (result.length() > MAX_CHARACTERS) {
            result.setLength(MAX_CHARACTERS - TRUNCATED.length());
            result.append(TRUNCATED);
        }
        return result.toString();
    }
}
