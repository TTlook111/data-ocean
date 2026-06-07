package com.dataocean.module.knowledge.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.dataocean.common.security.LoginUser;
import com.dataocean.common.security.UserContext;
import com.dataocean.module.knowledge.entity.KnowledgeChunk;
import com.dataocean.module.knowledge.entity.KnowledgeDoc;
import com.dataocean.module.knowledge.entity.KnowledgeDocVersion;
import com.dataocean.module.knowledge.enums.DocStatus;
import com.dataocean.module.knowledge.mapper.KnowledgeChunkMapper;
import com.dataocean.module.knowledge.mapper.KnowledgeDocMapper;
import com.dataocean.module.knowledge.mapper.KnowledgeDocVersionMapper;
import com.dataocean.module.knowledge.service.VectorIndexTaskService;
import com.dataocean.module.knowledge.support.KnowledgeDependencySnapshotBuilder;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
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
    private VectorIndexTaskService vectorIndexTaskService;
    @Mock
    private KnowledgeDependencySnapshotBuilder dependencySnapshotBuilder;

    @InjectMocks
    private KnowledgeVersionServiceImpl knowledgeVersionService;

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

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
                .build();
        KnowledgeDoc docWithDraftVersion = KnowledgeDoc.builder()
                .id(docId)
                .datasourceId(10L)
                .currentVersion(3)
                .content("draft content")
                .status(DocStatus.APPROVED.name())
                .build();
        KnowledgeChunk indexedChunk = KnowledgeChunk.builder()
                .docId(docId)
                .versionNo(2)
                .vectorStatus("INDEXED")
                .build();

        when(knowledgeDocVersionMapper.selectOne(any(Wrapper.class))).thenReturn(targetVersion);
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
