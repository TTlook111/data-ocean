package com.dataocean.module.knowledge.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.common.security.LoginUser;
import com.dataocean.common.security.UserContext;
import com.dataocean.module.knowledge.entity.KnowledgeDoc;
import com.dataocean.module.knowledge.entity.KnowledgeDocVersion;
import com.dataocean.module.knowledge.entity.KnowledgeReviewTask;
import com.dataocean.module.knowledge.enums.DocStatus;
import com.dataocean.module.knowledge.enums.ReviewStatus;
import com.dataocean.module.knowledge.mapper.KnowledgeDocMapper;
import com.dataocean.module.knowledge.mapper.KnowledgeDocVersionMapper;
import com.dataocean.module.knowledge.mapper.KnowledgeReviewTaskMapper;
import com.dataocean.module.knowledge.service.VectorIndexTaskService;
import com.dataocean.module.metadata.mapper.DbColumnMetaMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 知识文档生命周期服务测试。
 * <p>
 * 重点覆盖审核结果写入版本行（`knowledge_doc_version.review_status` / `reviewer_id`）。
 * 该列在 V51 之前从未被写入，恒为建表默认值 `PENDING`，会误导任何按版本展示审核状态的地方。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeDocLifecycleServiceTest {

    @Mock
    private KnowledgeDocMapper knowledgeDocMapper;
    @Mock
    private KnowledgeDocVersionMapper knowledgeDocVersionMapper;
    @Mock
    private KnowledgeReviewTaskMapper knowledgeReviewTaskMapper;
    @Mock
    private VectorIndexTaskService vectorIndexTaskService;
    @Mock
    private DbColumnMetaMapper dbColumnMetaMapper;
    @Mock
    private KnowledgeDocHelper helper;

    @InjectMocks
    private KnowledgeDocLifecycleService lifecycleService;

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void approveWritesReviewStatusOntoVersionRow() {
        setLoginUser();
        KnowledgeDoc doc = knowledgeDoc("2", DocStatus.PENDING_REVIEW);
        KnowledgeDocVersion version = version(200L, 2);
        when(helper.requireDoc(1L)).thenReturn(doc);
        when(knowledgeDocVersionMapper.selectOne(any(Wrapper.class))).thenReturn(version);

        lifecycleService.approve(1L, "内容准确，可以发布");

        ArgumentCaptor<KnowledgeDocVersion> versionCaptor = ArgumentCaptor.forClass(KnowledgeDocVersion.class);
        verify(knowledgeDocVersionMapper).updateById(versionCaptor.capture());
        assertThat(versionCaptor.getValue().getReviewStatus()).isEqualTo(ReviewStatus.APPROVED.name());
        assertThat(versionCaptor.getValue().getReviewerId()).isEqualTo(7L);

        ArgumentCaptor<KnowledgeReviewTask> taskCaptor = ArgumentCaptor.forClass(KnowledgeReviewTask.class);
        verify(knowledgeReviewTaskMapper).insert(taskCaptor.capture());
        assertThat(taskCaptor.getValue().getDocVersionId()).isEqualTo(200L);
        assertThat(taskCaptor.getValue().getReviewComment()).isEqualTo("内容准确，可以发布");
        assertThat(taskCaptor.getValue().getReviewStatus()).isEqualTo(ReviewStatus.APPROVED.name());
    }

    @Test
    void rejectWritesRejectedReviewStatusOntoVersionRow() {
        setLoginUser();
        KnowledgeDoc doc = knowledgeDoc("2", DocStatus.PENDING_REVIEW);
        KnowledgeDocVersion version = version(200L, 2);
        when(helper.requireDoc(1L)).thenReturn(doc);
        when(knowledgeDocVersionMapper.selectOne(any(Wrapper.class))).thenReturn(version);

        lifecycleService.reject(1L, "字段描述不清晰");

        ArgumentCaptor<KnowledgeDocVersion> versionCaptor = ArgumentCaptor.forClass(KnowledgeDocVersion.class);
        verify(knowledgeDocVersionMapper).updateById(versionCaptor.capture());
        assertThat(versionCaptor.getValue().getReviewStatus()).isEqualTo(ReviewStatus.REJECTED.name());
        assertThat(versionCaptor.getValue().getReviewerId()).isEqualTo(7L);
    }

    @Test
    void approveStillSucceedsWhenVersionRowIsMissing() {
        setLoginUser();
        KnowledgeDoc doc = knowledgeDoc("2", DocStatus.PENDING_REVIEW);
        when(helper.requireDoc(1L)).thenReturn(doc);
        // 版本行缺失属数据异常，不应阻断审核本身——文档主表状态已经更新
        when(knowledgeDocVersionMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        lifecycleService.approve(1L, null);

        assertThat(doc.getStatus()).isEqualTo(DocStatus.APPROVED.name());
        verify(knowledgeDocMapper).updateById(doc);
        verify(knowledgeDocVersionMapper, never()).updateById(any(KnowledgeDocVersion.class));
        ArgumentCaptor<KnowledgeReviewTask> taskCaptor = ArgumentCaptor.forClass(KnowledgeReviewTask.class);
        verify(knowledgeReviewTaskMapper).insert(taskCaptor.capture());
        assertThat(taskCaptor.getValue().getDocVersionId()).isNull();
    }

    @Test
    void approveRejectsDocumentNotInPendingReview() {
        setLoginUser();
        KnowledgeDoc doc = knowledgeDoc("2", DocStatus.DRAFT);
        when(helper.requireDoc(1L)).thenReturn(doc);

        assertThatThrownBy(() -> lifecycleService.approve(1L, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("只有待审核状态的文档才能审核");

        verify(knowledgeReviewTaskMapper, never()).insert(any(KnowledgeReviewTask.class));
    }

    private KnowledgeDoc knowledgeDoc(String currentVersion, DocStatus status) {
        return KnowledgeDoc.builder()
                .id(1L)
                .datasourceId(10L)
                .currentVersion(Integer.valueOf(currentVersion))
                .status(status.name())
                .build();
    }

    private KnowledgeDocVersion version(Long id, Integer versionNo) {
        return KnowledgeDocVersion.builder()
                .id(id)
                .docId(1L)
                .versionNo(versionNo)
                .reviewStatus(ReviewStatus.PENDING.name())
                .build();
    }

    private void setLoginUser() {
        LoginUser loginUser = new LoginUser(
                7L,
                "admin",
                "password",
                "管理员",
                List.of("ADMIN"),
                List.of("knowledge:manage"),
                List.of(new SimpleGrantedAuthority("knowledge:manage"))
        );
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                loginUser,
                null,
                loginUser.getAuthorities()
        );
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
