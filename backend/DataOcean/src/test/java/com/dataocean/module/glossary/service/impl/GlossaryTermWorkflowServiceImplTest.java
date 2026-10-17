package com.dataocean.module.glossary.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.common.security.*;
import com.dataocean.module.glossary.entity.dto.*;
import com.dataocean.module.glossary.entity.po.*;
import com.dataocean.module.glossary.entity.vo.TermColumnVO;
import com.dataocean.module.glossary.mapper.*;
import com.dataocean.module.glossary.service.*;
import com.dataocean.module.metadata.entity.po.MetadataRelationship;
import com.dataocean.module.metadata.service.MetadataRelationshipService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;

class GlossaryTermWorkflowServiceImplTest {
    final GlossaryTermMapper mapper = mock(GlossaryTermMapper.class);
    final GlossaryTermReviewMapper reviews = mock(GlossaryTermReviewMapper.class);
    final GlossaryMapper groups = mock(GlossaryMapper.class);
    final GlossaryTermService terms = mock(GlossaryTermService.class);
    final GlossaryFieldService fields = mock(GlossaryFieldService.class);
    final GlossaryScopeService scope = mock(GlossaryScopeService.class);
    final MetadataRelationshipService relations = mock(MetadataRelationshipService.class);
    final GlossaryTermWorkflowServiceImpl service = new GlossaryTermWorkflowServiceImpl(
            mapper, reviews, terms, fields, scope, relations, new ObjectMapper().findAndRegisterModules(), groups);
    GlossaryTerm term;

    @BeforeEach void setup() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), GlossaryTerm.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), MetadataRelationship.class);
        var login = new LoginUser(7L, "reviewer", "", "审核员", List.of());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(login, null, List.of()));
        term = new GlossaryTerm(); term.setId(1L); term.setGlossaryId(2L); term.setName("客户编号");
        term.setDescription("客户唯一标识"); term.setStatus("DRAFT"); term.setRevision(4L);
        when(mapper.selectForUpdate(1L)).thenReturn(term);
        when(mapper.selectById(1L)).thenReturn(term);
        when(groups.selectForUpdate(2L)).thenReturn(new Glossary());
        when(scope.termScope(1L)).thenReturn(GlossaryScopeService.Scope.unbound());
    }
    @AfterEach void cleanup() { UserContext.clear(); SecurityContextHolder.clearContext(); }

    @Test void staleRevisionCannotOverwriteDefinitionOrMappings() {
        var draft = new TermDraftDTO(); draft.setRevision(3L); draft.setName("新定义");
        assertThatThrownBy(() -> service.save(null, 1L, draft)).isInstanceOf(BusinessException.class)
                .hasMessageContaining("已被修改");
        verify(terms, never()).updateTerm(anyLong(), any());
        verify(relations, never()).remove(any());
    }

    @Test void approvedMappingCannotBeChangedThroughLegacyLinkPath() {
        term.setStatus("APPROVED");
        assertThatThrownBy(() -> service.link(1L, 30L, 4L, false)).hasMessageContaining("不能修改");
        verify(fields, never()).resolve(any()); verify(relations, never()).upsert(any());
    }

    @Test void unboundDraftCannotBeSubmittedAsReviewedFieldMeaning() {
        when(relations.getBySource(1L, "GLOSSARY_TERM")).thenReturn(List.of());
        var request = new TermActionDTO(); request.setRevision(4L);
        assertThatThrownBy(() -> service.action(1L, "SUBMIT", request)).hasMessageContaining("关联字段");
        verify(mapper, never()).updateById(any(GlossaryTerm.class));
        verify(reviews, never()).insert(any(GlossaryTermReview.class));
    }

    @Test void unauthorizedTargetIsRejectedBeforeAnyDefinitionWrite() {
        when(fields.resolve(30L)).thenReturn(new TermColumnVO(30L, 9L, "无权源", 2L, 3L, 4L,
                "customers", "id", "bigint", "编号", "source.db.customers.id"));
        doThrow(new BusinessException(403, "没有负责源权限")).when(scope)
                .requireWritableScope(eq(7L), eq("glossary:manage"), any());
        var draft = new TermDraftDTO(); draft.setRevision(4L); draft.setName("客户编号"); draft.setEntityIds(List.of(30L));
        assertThatThrownBy(() -> service.save(null, 1L, draft)).hasMessageContaining("负责源");
        verify(terms, never()).updateTerm(anyLong(), any()); verify(relations, never()).remove(any());
    }

    @Test void legacyApprovedTermWithoutSnapshotIsNotKnowledgeEvidence() {
        when(mapper.selectById(1L)).thenReturn(term); term.setStatus("APPROVED");
        assertThatThrownBy(() -> service.approvedFact(1L)).hasMessageContaining("修订审核");
    }

    @Test void reviewRejectsFieldMeaningChangedSinceSubmission() {
        term.setStatus("PENDING_REVIEW"); term.setReviewHash("wrong-hash");
        when(relations.getBySource(1L, "GLOSSARY_TERM")).thenReturn(List.of());
        var request = new TermActionDTO(); request.setRevision(4L); request.setApproved(true);
        assertThatThrownBy(() -> service.action(1L, "REVIEW", request)).hasMessageContaining("来源已变化");
        verify(reviews, never()).insert(any(GlossaryTermReview.class));
    }
    @Test void mysqlJsonWhitespaceNormalizationDoesNotInvalidateReviewSnapshot() {
        var relation = new MetadataRelationship(); relation.setTargetId(30L); relation.setRelationType("GLOSSARY_OF");
        when(relations.getBySource(1L, "GLOSSARY_TERM")).thenReturn(List.of(relation));
        when(fields.resolve(30L)).thenReturn(new TermColumnVO(30L, 9L, "商品库", 2L, 3L, 4L,
                "products", "id", "bigint", "编号", "source.db.products.id"));
        when(mapper.updateById(any(GlossaryTerm.class))).thenReturn(1);
        term.setSynonyms("[\"ID\",\"编号\"]");
        var submit = new TermActionDTO(); submit.setRevision(4L);
        service.action(1L, "SUBMIT", submit);
        term.setSynonyms("[\"ID\", \"编号\"]");
        var review = new TermActionDTO(); review.setRevision(term.getRevision()); review.setApproved(true);
        service.action(1L, "REVIEW", review);
        assertThat(term.getStatus()).isEqualTo("APPROVED");
    }

}
