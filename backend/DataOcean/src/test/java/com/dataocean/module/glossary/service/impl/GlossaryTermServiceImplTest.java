package com.dataocean.module.glossary.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.glossary.entity.Glossary;
import com.dataocean.module.glossary.entity.GlossaryTerm;
import com.dataocean.module.glossary.mapper.GlossaryMapper;
import com.dataocean.module.glossary.mapper.GlossaryTermMapper;
import com.dataocean.module.metadata.entity.MetadataRelationship;
import com.dataocean.module.metadata.service.MetadataRelationshipService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 术语条目服务测试。
 * <p>
 * 覆盖状态机收敛（P2-3.1）与删除清理关联数据（P2-3.2）两处修复。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class GlossaryTermServiceImplTest {

    @Mock
    private GlossaryTermMapper termMapper;
    @Mock
    private GlossaryMapper glossaryMapper;
    @Mock
    private MetadataRelationshipService relationshipService;

    private GlossaryTermServiceImpl service;

    @BeforeEach
    void setUp() {
        // LambdaUpdateWrapper 需要实体的 TableInfo 缓存。生产环境由 MyBatis 启动时扫描
        // Mapper 自动注册；纯单测下没有这一层，需手动注册，否则抛
        // "can not find lambda cache for this entity"。
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), GlossaryTerm.class);

        service = new GlossaryTermServiceImpl(glossaryMapper, relationshipService);
        // ServiceImpl 的 baseMapper 是非构造注入的父类字段，显式设置避免依赖 Mockito 的字段注入
        ReflectionTestUtils.setField(service, "baseMapper", termMapper);
    }

    // ==================== 修改术语的状态校验与字段白名单 ====================

    @Test
    void updateTermRejectsApprovedTerm() {
        when(termMapper.selectById(1L)).thenReturn(term(GlossaryTerm.STATUS_APPROVED));

        assertThatThrownBy(() -> service.updateTerm(1L, term(null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("只有 DRAFT 或 REJECTED 状态的术语才能修改");

        verify(termMapper, never()).updateById(any(GlossaryTerm.class));
    }

    @Test
    void updateTermRejectsPendingReviewTerm() {
        when(termMapper.selectById(1L)).thenReturn(term(GlossaryTerm.STATUS_PENDING_REVIEW));

        assertThatThrownBy(() -> service.updateTerm(1L, term(null)))
                .isInstanceOf(BusinessException.class);

        verify(termMapper, never()).updateById(any(GlossaryTerm.class));
    }

    @Test
    void updateTermDoesNotAcceptStatusFromRequestBody() {
        GlossaryTerm existing = term(GlossaryTerm.STATUS_DRAFT);
        existing.setReviewerId(999L);
        when(termMapper.selectById(1L)).thenReturn(existing);

        // 请求体试图直接把状态改成 APPROVED 并伪造审核人
        GlossaryTerm patch = term(GlossaryTerm.STATUS_APPROVED);
        patch.setReviewerId(42L);
        service.updateTerm(1L, patch);

        ArgumentCaptor<GlossaryTerm> captor = ArgumentCaptor.forClass(GlossaryTerm.class);
        verify(termMapper).updateById(captor.capture());
        // 白名单赋值：状态与审核记录只能由状态机自身推进，请求体改不动它们
        assertThat(captor.getValue().getStatus()).isEqualTo(GlossaryTerm.STATUS_DRAFT);
        assertThat(captor.getValue().getReviewerId()).isEqualTo(999L);
    }

    @Test
    void updateTermRegeneratesFqnWhenNameChanged() {
        GlossaryTerm existing = term(GlossaryTerm.STATUS_DRAFT);
        existing.setFqn("old-fqn");
        when(termMapper.selectById(1L)).thenReturn(existing);
        when(glossaryMapper.selectById(10L)).thenReturn(glossary());
        when(termMapper.selectByFqn(any())).thenReturn(null);

        GlossaryTerm patch = term(null);
        patch.setName("GMV2");
        service.updateTerm(1L, patch);

        ArgumentCaptor<GlossaryTerm> captor = ArgumentCaptor.forClass(GlossaryTerm.class);
        verify(termMapper).updateById(captor.capture());
        assertThat(captor.getValue().getName()).isEqualTo("GMV2");
        // 改名必须同步重建 FQN，否则旧 FQN 会一直占用 uk_term_fqn
        // （FQN 由 fqnGlossaryTerm 生成，会做小写化处理）
        assertThat(captor.getValue().getFqn()).isNotEqualTo("old-fqn").containsIgnoringCase("gmv2");
    }

    // ==================== 退回草稿 ====================

    @Test
    void revertToDraftRejectsNonApprovedTerm() {
        when(termMapper.selectById(1L)).thenReturn(term(GlossaryTerm.STATUS_DRAFT));

        assertThatThrownBy(() -> service.revertToDraft(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("只有已通过的术语才能退回草稿");
    }

    @Test
    void revertToDraftClearsReviewRecordWithUpdateWrapper() {
        when(termMapper.selectById(1L)).thenReturn(term(GlossaryTerm.STATUS_APPROVED));

        service.revertToDraft(1L);

        // 必须走 UpdateWrapper 而不是 updateById：updateById 会跳过 null 字段，
        // 清不掉 reviewer_id / reviewed_at，会留下「已审核」的假标记。
        ArgumentCaptor<Wrapper<GlossaryTerm>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(termMapper).update(isNull(), captor.capture());
        verify(termMapper, never()).updateById(any(GlossaryTerm.class));
        assertThat(captor.getValue()).isInstanceOf(LambdaUpdateWrapper.class);
        String sqlSet = ((LambdaUpdateWrapper<GlossaryTerm>) captor.getValue()).getSqlSet();
        assertThat(sqlSet).contains("status").contains("reviewer_id").contains("reviewed_at");
    }

    // ==================== 删除清理关联数据 ====================

    @Test
    void deleteTermRemovesGlossaryOfRelations() {
        when(termMapper.selectById(1L)).thenReturn(term(GlossaryTerm.STATUS_APPROVED));
        when(relationshipService.getBySource(1L, "GLOSSARY_TERM")).thenReturn(List.of(
                relationship(11L, MetadataRelationship.TYPE_GLOSSARY_OF),
                relationship(12L, "OTHER_TYPE")));

        service.deleteTerm(1L);

        // 只清理 GLOSSARY_OF，其他类型的关系不动
        verify(relationshipService).removeById(11L);
        verify(relationshipService, never()).removeById(12L);
        verify(termMapper).deleteById(1L);
        verify(termMapper).update(isNull(), any(Wrapper.class));
    }

    @Test
    void deleteTermsOfGlossaryCascadesAndCleansRelations() {
        GlossaryTerm first = term(GlossaryTerm.STATUS_APPROVED);
        GlossaryTerm second = term(GlossaryTerm.STATUS_APPROVED);
        second.setId(2L);
        when(termMapper.selectList(any(Wrapper.class))).thenReturn(List.of(first, second));
        when(relationshipService.getBySource(1L, "GLOSSARY_TERM")).thenReturn(List.of(
                relationship(21L, MetadataRelationship.TYPE_GLOSSARY_OF)));
        when(relationshipService.getBySource(2L, "GLOSSARY_TERM")).thenReturn(List.of(
                relationship(22L, MetadataRelationship.TYPE_GLOSSARY_OF)));

        int removed = service.deleteTermsOfGlossary(10L);

        assertThat(removed).isEqualTo(2);
        // 每个术语各自的 GLOSSARY_OF 关系都要清理，否则会留下指向已删术语的孤儿关系
        verify(relationshipService).removeById(21L);
        verify(relationshipService).removeById(22L);
        verify(termMapper).delete(any(Wrapper.class));
    }

    @Test
    void deleteTermsOfGlossaryReturnsZeroWhenEmpty() {
        when(termMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        assertThat(service.deleteTermsOfGlossary(10L)).isZero();
        verify(relationshipService, never()).removeById(any(Long.class));
        verify(termMapper, never()).delete(any(Wrapper.class));
    }

    private GlossaryTerm term(String status) {
        GlossaryTerm term = new GlossaryTerm();
        term.setId(1L);
        term.setGlossaryId(10L);
        term.setName("GMV");
        term.setStatus(status);
        term.setFqn("fqn-gmv");
        return term;
    }

    private Glossary glossary() {
        Glossary glossary = new Glossary();
        glossary.setId(10L);
        glossary.setName("销售域");
        return glossary;
    }

    private MetadataRelationship relationship(Long id, String type) {
        MetadataRelationship rel = new MetadataRelationship();
        rel.setId(id);
        rel.setRelationType(type);
        return rel;
    }
}
