package site.omagotchi.gatewayservice.global.requestid;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/** Gateway 내부의 소문자 16진수 32자리 Request ID. */
public record RequestId(@NonNull String value) {

    public static final String HEADER_NAME = "X-Request-ID";
    public static final String ATTRIBUTE_NAME = RequestId.class.getName();

    private static final Pattern VALID_VALUE = Pattern.compile("^[0-9a-f]{32}$");

    public RequestId {
        if (!isValid(value)) {
            throw new IllegalArgumentException("Request ID는 소문자 16진수 32자리여야 합니다.");
        }
    }

    public static RequestId fromHeaderValues(@NonNull List<String> values) {
        if (values.size() == 1 && isValid(values.getFirst())) {
            return new RequestId(values.getFirst());
        }
        return generate();
    }

    public static RequestId generate() {
        return new RequestId(UUID.randomUUID().toString().replace("-", ""));
    }

    public static boolean isValid(@Nullable String value) {
        return value != null && VALID_VALUE.matcher(value).matches();
    }
}
