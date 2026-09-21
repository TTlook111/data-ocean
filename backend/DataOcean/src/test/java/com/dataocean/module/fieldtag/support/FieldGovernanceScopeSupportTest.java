package com.dataocean.module.fieldtag.support;

import com.dataocean.common.exception.BusinessException;
import com.dataocean.common.security.LoginUser;
import com.dataocean.common.security.UserContext;
import com.dataocean.module.metadata.entity.DbColumnMeta;
import com.dataocean.module.metadata.mapper.DbColumnMetaMapper;
import com.dataocean.module.permission.s1.service.IamS1CapabilityService;
import com.dataocean.module.permission.s1.support.IamS1AdminGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 字段治理写入前必须批量读取真实归属并逐源校验，任一失败零写入。
 */
@ExtendWith(MockitoExtension.class)
class FieldGovernanceScopeSupportTest {

    @Mock
    private IamS1AdminGuard adminGuard;
    @Mock
    private IamS1CapabilityService capabilityService;
    @Mock
    private DbColumnMetaMapper columnMetaMapper;
    @InjectMocks
    private FieldGovernanceScopeSupport support;

    @BeforeEach
    void login() {
        LoginUser loginUser = new LoginUser(
                7L, "alice", "x", "Alice",
                List.of("ROLE"), List.of("governance:field:manage"),
                List.of(new SimpleGrantedAuthority("governance:field:manage")));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.getAuthorities()));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void requireColumnsWritableRejectsWhenAnyColumnBelongsToAnotherDatasource() {
        DbColumnMeta owned = column(11L, 5L);
        DbColumnMeta foreign = column(22L, 6L);
        when(columnMetaMapper.selectBatchIds(List.of(11L, 22L))).thenReturn(List.of(owned, foreign));
        doAnswer(invocation -> {
            Long datasourceId = invocation.getArgument(2);
            if (Long.valueOf(6L).equals(datasourceId)) {
                throw new BusinessException(403, "无权维护该数据源的字段治理");
            }
            return null;
        }).when(adminGuard).requireDatasourceFunction(eq(7L), eq("governance:field:manage"), anyLong());

        assertThatThrownBy(() -> support.requireColumnsWritable(List.of(11L, 22L)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("无权");
    }

    @Test
    void requireColumnsWritableRejectsMissingColumnsWithoutPartialProcessing() {
        when(columnMetaMapper.selectBatchIds(List.of(11L, 22L))).thenReturn(List.of(column(11L, 5L)));

        assertThatThrownBy(() -> support.requireColumnsWritable(List.of(11L, 22L)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不存在");
    }

    @Test
    void requireColumnsWritableRejectsBrokenOwnership() {
        DbColumnMeta orphan = column(11L, null);
        when(columnMetaMapper.selectBatchIds(List.of(11L))).thenReturn(List.of(orphan));

        assertThatThrownBy(() -> support.requireColumnsWritable(List.of(11L)))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        org.assertj.core.api.Assertions.assertThat(exception.getCode()).isEqualTo(409));
        verify(adminGuard, never()).requireDatasourceFunction(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void requireColumnsVisibleRejectsUnauthorizedIdWithoutSilentFilter() {
        DbColumnMeta owned = column(11L, 5L);
        DbColumnMeta foreign = column(22L, 6L);
        when(columnMetaMapper.selectBatchIds(List.of(11L, 22L))).thenReturn(List.of(owned, foreign));
        doAnswer(invocation -> {
            Long datasourceId = invocation.getArgument(2);
            if (Long.valueOf(6L).equals(datasourceId)) {
                throw new BusinessException(403, "无权查看该数据源的字段治理数据");
            }
            return null;
        }).when(adminGuard).requireDatasourceFunction(eq(7L), eq("governance:field:view"), anyLong());

        assertThatThrownBy(() -> support.requireColumnsVisible(List.of(11L, 22L)))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        org.assertj.core.api.Assertions.assertThat(exception.getCode()).isEqualTo(403));
    }

    @Test
    void requireColumnsVisibleRejectsMoreThanMaxBatch() {
        java.util.List<Long> ids = java.util.stream.LongStream.rangeClosed(1, 201).boxed().toList();

        assertThatThrownBy(() -> support.requireColumnsVisible(ids))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("200");
        verify(columnMetaMapper, never()).selectBatchIds(org.mockito.ArgumentMatchers.anyCollection());
    }

    @Test
    void rejectExplicitDatasourceOutsideScopeIsForbiddenNotEmpty() {
        assertThatThrownBy(() -> support.rejectExplicitDatasourceOutsideScope(6L, List.of(5L)))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        org.assertj.core.api.Assertions.assertThat(exception.getCode()).isEqualTo(403));
    }

    private static DbColumnMeta column(Long id, Long datasourceId) {
        DbColumnMeta column = new DbColumnMeta();
        column.setId(id);
        column.setDatasourceId(datasourceId);
        return column;
    }
}
