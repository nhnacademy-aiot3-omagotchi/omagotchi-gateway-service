package site.omagotchi.gatewayservice.global.auth;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

public enum GlobalRole {

    // 시스템 사용자
    USER,

    // 시스템 전역 운영 관리자
    SYSTEM_ADMIN;

    public static boolean isSupported(String name) {
        return name != null && NAMES.contains(name);
    }

    private static final Set<String> NAMES = Arrays.stream(values())
            .map(Enum::name)
            .collect(Collectors.toUnmodifiableSet());
}
