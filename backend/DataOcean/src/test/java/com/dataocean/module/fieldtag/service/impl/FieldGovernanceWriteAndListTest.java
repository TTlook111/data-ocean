package com.dataocean.module.fieldtag.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.common.security.LoginUser;
import com.dataocean.common.security.UserContext;
import com.dataocean.module.fieldtag.entity.dto.BatchTagRequestDTO;
import com.dataocean.module.fieldtag.mapper.FieldConfidenceMapper;
import com.dataocean.module.fieldtag.mapper.FieldTagMapper;
import com.dataocean.module.fieldtag.mapper.PredefinedTagMapper;
import com.dataocean.module.fieldtag.support.FieldGovernanceScopeSupport;
import com.dataocean.module.metadata.mapper.DbColumnMetaMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 字段标签批量/CSV 必须先校验再写；列表在空负责源时不得回退成全库。
 */
@ExtendWith(MockitoExtension.class)
class FieldGovernanceWriteAndListTest {

    @Mock
    private FieldTagMapper fieldTagMapper;
    @Mock
    private PredefinedTagMapper predefinedTagMapper;
    @Mock
    private FieldGovernanceScopeSupport fieldScope;
    @InjectMocks
    private FieldTagServiceImpl fieldTagService;

    @Mock
    private com.dataocean.module.fieldtag.mapper.FieldConfidenceEventMapper eventMapper;
    @Mock
    private FieldConfidenceMapper confidenceMapper;
    @Mock
    private DbColumnMetaMapper dbColumnMetaMapper;

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @BeforeEach
    void login() {
        LoginUser loginUser = new LoginUser(
                7L, "alice", "x", "Alice",
                List.of(new SimpleGrantedAuthority("governance:field:manage")));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.getAuthorities()));
    }

    @Test
    void batchAddTagsDoesNotInsertWhenScopeCheckFails() {
        BatchTagRequestDTO request = new BatchTagRequestDTO();
        request.setColumnMetaIds(List.of(11L, 22L));
        request.setTagCode("AMOUNT");
        when(fieldScope.requireColumnsWritable(anyCollection()))
                .thenThrow(new BusinessException(403, "无权维护该数据源的字段治理"));

        assertThatThrownBy(() -> fieldTagService.batchAddTags(request))
                .isInstanceOf(BusinessException.class);

        verify(fieldTagMapper, never()).batchInsert(anyList());
        verify(predefinedTagMapper, never()).selectOne(any());
    }

    @Test
    void byTagReturnsEmptyWhenCallerHasNoResponsibleDatasource() {
        List<Long> result = fieldTagService.getColumnIdsByTagCodeInDatasources("AMOUNT", List.of());
        assertThat(result).isEmpty();
        verify(fieldTagMapper, never()).selectColumnIdsByTagCodeInDatasources(
                org.mockito.ArgumentMatchers.anyString(), anyCollection());
    }

    @Test
    void importCsvDoesNotWriteWhenAnyColumnIsOutsideScope() {
        ConfidenceTrendServiceImpl csvService = new ConfidenceTrendServiceImpl(
                eventMapper, confidenceMapper, fieldTagMapper, predefinedTagMapper, dbColumnMetaMapper, fieldScope);
        MockMultipartFile file = new MockMultipartFile(
                "file", "tags.csv", "text/csv",
                "column_id,tag_code\n11,AMOUNT\n22,TIME\n".getBytes(StandardCharsets.UTF_8));
        when(fieldScope.requireColumnsWritable(anyCollection()))
                .thenThrow(new BusinessException(403, "无权维护该数据源的字段治理"));

        assertThatThrownBy(() -> csvService.importTagsFromCsv(file))
                .isInstanceOf(BusinessException.class);

        verify(fieldTagMapper, never()).insert(any(com.dataocean.module.fieldtag.entity.FieldTag.class));
    }

    @Test
    void pageConfidenceReturnsEmptyPageWhenCallerHasNoResponsibleDatasource() {
        FieldConfidenceServiceImpl confidenceService = new FieldConfidenceServiceImpl(
                confidenceMapper, eventMapper,
                org.mockito.Mockito.mock(com.dataocean.module.fieldtag.service.ConfidenceCalculator.class),
                dbColumnMetaMapper, fieldScope);

        var page = confidenceService.pageConfidence(1, 20, null, null, List.of());

        assertThat(page.getTotal()).isZero();
        assertThat(page.getRecords()).isEmpty();
        verify(confidenceMapper, never()).selectPage(any(), any(Wrapper.class));
    }

    @Test
    void batchGetConfidenceRejectsUnauthorizedIdsInsteadOfSilentFilter() {
        FieldConfidenceServiceImpl confidenceService = new FieldConfidenceServiceImpl(
                confidenceMapper, eventMapper,
                org.mockito.Mockito.mock(com.dataocean.module.fieldtag.service.ConfidenceCalculator.class),
                dbColumnMetaMapper, fieldScope);
        when(fieldScope.requireColumnsVisible(anyCollection()))
                .thenThrow(new BusinessException(403, "无权查看该数据源的字段治理数据"));

        assertThatThrownBy(() -> confidenceService.batchGetConfidence(List.of(11L, 22L), List.of(5L)))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        org.assertj.core.api.Assertions.assertThat(exception.getCode()).isEqualTo(403));

        verify(confidenceMapper, never()).selectList(any(Wrapper.class));
    }

    @Test
    void batchGetConfidenceRejectsEmptyResponsibleScope() {
        FieldConfidenceServiceImpl confidenceService = new FieldConfidenceServiceImpl(
                confidenceMapper, eventMapper,
                org.mockito.Mockito.mock(com.dataocean.module.fieldtag.service.ConfidenceCalculator.class),
                dbColumnMetaMapper, fieldScope);

        assertThatThrownBy(() -> confidenceService.batchGetConfidence(List.of(11L), List.of()))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        org.assertj.core.api.Assertions.assertThat(exception.getCode()).isEqualTo(403));

        verify(fieldScope, never()).requireColumnsVisible(anyCollection());
        verify(confidenceMapper, never()).selectList(any(Wrapper.class));
    }

    @Test
    void importCsvRejectsOversizedFileBeforeParsing() {
        ConfidenceTrendServiceImpl csvService = new ConfidenceTrendServiceImpl(
                eventMapper, confidenceMapper, fieldTagMapper, predefinedTagMapper, dbColumnMetaMapper, fieldScope);
        org.springframework.web.multipart.MultipartFile file =
                org.mockito.Mockito.mock(org.springframework.web.multipart.MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getSize()).thenReturn(FieldGovernanceScopeSupport.MAX_CSV_BYTES + 1);

        assertThatThrownBy(() -> csvService.importTagsFromCsv(file))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("1MB");

        verify(fieldScope, never()).requireColumnsWritable(anyCollection());
        verify(fieldTagMapper, never()).insert(any(com.dataocean.module.fieldtag.entity.FieldTag.class));
    }
}
