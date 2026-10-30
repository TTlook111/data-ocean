package com.dataocean.module.permission.s1.controller;

import com.dataocean.common.security.UserContext;
import com.dataocean.module.datasource.entity.vo.DatasourceReadinessVO;
import com.dataocean.module.datasource.service.DatasourceReadinessService;
import com.dataocean.module.permission.s1.entity.vo.IamS1DatasourceRefVO;
import com.dataocean.module.permission.s1.service.IamS1UserResourceService;
import com.dataocean.module.permission.s1.support.IamS1AdminGuard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IamS1UserResourceControllerTest {

    @Mock private IamS1UserResourceService userResourceService;
    @Mock private IamS1AdminGuard iamS1AdminGuard;
    @Mock private DatasourceReadinessService readinessService;
    @InjectMocks private IamS1UserResourceController controller;

    @Test
    void readinessRequiresQueryUseAndS1VisibleDatasource() {
        when(userResourceService.datasources(7L, "QUERY"))
                .thenReturn(List.of(new IamS1DatasourceRefVO(7L, "销售库", true)));
        when(readinessService.getCurrentUserReadiness(7L))
                .thenReturn(DatasourceReadinessVO.builder().datasourceId(7L).permissionReady(true).build());

        try (MockedStatic<UserContext> userContext = org.mockito.Mockito.mockStatic(UserContext.class)) {
            userContext.when(UserContext::currentUserId).thenReturn(7L);
            var result = controller.readiness(7L);

            assertThat(result.getData().isPermissionReady()).isTrue();
        }

        verify(iamS1AdminGuard).requireGlobalFunction(eq(7L), eq("query:use"));
        verify(userResourceService).datasources(7L, "QUERY");
    }
}
