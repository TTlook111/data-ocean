package com.dataocean.module.knowledge.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.common.security.LoginUser;
import com.dataocean.common.security.UserContext;
import com.dataocean.module.knowledge.dto.KnowledgeReviewRecordVO;
import com.dataocean.module.knowledge.entity.KnowledgeChunk;
import com.dataocean.module.knowledge.entity.KnowledgeDoc;
import com.dataocean.module.knowledge.entity.KnowledgeDocVersion;
import com.dataocean.module.knowledge.entity.KnowledgeReviewTask;
import com.dataocean.module.knowledge.enums.DocStatus;
import com.dataocean.module.knowledge.enums.ReviewStatus;
import com.dataocean.module.knowledge.mapper.KnowledgeChunkMapper;
import com.dataocean.module.knowledge.mapper.KnowledgeDocMapper;
import com.dataocean.module.knowledge.mapper.KnowledgeDocVersionMapper;
import com.dataocean.module.knowledge.mapper.KnowledgeReviewTaskMapper;
import com.dataocean.module.knowledge.service.VectorIndexTaskService;
import com.dataocean.module.knowledge.support.KnowledgeDependencySnapshotBuilder;
import com.dataocean.module.user.entity.SysUser;
import com.dataocean.module.user.mapper.UserMapper;
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

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeVersionServiceImplTest {

    @Mock
    private KnowledgeDocVersionMapper knowledgeDocVersionMapper;
    @Mock
    private KnowledgeDocMapper knowledgeDocMapper;
    @Mock
    private KnowledgeChunkMapper knowledgeChunkMapper;
    @Mock
    private KnowledgeReviewTaskMapper knowledgeReviewTaskMapper;
    @Mock
    private VectorIndexTaskService vectorIndexTaskService;
    @Mock
    private KnowledgeDependencySnapshotBuilder dependencySnapshotBuilder;
    @Mock
    private UserMapper userMapper;

    @InjectMocks
    private KnowledgeVersionServiceImpl knowledgeVersionService;

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    // ==================== 回滚 ====================

    @Test
    void rollbackUsesCurrentlyIndexedVersionAsPreviousVectorVersion() {
        setLoginUser();
        long docId = 99L;
        KnowledgeDocVersion targetVersion = KnowledgeDocVersion.builder()
                .docId(docId)
                .datasourceId(10L)
                .metadataSnapshotId(5L)
                .versionNo(1)
                .content("rollback content")
                .reviewStatus(ReviewStatus.APPROVED.name())
                .build();
        KnowledgeDocVersion createdVersion = KnowledgeDocVersion.builder()
                .docId(docId)
                .versionNo(4)
                .content("rollback content")
                .build();
        KnowledgeDoc docWithDraftVersion = KnowledgeDoc.builder()
                .id(docId)
                .datasourceId(10L)
                .currentVersion(3)
                .content("draft content")
                .status(DocStatus.PUBLISHED.name())
                .build();
        KnowledgeChunk indexedChunk = KnowledgeChunk.builder()
                .docId(docId)
                .versionNo(2)
                .vectorStatus("INDEXED")
                .build();

        // 第一次 selectOne 是回滚前的目标版本校验，第二次是回滚后取新版本行写审核状态
        when(knowledgeDocVersionMapper.selectOne(any(Wrapper.class)))
                .thenReturn(targetVersion, createdVersion);
        when(knowledgeChunkMapper.selectList(any(Wrapper.class))).thenReturn(List.of(indexedChunk));
        when(knowledgeDocMapper.selectById(docId)).thenReturn(docWithDraftVersion);
        when(dependencySnapshotBuilder.build(eq(10L), eq(5L), eq("ROLLBACK"))).thenReturn("{}");

        Integer newVersionNo = knowledgeVersionService.rollback(docId, 1);

        assertThat(newVersionNo).isEqualTo(4);
        verify(vectorIndexTaskService).createTask(10L, "DOC", docId, 5L, 4, 2);
        ArgumentCaptor<KnowledgeDoc> docCaptor = ArgumentCaptor.forClass(KnowledgeDoc.class);
        verify(knowledgeDocMapper, times(2)).updateById(docCaptor.capture());
        KnowledgeDoc finalDoc = docCaptor.getAllValues().get(1);
        assertThat(finalDoc.getStatus()).isEqualTo(DocStatus.INDEXING.name());
        assertThat(finalDoc.getCurrentVersion()).isEqualTo(4);
        assertThat(finalDoc.getContent()).isEqualTo("rollback content");
    }

    @Test
    void rollbackMarksNewVersionAsApprovedWithOperatorAsReviewer() {
        setLoginUser();
        long docId = 99L;
        KnowledgeDocVersion targetVersion = KnowledgeDocVersion.builder()
                .docId(docId)
                .datasourceId(10L)
                .metadataSnapshotId(5L)
                .versionNo(1)
                .content("rollback content")
                .reviewStatus(ReviewStatus.APPROVED.name())
                .build();
        KnowledgeDocVersion createdVersion = KnowledgeDocVersion.builder()
                .docId(docId)
                .versionNo(4)
                .build();
        KnowledgeDoc doc = KnowledgeDoc.builder()
                .id(docId)
                .datasourceId(10L)
                .currentVersion(3)
                .status(DocStatus.PUBLISHED.name())
                .build();

        when(knowledgeDocVersionMapper.selectOne(any(Wrapper.class)))
                .thenReturn(targetVersion, createdVersion);
        when(knowledgeChunkMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(knowledgeDocMapper.selectById(docId)).thenReturn(doc);
        when(dependencySnapshotBuilder.build(any(), any(), any())).thenReturn("{}");

        knowledgeVersionService.rollback(docId, 1);

        // 回滚版本的内容来自已校验为 APPROVED 的目标版本，因此其审核状态记为 APPROVED，
        // 审核人记为执行回滚的操作人（登录用户 7L）。
        ArgumentCaptor<KnowledgeDocVersion> versionCaptor = ArgumentCaptor.forClass(KnowledgeDocVersion.class);
        verify(knowledgeDocVersionMapper).updateById(versionCaptor.capture());
        assertThat(versionCaptor.getValue().getReviewStatus()).isEqualTo(ReviewStatus.APPROVED.name());
        assertThat(versionCaptor.getValue().getReviewerId()).isEqualTo(7L);
    }

    @Test
    void rollbackRejectsDocumentThatIsNotPublished() {
        setLoginUser();
        long docId = 99L;
        KnowledgeDoc draftDoc = KnowledgeDoc.builder()
                .id(docId)
                .datasourceId(10L)
                .currentVersion(1)
                .status(DocStatus.DRAFT.name())
                .build();
        when(knowledgeDocMapper.selectById(docId)).thenReturn(draftDoc);

        // P0：未发布的文档没有线上内容可退，且其内容应走正常审核流程。
        // 缺少该校验时，任何调用方都能对 DRAFT 文档回滚，把未审核内容直接写入 Milvus。
        assertThatThrownBy(() -> knowledgeVersionService.rollback(docId, 1))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("只有已发布状态的文档才能回滚");

        verify(vectorIndexTaskService, never()).createTask(any(), any(), any(), any(), any(), any());
        verify(knowledgeDocVersionMapper, never()).insert(any(KnowledgeDocVersion.class));
    }

    @Test
    void rollbackRejectsTargetVersionWithoutApprovedReview() {
        setLoginUser();
        long docId = 99L;
        KnowledgeDoc publishedDoc = KnowledgeDoc.builder()
                .id(docId)
                .datasourceId(10L)
                .currentVersion(3)
                .status(DocStatus.PUBLISHED.name())
                .build();
        KnowledgeDocVersion unapprovedTarget = KnowledgeDocVersion.builder()
                .docId(docId)
                .versionNo(1)
                .reviewStatus(ReviewStatus.PENDING.name())
                .build();
        when(knowledgeDocMapper.selectById(docId)).thenReturn(publishedDoc);
        when(knowledgeDocVersionMapper.selectOne(any(Wrapper.class))).thenReturn(unapprovedTarget);

        assertThatThrownBy(() -> knowledgeVersionService.rollback(docId, 1))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("只能回滚到审核已通过的版本");

        verify(vectorIndexTaskService, never()).createTask(any(), any(), any(), any(), any(), any());
    }

    // ==================== 版本创建 ====================

    @Test
    void createVersionWritesPendingReviewStatus() {
        setLoginUser();
        long docId = 99L;
        KnowledgeDoc doc = KnowledgeDoc.builder()
                .id(docId)
                .datasourceId(10L)
                .currentVersion(0)
                .build();
        when(knowledgeDocMapper.selectById(docId)).thenReturn(doc);
        when(dependencySnapshotBuilder.build(any(), any(), any())).thenReturn("{}");

        knowledgeVersionService.createVersion(docId, "content", "MANUAL", 5L, "摘要");

        // 该列在 V51 之前是死列（恒为建表默认值），现在由应用显式写入
        ArgumentCaptor<KnowledgeDocVersion> captor = ArgumentCaptor.forClass(KnowledgeDocVersion.class);
        verify(knowledgeDocVersionMapper).insert(captor.capture());
        assertThat(captor.getValue().getReviewStatus()).isEqualTo(ReviewStatus.PENDING.name());
    }

    // ==================== 审核记录查询 ====================

    @Test
    void listReviewRecordsResolvesVersionNoAndReviewerName() {
        long docId = 99L;
        KnowledgeDocVersion v1 = KnowledgeDocVersion.builder().id(100L).docId(docId).versionNo(1).build();
        KnowledgeDocVersion v2 = KnowledgeDocVersion.builder().id(200L).docId(docId).versionNo(2).build();
        KnowledgeReviewTask task = KnowledgeReviewTask.builder()
                .id(9L)
                .docVersionId(200L)
                .reviewerId(7L)
                .reviewStatus(ReviewStatus.REJECTED.name())
                .reviewComment("字段描述不清晰")
                .submittedAt(LocalDateTime.of(2026, 9, 1, 10, 0))
                .reviewedAt(LocalDateTime.of(2026, 9, 2, 11, 0))
                .build();
        SysUser reviewer = new SysUser();
        reviewer.setId(7L);
        reviewer.setRealName("管理员");

        when(knowledgeDocVersionMapper.selectList(any(Wrapper.class))).thenReturn(List.of(v2, v1));
        when(knowledgeReviewTaskMapper.selectList(any(Wrapper.class))).thenReturn(List.of(task));
        when(userMapper.selectByIds(anyCollection())).thenReturn(List.of(reviewer));

        List<KnowledgeReviewRecordVO> records = knowledgeVersionService.listReviewRecords(docId);

        assertThat(records).hasSize(1);
        KnowledgeReviewRecordVO record = records.get(0);
        assertThat(record.getVersionNo()).isEqualTo(2);
        assertThat(record.getReviewStatus()).isEqualTo(ReviewStatus.REJECTED.name());
        assertThat(record.getReviewComment()).isEqualTo("字段描述不清晰");
        assertThat(record.getReviewerName()).isEqualTo("管理员");
        assertThat(record.getReviewedAt()).isEqualTo(LocalDateTime.of(2026, 9, 2, 11, 0));
    }

    @Test
    void listReviewRecordsReturnsEmptyWhenDocumentHasNoVersions() {
        when(knowledgeDocVersionMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        assertThat(knowledgeVersionService.listReviewRecords(99L)).isEmpty();
        verify(knowledgeReviewTaskMapper, never()).selectList(any());
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
