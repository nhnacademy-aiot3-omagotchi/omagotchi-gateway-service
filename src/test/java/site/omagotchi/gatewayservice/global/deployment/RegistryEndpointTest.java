package site.omagotchi.gatewayservice.global.deployment;

import com.netflix.appinfo.ApplicationInfoManager;
import com.netflix.appinfo.InstanceInfo;
import com.netflix.discovery.EurekaClient;
import com.netflix.discovery.shared.Application;
import com.netflix.discovery.shared.Applications;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import site.omagotchi.gatewayservice.global.deployment.response.RegistryResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RegistryEndpointTest {

    @Test
    @DisplayName("로컬 Registry에서 UP인 실행만 반환, 서버 조회 없는 Cache 반영 확인")
    void reportsOnlyReadyInstancesFromLocalCache() {
        // Given
        EurekaClient client = mock(EurekaClient.class);
        ApplicationInfoManager manager = mock(ApplicationInfoManager.class);
        InstanceInfo current = InstanceInfo.Builder.newBuilder()
                .setAppName("CALLER").setInstanceId("caller-new-run").build();
        InstanceInfo ready = InstanceInfo.Builder.newBuilder()
                .setAppName("LEARNING-SERVICE").setInstanceId("learning-ready")
                .setStatus(InstanceInfo.InstanceStatus.UP).build();
        InstanceInfo draining = InstanceInfo.Builder.newBuilder()
                .setAppName("LEARNING-SERVICE").setInstanceId("learning-draining")
                .setStatus(InstanceInfo.InstanceStatus.OUT_OF_SERVICE).build();
        Application application = new Application("LEARNING-SERVICE");
        application.addInstance(ready);
        application.addInstance(draining);
        Applications applications = new Applications();
        applications.addApplication(application);
        when(client.getApplications()).thenReturn(applications);
        when(client.getApplicationInfoManager()).thenReturn(manager);
        when(manager.getInfo()).thenReturn(current);

        // When
        RegistryResponse snapshot = new RegistryEndpoint(client).registry();

        // Then
        assertThat(snapshot.instanceId()).isEqualTo("caller-new-run");
        assertThat(snapshot.services().get("LEARNING-SERVICE")).containsExactly("learning-ready");
        verify(client).getApplications();
    }
}
