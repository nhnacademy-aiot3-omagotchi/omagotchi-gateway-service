package site.omagotchi.gatewayservice.global.deployment;

import com.netflix.appinfo.InstanceInfo;
import com.netflix.discovery.EurekaClient;
import com.netflix.discovery.shared.Application;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import site.omagotchi.gatewayservice.global.deployment.response.RegistryResponse;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** 배포 중 호출 대상 반영 확인을 위한 로컬 Eureka 목록 조회. */
@Component
@Endpoint(id = "registry")
@ConditionalOnProperty(name = "eureka.client.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class RegistryEndpoint {

    private final EurekaClient eurekaClient;

    @ReadOperation
    public RegistryResponse registry() {
        Map<String, List<String>> services = new TreeMap<>();
        // 중앙 서버의 목록이 아닌, 이 앱이 호출 대상을 고를 때 사용하는 로컬 캐시 조회.
        for (Application application : eurekaClient.getApplications().getRegisteredApplications()) {
            List<String> instances = application.getInstances().stream()
                    .filter(instance -> instance.getStatus() == InstanceInfo.InstanceStatus.UP)
                    .map(InstanceInfo::getInstanceId)
                    .sorted()
                    .toList();
            services.put(application.getName(), instances);
        }
        return new RegistryResponse(
                eurekaClient.getApplicationInfoManager().getInfo().getInstanceId(), services);
    }
}
