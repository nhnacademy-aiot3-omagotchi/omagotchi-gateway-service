package site.omagotchi.gatewayservice.global.deployment.response;

import java.util.List;
import java.util.Map;

/** 현재 실행 ID와 로컬 Eureka 캐시에 등록된 서비스별 UP 인스턴스 목록. */
public record RegistryResponse(
        String instanceId,
        Map<String, List<String>> services
) {
}
