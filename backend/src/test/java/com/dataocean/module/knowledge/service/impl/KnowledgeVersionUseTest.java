package com.dataocean.module.knowledge.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.common.security.IamPrincipal;
import com.dataocean.module.access.service.AccessScopeService;
import com.dataocean.module.audit.service.AuditService;
import com.dataocean.module.datasource.service.GovernanceCatalog;
import com.dataocean.module.datasource.service.GovernanceCatalog.Source;
import com.dataocean.module.iam.service.MutationService;
import com.dataocean.module.iam.service.PermissionService;
import com.dataocean.module.knowledge.mapper.KnowledgeDocumentDependencyMapper;
import com.dataocean.module.knowledge.mapper.KnowledgeDocumentMapper;
import com.dataocean.module.knowledge.mapper.KnowledgePublicationBatchMapper;
import com.dataocean.module.knowledge.mapper.KnowledgePublicationMapper;
import com.dataocean.module.knowledge.mapper.KnowledgePublicationMapper.Binding;
import com.dataocean.module.knowledge.mapper.KnowledgeVersionPauseMapper;
import com.dataocean.module.knowledge.mapper.KnowledgeWithdrawalMapper;
import com.dataocean.module.knowledge.service.KnowledgeDocumentLinks;
import com.dataocean.module.knowledge.service.KnowledgeReferences;
import com.dataocean.module.model.service.ModelRegistry;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 对照普通切版前后的原绑定与当前绑定，验证实时保护覆盖依赖图。
 */
class KnowledgeVersionUseTest {

    /**
     * 使用可核验且同源的测试发布记录。
     */
    private final KnowledgePublicationMapper mapper = mock(KnowledgePublicationMapper.class);

    /**
     * 构建核实时需要知道版本是否仍被保留。
     */
    private final KnowledgePublicationBatchMapper batches = mock(KnowledgePublicationBatchMapper.class);

    /**
     * 暂停独立于当前及历史绑定。
     */
    private final KnowledgeVersionPauseMapper pauses = mock(KnowledgeVersionPauseMapper.class);

    /**
     * 退出的历史发布不得继续使用。
     */
    private final KnowledgeWithdrawalMapper withdrawals = mock(KnowledgeWithdrawalMapper.class);

    /**
     * 验证采用旧组合的明确依赖。
     */
    private final KnowledgeDocumentDependencyMapper dependencies = mock(KnowledgeDocumentDependencyMapper.class);

    /**
     * 验证固定版本采用的模型仍启用。
     */
    private final ModelRegistry models = mock(ModelRegistry.class);

    /**
     * 原用户的源授权可能在执行期间失效。
     */
    private final AccessScopeService access = mock(AccessScopeService.class);

    /**
     * 使用真实依赖遍历和版本判定。
     */
    private KnowledgeVersionUse versions;

    /**
     * 设置两组独立文档在同一来源内各完成普通切换。
     */
    @BeforeEach
    void setup() {
        var catalog = mock(GovernanceCatalog.class);
        var documents = mock(KnowledgeDocumentMapper.class);
        when(catalog.source(1L, false))
                .thenReturn(new Source(1L, "隔离源", "ENABLED", 10L, 11L, 1, false, null, null, null, null));
        when(mapper.get(1L, 20L)).thenReturn(binding(20L, 2L, 21L));
        when(mapper.get(1L, 21L)).thenReturn(binding(21L, 2L, 21L));
        when(mapper.get(1L, 30L)).thenReturn(binding(30L, 3L, 31L));
        when(mapper.get(1L, 31L)).thenReturn(binding(31L, 3L, 31L));
        when(dependencies.requiredBindings(20L)).thenReturn(List.of(30L));
        versions = new KnowledgeVersionUse(
                catalog,
                mapper,
                batches,
                pauses,
                withdrawals,
                new KnowledgeDocumentLinks(documents, mapper, dependencies, withdrawals),
                documents,
                mock(KnowledgeReferences.class),
                models,
                mock(MutationService.class),
                mock(AuditService.class),
                mock(PermissionService.class),
                access);
        var actor = new IamPrincipal(7L, 1L, "viewer", "验收人");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(actor, null));
    }

    /**
     * 清理原会话上下文。
     */
    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
    }

    /**
     * 普通发布后，原轮次仍能读取两个旧绑定，新轮次只能用两个新绑定。
     */
    @Test
    void preservesTheFrozenBindingsAcrossACompatiblePublication() {
        assertThatThrownBy(() -> versions.requireForQuery(1L, 20L)).isInstanceOf(BizIllegalException.class);
        assertThat(versions.requirePinnedForQuery(1L, 20L).id()).isEqualTo(20L);
        verify(models).verifyActive(120L);
        verify(models).verifyActive(130L);
        verify(models, never()).verifyActive(131L);
        assertThat(versions.requireForQuery(1L, 21L).id()).isEqualTo(21L);
    }

    /**
     * 原依赖被暂停、撤下或失去同范围当前入口时，旧轮次也失去资格。
     */
    @Test
    void checksCurrentControlsOfTheFrozenDependency() {
        when(pauses.isPaused(3L)).thenReturn(true);
        assertThatThrownBy(() -> versions.requirePinnedForQuery(1L, 20L))
                .isInstanceOf(BizIllegalException.class)
                .hasMessageContaining("暂停");
        when(pauses.isPaused(3L)).thenReturn(false);
        when(withdrawals.withdrawn(30L)).thenReturn(true);
        assertThatThrownBy(() -> versions.requirePinnedForQuery(1L, 20L))
                .isInstanceOf(BizIllegalException.class)
                .hasMessageContaining("撤下");
        when(withdrawals.withdrawn(30L)).thenReturn(false);
        when(mapper.get(1L, 30L)).thenReturn(binding(30L, 3L, null));
        assertThatThrownBy(() -> versions.requirePinnedForQuery(1L, 20L))
                .isInstanceOf(BizIllegalException.class)
                .hasMessageContaining("未生效");
    }

    /**
     * 即使产物仍保留，撤权后不读取旧绑定，模型停用后不发起后续查询。
     */
    @Test
    void keepsRevocationAndModelAvailabilityEffectiveForRetainedBindings() {
        when(access.requireSource(7L, 1L))
                .thenThrow(BizIllegalException.denied())
                .thenReturn(null);
        assertThatThrownBy(() -> versions.requirePinnedForQuery(1L, 20L)).isInstanceOf(BizIllegalException.class);
        verify(mapper, never()).get(1L, 20L);
        doThrow(BizIllegalException.state("向量模型已停用")).when(models).verifyActive(130L);
        assertThatThrownBy(() -> versions.requirePinnedForQuery(1L, 20L))
                .isInstanceOf(BizIllegalException.class)
                .hasMessageContaining("模型已停用");
    }

    /**
     * 同一文档在切版前后使用不同索引，源与发布范围相同。
     */
    private Binding binding(long id, long scope, Long active) {
        var now = LocalDateTime.of(2026, 11, 26, 12, 0);
        return new Binding(
                id,
                scope,
                1L,
                4L,
                id,
                scope + 1000L,
                id,
                1,
                "验收发布",
                "build-" + id,
                "validation-" + id,
                id + 100L,
                null,
                active,
                "fixture",
                7L,
                now,
                now,
                1L);
    }
}
