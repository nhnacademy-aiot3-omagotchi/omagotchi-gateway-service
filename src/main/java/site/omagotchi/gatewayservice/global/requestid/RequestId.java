package site.omagotchi.gatewayservice.global.requestid;

import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/** 로그·응답·내부 호출에서 함께 사용하는 최대 32자의 Request ID. */
@Slf4j
public record RequestId(@NonNull String value) {

    public static final String HEADER_NAME = "X-Request-ID";
    public static final String ATTRIBUTE_NAME = RequestId.class.getName();

    private static final int MAX_LENGTH = 32;
    private static final Pattern ALLOWED_CHARACTERS = Pattern.compile("[A-Za-z0-9._-]+");

    public RequestId {
        if (!isValid(value)) {
            throw new IllegalArgumentException("Request ID는 영문·숫자·점·밑줄·하이픈으로 이루어진 1~32자여야 합니다.");
        }
    }

    /** 단일 수신값의 보존·길이 제한과 모호한 입력의 신규 발급. */
    public static RequestId fromHeaderValues(@NonNull List<String> values) {
        if (values.isEmpty() || (values.size() == 1 && values.getFirst().isEmpty())) {
            return generate();
        }

        // 자르기 전에 전체 문자 검사: 뒷부분의 잘못된 문자를 숨기지 않는 처리
        if (values.size() == 1 && ALLOWED_CHARACTERS.matcher(values.getFirst()).matches()) {
            String incoming = values.getFirst();
            if (incoming.length() <= MAX_LENGTH) {
                return new RequestId(incoming);
            }

            RequestId requestId = new RequestId(incoming.substring(0, MAX_LENGTH));
            log.atWarn().addKeyValue("http.request.id", requestId.value())
                    .log("Request ID truncated: length limit exceeded");
            return requestId;
        }

        // 원문 제외: 안전하지 않은 입력이 로그에 다시 노출되지 않도록 확정값만 기록
        RequestId requestId = generate();
        log.atWarn().addKeyValue("http.request.id", requestId.value())
                .log("Request ID regenerated: invalid or duplicate header");
        return requestId;
    }

    /** 기존 생성 형식인 소문자 16진수 32자리의 신규 발급. */
    public static RequestId generate() {
        return new RequestId(UUID.randomUUID().toString().replace("-", ""));
    }

    public static boolean isValid(@Nullable String value) {
        return value != null
                && value.length() <= MAX_LENGTH
                && ALLOWED_CHARACTERS.matcher(value).matches();
    }
}
